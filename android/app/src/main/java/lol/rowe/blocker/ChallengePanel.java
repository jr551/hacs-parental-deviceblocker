package lol.rowe.blocker;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The chore-challenge lock: the phrase, the answer field, the photo evidence
 * and the button that sends it to a parent. Once it is sent the panel waits —
 * the phone stays locked until a parent approves the photo in Home Assistant.
 * It lives inside the block screen, so an unapproved challenge is enforced by
 * the same path as a parent block and cannot be dismissed on its own.
 *
 * <p>The photo is taken by a camera preview inside this panel, not by handing
 * the shot to the system camera app. The lock screen is what covers every
 * disallowed app, so an external camera activity would be covered by our own
 * lock the moment anything re-raised it, and the child could never take the
 * evidence photo.
 */
public final class ChallengePanel extends LinearLayout {
    /** The longest edge of the thumbnail shown on the lock. */
    private static final int THUMBNAIL_EDGE = 512;
    /** Asking for the camera the first time the child takes a photo. */
    private static final int REQUEST_CAMERA_PERMISSION = 0x43484D;
    /** One half of the highlight pulse. */
    private static final long PULSE_MILLIS = 700L;
    /** How often the waiting view re-reads the clock. */
    private static final long COUNTDOWN_MILLIS = 1000L;
    /** The preview's height, in dp. */
    private static final int PREVIEW_HEIGHT_DP = 320;
    private static final String STATE_SUPER = "super";
    private static final String STATE_CHALLENGE_ID = "challenge_id";
    private static final String STATE_PHRASE = "phrase";
    private static final String STATE_ANSWER = "answer";
    private static final String STATE_MESSAGE = "message";
    private static final String STATE_PHOTO = "photo";
    private static final String STATE_STATUS = "status";
    private static final String STATE_SUBMITTED_AT = "submitted_at";
    private static final String STATE_ATTEMPT = "attempt";
    private static final String STATE_AUTO_APPROVE_AT = "auto_approve_at";
    private static final String STATE_DENIED = "denied";

    /** Told when Home Assistant no longer has the challenge, so the lock can go. */
    interface Listener {
        void onChallengeGone();

        /**
         * The panel's denial state changed. The block screen behind the panel
         * follows it, so a parent's "no" is visible on the whole screen.
         */
        void onChallengeDenied(boolean denied);
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private Listener listener;
    private String challengeId = "";
    private String phrase = "";
    /** The status the panel is showing: pending is the form, submitted is the wait. */
    private String status = Challenge.STATUS_PENDING;
    /** The server's submission time, or "" when it has not sent one. */
    private String submittedAt = "";
    /** When the child sent the challenge, for the waiting view's countdown. */
    private long submittedAtMillis;
    /** Which attempt this is, 1-based, or 0 when Home Assistant did not say. */
    private int attempt;
    /** The server's auto-approval time, or "" when it has not sent one. */
    private String autoApproveAt = "";
    /** True while the panel is showing a parent's rejection. */
    private boolean denied;
    private LinearLayout form;
    private LinearLayout waiting;
    private TextView title;
    private TextView phraseView;
    private EditText answer;
    private Button submitButton;
    private Button takePhoto;
    private Button retake;
    private ImageView thumbnail;
    private TextView statusLine;
    private TextView waitingHint;
    private TextView countdown;
    private LinearLayout cameraView;
    private TextureView preview;
    private Button captureButton;
    private Button cancelCapture;
    /** The open camera, or null when the preview is not running. */
    private android.hardware.Camera camera;
    private File photoFile;
    private boolean uploading;
    /** A message that outranks the step hints until the child changes something. */
    private String message = "";
    /** The element the current step pulses, or null. */
    private View pulsing;
    private ObjectAnimator pulse;
    /** The text colour the highlighted element had before a static highlight. */
    private Integer originalTextColour;
    /** The answer field's own background, so the whole box can glow. */
    private GradientDrawable answerBackground;
    /** The background glow, in step with the alpha pulse. */
    private ValueAnimator answerGlow;
    private int answerStrokeWidth;

