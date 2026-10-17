package lol.rowe.blocker;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The chore-challenge lock: the phrase, the answer field, the photo evidence
 * and the button that sends it to a parent. Once it is sent the panel waits —
 * the phone stays locked until a parent approves the photo in Home Assistant.
 * It lives inside the block screen, so an unapproved challenge is enforced by
 * the same path as a parent block and cannot be dismissed on its own.
 */
public final class ChallengePanel extends LinearLayout {
    /** The longest edge of the thumbnail shown on the lock. */
    private static final int THUMBNAIL_EDGE = 512;
    private static final int REQUEST_CAPTURE = 0x43484C;
    /** One half of the highlight pulse. */
    private static final long PULSE_MILLIS = 700L;
    /** How often the waiting view re-reads the clock. */
    private static final long COUNTDOWN_MILLIS = 1000L;
    private static final String STATE_SUPER = "super";
    private static final String STATE_CHALLENGE_ID = "challenge_id";
    private static final String STATE_PHRASE = "phrase";
    private static final String STATE_ANSWER = "answer";
    private static final String STATE_MESSAGE = "message";
    private static final String STATE_PHOTO = "photo";
    private static final String STATE_PENDING_PHOTO = "pending_photo";
    private static final String STATE_STATUS = "status";
    private static final String STATE_SUBMITTED_AT = "submitted_at";

    /** Told when Home Assistant no longer has the challenge, so the lock can go. */
    interface Listener {
        void onChallengeGone();
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
    private LinearLayout form;
    private LinearLayout waiting;
    private TextView phraseView;
    private EditText answer;
    private Button submitButton;
    private Button takePhoto;
    private Button retake;
    private ImageView thumbnail;
    private TextView statusLine;
    private TextView countdown;
    private File photoFile;
    /** The file the camera is writing to, until the shot comes back. */
    private File pendingPhotoFile;
    private boolean uploading;
    /** A message that outranks the step hints until the child changes something. */
    private String message = "";
    /** The element the current step pulses, or null. */
    private View pulsing;
    private ObjectAnimator pulse;
    /** The text colour the highlighted element had before a static highlight. */
    private Integer originalTextColour;

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
        if (!challengeId.equals(challenge.id)) {
            challengeId = challenge.id;
            phrase = challenge.phrase;
            phraseView.setText(phrase);
            answer.setText("");
            clearPhoto();
            message = "";
            submittedAtMillis = 0;
        } else if (Challenge.statusTransition(status, challenge.status)
                == Challenge.TRANSITION_REJECTED) {
            // A parent sent the challenge back: the photo they saw is gone, so
            // the child has to take a new one. What they typed stays put.
            clearPhoto();
            message = "A parent asked for a new photo. Please retake it.";
        }
        status = challenge.status;
        submittedAt = challenge.submittedAt;
        applyState(Challenge.panelState(status, true));
        setVisibility(VISIBLE);
    }

    /** Take the lock down: the challenge is over, or a parent override is running. */
    void hide() {
        setVisibility(GONE);
        stopPulse();
        stopCountdown();
        endCapture();
    }

    /** The block screen is going away for good. */
    void release() {
        stopPulse();
        stopCountdown();
        endCapture();
        executor.shutdownNow();
    }

    /** Show the form or the wait, and keep the countdown running only while waiting. */
    private void applyState(int state) {
        if (state == Challenge.PANEL_WAITING) {
            applySubmittedAt();
            form.setVisibility(GONE);
            waiting.setVisibility(VISIBLE);
            stopPulse();
            startCountdown();
            return;
        }
        stopCountdown();
        waiting.setVisibility(GONE);
        form.setVisibility(VISIBLE);
        updateSubmitState();
    }

