package lol.rowe.blocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BlockAccessibilityService extends AccessibilityService {
    private static volatile BlockAccessibilityService activeInstance;

    private final ScheduledExecutorService policyExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService activityExecutor = Executors.newSingleThreadScheduledExecutor();
    /** Downloads and verifies an update; never the poll thread. */
    private final ScheduledExecutorService updateExecutor = Executors.newSingleThreadScheduledExecutor();
    /** Posts the day's notification dump; never the poll thread. */
    private final ScheduledExecutorService notificationExecutor = Executors.newSingleThreadScheduledExecutor();
    /** Fetches one on-demand location fix; never the poll thread. */
    private final ScheduledExecutorService locationExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pollRunning = new AtomicBoolean();
    private final AtomicBoolean pollScheduled = new AtomicBoolean();
    private volatile Policy currentPolicy = Policy.UNBLOCKED;
    private volatile String enforcementStatus = EnforcementStatus.MONITORING;
    private volatile String enforcementDetail = "";
    private volatile String foregroundPackage = "";
    /** The verified update waiting to be installed, or null. */
    private volatile AppUpdater.Pending pendingUpdate;
    /** Allow-listed package just opened from the block screen, if any. */
    private volatile String pendingAllowedPackage;
    private volatile long pendingAllowedAt;
    /** Last disallowed app, so its closing window does not cover the launch. */
    private volatile String lastDisallowedPackage = "";
    private long lastStatusPost;
    private volatile long lastBlockLaunch;
    private String lastReportedPackage = "";
    private String lastReportedTitle = "";
    private long lastHeartbeat;
    /** The status Home Assistant last received, so a change posts at once. */
    private volatile String lastPostedStatus = "";
    /** The location request id this phone last answered or gave up on. */
    private volatile String lastLocationRequest = "";
    // One requested fix in flight at a time; a poll every minute must not
    // stack fetches while the provider is slow.
    private final AtomicBoolean locationFetchRunning = new AtomicBoolean();
    private PolicyDecision decision;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        decision = new PolicyDecision(getPackageName());
        refreshPendingUpdate();
        lastLocationRequest = OnDemandLocation.lastRequestId(this);
        if (AppConfig.load(this).isConfigured()) {
            EnforcementWatchdogJobService.schedule(this);
            EnforcementWatchdogService.start(this);
        }
        // onServiceConnected can run more than once for the same instance; schedule one poller.
        if (pollScheduled.compareAndSet(false, true)) {
            policyExecutor.scheduleWithFixedDelay(
                    this::pollPolicySafely,
                    0,
                    ReportSchedule.POLICY_POLL_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    /**
     * A scheduled task that throws is cancelled permanently, so the poll body
     * must swallow every Throwable — including Errors — or one bad poll would
     * silently stop every check-in while the service still looks bound and
     * enabled. {@link #pollPolicy} already handles ordinary failures; this is
     * the backstop that keeps the schedule alive.
     */
    private void pollPolicySafely() {
        try {
            pollPolicy();
        } catch (Throwable ignored) {
            // The next scheduled poll retries.
        }
    }

    static void noteAllowedLaunch(String packageName) {
        BlockAccessibilityService service = activeInstance;
        if (service == null || packageName == null || packageName.isEmpty()) {
            return;
        }
        service.adoptAllowedLaunch(packageName);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || decision == null) {
            return;
        }
        String eventPackage = packageName.toString();
        boolean windowStateChanged =
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        String title = eventTitle(event.getClassName());
        Policy policy = currentPolicy;
        boolean kiosk = kioskActive(policy);
        if (windowStateChanged
                && decision.isUninstallSurface(eventPackage, title)
                && !ParentMaintenance.allows()) {
            foregroundPackage = eventPackage;
            if (kiosk || policy.shouldWarnGrace()) {
                showBlockScreen(policy);
            } else {
                showUninstallGuard();
            }
            return;
        }
        if (!kiosk
                && !policy.shouldWarnGrace()
                && !eventPackage.equals(getPackageName())
                && !decision.isUninstallSurface(eventPackage, title)) {
            MainActivity.clearUninstallGuard();
        }
        long sinceLaunch = pendingAllowedPackage == null
                ? -1L
                : SystemClock.elapsedRealtime() - pendingAllowedAt;
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                kiosk,
                windowStateChanged,
                foregroundPackage,
                eventPackage,
                pendingAllowedPackage,
                lastDisallowedPackage,
                sinceLaunch);
        if (update.raiseBlockScreen) {
            lastDisallowedPackage = update.foreground;
        }
        foregroundPackage = update.foreground;
        if (windowStateChanged) {
            reportActivityIfNeeded(foregroundPackage, title);
        }
        if (update.raiseBlockScreen) {
            showBlockScreen(policy);
        }
    }

    @Override
    public void onInterrupt() {
        failOpen(EnforcementStatus.ACCESSIBILITY_INTERRUPTED, "Accessibility was interrupted.");
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        currentPolicy = Policy.UNBLOCKED;
        enforcementStatus = EnforcementStatus.ACCESSIBILITY_DISABLED;
        enforcementDetail = "Accessibility service stopped.";
        EnforcementNote.record(this, enforcementStatus, enforcementDetail);
        MainActivity.applyPolicySnapshot(Policy.UNBLOCKED);
        String status = enforcementStatus;
        String detail = enforcementDetail;
        String application = foregroundPackage.isEmpty() ? "unknown" : foregroundPackage;
        Thread reporter = new Thread(() -> {
            try {
                AppConfig config = AppConfig.load(this);
                if (config.isConfigured()) {
                    new HomeAssistantClient(config).postActivity(
                            application, "", null, status, detail);
                }
            } catch (Exception ignored) {
                // The watchdog job reports the same state if the process survives.
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        policyExecutor.shutdownNow();
        activityExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        notificationExecutor.shutdownNow();
        locationExecutor.shutdownNow();
        super.onDestroy();
    }

    static void applyPolicySnapshot(Policy policy) {
        BlockAccessibilityService service = activeInstance;
        if (service != null) {
            service.currentPolicy = policy;
            if (service.decision != null) {
                service.decision.setAllowedApps(policy.allowedApps);
            }
        }
    }

    private void pollPolicy() {
        if (!pollRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                failOpen(EnforcementStatus.NOT_CONFIGURED, "Home Assistant is not configured.");
                return;
            }
            Policy policy = Challenge.enforced(
                    this,
                    new HomeAssistantClient(config).getPolicy(),
                    false,
                    LocalOverride.isActive(this));
            WakeAlarm.applyServer(this, policy.wakeAlarm);
            currentPolicy = policy;
            decision.setAllowedApps(policy.allowedApps);
            boolean challengePending = policy.challengePending();
            AppUpdater.challengeActive = challengePending;
            refreshPendingUpdate();
            publishStatus(
                    policy.blocked ? EnforcementStatus.ENFORCING : EnforcementStatus.MONITORING,
                    policy.blocked || !policy.blockRequested ? "" : notEnforcingReason(policy),
                    false);
            MainActivity.applyPolicySnapshot(policy);
            answerLocationRequest(policy);
            if (decision.shouldShowBlockScreen(kioskActive(policy), foregroundPackage)) {
                showBlockScreen(policy);
            } else if (policy.shouldWarnGrace()) {
                // Re-show the grace warning (countdown + extension button) on
                // every poll during the grace window: a holder who dismissed
                // the screen or was away must still see the deadline before
                // enforcement turns it into a hard block.
                showBlockScreen(policy);
            }
            // A challenge must never be interrupted by an update, and the
            // download must never hold up the poll.
            if (!challengePending) {
                updateExecutor.execute(this::checkForUpdate);
            }
            // A midnight the phone slept through is caught up here.
            notificationExecutor.execute(() -> NotificationDump.flushIfDue(this));
        } catch (Exception exception) {
            String detail = exception.getMessage();
            failOpen(
                    EnforcementStatus.FAIL_OPEN,
                    detail == null || detail.trim().isEmpty()
                            ? "Could not verify Home Assistant policy."
                            : detail);
        } finally {
            pollRunning.set(false);
        }
    }

    /** Check the release off the poll thread, then raise the update screen at once. */
    private void checkForUpdate() {
        AppUpdater.check(this);
        mainHandler.post(() -> {
            refreshPendingUpdate();
            if (kioskActive(currentPolicy)) {
                showBlockScreen(currentPolicy);
            }
        });
    }

    private void refreshPendingUpdate() {
        pendingUpdate = AppUpdater.pending(this);
    }

    /**
     * True when a lock screen (a parent block, an unapproved challenge or a
     * verified update) must cover disallowed apps. All three go through the
     * same enforcement path; only the screen differs.
     */
    private boolean kioskActive(Policy policy) {
        return policy.isLocked() || updateScreenPending(policy);
    }

    private boolean updateScreenPending(Policy policy) {
        return UpdateGate.shouldShowUpdateScreen(
                pendingUpdate != null,
                policy.challengePending(),
                policy.isExtensionActive(),
                LocalOverride.isActive(this));
    }

    /**
     * Answer a parent's location request. Nothing touches the provider until
     * one arrives, and a request is only recorded as answered once the fix has
     * been posted.
     *
     * <p>A request used to be recorded before the fetch, so the first attempt
     * that timed out — the common case on a phone whose screen is off and whose
     * background work the OEM has frozen — silently swallowed the request for
     * good and the parent's button did nothing. Until it is answered the request
     * stays pending, so the next poll tries again, and one fetch is in flight at
     * a time so a slow provider cannot pile up threads.
     */
    private void answerLocationRequest(Policy policy) {
        Policy.LocationRequest request = policy.locationRequest;
        if (request == null || !OnDemandLocation.shouldFetch(request.id, lastLocationRequest)) {
            return;
        }
        if (!OnDemandLocation.hasPermission(this)) {
            // Never prompt from the background; the setup screen offers the button.
            return;
        }
        if (!locationFetchRunning.compareAndSet(false, true)) {
            return;
        }
        locationExecutor.execute(() -> {
            try {
                android.location.Location fix =
                        OnDemandLocation.fetch(this, OnDemandLocation.TIMEOUT_MILLIS);
                if (fix == null) {
                    return;
                }
                long ageSeconds = Math.max(0L, (System.currentTimeMillis() - fix.getTime()) / 1000L);
                Double latitude = fix.getLatitude();
                Double longitude = fix.getLongitude();
                if (!OnDemandLocation.isSendable(latitude, longitude, ageSeconds)) {
                    return;
                }
                AppConfig config = AppConfig.load(this);
                if (!config.isConfigured()) {
                    return;
                }
                new HomeAssistantClient(config).postLocation(
                        request.id,
                        latitude,
                        longitude,
                        fix.hasAccuracy() ? fix.getAccuracy() : 0f,
                        fix.getProvider() == null ? "" : fix.getProvider(),
                        ageSeconds);
                // Only now is the request spent: a fetch or an upload that
                // failed leaves it pending for the next poll.
                lastLocationRequest = request.id;
                OnDemandLocation.rememberRequestId(this, request.id);
            } catch (Exception ignored) {
                // A failed upload must not affect blocking, the challenge or the
                // poll; the request stays pending and is retried on the next one.
            } finally {
                locationFetchRunning.set(false);
            }
        });
    }

    private void reportActivityIfNeeded(String packageName, String title) {
        lastReportedPackage = packageName;
        lastReportedTitle = title;
        long now = SystemClock.elapsedRealtime();
        if (!ReportSchedule.due(lastHeartbeat, now)) {
            return;
        }
        lastHeartbeat = now;
        String application = packageName;
        String window = title;
        activityExecutor.execute(() -> postReport(application, window));
    }

    private void adoptAllowedLaunch(String packageName) {
        if (decision != null && !decision.isAllowed(true, foregroundPackage)) {
            lastDisallowedPackage = foregroundPackage;
        }
        pendingAllowedPackage = packageName;
        pendingAllowedAt = SystemClock.elapsedRealtime();
        foregroundPackage = packageName;
    }

    private void showBlockScreen(Policy policy) {
        // The system installer prompt must stay on top of the lock screen.
        if (UpdateGate.installInProgress) {
            return;
        }
        boolean kiosk = policy.isLocked() || updateScreenPending(policy);
        // Never resume the block activity over an app the holder is allowed to use.
        if (kiosk && decision != null && decision.isAllowed(true, foregroundPackage)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBlockLaunch < 750) {
            return;
        }
        lastBlockLaunch = now;
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_BLOCK_SCREEN, true)
                .putExtra(MainActivity.EXTRA_BLOCKED, policy.blocked)
                .putExtra(MainActivity.EXTRA_BLOCK_REQUESTED, policy.blockRequested)
                .putExtra(MainActivity.EXTRA_BLOCK_MESSAGE, policy.message)
                .putExtra(MainActivity.EXTRA_EXTENSION_AVAILABLE, policy.extensionAvailable)
                .putExtra(MainActivity.EXTRA_ENFORCE_AT, policy.enforceAt == null ? null : policy.enforceAt.toString())
                .putExtra(MainActivity.EXTRA_EXTENSION_UNTIL, policy.extensionUntil == null ? null : policy.extensionUntil.toString())
                .putExtra(MainActivity.EXTRA_BLOCKED_PACKAGE, blockedPackageForScreen())
                .putStringArrayListExtra(
                        MainActivity.EXTRA_ALLOWED_APPS, new ArrayList<>(policy.allowedApps));
        if (policy.challengePending()) {
            intent.putExtra(MainActivity.EXTRA_CHALLENGE_ID, policy.challenge.id)
                    .putExtra(MainActivity.EXTRA_CHALLENGE_PHRASE, policy.challenge.phrase)
                    .putExtra(MainActivity.EXTRA_CHALLENGE_STATUS, policy.challenge.status)
                    .putExtra(
                            MainActivity.EXTRA_CHALLENGE_SUBMITTED_AT,
                            policy.challenge.submittedAt)
                    .putExtra(MainActivity.EXTRA_CHALLENGE_ATTEMPT, policy.challenge.attempt)
                    .putExtra(
                            MainActivity.EXTRA_CHALLENGE_AUTO_APPROVE_AT,
                            policy.challenge.autoApproveAt);
        }
        mainHandler.post(() -> {
            if (kiosk && decision != null && decision.isAllowed(true, foregroundPackage)) {
                return;
            }
            startActivity(intent);
        });
    }

    private void showUninstallGuard() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBlockLaunch < 750) {
            return;
        }
        lastBlockLaunch = now;
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_UNINSTALL_GUARD, true);
        mainHandler.post(() -> startActivity(intent));
    }

    /**
     * The app the block screen was raised for, so the child can ask a parent to
     * allow it. The last disallowed app is the one the holder was using; the
     * foreground is the fallback when no event has named one yet.
     */
    private String blockedPackageForScreen() {
        return lastDisallowedPackage.isEmpty() ? foregroundPackage : lastDisallowedPackage;
    }

    private void failOpen(String status, String detail) {
        currentPolicy = Challenge.enforced(this, null, true, false);
        AppUpdater.challengeActive = currentPolicy.challengePending();
        MainActivity.applyPolicySnapshot(currentPolicy);
        publishStatus(status, detail == null ? "" : detail, true);
    }

    /**
     * Why a block a parent asked for is not in force yet, in words a parent can
     * act on. A local PIN unlock used to silence enforcement for the rest of
     * the hour with no visible cause; naming it here makes that recoverable.
     */
    private String notEnforcingReason(Policy policy) {
        if (LocalOverride.isActive(this)) {
            long minutes = Math.max(
                    1L, (LocalOverride.activeUntil(this) - System.currentTimeMillis()) / 60_000L);
            return "Local PIN unlock active for another " + minutes
                    + " minute(s); turn the block off and on to revoke it.";
        }
        if (policy.shouldWarnGrace()) {
            return "Grace period: enforcement starts in "
                    + policy.secondsUntilEnforcement() + " second(s).";
        }
        if (policy.isExtensionActive()) {
            return "An extension is holding the block off.";
        }
        return "The agent has not applied the block yet.";
    }

    private void publishStatus(String status, String detail, boolean force) {
        enforcementStatus = status;
        enforcementDetail = detail;
        EnforcementNote.record(this, status, detail);
        long now = SystemClock.elapsedRealtime();
        // A status change is what a parent watches for after flipping the
        // switch, so it must not wait for the hourly batch: Home Assistant
        // would keep showing the previous status and the block would look
        // broken. An unchanged status still rides that batch.
        boolean changed = !status.equals(lastPostedStatus);
        if (!changed && !ReportSchedule.due(lastHeartbeat, now)) {
            return;
        }
        lastHeartbeat = now;
        lastStatusPost = now;
        lastPostedStatus = status;
        String application = foregroundPackage.isEmpty() ? "unknown" : foregroundPackage;
        activityExecutor.execute(() -> postReport(application, ""));
    }

    private void postReport(String application, String window) {
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                return;
            }
            List<InstalledApps.App> apps = InstalledApps.launchable(getPackageManager());
            String fingerprint = InstalledApps.fingerprint(apps);
            boolean changed = InstalledApps.shouldSend(
                    InstalledApps.lastFingerprint(this), fingerprint);
            new HomeAssistantClient(config).postActivity(
                    application,
                    window,
                    null,
                    enforcementStatus,
                    enforcementDetail,
                    changed ? apps : null);
            if (changed) {
                // Only after a successful report, so a failed upload is retried.
                InstalledApps.rememberFingerprint(this, fingerprint);
            }
        } catch (Exception ignored) {
            // The next hourly batch retries. A failed report must not keep retrying
            // on every foreground change.
        }
    }

    private static String eventTitle(CharSequence className) {
        if (className == null || className.length() == 0) {
            return "";
        }
        String title = className.toString();
        return title.length() <= 255 ? title : title.substring(0, 255);
    }
}