    public ChallengePanel(Context context, AttributeSet attributes) {
        super(context, attributes);
        build();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Show the challenge. Whatever the child has already typed and shot stays
     * put when the same challenge is shown again on the next poll, and a
     * challenge a parent sent back returns to the form with the typed phrase
     * still in place.
     */
    void show(Challenge challenge) {
        if (challenge == null) {
            hide();
            return;
        }
        String previousStatus = status;
        boolean sameChallenge = challengeId.equals(challenge.id);
        boolean rejected = sameChallenge
                && Challenge.statusTransition(previousStatus, challenge.status)
                        == Challenge.TRANSITION_REJECTED;
        if (!sameChallenge) {
            // A different challenge is a fresh start: nothing from the last one
            // carries over, including a parent's rejection.
            challengeId = challenge.id;
            phrase = challenge.phrase;
            answer.setText("");
            clearPhoto();
            message = "";
            submittedAtMillis = 0;
            setDenied(Challenge.denied(denied, false, true));
        } else if (rejected) {
            // A parent sent the challenge back: the photo they saw is gone, so
            // the child has to take a new one. What they typed stays put.
            clearPhoto();
            message = "A parent asked for a new photo. Please retake it.";
            setDenied(Challenge.denied(denied, true, false));
        }
        status = challenge.status;
        submittedAt = challenge.submittedAt;
        attempt = challenge.attempt;
        autoApproveAt = challenge.autoApproveAt;
        phraseView.setText(Challenge.attemptLabel(phrase, attempt));
        applyState(Challenge.panelState(status, true));
        setVisibility(VISIBLE);
    }

    /** Take the lock down: the challenge is over, or a parent override is running. */
    void hide() {
        setVisibility(GONE);
        stopPulse();
        stopCountdown();
        closeCamera();
    }

    /** The block screen is going away for good. */
    void release() {
        stopPulse();
        stopCountdown();
        closeCamera();
        executor.shutdownNow();
    }

    /** The block screen is no longer in front: the camera must not stay open. */
    void onPause() {
        closeCamera();
    }

    /** True while the panel is showing a parent's rejection. */
    boolean isDenied() {
        return denied;
    }

    /** Show the form or the wait, and keep the countdown running only while waiting. */
    private void applyState(int state) {
        if (state == Challenge.PANEL_WAITING) {
            closeCamera();
            applySubmittedAt();
            form.setVisibility(GONE);
            waiting.setVisibility(VISIBLE);
            stopPulse();
            startCountdown();
            return;
        }
        stopCountdown();
        waiting.setVisibility(GONE);
        form.setVisibility(cameraView.getVisibility() == VISIBLE ? GONE : VISIBLE);
        updateSubmitState();
    }

    /**
     * Point the countdown at Home Assistant's auto-approval time when it sent
     * one, at the server's submission time plus the approval window when it did
     * not, and at the moment this phone sent it when there is no submission
     * time either — an older Home Assistant sends neither.
     */
    private void applySubmittedAt() {
        long parsed = Challenge.epochMillis(submittedAt);
        if (parsed > 0) {
            submittedAtMillis = parsed;
        } else if (submittedAtMillis == 0) {
            submittedAtMillis = System.currentTimeMillis();
        }
        long deadline = Challenge.approvalDeadlineMillis(
                autoApproveAt, submittedAt, submittedAtMillis);
        waitingHint.setText(Challenge.autoApproveText(deadline));
        waitingHint.setVisibility(deadline > 0 ? VISIBLE : GONE);
    }

    private void startCountdown() {
        removeCallbacks(countdownTick);
        refreshCountdown();
        postDelayed(countdownTick, COUNTDOWN_MILLIS);
    }

    private void stopCountdown() {
        removeCallbacks(countdownTick);
    }

    private void refreshCountdown() {
        long deadline = Challenge.approvalDeadlineMillis(
                autoApproveAt, submittedAt, submittedAtMillis);
        long remaining = deadline <= 0
                ? 0L
                : Math.max(0L, (deadline - System.currentTimeMillis()) / 1000L);
        countdown.setText(Challenge.countdownText(remaining));
    }

    /** Re-reads the clock once a second for as long as the waiting view is up. */
    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            refreshCountdown();
            if (waiting.getVisibility() == VISIBLE) {
                postDelayed(this, COUNTDOWN_MILLIS);
            }
        }
    };

