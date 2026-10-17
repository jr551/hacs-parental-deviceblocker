package lol.rowe.blocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The chore-challenge lock: the phrase, the answer field, the photo evidence
 * and the Accept button. It lives inside the block screen, so an unanswered
 * challenge is enforced by the same path as a parent block and cannot be
 * dismissed on its own.
 */
public final class ChallengePanel extends LinearLayout {
    /** How often the lock beeps while it is up. */
    static final long BEEP_INTERVAL_MS = 25_000L;
    /** How long each beep lasts. */
    static final long BEEP_LENGTH_MS = 2_000L;
    /** The longest edge of the thumbnail shown on the lock. */
    private static final int THUMBNAIL_EDGE = 512;
    private static final int REQUEST_CAPTURE = 0x43484C;

    /** Told when the child answers the challenge, so the lock can be taken down. */
    interface Listener {
        void onChallengeAccepted();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Runnable beepTask = this::beep;
    private Listener listener;
    private String challengeId = "";
    private String phrase = "";
    private TextView phraseView;
    private EditText answer;
    private Button accept;
    private Button takePhoto;
    private Button retake;
    private ImageView thumbnail;
    private TextView status;
    private File photoFile;
    /** The file the camera is writing to, until the shot comes back. */
    private File pendingPhotoFile;
    private MediaPlayer beepPlayer;
    private boolean uploading;
    /** A message that outranks the step hints until the child changes something. */
    private String message = "";

    public ChallengePanel(Context context, AttributeSet attributes) {
        super(context, attributes);
        build();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Show the challenge. Whatever the child has already typed and shot stays
     * put when the same challenge is shown again on the next poll.
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
            updateAcceptState();
            beep();
        }
        setVisibility(VISIBLE);
    }

    /** Take the lock down: the challenge was answered, or a parent override is running. */
    void hide() {
        setVisibility(GONE);
        stopBeep();
        endCapture();
    }

    /** Beep again when the lock comes back to the front. */
    void onResume() {
        if (getVisibility() == VISIBLE) {
            beep();
        }
    }

    void onPause() {
        stopBeep();
    }

    /** The block screen is going away for good. */
    void release() {
        stopBeep();
        endCapture();
        executor.shutdownNow();
    }

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
                updateAcceptState();
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
        updateAcceptState();
        return true;
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

