package lol.rowe.blocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

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
    private volatile String foregroundPackage = "";
    private volatile long lastBlockLaunch;
    private String lastReportedPackage = "";
    private String lastReportedTitle = "";
    private long lastHeartbeat;
    private PolicyDecision decision;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        decision = new PolicyDecision(getPackageName());
        if (AppConfig.load(this).isConfigured()) {
            MediaBackupScheduler.schedule(this);
        }
        // onServiceConnected can run more than once for the same instance; schedule one poller.
        if (pollScheduled.compareAndSet(false, true)) {
            policyExecutor.scheduleWithFixedDelay(this::pollPolicy, 0, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null) {
            return;
        }
        foregroundPackage = packageName.toString();
        String title = eventTitle(event.getClassName());
        reportActivityIfNeeded(foregroundPackage, title);

        Policy policy = currentPolicy;
        if (decision != null && decision.shouldShowBlockScreen(policy.shouldCaptureForeground(), foregroundPackage)) {
            showBlockScreen(policy);
        }
    }

    @Override
    public void onInterrupt() {
        failOpen();
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        failOpen();
        policyExecutor.shutdownNow();
        activityExecutor.shutdownNow();
        super.onDestroy();
    }

    static void applyPolicySnapshot(Policy policy) {
        BlockAccessibilityService service = activeInstance;
        if (service != null) {
            service.currentPolicy = policy;
        }
    }

    private void pollPolicy() {
        if (!pollRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                failOpen();
                return;
            }
            Policy policy = new HomeAssistantClient(config).getPolicy();
            currentPolicy = policy;
            MainActivity.applyPolicySnapshot(policy);
            if (decision.shouldShowBlockScreen(policy.shouldCaptureForeground(), foregroundPackage)) {
                showBlockScreen(policy);
            } else if (policy.shouldWarnGrace()) {
                // Re-show the grace warning (countdown + extension button) on
                // every poll during the grace window: a holder who dismissed
                // the screen or was away must still see the deadline before
                // enforcement turns it into a hard block.
                showBlockScreen(policy);
            }
        } catch (Exception ignored) {
            failOpen();
        } finally {
            pollRunning.set(false);
        }
    }

    private void reportActivityIfNeeded(String packageName, String title) {
        long now = SystemClock.elapsedRealtime();
        boolean changed = !packageName.equals(lastReportedPackage) || !title.equals(lastReportedTitle);
        boolean heartbeat = now - lastHeartbeat >= 60_000;
        if (!changed && !heartbeat) {
            return;
        }
        lastReportedPackage = packageName;
        lastReportedTitle = title;
        lastHeartbeat = now;
        activityExecutor.execute(() -> {
            try {
                AppConfig config = AppConfig.load(this);
                if (config.isConfigured()) {
                    // Only attach a position on the 60-second heartbeat: the read is
                    // cheap but pointless on every foreground-app change.
                    android.location.Location location =
                            heartbeat ? PassiveLocation.latest(this) : null;
                    new HomeAssistantClient(config).postActivity(packageName, title, location);
                }
            } catch (Exception ignored) {
                // The next foreground event or heartbeat retries.
            }
        });
    }

    private void showBlockScreen(Policy policy) {
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
                .putExtra(MainActivity.EXTRA_EXTENSION_UNTIL, policy.extensionUntil == null ? null : policy.extensionUntil.toString());
        mainHandler.post(() -> startActivity(intent));
    }

    private void failOpen() {
        currentPolicy = Policy.UNBLOCKED;
        MainActivity.applyPolicySnapshot(Policy.UNBLOCKED);
    }

    private static String eventTitle(CharSequence className) {
        if (className == null || className.length() == 0) {
            return "";
        }
        String title = className.toString();
        return title.length() <= 255 ? title : title.substring(0, 255);
    }
}
