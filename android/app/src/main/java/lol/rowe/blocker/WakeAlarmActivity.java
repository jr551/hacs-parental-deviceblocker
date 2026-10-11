package lol.rowe.blocker;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class WakeAlarmActivity extends Activity {
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

        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        if (audio != null) {
            int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
            int level = WakeSchedule.alarmStreamIndex(WakeAlarm.volumePercent(this), max);
            audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, level, 0);
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(0xFF111827);

        TextView title = new TextView(this);
        title.setText("Wake up");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(36);
        title.setGravity(android.view.Gravity.CENTER);
        layout.addView(title);

        Button stop = new Button(this);
        stop.setText("Stop alarm");
        stop.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(72 * getResources().getDisplayMetrics().density));
        params.topMargin = padding;
        layout.addView(stop, params);
        setContentView(layout);

        Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        ringtone = RingtoneManager.getRingtone(this, tone);
        if (ringtone != null) {
            if (Build.VERSION.SDK_INT >= 21) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            if (Build.VERSION.SDK_INT >= 28) {
                ringtone.setLooping(true);
            }
            ringtone.play();
        }
    }

    @Override
    protected void onDestroy() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        super.onDestroy();
    }
}