    /** The child answered the camera permission prompt. */
    void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            openCamera();
        } else {
            showMessage("A parent has to allow the camera on this phone.");
        }
    }

    /**
     * Keep the challenge, the typed answer and the photo across a recreation.
     * The child must come back to what they had rather than to an empty lock.
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putParcelable(STATE_SUPER, super.onSaveInstanceState());
        state.putString(STATE_CHALLENGE_ID, challengeId);
        state.putString(STATE_PHRASE, phrase);
        state.putString(STATE_ANSWER, answer.getText().toString());
        state.putString(STATE_MESSAGE, message);
        state.putString(STATE_PHOTO, photoFile == null ? null : photoFile.getAbsolutePath());
        state.putString(STATE_STATUS, status);
        state.putLong(STATE_SUBMITTED_AT, submittedAtMillis);
        state.putInt(STATE_ATTEMPT, attempt);
        state.putString(STATE_AUTO_APPROVE_AT, autoApproveAt);
        state.putBoolean(STATE_DENIED, denied);
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }
        Bundle saved = (Bundle) state;
        super.onRestoreInstanceState(saved.getParcelable(STATE_SUPER));
        challengeId = text(saved.getString(STATE_CHALLENGE_ID));
        phrase = text(saved.getString(STATE_PHRASE));
        answer.setText(text(saved.getString(STATE_ANSWER)));
        message = text(saved.getString(STATE_MESSAGE));
        photoFile = file(saved.getString(STATE_PHOTO));
        status = Challenge.normalizeStatus(saved.getString(STATE_STATUS));
        submittedAtMillis = saved.getLong(STATE_SUBMITTED_AT, 0L);
        attempt = saved.getInt(STATE_ATTEMPT, 0);
        autoApproveAt = text(saved.getString(STATE_AUTO_APPROVE_AT));
        denied = saved.getBoolean(STATE_DENIED, false);
        phraseView.setText(Challenge.attemptLabel(phrase, attempt));
        applyDeniedTone();
        if (hasPhoto()) {
            showThumbnail();
        }
        applyState(Challenge.panelState(status, !challengeId.isEmpty()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static File file(String path) {
        return path == null || path.isEmpty() ? null : new File(path);
    }

    private void build() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(24 * density);

        title = new TextView(getContext());
        title.setText("Chore challenge");
        title.setTextColor(getContext().getColor(R.color.white));
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        addView(title);

        form = new LinearLayout(getContext());
        form.setOrientation(VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView instruction = new TextView(getContext());
        instruction.setText("Type this back to unlock this phone:");
        instruction.setTextColor(getContext().getColor(R.color.muted));
        instruction.setTextSize(16);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, padding / 2, 0, 0);
        form.addView(instruction);

        phraseView = new TextView(getContext());
        phraseView.setTextColor(getContext().getColor(R.color.white));
        phraseView.setTextSize(24);
        phraseView.setTypeface(Typeface.DEFAULT_BOLD);
        phraseView.setGravity(Gravity.CENTER);
        phraseView.setPadding(0, padding / 2, 0, padding / 2);
        form.addView(phraseView);

        answer = new EditText(getContext());
        answer.setHint("Type the phrase here");
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        answer.setTextColor(getContext().getColor(R.color.white));
        answer.setHintTextColor(getContext().getColor(R.color.muted));
        answer.setPadding(padding, padding / 2, padding, padding / 2);
        answerStrokeWidth = Math.round(2 * density);
        answerBackground = new GradientDrawable();
        answerBackground.setShape(GradientDrawable.RECTANGLE);
        answerBackground.setCornerRadius(12 * density);
        answer.setBackground(answerBackground);
        paintAnswerField(0f);
        answer.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                message = "";
                // The child is trying again: a parent's "no" stops being the
                // thing the screen is about.
                clearDenied();
                updateSubmitState();
            }
        });
        form.addView(answer);

        takePhoto = new Button(getContext());
        takePhoto.setText("Take photo");
        takePhoto.setOnClickListener(view -> takePhoto());
        form.addView(takePhoto, buttonParams(density));

        thumbnail = new ImageView(getContext());
        thumbnail.setAdjustViewBounds(true);
        thumbnail.setVisibility(GONE);
        form.addView(thumbnail);

        retake = new Button(getContext());
        retake.setText("Retake");
        retake.setVisibility(GONE);
        retake.setOnClickListener(view -> takePhoto());
        form.addView(retake, buttonParams(density));

        statusLine = new TextView(getContext());
        statusLine.setTextColor(getContext().getColor(R.color.muted));
        statusLine.setTextSize(16);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setPadding(0, padding / 2, 0, padding / 2);
        form.addView(statusLine);

        submitButton = new Button(getContext());
        submitButton.setText("Send for approval");
        submitButton.setEnabled(false);
        submitButton.setOnClickListener(view -> submit());
        form.addView(submitButton, buttonParams(density));

        addView(form);

        cameraView = new LinearLayout(getContext());
        cameraView.setOrientation(VERTICAL);
        cameraView.setGravity(Gravity.CENTER_HORIZONTAL);
        cameraView.setVisibility(GONE);

        preview = new TextureView(getContext());
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startPreview(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        cameraView.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(PREVIEW_HEIGHT_DP * density)));

        captureButton = new Button(getContext());
        captureButton.setText("Capture");
        captureButton.setOnClickListener(view -> capturePhoto());
        cameraView.addView(captureButton, buttonParams(density));

        cancelCapture = new Button(getContext());
        cancelCapture.setText("Cancel");
        cancelCapture.setOnClickListener(view -> showForm());
        cameraView.addView(cancelCapture, buttonParams(density));

        addView(cameraView);

        waiting = new LinearLayout(getContext());
        waiting.setOrientation(VERTICAL);
        waiting.setGravity(Gravity.CENTER_HORIZONTAL);
        waiting.setVisibility(GONE);

        TextView waitingTitle = new TextView(getContext());
        waitingTitle.setText("Waiting for a parent to approve");
        waitingTitle.setTextColor(getContext().getColor(R.color.white));
        waitingTitle.setTextSize(24);
        waitingTitle.setGravity(Gravity.CENTER);
        waitingTitle.setPadding(0, padding / 2, 0, 0);
        waiting.addView(waitingTitle);

        waitingHint = new TextView(getContext());
        waitingHint.setTextColor(getContext().getColor(R.color.muted));
        waitingHint.setTextSize(16);
        waitingHint.setGravity(Gravity.CENTER);
        waitingHint.setPadding(0, padding / 2, 0, 0);
        waiting.addView(waitingHint);

        countdown = new TextView(getContext());
        countdown.setTextColor(getContext().getColor(R.color.challenge_highlight));
        countdown.setTextSize(20);
        countdown.setTypeface(Typeface.DEFAULT_BOLD);
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, padding / 2, 0, 0);
        waiting.addView(countdown);

        addView(waiting);

        applyState(Challenge.PANEL_FORM);
    }

    private LinearLayout.LayoutParams buttonParams(float density) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(64 * density));
        params.topMargin = Math.round(12 * density);
        return params;
    }

    /**
     * The phrase and the photo are both required, and the phrase comes first:
     * the camera stays disabled until the typed answer matches, so the pulse
     * and the enabled state never disagree.
     */
    private void updateSubmitState() {
        boolean phraseMatches = Challenge.matches(phrase, answer.getText().toString());
        boolean hasPhoto = hasPhoto();
        Challenge.Gates gates = Challenge.gates(phraseMatches, hasPhoto);
        takePhoto.setEnabled(gates.takePhoto);
        retake.setEnabled(gates.takePhoto);
        submitButton.setEnabled(gates.send && !uploading);
        highlight(Challenge.step(phraseMatches, hasPhoto));
        if (!message.isEmpty()) {
            statusLine.setText(message);
            return;
        }
        if (!phraseMatches && !hasPhoto) {
            statusLine.setText("Type the phrase and take a photo.");
        } else if (!phraseMatches) {
            statusLine.setText("Now type the phrase exactly.");
        } else if (!hasPhoto) {
            statusLine.setText("Now take a photo as evidence.");
        } else {
            statusLine.setText("Ready to send.");
        }
    }

    private boolean hasPhoto() {
        return photoFile != null && photoFile.exists() && photoFile.length() > 0;
    }

    private void clearPhoto() {
        if (photoFile != null) {
            photoFile.delete();
            photoFile = null;
        }
        thumbnail.setImageDrawable(null);
        thumbnail.setVisibility(GONE);
        retake.setVisibility(GONE);
        takePhoto.setVisibility(VISIBLE);
    }

    /** Show a parent's rejection on the whole panel, or take it back down. */
    private void setDenied(boolean value) {
        if (denied == value) {
            return;
        }
        denied = value;
        applyDeniedTone();
        Listener target = listener;
        if (target != null) {
            target.onChallengeDenied(denied);
        }
    }

    /** The child is starting again, so the rejection is over. */
    private void clearDenied() {
        setDenied(Challenge.denied(denied, false, true));
    }

    private void applyDeniedTone() {
        title.setTextColor(getContext().getColor(
                denied ? R.color.challenge_denied_heading : R.color.white));
        title.setText(denied ? "A parent said no" : "Chore challenge");
    }

    /**
     * Open the camera preview inside the panel. The phrase is the first gate:
     * a child who has not typed it back cannot reach the camera at all.
     */
    private void takePhoto() {
        if (!Challenge.matches(phrase, answer.getText().toString())) {
            showMessage("Type the phrase first, then take the photo.");
            return;
        }
        clearDenied();
        if (!hasCameraPermission()) {
            Activity activity = activity();
            if (activity == null) {
                return;
            }
            activity.requestPermissions(
                    new String[] {android.Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }
        openCamera();
    }

    private boolean hasCameraPermission() {
        return getContext().checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openCamera() {
        if (getVisibility() != VISIBLE) {
            // The lock went away while the permission prompt was up.
            return;
        }
        message = "";
        updateSubmitState();
        cameraView.setVisibility(VISIBLE);
        form.setVisibility(GONE);
        if (preview.isAvailable()) {
            startPreview(preview.getSurfaceTexture());
        }
    }

    /** Start the preview once the panel's surface exists. */
    private void startPreview(SurfaceTexture surface) {
        if (camera != null || surface == null || cameraView.getVisibility() != VISIBLE) {
            return;
        }
        try {
            camera = android.hardware.Camera.open();
            if (camera == null) {
                showCameraFailure("The camera is not available.");
                return;
            }
            android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(0, info);
            camera.setDisplayOrientation(displayOrientation(info.orientation));
            camera.setPreviewTexture(surface);
            camera.startPreview();
        } catch (Exception exception) {
            showCameraFailure("The camera could not be opened.");
        }
    }

    /** The rotation that makes the preview upright on this display. */
    private int displayOrientation(int cameraOrientation) {
        int rotation = 0;
        android.view.Display display = getDisplay();
        if (display != null) {
            rotation = display.getRotation();
        }
        int degrees = rotation * 90;
        return (cameraOrientation - degrees + 360) % 360;
    }

    private void showCameraFailure(String text) {
        showForm();
        showMessage(text);
    }

    /** Take the shot; the JPEG comes back on the camera's own thread. */
    private void capturePhoto() {
        final android.hardware.Camera open = camera;
        if (open == null) {
            showCameraFailure("The camera is not ready.");
            return;
        }
        captureButton.setEnabled(false);
        try {
            open.takePicture(null, null, (data, source) -> writePhoto(data));
        } catch (Exception exception) {
            captureButton.setEnabled(true);
            showCameraFailure("The photo could not be taken.");
        }
    }

    /** Keep the shot, then put the form back with the new thumbnail on it. */
    private void writePhoto(byte[] data) {
        File directory = photoDirectory(getContext());
        if (data == null || data.length == 0) {
            post(() -> {
                captureButton.setEnabled(true);
                showCameraFailure("The photo could not be taken.");
            });
            return;
        }
        if (!directory.exists() && !directory.mkdirs()) {
            post(() -> {
                captureButton.setEnabled(true);
                showCameraFailure("Could not save the photo.");
            });
            return;
        }
        File file = new File(directory, "evidence-" + System.currentTimeMillis() + ".jpg");
        boolean written = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
            written = true;
        } catch (Exception exception) {
            // The message below says what happened; the file is cleaned up.
        }
        if (!written) {
            file.delete();
            post(() -> {
                captureButton.setEnabled(true);
                showCameraFailure("Could not save the photo.");
            });
            return;
        }
        post(() -> {
            captureButton.setEnabled(true);
            showForm();
            if (photoFile != null && !photoFile.equals(file)) {
                photoFile.delete();
            }
            photoFile = file;
            message = "";
            showThumbnail();
            updateSubmitState();
        });
    }

    /** Stop the preview and release the camera; safe to call at any time. */
    private void closeCamera() {
        android.hardware.Camera open = camera;
        camera = null;
        if (open != null) {
            try {
                open.stopPreview();
            } catch (Exception ignored) {
                // The preview was already stopped.
            }
            try {
                open.release();
            } catch (Exception ignored) {
                // The camera was already released.
            }
        }
        if (cameraView != null) {
            cameraView.setVisibility(GONE);
        }
        if (captureButton != null) {
            captureButton.setEnabled(true);
        }
    }

    /** Put the form back after the camera closes. */
    private void showForm() {
        closeCamera();
        applyState(Challenge.panelState(status, true));
    }

    /**
     * Send the photo and hand the challenge to a parent. The lock stays up
     * afterwards: the phone only unlocks when a state poll stops carrying the
     * challenge, which happens once a parent approves it.
     */
    private void submit() {
        if (uploading || !hasPhoto()) {
            return;
        }
        uploading = true;
        message = "Sending the photo…";
        updateSubmitState();
        final String id = challengeId;
        final File file = photoFile;
        executor.execute(() -> {
            String failure = "";
            boolean sent = false;
            boolean gone = false;
            byte[] jpeg = null;
            try {
                jpeg = uploadBytes(file);
            } catch (Exception exception) {
                failure = "The photo could not be read. Please retake it.";
            }
            if (jpeg != null) {
                try {
                    HomeAssistantClient client = new HomeAssistantClient(AppConfig.load(getContext()));
                    Challenge.Outcome evidence = client.uploadChallengeEvidence(jpeg);
                    if (evidence.gone) {
                        gone = true;
                    } else if (!evidence.accepted) {
                        failure = evidence.message;
                    } else {
                        Challenge.Outcome result = client.submitChallenge(id);
                        sent = result.accepted;
                        gone = result.gone;
                        failure = result.message;
                    }
                } catch (Exception exception) {
                    failure = "Could not reach Home Assistant. Please try again.";
                }
            }
            final boolean finished = gone;
            final boolean waiting = sent;
            final String text = failure;
            post(() -> {
                uploading = false;
                if (finished) {
                    // Home Assistant no longer has this challenge: let the lock go.
                    Challenge.hold(getContext(), null);
                    Listener target = listener;
                    if (target != null) {
                        target.onChallengeGone();
                    }
                    return;
                }
                if (waiting) {
                    // The photo is in and a parent now has to approve it. The
                    // wait is held from now until the next poll brings the
                    // server's own submission time.
                    submittedAt = "";
                    submittedAtMillis = System.currentTimeMillis();
                    status = Challenge.STATUS_SUBMITTED;
                    Challenge.hold(getContext(), new Challenge(
                            challengeId,
                            phrase,
                            null,
                            Challenge.STATUS_SUBMITTED,
                            Instant.now().toString(),
                            attempt,
                            ""));
                    applyState(Challenge.PANEL_WAITING);
                    return;
                }
                message = text;
                updateSubmitState();
            });
        });
    }

    /**
     * The JPEG to upload: the captured photo scaled and compressed to fit
     * {@link PhotoBudget}, which keeps a modern camera far under Home Assistant's
     * size cap. The EXIF orientation is applied, so the photo is not sideways.
     */
    private static byte[] uploadBytes(File file) throws Exception {
        int orientation = exifOrientation(file);
        int[] bounds = orientedBounds(file, orientation);
        PhotoEncoder encoder = new PhotoEncoder(file, orientation, bounds);
        try {
            return PhotoBudget.encodeWithinTarget(bounds[0], bounds[1], encoder);
        } finally {
            encoder.release();
        }
    }

    /** The EXIF orientation of the captured photo, or normal when it has none. */
    private static int exifOrientation(File file) {
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            return new android.media.ExifInterface(input)
                    .getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception exception) {
            return android.media.ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** The photo's width and height as the child sees it, after any EXIF rotation. */
    private static int[] orientedBounds(File file, int orientation) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int width = Math.max(1, bounds.outWidth);
        int height = Math.max(1, bounds.outHeight);
        return swapsSides(orientation)
                ? new int[] {height, width}
                : new int[] {width, height};
    }

    /** True when the EXIF orientation turns the photo a quarter turn. */
    private static boolean swapsSides(int orientation) {
        return orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90
                || orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270
                || orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE;
    }

    /** The photo's pixels turned the way the EXIF orientation says. */
    private static Bitmap rotate(Bitmap bitmap, int orientation) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        Bitmap turned = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (turned != bitmap) {
            bitmap.recycle();
        }
        return turned;
    }

    /** Encodes the captured photo at each size and quality the budget asks for. */
    private static final class PhotoEncoder implements PhotoBudget.Encoder {
        private final File file;
        private final int orientation;
        private final int[] bounds;
        private Bitmap decoded;

        PhotoEncoder(File file, int orientation, int[] bounds) {
            this.file = file;
            this.orientation = orientation;
            this.bounds = bounds;
        }

        @Override
        public byte[] encode(int width, int height, int quality) throws Exception {
            Bitmap source = decoded();
            Bitmap scaled = source.getWidth() == width && source.getHeight() == height
                    ? source
                    : Bitmap.createScaledBitmap(source, width, height, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            if (scaled != source) {
                scaled.recycle();
            }
            return out.toByteArray();
        }

        /** The photo decoded once, subsampled to about the largest edge we keep. */
        private Bitmap decoded() {
            if (decoded == null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(
                        bounds[0], bounds[1], PhotoBudget.MAX_EDGE);
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) {
                    throw new IllegalStateException("The photo could not be read.");
                }
                decoded = rotate(bitmap, orientation);
            }
            return decoded;
        }

        void release() {
            if (decoded != null) {
                decoded.recycle();
                decoded = null;
            }
        }
    }

    /** The largest power-of-two subsample that still covers {@code targetEdge}. */
    private static int sampleSize(int width, int height, int targetEdge) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (targetEdge > 0 && longest / (sample * 2) >= targetEdge) {
            sample *= 2;
        }
        return sample;
    }

    private void showThumbnail() {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_EDGE);
        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
        if (bitmap == null) {
            thumbnail.setVisibility(GONE);
            return;
        }
        thumbnail.setImageBitmap(rotate(bitmap, exifOrientation(photoFile)));
        thumbnail.setVisibility(VISIBLE);
        retake.setVisibility(VISIBLE);
        takePhoto.setVisibility(GONE);
    }

    /**
     * Point the child at the step they are on: the phrase field, then the
     * camera, then the button that sends it. Exactly one element is highlighted
     * at a time, and the highlight is a pulse unless the system has animations
     * turned off, in which case it is a plain colour change.
     */
    private void highlight(int step) {
        View target = step == Challenge.STEP_PHRASE
                ? answer
                : step == Challenge.STEP_PHOTO ? takePhoto : submitButton;
        if (target == pulsing) {
            return;
        }
        stopPulse();
        pulsing = target;
        if (target == answer) {
            startAnswerGlow();
        }
        if (animationsDisabled()) {
            target.setAlpha(1f);
            if (target instanceof TextView) {
                TextView text = (TextView) target;
                originalTextColour = text.getCurrentTextColor();
                text.setTextColor(getContext().getColor(R.color.challenge_highlight));
            }
            return;
        }
        pulse = ObjectAnimator.ofFloat(target, "alpha", 1f, 0.35f);
        pulse.setDuration(PULSE_MILLIS);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.start();
    }

    /** Stop the pulse and put the element back the way it was. */
    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        stopAnswerGlow();
        if (pulsing != null) {
            pulsing.setAlpha(1f);
            if (originalTextColour != null && pulsing instanceof TextView) {
                ((TextView) pulsing).setTextColor(originalTextColour);
            }
            originalTextColour = null;
            pulsing = null;
        }
    }

    /**
     * Glow the answer field itself, not just its text: the fill and the outline
     * move between the normal field colours and the highlight colour in step
     * with the alpha pulse, so the whole box reads as one glowing element.
     */
    private void startAnswerGlow() {
        stopAnswerGlow();
        if (animationsDisabled()) {
            paintAnswerField(1f);
            return;
        }
        answerGlow = ValueAnimator.ofFloat(0f, 1f);
        answerGlow.setDuration(PULSE_MILLIS);
        answerGlow.setRepeatMode(ValueAnimator.REVERSE);
        answerGlow.setRepeatCount(ValueAnimator.INFINITE);
        answerGlow.addUpdateListener(
                animation -> paintAnswerField((float) animation.getAnimatedValue()));
        answerGlow.start();
    }

    private void stopAnswerGlow() {
        if (answerGlow != null) {
            answerGlow.cancel();
            answerGlow = null;
        }
        paintAnswerField(0f);
    }

    /** Paint the field at {@code fraction} of the way to the highlight colour. */
    private void paintAnswerField(float fraction) {
        if (answerBackground == null) {
            return;
        }
        ArgbEvaluator blend = new ArgbEvaluator();
        int fill = (int) blend.evaluate(
                fraction,
                getContext().getColor(R.color.challenge_field),
                getContext().getColor(R.color.challenge_field_highlight));
        int stroke = (int) blend.evaluate(
                fraction,
                getContext().getColor(R.color.challenge_field_stroke),
                getContext().getColor(R.color.challenge_highlight));
        answerBackground.setColor(fill);
        answerBackground.setStroke(answerStrokeWidth, stroke);
    }

    /** True when the system asks apps not to animate. */
    private boolean animationsDisabled() {
        try {
            return Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f) == 0f;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void showMessage(String text) {
        message = text;
        updateSubmitState();
    }

    /** The cache directory the challenge photos live in. */
    private static File photoDirectory(Context context) {
        return new File(context.getCacheDir(), "challenge");
    }

    /** The block screen this panel lives in. */
    private Activity activity() {
        Context context = getContext();
        return context instanceof Activity ? (Activity) context : null;
    }
}
