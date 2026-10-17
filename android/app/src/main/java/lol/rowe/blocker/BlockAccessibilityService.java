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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pollRunning = new AtomicBoolean();
    private final AtomicBoolean pollScheduled = new AtomicBoolean();
    private volatile Policy currentPolicy = Policy.UNBLOCKED;
    private volatile String enforcementStatus = EnforcementStatus.MONITORING;
    private volatile String enforcementDetail = "";
    private volatile String foregroundPackage = "";
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
    /** Elapsed time of the last report that carried a position. */
    private long lastLocationReport;
    private PolicyDecision decision;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        decision = new PolicyDecision(getPackageName());
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
        if (windowStateChanged
                && decision.isUninstallSurface(eventPackage, title)
                && !ParentMaintenance.allows()) {
            foregroundPackage = eventPackage;
            if (policy.shouldCaptureForeground() || policy.shouldWarnGrace()) {
                showBlockScreen(policy);
            } else {
                showUninstallGuard();
            }
            return;
        }
        if (!policy.shouldCaptureForeground()
                && !policy.shouldWarnGrace()
                && !eventPackage.equals(getPackageName())
                && !decision.isUninstallSurface(eventPackage, title)) {
            MainActivity.clearUninstallGuard();
        }
        long sinceLaunch = pendingAllowedPackage == null
                ? -1L
                : SystemClock.elapsedRealtime() - pendingAllowedAt;
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                policy.shouldCaptureForeground(),
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
            Policy policy = new HomeAssistantClient(config).getPolicy();
            WakeAlarm.applyServer(this, policy.wakeAlarm);
            currentPolicy = policy;
            decision.setAllowedApps(policy.allowedApps);
            publishStatus(
                    policy.blocked ? EnforcementStatus.ENFORCING : EnforcementStatus.MONITORING,
                    "",
                    false);
            MainActivity.applyPolicySnapshot(policy);
            reportLocationIfDue();
            if (decision.shouldShowBlockScreen(policy.shouldCaptureForeground(), foregroundPackage)) {
                showBlockScreen(policy);
            } else if (policy.shouldWarnGrace()) {
                // Re-show the grace warning (countdown + extension button) on
                // every poll during the grace window: a holder who dismissed
                // the screen or was away must still see the deadline before
                // enforcement turns it into a hard block.
                showBlockScreen(policy);
            }
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

    /**
     * Send a position as soon as one is available instead of waiting for the
     * hourly batch. {@link PassiveLocation#latest} also asks a provider for a
     * fresh fix when the cache is stale, so the poll both warms the cache and
     * reports it. Reading a cached fix is free; the request itself is throttled
     * inside PassiveLocation, so this adds no extra wakeups.
     */
    private void reportLocationIfDue() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLocationReport < ReportSchedule.LOCATION_REPORT_INTERVAL_MS) {
            return;
        }
        android.location.Location location = PassiveLocation.latest(this);
        if (location == null) {
            // Nothing recent enough yet; the request above will fill the cache.
            return;
        }
        lastLocationReport = now;
        String application = foregroundPackage.isEmpty() ? "unknown" : foregroundPackage;
        activityExecutor.execute(() -> postReport(application, "", true));
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
        activityExecutor.execute(() -> postReport(application, window, true));
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
        // Never resume the block activity over an app the holder is allowed to use.
        if (policy.shouldCaptureForeground()
                && decision != null
                && decision.isAllowed(true, foregroundPackage)) {
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
                .putStringArrayListExtra(
                        MainActivity.EXTRA_ALLOWED_APPS, new ArrayList<>(policy.allowedApps));
        mainHandler.post(() -> {
            if (policy.shouldCaptureForeground()
                    && decision != null
                    && decision.isAllowed(true, foregroundPackage)) {
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

    private void failOpen(String status, String detail) {
        currentPolicy = Policy.UNBLOCKED;
        MainActivity.applyPolicySnapshot(Policy.UNBLOCKED);
        publishStatus(status, detail == null ? "" : detail, true);
    }

    private void publishStatus(String status, String detail, boolean force) {
        enforcementStatus = status;
        enforcementDetail = detail;
        EnforcementNote.record(this, status, detail);
        long now = SystemClock.elapsedRealtime();
        // Local fail-open already happened. Status, activity, and location share
        // one hourly upload. The unused force flag stays so callers can ask for
        // a report without bypassing that batch.
        if (!ReportSchedule.due(lastHeartbeat, now)) {
            return;
        }
        lastHeartbeat = now;
        lastStatusPost = now;
        String application = foregroundPackage.isEmpty() ? "unknown" : foregroundPackage;
        activityExecutor.execute(() -> postReport(application, ""));
    }

    private void postReport(String application, String window) {
        postReport(application, window, false);
    }

    private void postReport(String application, String window, boolean includeLocation) {
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                return;
            }
            android.location.Location location = includeLocation ? PassiveLocation.latest(this) : null;
            List<InstalledApps.App> apps = InstalledApps.launchable(getPackageManager());
            String fingerprint = InstalledApps.fingerprint(apps);
            boolean changed = InstalledApps.shouldSend(
                    InstalledApps.lastFingerprint(this), fingerprint);
            new HomeAssistantClient(config).postActivity(
                    application,
                    window,
                    location,
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