        TextView instruction = new TextView(getContext());
        instruction.setText("Type this back to unlock this phone:");
        instruction.setTextColor(getContext().getColor(R.color.muted));
        instruction.setTextSize(16);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, padding / 2, 0, 0);
        addView(instruction);

        phraseView = new TextView(getContext());
        phraseView.setTextColor(getContext().getColor(R.color.white));
        phraseView.setTextSize(24);
        phraseView.setTypeface(Typeface.DEFAULT_BOLD);
        phraseView.setGravity(Gravity.CENTER);
        phraseView.setPadding(0, padding / 2, 0, padding / 2);
        addView(phraseView);

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
                updateAcceptState();
            }
        });
        addView(answer);

        takePhoto = new Button(getContext());
        takePhoto.setText("Take photo");
        takePhoto.setOnClickListener(view -> takePhoto());
        addView(takePhoto, buttonParams(density));

        thumbnail = new ImageView(getContext());
        thumbnail.setAdjustViewBounds(true);
        thumbnail.setVisibility(GONE);
        addView(thumbnail);

        retake = new Button(getContext());
        retake.setText("Retake");
        retake.setVisibility(GONE);
        retake.setOnClickListener(view -> takePhoto());
        addView(retake, buttonParams(density));

        status = new TextView(getContext());
        status.setTextColor(getContext().getColor(R.color.muted));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, padding / 2, 0, padding / 2);
        addView(status);

        accept = new Button(getContext());
        accept.setText("Accept");
        accept.setEnabled(false);
        accept.setOnClickListener(view -> accept());
        addView(accept, buttonParams(density));

        updateAcceptState();
    }

    private LinearLayout.LayoutParams buttonParams(float density) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(64 * density));
        params.topMargin = Math.round(12 * density);
        return params;
    }

    /** The phrase and the photo are both required; the hint says which is missing. */
    private void updateAcceptState() {
        boolean phraseMatches = Challenge.matches(phrase, answer.getText().toString());
        boolean hasPhoto = hasPhoto();
        accept.setEnabled(Challenge.canAccept(phraseMatches, hasPhoto) && !uploading);
        if (!message.isEmpty()) {
            status.setText(message);
            return;
        }
        if (!phraseMatches && !hasPhoto) {
            status.setText("Type the phrase and take a photo.");
        } else if (!phraseMatches) {
            status.setText("Now type the phrase exactly.");
        } else if (!hasPhoto) {
            status.setText("Now take a photo as evidence.");
        } else {
            status.setText("Ready to send.");
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

    private void accept() {
        if (uploading || !hasPhoto()) {
            return;
        }
        uploading = true;
        message = "Sending the photo…";
        updateAcceptState();
        final String id = challengeId;
        final File file = photoFile;
        executor.execute(() -> {
            String failure = "";
            boolean accepted = false;
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
                    if (!evidence.accepted) {
                        failure = evidence.message;
                    } else {
                        Challenge.Outcome result = client.acceptChallenge(id);
                        accepted = result.accepted;
                        failure = result.message;
                    }
                } catch (Exception exception) {
                    failure = "Could not reach Home Assistant. Please try again.";
                }
            }
            final boolean done = accepted;
            final String text = failure;
            mainHandler.post(() -> {
                uploading = false;
                if (done) {
                    Challenge.rememberAccepted(getContext(), id);
                    Listener target = listener;
                    if (target != null) {
                        target.onChallengeAccepted();
                    }
                    return;
                }
                message = text;
                updateAcceptState();
            });
        });
    }

    /**
     * The JPEG to upload: the captured photo downscaled so its longest edge is
     * at most {@link Challenge#MAX_UPLOAD_EDGE}, which keeps a modern camera
     * well under Home Assistant's size cap.
     */
    private static byte[] uploadBytes(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int[] target = Challenge.uploadSize(
                bounds.outWidth, bounds.outHeight, Challenge.MAX_UPLOAD_EDGE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        if (target != null) {
            options.inSampleSize = sampleSize(
                    bounds.outWidth, bounds.outHeight, Math.max(target[0], target[1]));
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new IllegalStateException("The photo could not be read.");
        }
        if (target != null
                && (bitmap.getWidth() != target[0] || bitmap.getHeight() != target[1])) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, target[0], target[1], true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, Challenge.UPLOAD_QUALITY, out);
        bitmap.recycle();
        return out.toByteArray();
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
        thumbnail.setImageBitmap(bitmap);
        thumbnail.setVisibility(VISIBLE);
        retake.setVisibility(VISIBLE);
        takePhoto.setVisibility(GONE);
    }

    private void showMessage(String text) {
        message = text;
        updateAcceptState();
    }

    /** Beep on appearance and every {@link #BEEP_INTERVAL_MS} while the lock is up. */
    private void beep() {
        AlarmAudio.unmuteAlarmStream(getContext());
        stopBeep();
        Uri tone = AlarmAudio.alarmTone();
        if (tone != null) {
            try {
                MediaPlayer created = new MediaPlayer();
                created.setAudioAttributes(AlarmAudio.alarmAttributes());
                created.setDataSource(getContext(), tone);
                created.prepare();
                created.start();
                beepPlayer = created;
                mainHandler.postDelayed(this::stopBeep, BEEP_LENGTH_MS);
            } catch (Exception ignored) {
                // A silent beep must not stop the lock from working.
            }
        }
        mainHandler.postDelayed(beepTask, BEEP_INTERVAL_MS);
    }

    private void stopBeep() {
        mainHandler.removeCallbacks(beepTask);
        if (beepPlayer != null) {
            beepPlayer.release();
            beepPlayer = null;
        }
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