    /**
     * Point the countdown at the server's submission time when it has one, and
     * at the moment this phone sent it otherwise — an older Home Assistant
     * sends no {@code submitted_at}, and the wait still has to be counted.
     */
    private void applySubmittedAt() {
        long parsed = Challenge.epochMillis(submittedAt);
        if (parsed > 0) {
            submittedAtMillis = parsed;
        } else if (submittedAtMillis == 0) {
            submittedAtMillis = System.currentTimeMillis();
        }
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
        long elapsed = Math.max(0L, (System.currentTimeMillis() - submittedAtMillis) / 1000L);
        countdown.setText(Challenge.countdownText(elapsed));
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

    /** @return true when the result belonged to the challenge camera. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CAPTURE) {
            return false;
        }
        endCapture();
        File captured = pendingPhotoFile;
        pendingPhotoFile = null;
        if (resultCode != Activity.RESULT_OK || captured == null || !captured.exists()
                || captured.length() == 0) {
            if (captured != null) {
                captured.delete();
            }
            if (hasPhoto()) {
                // The child backed out of the camera; the earlier photo stands.
                updateSubmitState();
            } else {
                showMessage("No photo was taken.");
            }
            return true;
        }
        if (photoFile != null && !photoFile.equals(captured)) {
            photoFile.delete();
        }
        photoFile = captured;
        message = "";
        showThumbnail();
        updateSubmitState();
        return true;
    }

    /**
     * Keep the challenge, the typed answer and the photo across a recreation.
     * The camera can push this screen out of memory, and the child must come
     * back to what they had rather than to an empty lock.
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
        state.putString(
                STATE_PENDING_PHOTO,
                pendingPhotoFile == null ? null : pendingPhotoFile.getAbsolutePath());
        state.putString(STATE_STATUS, status);
        state.putLong(STATE_SUBMITTED_AT, submittedAtMillis);
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
        phraseView.setText(phrase);
        answer.setText(text(saved.getString(STATE_ANSWER)));
        message = text(saved.getString(STATE_MESSAGE));
        photoFile = file(saved.getString(STATE_PHOTO));
        pendingPhotoFile = file(saved.getString(STATE_PENDING_PHOTO));
        status = Challenge.normalizeStatus(saved.getString(STATE_STATUS));
        submittedAtMillis = saved.getLong(STATE_SUBMITTED_AT, 0L);
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

        TextView title = new TextView(getContext());
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

        TextView waitingHint = new TextView(getContext());
        waitingHint.setText("This can take up to 1 hour.");
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

    /** The phrase and the photo are both required; the hint says which is missing. */
    private void updateSubmitState() {
        boolean phraseMatches = Challenge.matches(phrase, answer.getText().toString());
        boolean hasPhoto = hasPhoto();
        submitButton.setEnabled(Challenge.canSubmit(phraseMatches, hasPhoto) && !uploading);
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

    private void takePhoto() {
        Activity activity = activity();
        if (activity == null) {
            return;
        }
        try {
            File directory = ChallengeFileProvider.directory(getContext());
            if (!directory.exists() && !directory.mkdirs()) {
                showMessage("Could not prepare the camera.");
                return;
            }
            File file = new File(directory, "evidence-" + System.currentTimeMillis() + ".jpg");
            Uri uri = ChallengeFileProvider.uriFor(getContext(), file);
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            ResolveInfo camera = getContext().getPackageManager().resolveActivity(capture, 0);
            if (camera == null || camera.activityInfo == null) {
                showMessage("No camera app is available.");
                return;
            }
            // The camera is not on the allow-list, so the lock screen would
            // cover it mid-shot. Allow it until the shot comes back.
            BlockAccessibilityService.beginCapture(camera.activityInfo.packageName);
            pendingPhotoFile = file;
            message = "";
            activity.startActivityForResult(capture, REQUEST_CAPTURE);
        } catch (Exception exception) {
            showMessage("Could not open the camera.");
        }
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
                            Instant.now().toString()));
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
        if (pulsing != null) {
            pulsing.setAlpha(1f);
            if (originalTextColour != null && pulsing instanceof TextView) {
                ((TextView) pulsing).setTextColor(originalTextColour);
            }
            originalTextColour = null;
            pulsing = null;
        }
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

    private void endCapture() {
        BlockAccessibilityService.endCapture();
    }

    /** The block screen this panel lives in. */
    private Activity activity() {
        Context context = getContext();
        return context instanceof Activity ? (Activity) context : null;
    }
}
