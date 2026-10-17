package lol.rowe.blocker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * The alarm stream, unmuted, so an alarm or a challenge beep is audible when
 * the phone is on silent or in Do Not Disturb.
 */
final class AlarmAudio {
    private AlarmAudio() {
    }

    static void unmuteAlarmStream(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        try {
            audio.setStreamMute(AudioManager.STREAM_ALARM, false);
        } catch (RuntimeException ignored) {
            // The alarm usage below is still exempt from Do Not Disturb.
        }
    }

    static AudioAttributes alarmAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    /** The phone's default alarm tone, or its ringtone when it has no alarm tone. */
    static Uri alarmTone() {
        Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return tone == null ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) : tone;
    }
}
