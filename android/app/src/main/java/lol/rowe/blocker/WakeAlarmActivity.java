package lol.rowe.blocker;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class WakeAlarmActivity extends Activity {
    private MediaPlayer player;
    private Ringtone ringtone;

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

        AudioManager audio = getSystemService(AudioManager.class);
        if (audio != null) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int level = WakeSchedule.alarmStreamIndex(WakeAlarm.volumePercent(this), max);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0);
        }
        // A phone left on silent or in Do Not Disturb still has its alarm stream
        // muted; the alarm must be audible anyway.
        AlarmAudio.unmuteAlarmStream(this);

        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padding = Math.round(28 * density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(0xFF111827);

        TextView title = new TextView(this);
        title.setText("Wake up");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(36);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button snooze = new Button(this);
        snooze.setText("Snooze 5 min");
        snooze.setOnClickListener(view -> snoozeAlarm());
        Button stop = new Button(this);
        stop.setText("Stop alarm");
        stop.setOnClickListener(view -> finish());
        int buttonHeight = Math.round(72 * density);
        buttons.addView(snooze, new LinearLayout.LayoutParams(0, buttonHeight, 1f));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, buttonHeight, 1f);
        stopParams.leftMargin = Math.round(12 * density);
        buttons.addView(stop, stopParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = padding;
        layout.addView(buttons, rowParams);
        setContentView(layout);

        startAlarmSound();
    }

    /**
     * Play the alarm tone as an alarm, so silent mode and Do Not Disturb do not
     * hide it. The player loops; the Ringtone path is the fallback when the
     * player cannot start.
     */
    private void startAlarmSound() {
        Uri tone = AlarmAudio.alarmTone();
        if (tone != null) {
            try {
                MediaPlayer created = new MediaPlayer();
                created.setAudioAttributes(AlarmAudio.alarmAttributes());
                created.setDataSource(this, tone);
                created.setLooping(true);
                created.setOnErrorListener((failed, what, extra) -> {
                    failed.release();
                    if (player == failed) {
                        player = null;
                    }
                    startRingtoneFallback();
                    return true;
                });
                created.prepare();
                created.start();
                player = created;
                return;
            } catch (Exception exception) {
                // Fall through to the Ringtone path.
            }
        }
        startRingtoneFallback();
    }

    private void startRingtoneFallback() {
        Uri tone = AlarmAudio.alarmTone();
        if (tone == null) {
            return;
        }
        Ringtone created = RingtoneManager.getRingtone(this, tone);
        if (created == null) {
            return;
        }
        created.setAudioAttributes(AlarmAudio.alarmAttributes());
        created.play();
        ringtone = created;
    }

    private void snoozeAlarm() {
        WakeAlarm.snooze(this, WakeAlarm.SNOOZE_MILLIS);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        super.onDestroy();
    }
}
