package lol.rowe.blocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static volatile MainActivity activeInstance;
    static final String EXTRA_BLOCK_SCREEN = "block_screen";
    static final String EXTRA_BLOCKED = "blocked";
    static final String EXTRA_BLOCK_REQUESTED = "block_requested";
    static final String EXTRA_BLOCK_MESSAGE = "block_message";
    static final String EXTRA_EXTENSION_AVAILABLE = "extension_available";
    static final String EXTRA_ENFORCE_AT = "enforce_at";
    static final String EXTRA_EXTENSION_UNTIL = "extension_until";
    static final String EXTRA_UNINSTALL_GUARD = "uninstall_guard";
    static final String EXTRA_ALLOWED_APPS = "allowed_apps";
    /** The disallowed app the block screen was raised for, from the service. */
    static final String EXTRA_BLOCKED_PACKAGE = "blocked_package";
    /** The unanswered challenge the block screen must show, from the service. */
    static final String EXTRA_CHALLENGE_ID = "challenge_id";
    static final String EXTRA_CHALLENGE_PHRASE = "challenge_phrase";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    /** Separate from {@link #executor}: the approval wait blocks for up to 30 minutes. */
    private final ScheduledExecutorService setupExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private ScrollView setupScroll;
    private View blockedPanel;
    private View blockCopyPanel;
    private ChallengePanel challengePanel;
    private Button installUpdateButton;
    private TextView statusText;
    private TextView blockTitle;
    private TextView blockMessage;
    private TextView blockCountdown;
    private TextView familyStatus;
    private TextView blockLastApp;
    private TextView blockRequestStatus;
    private Button extensionButton;
    private Button pointsHourButton;
    private Button requestUnblockButton;
    private GridLayout allowedApps;
    private EditText homeAssistantUrl;
    private EditText childName;
    private View moreSection;
    private volatile Policy currentPolicy = Policy.UNBLOCKED;
    /** The disallowed app this block screen was raised for, or "" when unknown. */
    private String blockedPackage = "";
    /** The name shown for {@link #blockedPackage}. */
    private String blockedAppLabel = "";
    /** Local PIN unlock for changing a phone that is already set up. */
    private boolean setupUnlocked;
    private String pendingAfterPin;
    private boolean uninstallGuard;
    /** True while the update screen is the one on show. */
    private boolean updateScreen;
    private boolean pinDialogShowing;
    private int pinFailures;
    private long pinLockoutUntil;

    private void loadPinFailures() {
        android.content.SharedPreferences preferences =
                getSharedPreferences("rowe_blocker", MODE_PRIVATE);
        pinFailures = preferences.getInt("pin_failures", 0);
        pinLockoutUntil = preferences.getLong("pin_lockout_until", 0L);
    }

    private void savePinFailures() {
        getSharedPreferences("rowe_blocker", MODE_PRIVATE)
                .edit()
                .putInt("pin_failures", pinFailures)
                .putLong("pin_lockout_until", pinLockoutUntil)
                .apply();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinFailures();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setupScroll = findViewById(R.id.setupScroll);
        blockedPanel = findViewById(R.id.blockedPanel);
        blockCopyPanel = findViewById(R.id.blockCopyPanel);
        challengePanel = findViewById(R.id.challengePanel);
        challengePanel.setListener(this::onChallengeAccepted);
        installUpdateButton = findViewById(R.id.installUpdateButton);
        statusText = findViewById(R.id.statusText);
        blockTitle = findViewById(R.id.blockTitle);
        blockMessage = findViewById(R.id.blockMessage);
        blockCountdown = findViewById(R.id.blockCountdown);
        familyStatus = findViewById(R.id.familyStatus);
        blockLastApp = findViewById(R.id.blockLastApp);
        blockRequestStatus = findViewById(R.id.blockRequestStatus);
        extensionButton = findViewById(R.id.extensionButton);
        pointsHourButton = findViewById(R.id.pointsHourButton);
        requestUnblockButton = findViewById(R.id.requestUnblockButton);
        allowedApps = findViewById(R.id.allowedApps);
        homeAssistantUrl = findViewById(R.id.homeAssistantUrl);
        childName = findViewById(R.id.childName);
        moreSection = findViewById(R.id.moreSection);

        findViewById(R.id.saveButton).setOnClickListener(view -> setUpThisPhone());
        findViewById(R.id.moreButton).setOnClickListener(view -> toggleMore());
        findViewById(R.id.changePinButton).setOnClickListener(
                view -> promptForNewParentPin(false));
        findViewById(R.id.accessibilityButton).setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.batteryButton).setOnClickListener(view -> requestUnrestrictedBattery());
        findViewById(R.id.uninstallGuardButton).setOnClickListener(view -> activateUninstallGuard());
        findViewById(R.id.wakePermissionButton).setOnClickListener(view -> requestExactAlarmPermission());
        findViewById(R.id.notificationAccessButton)
                .setOnClickListener(view -> openNotificationAccess());
        extensionButton.setOnClickListener(view -> requestExtension());
        pointsHourButton.setOnClickListener(view -> requestPointsHour());
        requestUnblockButton.setOnClickListener(view -> requestUnblock());
        installUpdateButton.setOnClickListener(view -> AppUpdater.install(this));
        findViewById(R.id.parentOverrideButton)
                .setOnClickListener(view -> promptForParentOverride());

        // Publish the activity only after every view used by applyPolicy is ready.
        activeInstance = this;

        if (getIntent().getBooleanExtra(EXTRA_UNINSTALL_GUARD, false)) {
            showUninstallGuard();
        } else if (getIntent().getBooleanExtra(EXTRA_BLOCK_SCREEN, false)) {
            showFromIntent(getIntent());
        } else {
            showSetupForm();
        }
        refreshWakeStatus();
        executor.scheduleWithFixedDelay(this::refreshPolicyIfServiceIsDown, 0, ReportSchedule.POLICY_POLL_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::tickCountdown, 1, 1, TimeUnit.SECONDS);
        if (AppConfig.load(this).isConfigured()) {
            EnforcementWatchdogJobService.schedule(this);
            EnforcementWatchdogService.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_UNINSTALL_GUARD, false)) {
            showUninstallGuard();
        } else if (intent.getBooleanExtra(EXTRA_BLOCK_SCREEN, false)) {
            showFromIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        refreshNotificationAccess();
        executor.execute(this::refreshPolicy);
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        challengePanel.release();
        executor.shutdownNow();
        setupExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        challengePanel.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (!updateScreen && !currentPolicy.isLocked(Challenge.acceptedId(this))) {
            super.onBackPressed();
        }
    }

    /** Gate the configuration editor behind a parent-chosen PIN. */
    private void promptForParentPin() {
        if (isFinishing() || pinDialogShowing) {
            return;
        }
        if (!ParentPin.isSet(this)) {
            promptForNewParentPin(true);
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = ParentPin.remainingMillis(pinLockoutUntil, now);
        if (remaining == 0L && pinLockoutUntil != 0L) {
            pinFailures = 0;
            pinLockoutUntil = 0L;
            savePinFailures();
        } else if (remaining == 0L && pinFailures >= ParentPin.MAX_ATTEMPTS) {
            pinLockoutUntil = ParentPin.lockoutDeadline(pinFailures, now);
            savePinFailures();
            remaining = ParentPin.remainingMillis(pinLockoutUntil, now);
        }
        if (remaining > 0L) {
            showPinLockout(remaining);
            return;
        }
        pinDialogShowing = true;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Parent PIN");
        int triesLeft = ParentPin.MAX_ATTEMPTS - pinFailures;
        new AlertDialog.Builder(this)
                .setTitle("Parent PIN required")
                .setMessage("Enter the parent PIN to change this phone's settings. "
                        + triesLeft + " attempt(s) left.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    pinDialogShowing = false;
                    if (ParentPin.verify(this, input.getText().toString().trim())) {
                        pinFailures = 0;
                        pinLockoutUntil = 0L;
                        savePinFailures();
                        unlockSetup();
                        String pending = pendingAfterPin;
                        pendingAfterPin = null;
                        if ("setup".equals(pending)) {
                            setUpThisPhone();
                        } else if ("more".equals(pending)) {
                            moreSection.setVisibility(View.VISIBLE);
                        }
                        return;
                    }
                    pinFailures++;
                    if (pinFailures >= ParentPin.MAX_ATTEMPTS) {
                        pinLockoutUntil = ParentPin.lockoutDeadline(
                                pinFailures, System.currentTimeMillis());
                        savePinFailures();
                        showPinLockout(ParentPin.remainingMillis(
                                pinLockoutUntil, System.currentTimeMillis()));
                    } else {
                        savePinFailures();
                        Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                        promptForParentPin();
                    }
                })
                .setNegativeButton("Close", (dialog, which) -> {
                    pinDialogShowing = false;
                    finish();
                })
                .show();
    }

    private void showPinLockout(long remainingMillis) {
        if (isFinishing() || pinDialogShowing) {
            return;
        }
        pinDialogShowing = true;
        int minutes = ParentPin.wholeMinutesRemaining(remainingMillis);
        new AlertDialog.Builder(this)
                .setTitle("Parent PIN locked")
                .setMessage("Too many wrong PINs. Try again in " + minutes + " minute(s).")
                .setCancelable(false)
                .setPositiveButton("Close", (dialog, which) -> {
                    pinDialogShowing = false;
                    finish();
                })
                .show();
    }

    private void promptForNewParentPin(boolean initialSetup) {
        if (isFinishing() || pinDialogShowing) {
            return;
        }
        pinDialogShowing = true;

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);

        EditText first = new EditText(this);
        first.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        first.setHint("New parent PIN");
        fields.addView(first);

        EditText confirmation = new EditText(this);
        confirmation.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        confirmation.setHint("Confirm parent PIN");
        fields.addView(confirmation);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(initialSetup ? "Create parent PIN" : "Change parent PIN")
                .setMessage("Choose at least four digits. There is no universal or recovery PIN.")
                .setView(fields)
                .setCancelable(false)
                .setPositiveButton("Save PIN", null)
                .setNegativeButton(initialSetup ? "Close" : "Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String pin = first.getText().toString().trim();
                String repeated = confirmation.getText().toString().trim();
                if (pin.length() < ParentPin.MIN_LENGTH) {
                    Toast.makeText(this, "Use at least four digits.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pin.equals(repeated)) {
                    Toast.makeText(this, "The PINs do not match.", Toast.LENGTH_SHORT).show();
                    return;
                }
                ParentPin.set(this, pin);
                pinFailures = 0;
                pinLockoutUntil = 0L;
                savePinFailures();
                pinDialogShowing = false;
                dialog.dismiss();
                if (initialSetup) {
                    unlockSetup();
                } else {
                    Toast.makeText(this, "Parent PIN changed.", Toast.LENGTH_SHORT).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                pinDialogShowing = false;
                dialog.dismiss();
                if (initialSetup) {
                    finish();
                }
            });
        });
        dialog.setOnDismissListener(ignored -> pinDialogShowing = false);
        dialog.show();
    }

    /**
     * Parent-facing escape hatch on the block screen: entering the PIN asks Home
     * Assistant for one extra hour. Home Assistant does the verification and
     * attempt limiting so this phone cannot be tricked into granting time. When
     * Home Assistant refuses or cannot be reached, the phone's own parent PIN
     * grants the hour locally instead, so a parent is never left with no way to
     * give time at the device.
     */
    private void promptForParentOverride() {
        if (isFinishing() || pinDialogShowing) {
            return;
        }
        pinDialogShowing = true;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Parent PIN");
        new AlertDialog.Builder(this)
                .setTitle("Give 1 more hour")
                .setMessage("A parent can enter the PIN to unlock this phone for another hour.")
                .setView(input)
                .setPositiveButton("Unlock for 1 hour", (dialog, which) -> {
                    pinDialogShowing = false;
                    String pin = input.getText().toString().trim();
                    blockCountdown.setText("Checking PIN…");
                    executor.execute(() -> {
                        HomeAssistantClient.OverrideOutcome outcome = null;
                        try {
                            outcome = new HomeAssistantClient(AppConfig.load(MainActivity.this))
                                    .requestParentOverride(pin);
                        } catch (Exception exception) {
                            // Unreachable: the local PIN is the only way to grant time.
                        }
                        final HomeAssistantClient.OverrideOutcome result = outcome;
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (result != null && result.granted) {
                                blockCountdown.setText(result.message);
                                return;
                            }
                            blockCountdown.setText(localOverrideFallback(pin));
                            if (LocalOverride.isActive(this)) {
                                applyPolicy(Policy.UNBLOCKED);
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", (dialog, which) -> pinDialogShowing = false)
                .setOnDismissListener(dialog -> pinDialogShowing = false)
                .show();
    }

    /**
     * Home Assistant refused the override or could not be reached, so the
     * phone's own parent PIN decides. The attempt counter and lockout are the
     * same ones the setup screen uses, so this adds no new way to guess the PIN.
     *
     * @return a message suitable for showing on the block screen.
     */
    private String localOverrideFallback(String pin) {
        long now = System.currentTimeMillis();
        long remaining = ParentPin.remainingMillis(pinLockoutUntil, now);
        if (remaining > 0L) {
            return "Too many wrong PINs. Try again in "
                    + ParentPin.wholeMinutesRemaining(remaining) + " minute(s).";
        }
        if (ParentPin.verify(this, pin)) {
            pinFailures = 0;
            pinLockoutUntil = 0L;
            savePinFailures();
            LocalOverride.grantFor(this, LocalOverride.DURATION_MS);
            return "Unlocked for 1 hour on this phone.";
        }
        pinFailures++;
        if (pinFailures >= ParentPin.MAX_ATTEMPTS) {
            pinLockoutUntil = ParentPin.lockoutDeadline(pinFailures, now);
        }
        savePinFailures();
        return "Incorrect PIN.";
    }

    private void unlockSetup() {
        setupUnlocked = true;
        ParentMaintenance.allowForTenMinutes();
        // Ask once, from the parent-gated screen: without this the passive
        // position reader has nothing it is allowed to read.
        if (!OnDemandLocation.hasPermission(this)) {
            requestPermissions(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
            }, 1);
        }
        setupScroll.setVisibility(View.VISIBLE);
        updateStatus();
    }

    private void showSetupForm() {
        AppConfig config = AppConfig.load(this);
        childName.setText(ChildDevice.displayName(config.childName, config.deviceId));
        if (homeAssistantUrl.getText().length() == 0) {
            homeAssistantUrl.setText(config.homeAssistantUrl);
        }
        findViewById(R.id.moreButton).setVisibility(config.isConfigured() ? View.VISIBLE : View.GONE);
        moreSection.setVisibility(View.GONE);
        setupScroll.setVisibility(View.VISIBLE);
        updateStatus();
    }

    private void toggleMore() {
        if (moreSection.getVisibility() == View.VISIBLE) {
            moreSection.setVisibility(View.GONE);
            return;
        }
        if (ParentPin.isSet(this) && !setupUnlocked) {
            pendingAfterPin = "more";
            promptForParentPin();
            return;
        }
        moreSection.setVisibility(View.VISIBLE);
    }

    private void setUpThisPhone() {
        String name = childName.getText().toString().trim();
        String url = homeAssistantUrl.getText().toString().trim();
        if (name.isEmpty() || url.isEmpty()) {
            statusText.setText("Enter this child's name and your Home Assistant address.");
            return;
        }
        if (!AppConfig.acceptsHomeAssistantUrl(url)) {
            statusText.setText("That Home Assistant address needs to start with https://.");
            return;
        }
        AppConfig existing = AppConfig.load(this);
        if (existing.isConfigured() && ParentPin.isSet(this) && !setupUnlocked) {
            pendingAfterPin = "setup";
            promptForParentPin();
            return;
        }
        statusText.setText("Asking Home Assistant for approval…");
        View saveButton = findViewById(R.id.saveButton);
        saveButton.setEnabled(false);
        setupExecutor.execute(() -> {
            ProvisionFlow flow = new ProvisionFlow(
                    new HomeAssistantClient.ProvisionTransport(url),
                    new ProvisionFlow.Clock() {
                        @Override
                        public long now() {
                            return System.currentTimeMillis();
                        }

                        @Override
                        public boolean await(long millis) {
                            try {
                                Thread.sleep(millis);
                                return true;
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        }
                    });
            ProvisionFlow.Outcome outcome = flow.run(name, () -> mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    statusText.setText("Waiting for a parent to approve in Home Assistant…");
                }
            }));
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                saveButton.setEnabled(true);
                if (outcome.status == ProvisionFlow.Status.APPROVED) {
                    finishSetup(url, outcome);
                    return;
                }
                statusText.setText(outcome.message);
            });
        });
    }

    /** Save the approved credentials and run the post-setup steps. */
    private void finishSetup(String url, ProvisionFlow.Outcome outcome) {
        AppConfig.save(this, new AppConfig(
                url, outcome.deviceId, outcome.deviceKey, outcome.childName));
        childName.setText(outcome.childName);
        statusText.setText("This phone is set up.");
        findViewById(R.id.moreButton).setVisibility(View.VISIBLE);
        if (!OnDemandLocation.hasPermission(this)) {
            requestPermissions(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
            }, 1);
        }
        EnforcementWatchdogJobService.schedule(this);
        EnforcementWatchdogService.start(this);
        executor.execute(this::refreshPolicy);
    }

    private void refreshPolicy() {
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                mainHandler.post(() -> failOpen(
                        EnforcementStatus.NOT_CONFIGURED,
                        "Home Assistant is not configured. Blocking is disabled."));
                return;
            }
            Policy policy = Challenge.enforced(
                    this,
                    new HomeAssistantClient(config).getPolicy(),
                    false,
                    LocalOverride.isActive(this));
            mainHandler.post(() -> {
                EnforcementNote.record(
                        this,
                        policy.blocked ? EnforcementStatus.ENFORCING : EnforcementStatus.MONITORING,
                        "");
                applyPolicy(policy);
            });
        } catch (Exception exception) {
            String detail = exception.getMessage();
            String message = detail == null || detail.trim().isEmpty()
                    ? "Cannot verify Home Assistant policy. Blocking is disabled."
                    : detail;
            mainHandler.post(() -> failOpen(EnforcementStatus.FAIL_OPEN, message));
        }
    }

    static void applyPolicySnapshot(Policy policy) {
        MainActivity activity = activeInstance;
        if (activity != null) {
            activity.mainHandler.post(() -> {
                if (!activity.isFinishing()) {
                    activity.applyPolicy(policy);
                }
            });
        }
    }

    private void failOpen(String status, String message) {
        EnforcementNote.record(this, status, message);
        applyPolicy(Challenge.enforced(this, null, true, false));
        if (!isFinishing() && setupUnlocked) {
            statusText.setText(EnforcementStatus.parentText(status, message));
        }
    }

    private void applyPolicy(Policy policy) {
        currentPolicy = policy;
        WakeAlarm.applyServer(this, policy.wakeAlarm);
        refreshWakeStatus();
        BlockAccessibilityService.applyPolicySnapshot(policy);
        AppUpdater.Pending pending = pendingUpdate(policy);
        if (pending != null) {
            uninstallGuard = false;
            showUpdateScreen(pending);
            return;
        }
        if (policy.blockRequested || policy.isLocked(Challenge.acceptedId(this))) {
            uninstallGuard = false;
            if (!policy.challengePending(Challenge.acceptedId(this))
                    && blockedPanel.getVisibility() == View.VISIBLE
                    && blockedAppAllowed(policy)) {
                // The parent allowed the app this screen was raised for. Leave so the
                // child can go back to it; the service raises the screen again for any
                // other disallowed app. An unanswered challenge keeps the screen up.
                finish();
            } else {
                showBlocked(policy);
            }
        } else if (uninstallGuard) {
            showUninstallGuard();
        } else if (blockedPanel.getVisibility() == View.VISIBLE) {
            // The block just lifted while the block screen was showing. Leave the screen
            // entirely instead of revealing the configuration editor to the device holder.
            finish();
        } else {
            blockedPanel.setVisibility(View.GONE);
            setupScroll.setVisibility(View.VISIBLE);
            if (childName.getText().length() == 0) {
                showSetupForm();
            } else {
                updateStatus();
            }
            exitImmersiveMode();
        }
    }

    /**
     * The verified update the lock screen must gate on, or null. A challenge
     * outranks it (an update must never interrupt a chore), and a parent
     * override releases it so a parent can always get the phone usable.
     */
    private AppUpdater.Pending pendingUpdate(Policy policy) {
        AppUpdater.Pending pending = AppUpdater.pending(this);
        if (!UpdateGate.shouldShowUpdateScreen(
                pending != null,
                policy.challengePending(Challenge.acceptedId(this)),
                policy.isExtensionActive(),
                LocalOverride.isActive(this))) {
            return null;
        }
        return pending;
    }

    /** The block screen was raised with a challenge the child has not answered. */
    private void showFromIntent(Intent intent) {
        uninstallGuard = false;
        blockedPackage = blockedPackageFrom(intent);
        Policy policy = policyFromIntent(intent);
        AppUpdater.Pending pending = pendingUpdate(policy);
        currentPolicy = policy;
        if (pending != null) {
            showUpdateScreen(pending);
            return;
        }
        showBlocked(policy);
    }

    /** The child answered the challenge: the lock is over unless a block is still on. */
    private void onChallengeAccepted() {
        if (currentPolicy.blocked || currentPolicy.blockRequested) {
            showBlocked(currentPolicy);
        } else {
            finish();
        }
    }

    private void showBlocked(Policy policy) {
        currentPolicy = policy;
        updateScreen = false;
        blockedPanel.setBackgroundColor(getColor(R.color.block_background));
        installUpdateButton.setVisibility(View.GONE);
        Challenge challenge = policy.challengePending(Challenge.acceptedId(this))
                ? policy.challenge
                : null;
        if (challenge != null) {
            showChallenge(challenge);
            return;
        }
        challengePanel.hide();
        blockCopyPanel.setVisibility(View.VISIBLE);
        blockMessage.setText(policy.message);
        refreshAllowedApps();
        refreshFamilyStatus();
        refreshBlockedApp();
        if (policy.blocked) {
            blockTitle.setText("This phone is paused");
            blockCountdown.setText("The apps below stay available until a parent unpauses this phone.");
            extensionButton.setVisibility(View.GONE);
            int cost = policy.pointsHourCost > 0 ? policy.pointsHourCost : 200;
            String label = "Buy 1 hour for " + cost + " points";
            if (policy.pointsBalance != null) {
                label += " (" + policy.pointsBalance + " now)";
            }
            pointsHourButton.setText(label);
            pointsHourButton.setVisibility(View.VISIBLE);
            pointsHourButton.setEnabled(true);
        } else if (policy.isExtensionActive()) {
            blockTitle.setText(policy.secondsUntilEnforcement() > 600
                    ? "Extra time granted"
                    : "5 extra minutes granted");
            blockCountdown.setText("Blocking in " + formatRemaining(policy.secondsUntilEnforcement()) + ". You can return to your app now.");
            extensionButton.setVisibility(View.GONE);
            pointsHourButton.setVisibility(View.GONE);
        } else {
            blockTitle.setText("Phone time is ending");
            blockCountdown.setText("Save what you are doing — blocking in " + formatRemaining(policy.secondsUntilEnforcement()) + ".");
            extensionButton.setVisibility(policy.extensionAvailable ? View.VISIBLE : View.GONE);
            extensionButton.setEnabled(true);
            pointsHourButton.setVisibility(View.GONE);
        }
        setupScroll.setVisibility(View.GONE);
        blockedPanel.setVisibility(View.VISIBLE);
        if (policy.blocked) {
            // Only an effective block pins the screen; during grace or an extension the
            // device holder may leave to finish what they are doing.
            enterImmersiveMode();
        } else {
            exitImmersiveMode();
        }
    }

    /** The challenge lock: the same kiosk screen, with the chore on it instead. */
    private void showChallenge(Challenge challenge) {
        updateScreen = false;
        blockedPanel.setBackgroundColor(getColor(R.color.challenge_background));
        installUpdateButton.setVisibility(View.GONE);
        blockCopyPanel.setVisibility(View.GONE);
        challengePanel.show(challenge);
        refreshAllowedApps();
        setupScroll.setVisibility(View.GONE);
        blockedPanel.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    /**
     * The update lock: the phone must install a verified update before it can be
     * used. It stays up until the running version changes or a parent override
     * releases it, and the allowed apps stay reachable underneath.
     */
    private void showUpdateScreen(AppUpdater.Pending pending) {
        updateScreen = true;
        blockedPanel.setBackgroundColor(getColor(R.color.block_background));
        challengePanel.hide();
        blockCopyPanel.setVisibility(View.VISIBLE);
        blockTitle.setText("Update needed");
        blockMessage.setText("This phone must install version " + pending.version
                + " before it can be used.");
        blockCountdown.setText("Tap Install, then confirm in the system installer.");
        familyStatus.setVisibility(View.GONE);
        blockLastApp.setVisibility(View.GONE);
        requestUnblockButton.setVisibility(View.GONE);
        blockRequestStatus.setVisibility(View.GONE);
        extensionButton.setVisibility(View.GONE);
        pointsHourButton.setVisibility(View.GONE);
        installUpdateButton.setVisibility(View.VISIBLE);
        refreshAllowedApps();
        setupScroll.setVisibility(View.GONE);
        blockedPanel.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    private void refreshPolicyIfServiceIsDown() {
        if (AccessibilityEnforcement.isEnabled(this)) {
            return;
        }
        refreshPolicy();
    }

    private void tickCountdown() {
        mainHandler.post(() -> {
            if (isFinishing()
                    || blockedPanel.getVisibility() != View.VISIBLE
                    || uninstallGuard
                    || updateScreen
                    || challengePanel.getVisibility() == View.VISIBLE) {
                return;
            }
            if (!currentPolicy.shouldWarnGrace() && !currentPolicy.isExtensionActive()) {
                return;
            }
            if (currentPolicy.isExtensionActive()) {
                blockCountdown.setText("Blocking in " + formatRemaining(currentPolicy.secondsUntilEnforcement())
                        + ". You can return to your app now.");
            } else if (currentPolicy.shouldWarnGrace()) {
                blockCountdown.setText("Save what you are doing — blocking in "
                        + formatRemaining(currentPolicy.secondsUntilEnforcement()) + ".");
            }
        });
    }

    private void showUninstallGuard() {
        uninstallGuard = true;
        updateScreen = false;
        blockedPanel.setBackgroundColor(getColor(R.color.block_background));
        challengePanel.hide();
        blockCopyPanel.setVisibility(View.VISIBLE);
        installUpdateButton.setVisibility(View.GONE);
        blockTitle.setText("Uninstall is locked");
        blockMessage.setText("A parent has to turn uninstall protection off before this app can be removed.");
        blockCountdown.setText("Leave Settings to go back to the phone.");
        familyStatus.setVisibility(View.GONE);
        blockLastApp.setVisibility(View.GONE);
        requestUnblockButton.setVisibility(View.GONE);
        blockRequestStatus.setVisibility(View.GONE);
        extensionButton.setVisibility(View.GONE);
        pointsHourButton.setVisibility(View.GONE);
        refreshAllowedApps();
        setupScroll.setVisibility(View.GONE);
        blockedPanel.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    static void clearUninstallGuard() {
        MainActivity activity = activeInstance;
        if (activity == null) {
            return;
        }
        activity.mainHandler.post(() -> {
            if (!activity.uninstallGuard) {
                return;
            }
            activity.uninstallGuard = false;
            if (!activity.currentPolicy.blockRequested && !activity.currentPolicy.blocked) {
                activity.finish();
            }
        });
    }

    private void refreshWakeStatus() {
        TextView status = findViewById(R.id.wakeStatus);
        if (status == null) {
            return;
        }
        status.setText(WakeAlarm.statusText(this));
        View allow = findViewById(R.id.wakePermissionButton);
        boolean needsPermission = WakeAlarm.isEnabled(this)
                && Build.VERSION.SDK_INT >= 31
                && !WakeAlarm.canScheduleExact(this);
        allow.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
    }

    private void requestExactAlarmPermission() {
        Intent permission = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        permission.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(permission);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Allow alarms for this app in system settings.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Notification access is optional: without it the daily notification dump is
     * simply empty. The status line says plainly what the app does with it, so a
     * parent knows before turning it on.
     */
    private void refreshNotificationAccess() {
        TextView status = findViewById(R.id.notificationAccessStatus);
        if (status == null) {
            return;
        }
        status.setText(NotificationDump.hasAccess(this)
                ? "Notification access is on. Every notification this phone receives is recorded, and the previous day's list is sent to Home Assistant at midnight."
                : "Notification access is off. Turn it on to record this phone's notifications and send the previous day's list to Home Assistant at midnight.");
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Open Settings, then Notification access.", Toast.LENGTH_LONG).show();
        }
    }

    private void requestPointsHour() {
        pointsHourButton.setEnabled(false);
        blockCountdown.setText("Spending points for 1 hour…");
        executor.execute(() -> {
            try {
                String message = new HomeAssistantClient(AppConfig.load(this)).requestPointsHour();
                Policy policy = new HomeAssistantClient(AppConfig.load(this)).getPolicy();
                mainHandler.post(() -> {
                    applyPolicy(policy);
                    blockCountdown.setText(message);
                    pointsHourButton.setEnabled(true);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    pointsHourButton.setEnabled(true);
                    blockCountdown.setText("Could not contact Home Assistant. Please try again.");
                });
            }
        });
    }

    /** The child's other device, e.g. "Your PC is open — Roblox · seen 2 minutes ago". */
    private void refreshFamilyStatus() {
        // The PC is the interesting peer; a child with two phones has no PC.
        Policy.FamilyPeer peer = currentPolicy.familyPc != null
                ? currentPolicy.familyPc
                : currentPolicy.familyPhone;
        String line = FamilyStatus.line(peer, System.currentTimeMillis());
        familyStatus.setText(line);
        familyStatus.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * The app the child was blocked on, with the button that asks a parent to
     * allow it. Launcher and other non-app windows are not something a parent
     * can allow, so they show nothing.
     */
    private void refreshBlockedApp() {
        String packageName = blockedPackage;
        if (packageName.isEmpty() || !isLaunchableApp(packageName)) {
            blockedAppLabel = "";
            blockLastApp.setVisibility(View.GONE);
            requestUnblockButton.setVisibility(View.GONE);
            blockRequestStatus.setVisibility(View.GONE);
            return;
        }
        blockedAppLabel = appLabel(packageName);
        blockLastApp.setText("Last app used: " + blockedAppLabel);
        blockLastApp.setVisibility(View.VISIBLE);
        boolean asked = !UnblockRequest.mayRequest(
                UnblockRequest.lastRequestAt(this, packageName), System.currentTimeMillis());
        requestUnblockButton.setVisibility(View.VISIBLE);
        requestUnblockButton.setEnabled(!asked);
        blockRequestStatus.setText(asked ? "Asked a parent to allow " + blockedAppLabel + "." : "");
        blockRequestStatus.setVisibility(asked ? View.VISIBLE : View.GONE);
    }

    /** Ask a parent to allow the app the child was blocked on. */
    private void requestUnblock() {
        final String packageName = blockedPackage;
        final String label = blockedAppLabel;
        if (packageName.isEmpty() || label.isEmpty()) {
            return;
        }
        if (!UnblockRequest.mayRequest(
                UnblockRequest.lastRequestAt(this, packageName), System.currentTimeMillis())) {
            blockRequestStatus.setText("Asked a parent to allow " + label + ".");
            blockRequestStatus.setVisibility(View.VISIBLE);
            requestUnblockButton.setEnabled(false);
            return;
        }
        requestUnblockButton.setEnabled(false);
        blockRequestStatus.setText("Asking a parent to allow " + label + "…");
        blockRequestStatus.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            UnblockRequest.Outcome outcome;
            try {
                outcome = new HomeAssistantClient(AppConfig.load(this))
                        .requestUnblock(packageName, label);
            } catch (Exception exception) {
                outcome = UnblockRequest.Outcome.failed(
                        "Could not reach Home Assistant. Please try again.");
            }
            final UnblockRequest.Outcome result = outcome;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                blockRequestStatus.setText(result.message);
                blockRequestStatus.setVisibility(View.VISIBLE);
                if (result.accepted) {
                    UnblockRequest.remember(this, packageName, System.currentTimeMillis());
                    requestUnblockButton.setEnabled(false);
                } else {
                    requestUnblockButton.setEnabled(true);
                }
            });
        });
    }

    /** The app's name for the block screen, or the package name when it has none. */
    private String appLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return UnblockRequest.label(packageName, label == null ? null : label.toString());
        } catch (PackageManager.NameNotFoundException exception) {
            return UnblockRequest.label(packageName, null);
        }
    }

    /** True when this package is an app the child can open, not launcher chrome. */
    private boolean isLaunchableApp(String packageName) {
        return getPackageManager().getLaunchIntentForPackage(packageName) != null;
    }

    private static String blockedPackageFrom(Intent intent) {
        String packageName = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE);
        return packageName == null ? "" : packageName.trim();
    }

    /** True when the parent has allowed the app this block screen was raised for. */
    private boolean blockedAppAllowed(Policy policy) {
        return !blockedPackage.isEmpty() && policy.allowedApps.contains(blockedPackage);
    }

    private void refreshAllowedApps() {
        allowedApps.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int iconSize = Math.round(56 * density);
        int margin = Math.round(6 * density);
        View parent = (View) allowedApps.getParent();
        int available = getResources().getDisplayMetrics().widthPixels
                - parent.getPaddingLeft() - parent.getPaddingRight();
        AllowedApps.Grid grid = AllowedApps.grid(available, Math.round(84 * density), margin);
        allowedApps.setColumnCount(grid.columns);
        for (AllowedApps.Shortcut shortcut
                : AllowedApps.installed(getPackageManager(), currentPolicy.allowedApps)) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(shortcut.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView label = new TextView(this);
            label.setText(shortcut.label);
            label.setTextColor(getColor(R.color.white));
            label.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            label.setTextSize(12);
            // A long label wraps inside the cell and is cut off after two lines,
            // so it can never widen the grid or push the note off the screen.
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(label);
            cell.setOnClickListener(view -> launchPackage(
                    shortcut.packageName, shortcut.label + " is not installed."));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = grid.cellWidth;
            params.setMargins(margin, Math.round(8 * density), margin, 0);
            allowedApps.addView(cell, params);
        }
    }

    private void activateUninstallGuard() {
        DevicePolicyManager manager = getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, BlockDeviceAdmin.class);
        if (manager != null && manager.isAdminActive(admin)) {
            Toast.makeText(this, "Uninstall protection is already on.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "This stops the app being removed until a parent deactivates protection.");
        startActivity(intent);
    }

    private void requestExtension() {
        extensionButton.setEnabled(false);
        blockCountdown.setText("Requesting another 5 minutes…");
        executor.execute(() -> {
            try {
                AppConfig config = AppConfig.load(this);
                HomeAssistantClient client = new HomeAssistantClient(config);
                boolean granted = client.requestExtension();
                Policy policy = client.getPolicy();
                mainHandler.post(() -> {
                    applyPolicy(policy);
                    if (!granted) {
                        Toast.makeText(this, "Another extension is not available yet.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    extensionButton.setEnabled(true);
                    blockCountdown.setText("Could not contact Home Assistant. Please try again.");
                });
            }
        });
    }

    private static Policy policyFromIntent(Intent intent) {
        return new Policy(
                intent.getBooleanExtra(EXTRA_BLOCKED, false),
                intent.getBooleanExtra(EXTRA_BLOCK_REQUESTED, false),
                intent.getStringExtra(EXTRA_BLOCK_MESSAGE),
                intent.getBooleanExtra(EXTRA_EXTENSION_AVAILABLE, false),
                intent.getStringExtra(EXTRA_ENFORCE_AT),
                intent.getStringExtra(EXTRA_EXTENSION_UNTIL),
                null,
                0,
                null,
                intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS),
                null,
                null,
                challengeFrom(intent));
    }

    /** The challenge the service put on the block-screen intent, or null. */
    private static Challenge challengeFrom(Intent intent) {
        String id = intent.getStringExtra(EXTRA_CHALLENGE_ID);
        String phrase = intent.getStringExtra(EXTRA_CHALLENGE_PHRASE);
        if (id == null || id.trim().isEmpty() || phrase == null || phrase.trim().isEmpty()) {
            return null;
        }
        return new Challenge(id, phrase, null);
    }

    private static String formatRemaining(long seconds) {
        if (seconds >= 60) {
            long minutes = (seconds + 59) / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private void updateStatus() {
        AppConfig config = AppConfig.load(this);
        StringBuilder text = new StringBuilder();
        if (!config.isConfigured()) {
            text.append("Enter this child's name and your Home Assistant address.");
        } else if (!AppConfig.acceptsHomeAssistantUrl(config.homeAssistantUrl)) {
            text.append("That Home Assistant address needs to start with https://.");
        } else {
            text.append("This phone is set up.");
        }
        statusText.setText(text.toString());
    }

    private void requestUnrestrictedBattery() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        String packageName = getPackageName();
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                request.setData(Uri.parse("package:" + packageName));
                startActivity(request);
            } catch (RuntimeException exception) {
                openApplicationSettings();
            }
            return;
        }
        Toast.makeText(
                this,
                "Battery optimization is already unrestricted. On Samsung, also remove this app from Sleeping and Deep sleeping apps.",
                Toast.LENGTH_LONG).show();
        openApplicationSettings();
    }

    private void openApplicationSettings() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        details.setData(Uri.parse("package:" + getPackageName()));
        startActivity(details);
    }

    private boolean isAccessibilityEnabled() {
        return AccessibilityEnforcement.isEnabled(this);
    }

    private void launchPackage(String packageName, String missingMessage) {
        // Only resolves packages visible to the current user/profile; Secure
        // Folder / Dual Messenger clones (separate users) are not visible via
        // getLaunchIntentForPackage — primary profile's install is launched.
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this, missingMessage, Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        BlockAccessibilityService.noteAllowedLaunch(packageName);
        startActivity(launch);
    }

    private void enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
