package lol.rowe.blocker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
    private static final int MEDIA_PERMISSION_REQUEST = 2;
    static final String EXTRA_BLOCK_SCREEN = "block_screen";
    static final String EXTRA_BLOCKED = "blocked";
    static final String EXTRA_BLOCK_REQUESTED = "block_requested";
    static final String EXTRA_BLOCK_MESSAGE = "block_message";
    static final String EXTRA_EXTENSION_AVAILABLE = "extension_available";
    static final String EXTRA_ENFORCE_AT = "enforce_at";
    static final String EXTRA_EXTENSION_UNTIL = "extension_until";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScrollView setupScroll;
    private View blockedPanel;
    private TextView statusText;
    private TextView blockTitle;
    private TextView blockMessage;
    private TextView blockCountdown;
    private TextView mediaBackupStatus;
    private Button extensionButton;
    private EditText homeAssistantUrl;
    private EditText deviceId;
    private EditText deviceKey;
    private volatile Policy currentPolicy = Policy.UNBLOCKED;
    /** The configuration editor stays hidden until a parent enters the PIN. */
    private boolean setupUnlocked;
    private boolean pinDialogShowing;
    private int pinFailures;

    private void loadPinFailures() {
        pinFailures = getSharedPreferences("rowe_blocker", MODE_PRIVATE).getInt("pin_failures", 0);
    }

    private void savePinFailures() {
        getSharedPreferences("rowe_blocker", MODE_PRIVATE).edit().putInt("pin_failures", pinFailures).apply();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinFailures();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setupScroll = findViewById(R.id.setupScroll);
        blockedPanel = findViewById(R.id.blockedPanel);
        statusText = findViewById(R.id.statusText);
        blockTitle = findViewById(R.id.blockTitle);
        blockMessage = findViewById(R.id.blockMessage);
        blockCountdown = findViewById(R.id.blockCountdown);
        mediaBackupStatus = findViewById(R.id.mediaBackupStatus);
        extensionButton = findViewById(R.id.extensionButton);
        homeAssistantUrl = findViewById(R.id.homeAssistantUrl);
        deviceId = findViewById(R.id.deviceId);
        deviceKey = findViewById(R.id.deviceKey);

        AppConfig config = AppConfig.load(this);
        homeAssistantUrl.setText(config.homeAssistantUrl);
        deviceId.setText(config.deviceId);
        deviceKey.setText(config.deviceKey);

        findViewById(R.id.saveButton).setOnClickListener(view -> saveConfiguration());
        findViewById(R.id.changePinButton).setOnClickListener(
                view -> promptForNewParentPin(false));
        findViewById(R.id.mediaBackupPermissionButton).setOnClickListener(
                view -> requestMediaBackupAccess());
        findViewById(R.id.accessibilityButton).setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.whatsAppButton).setOnClickListener(
                view -> launchPackage(PolicyDecision.WHATSAPP, "WhatsApp is not installed."));
        findViewById(R.id.mapsButton).setOnClickListener(
                view -> launchPackage(PolicyDecision.GOOGLE_MAPS, "Google Maps is not installed."));
        extensionButton.setOnClickListener(view -> requestExtension());
        findViewById(R.id.parentOverrideButton)
                .setOnClickListener(view -> promptForParentOverride());

        // Publish the activity only after every view used by applyPolicy is ready.
        activeInstance = this;

        // The layout ships with the editor visible; keep it hidden until either the
        // block screen takes over or a parent unlocks it with the PIN.
        setupScroll.setVisibility(View.GONE);
        if (getIntent().getBooleanExtra(EXTRA_BLOCK_SCREEN, false)) {
            showBlocked(policyFromIntent(getIntent()));
        } else {
            promptForParentPin();
        }
        executor.scheduleWithFixedDelay(this::refreshPolicy, 0, 3, TimeUnit.SECONDS);
        if (AppConfig.load(this).isConfigured()) {
            MediaBackupScheduler.schedule(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_BLOCK_SCREEN, false)) {
            showBlocked(policyFromIntent(intent));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        executor.execute(this::refreshPolicy);
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!currentPolicy.shouldCaptureForeground()) {
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
                        savePinFailures();
                        unlockSetup();
                        return;
                    }
                    pinFailures++;
                    savePinFailures();
                    if (pinFailures >= ParentPin.MAX_ATTEMPTS) {
                        Toast.makeText(this, "Incorrect PIN. Closing.", Toast.LENGTH_LONG).show();
                        finish();
                    } else {
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
     * attempt limiting so this phone cannot be tricked into granting time.
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
                        String message;
                        try {
                            message = new HomeAssistantClient(AppConfig.load(MainActivity.this))
                                    .requestParentOverride(pin);
                        } catch (Exception exception) {
                            message = "Could not reach Home Assistant.";
                        }
                        final String result = message;
                        mainHandler.post(() -> blockCountdown.setText(result));
                    });
                })
                .setNegativeButton("Cancel", (dialog, which) -> pinDialogShowing = false)
                .setOnDismissListener(dialog -> pinDialogShowing = false)
                .show();
    }

    private void unlockSetup() {
        setupUnlocked = true;
        // Ask once, from the parent-gated screen: without this the passive
        // position reader has nothing it is allowed to read.
        if (!PassiveLocation.hasPermission(this)) {
            requestPermissions(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
            }, 1);
        }
        setupScroll.setVisibility(View.VISIBLE);
        updateStatus();
        updateMediaBackupStatus();
    }

    private void saveConfiguration() {
        AppConfig config = new AppConfig(
                homeAssistantUrl.getText().toString().trim(),
                deviceId.getText().toString().trim().toLowerCase(Locale.ROOT),
                deviceKey.getText().toString().trim());
        if (!config.isConfigured()) {
            Toast.makeText(this, "Complete all three configuration fields.", Toast.LENGTH_LONG).show();
            return;
        }
        AppConfig.save(this, config);
        MediaBackupScheduler.schedule(this);
        Toast.makeText(this, "Configuration saved.", Toast.LENGTH_SHORT).show();
        updateStatus();
        executor.execute(this::refreshPolicy);
    }

    private void requestMediaBackupAccess() {
        AppConfig config = AppConfig.load(this);
        if (!config.isConfigured()) {
            Toast.makeText(this, "Save the Home Assistant configuration first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        executor.execute(() -> {
            try {
                HomeAssistantClient.MediaBackupConfig backup =
                        new HomeAssistantClient(config).getMediaBackupConfig();
                mainHandler.post(() -> {
                    if (!backup.enabled) {
                        Toast.makeText(this,
                                "Enable media backup in this device's Home Assistant options first.",
                                Toast.LENGTH_LONG).show();
                        updateMediaBackupStatus();
                        return;
                    }
                    if (MediaBackupScheduler.hasMediaPermission(this)) {
                        MediaBackupScheduler.schedule(this);
                        Toast.makeText(this, "Media backup access is already granted.",
                                Toast.LENGTH_SHORT).show();
                        updateMediaBackupStatus();
                        return;
                    }
                    String[] permissions;
                    if (Build.VERSION.SDK_INT >= 34) {
                        permissions = new String[] {
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            };
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        permissions = new String[] {
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                            };
                    } else {
                        permissions = new String[] {
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                        };
                    }
                    requestPermissions(permissions, MEDIA_PERMISSION_REQUEST);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> Toast.makeText(this,
                        "Could not read media backup settings from Home Assistant.",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSION_REQUEST) {
            return;
        }
        if (MediaBackupScheduler.hasMediaPermission(this)) {
            MediaBackupScheduler.setInitialComplete(this, false);
            MediaBackupScheduler.schedule(this);
            Toast.makeText(this,
                    "Backup access granted. Uploads wait for external power; initial sync also waits for Wi-Fi.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Full photo and video access was not granted.",
                    Toast.LENGTH_LONG).show();
        }
        updateMediaBackupStatus();
    }

    private void updateMediaBackupStatus() {
        AppConfig config = AppConfig.load(this);
        if (!config.isConfigured()) {
            mediaBackupStatus.setText("Optional media backup is not configured.");
            return;
        }
        executor.execute(() -> {
            String message;
            try {
                HomeAssistantClient.MediaBackupConfig backup =
                        new HomeAssistantClient(config).getMediaBackupConfig();
                if (!backup.enabled) {
                    message = "Optional media backup is disabled in Home Assistant.";
                } else if (!MediaBackupScheduler.hasMediaPermission(this)) {
                    message = "Media backup is enabled; parent permission is required.";
                } else if (!MediaBackupScheduler.isExternallyPowered(this)) {
                    message = "Media backup is waiting for external power.";
                } else if (!MediaBackupScheduler.isInitialComplete(this)) {
                    message = "Initial photo/video sync is waiting for or using Wi-Fi while powered.";
                } else {
                    message = "Initial media sync is complete; powered incremental backup is scheduled.";
                }
            } catch (Exception exception) {
                message = "Could not read media backup status from Home Assistant.";
            }
            String result = message;
            mainHandler.post(() -> mediaBackupStatus.setText(result));
        });
    }

    private void refreshPolicy() {
        try {
            AppConfig config = AppConfig.load(this);
            if (!config.isConfigured()) {
                mainHandler.post(() -> failOpen("Home Assistant is not configured. Blocking is disabled."));
                return;
            }
            Policy policy = new HomeAssistantClient(config).getPolicy();
            mainHandler.post(() -> applyPolicy(policy));
        } catch (Exception exception) {
            mainHandler.post(() -> failOpen("Cannot verify Home Assistant policy. Blocking is disabled."));
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

    private void failOpen(String message) {
        applyPolicy(Policy.UNBLOCKED);
        if (!isFinishing()) {
            statusText.setText(message);
        }
    }

    private void applyPolicy(Policy policy) {
        currentPolicy = policy;
        BlockAccessibilityService.applyPolicySnapshot(policy);
        if (policy.blockRequested || policy.blocked) {
            showBlocked(policy);
        } else if (blockedPanel.getVisibility() == View.VISIBLE) {
            // The block just lifted while the block screen was showing. Leave the screen
            // entirely instead of revealing the configuration editor to the device holder.
            finish();
        } else {
            blockedPanel.setVisibility(View.GONE);
            // Never reveal the editor (HA URL / device id / key) without the parent PIN.
            if (setupUnlocked) {
                setupScroll.setVisibility(View.VISIBLE);
                updateStatus();
            } else {
                setupScroll.setVisibility(View.GONE);
                promptForParentPin();
            }
            exitImmersiveMode();
        }
    }

    private void showBlocked(Policy policy) {
        currentPolicy = policy;
        blockMessage.setText(policy.message);
        if (policy.blocked) {
            blockTitle.setText("This phone is paused");
            blockCountdown.setText("Use WhatsApp or Google Maps while you wait for a parent to unpause this phone.");
            extensionButton.setVisibility(View.GONE);
        } else if (policy.isExtensionActive()) {
            blockTitle.setText("5 extra minutes granted");
            blockCountdown.setText("Blocking in " + formatRemaining(policy.secondsUntilEnforcement()) + ". You can return to your app now.");
            extensionButton.setVisibility(View.GONE);
        } else {
            blockTitle.setText("Phone time is ending");
            blockCountdown.setText("Save what you are doing — blocking in " + formatRemaining(policy.secondsUntilEnforcement()) + ".");
            extensionButton.setVisibility(policy.extensionAvailable ? View.VISIBLE : View.GONE);
            extensionButton.setEnabled(true);
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
                intent.getStringExtra(EXTRA_EXTENSION_UNTIL));
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
        if (!config.isConfigured()) {
            statusText.setText("Enter the Home Assistant device details below.");
            return;
        }
        statusText.setText(isAccessibilityEnabled()
                ? "Configured. Accessibility enforcement is enabled."
                : "Configured, but Accessibility enforcement still needs to be enabled.");
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName component = new ComponentName(this, BlockAccessibilityService.class);
        String expected = component.flattenToString();
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    private void launchPackage(String packageName, String missingMessage) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this, missingMessage, Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
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
