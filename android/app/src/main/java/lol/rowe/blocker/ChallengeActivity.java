package lol.rowe.blocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The chore challenge lock: the phone stays locked until the child types the
 * phrase back and sends a photo as evidence. Raised on every policy poll while
 * the challenge is unanswered, and taken down once it is accepted or a parent
 * override is running.
 */
public final class ChallengeActivity extends Activity {
    static final String EXTRA_CHALLENGE_ID = "challenge_id";
    static final String EXTRA_CHALLENGE_PHRASE = "challenge_phrase";
    /** How often the lock beeps while it is up. */
    static final long BEEP_INTERVAL_MS = 25_000L;
    /** How long each beep lasts. */
    static final long BEEP_LENGTH_MS = 2_000L;
    /** The longest edge of the thumbnail shown on the lock. */
    private static final int THUMBNAIL_EDGE = 512;
    private static final int REQUEST_CAPTURE = 0x43484C;

    private static volatile ChallengeActivity activeInstance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Runnable beepTask = () -> beep();
    private String challengeId = "";
    private String phrase = "";
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

    /**
     * Show the lock when the policy carries an unanswered challenge, and take it
     * down when the challenge is answered or a parent override is running. The
     * block screen is untouched: this is an extra lock, not a replacement.
     * Safe to call from any thread.
     */
    static void sync(Context context, Policy policy) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncNow(context, policy);
            return;
        }
        MAIN.post(() -> syncNow(context, policy));
    }

    private static void syncNow(Context context, Policy policy) {
        Challenge challenge = policy.challenge;
        boolean needed = challenge != null
                && Challenge.needsAnswer(challenge.id, Challenge.acceptedId(context))
                && !policy.isExtensionActive()
                && !LocalOverride.isActive(context);
        ChallengeActivity activity = activeInstance;
        if (!needed) {
            if (activity != null) {
                activity.finish();
            }
            return;
        }
        if (activity != null) {
            if (activity.challengeId.equals(challenge.id)) {
                return;
            }
            // A new challenge replaced the one on screen.
            activity.finish();
        }
        Intent intent = new Intent(context, ChallengeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHALLENGE_ID, challenge.id)
                .putExtra(EXTRA_CHALLENGE_PHRASE, challenge.phrase);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        challengeId = text(getIntent().getStringExtra(EXTRA_CHALLENGE_ID));
        phrase = text(getIntent().getStringExtra(EXTRA_CHALLENGE_PHRASE));
        buildLayout();
        activeInstance = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        beep();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(beepTask);
        stopBeep();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        mainHandler.removeCallbacks(beepTask);
        stopBeep();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildLayout() {
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(24 * density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(0xFF111827);

        TextView title = new TextView(this);
        title.setText("Chore challenge");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText("Type this back to unlock this phone:");
        instruction.setTextColor(0xFF9CA3AF);
        instruction.setTextSize(16);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, padding / 2, 0, 0);
        layout.addView(instruction);

        TextView phraseView = new TextView(this);
        phraseView.setText(phrase);
        phraseView.setTextColor(0xFFFFFFFF);
        phraseView.setTextSize(24);
        phraseView.setTypeface(Typeface.DEFAULT_BOLD);
        phraseView.setGravity(Gravity.CENTER);
        phraseView.setPadding(0, padding / 2, 0, padding / 2);
        layout.addView(phraseView);

        answer = new EditText(this);
        answer.setHint("Type the phrase here");
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        answer.setTextColor(0xFFFFFFFF);
        answer.setHintTextColor(0xFF9CA3AF);
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
        layout.addView(answer);

        takePhoto = new Button(this);
        takePhoto.setText("Take photo");
        takePhoto.setOnClickListener(view -> takePhoto());
        layout.addView(takePhoto, buttonParams(density));

        thumbnail = new ImageView(this);
        thumbnail.setAdjustViewBounds(true);
        thumbnail.setVisibility(View.GONE);
        layout.addView(thumbnail);

        retake = new Button(this);
        retake.setText("Retake");
        retake.setVisibility(View.GONE);
        retake.setOnClickListener(view -> takePhoto());
        layout.addView(retake, buttonParams(density));

        status = new TextView(this);
        status.setTextColor(0xFF9CA3AF);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, padding / 2, 0, padding / 2);
        layout.addView(status);

        accept = new Button(this);
        accept.setText("Accept");
        accept.setEnabled(false);
        accept.setOnClickListener(view -> accept());
        layout.addView(accept, buttonParams(density));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
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

    private void takePhoto() {
        try {
            File directory = ChallengeFileProvider.directory(this);
            if (!directory.exists() && !directory.mkdirs()) {
                showMessage("Could not prepare the camera.");
                return;
            }
            File file = new File(directory, "evidence-" + System.currentTimeMillis() + ".jpg");
            Uri uri = ChallengeFileProvider.uriFor(this, file);
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (capture.resolveActivity(getPackageManager()) == null) {
                showMessage("No camera app is available.");
                return;
            }
            pendingPhotoFile = file;
            message = "";
            startActivityForResult(capture, REQUEST_CAPTURE);
        } catch (Exception exception) {
            showMessage("Could not open the camera.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        File captured = pendingPhotoFile;
        pendingPhotoFile = null;
        if (resultCode != RESULT_OK || captured == null || !captured.exists()
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
            return;
        }
        if (photoFile != null && !photoFile.equals(captured)) {
            photoFile.delete();
        }
        photoFile = captured;
        message = "";
        showThumbnail();
        updateAcceptState();
    }

    private void showThumbnail() {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_EDGE);
        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
        if (bitmap == null) {
            thumbnail.setVisibility(View.GONE);
            return;
        }
        thumbnail.setImageBitmap(bitmap);
        thumbnail.setVisibility(View.VISIBLE);
        retake.setVisibility(View.VISIBLE);
        takePhoto.setVisibility(View.GONE);
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
                    HomeAssistantClient client = new HomeAssistantClient(AppConfig.load(this));
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
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                uploading = false;
                if (done) {
                    Challenge.rememberAccepted(this, id);
                    finish();
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

    private void showMessage(String text) {
        message = text;
        updateAcceptState();
    }

    /** Beep on appearance and every {@link #BEEP_INTERVAL_MS} while the lock is up. */
    private void beep() {
        AlarmAudio.unmuteAlarmStream(this);
        stopBeep();
        Uri tone = AlarmAudio.alarmTone();
        if (tone != null) {
            try {
                MediaPlayer created = new MediaPlayer();
                created.setAudioAttributes(AlarmAudio.alarmAttributes());
                created.setDataSource(this, tone);
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
        if (beepPlayer != null) {
            beepPlayer.release();
            beepPlayer = null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
