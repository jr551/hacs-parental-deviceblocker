package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

final class AppConfig {
    private static final String PREFS = "rowe_blocker";
    private static final String URL = "ha_url";
    private static final String DEVICE_ID = "device_id";
    private static final String DEVICE_KEY = "device_key";

    final String homeAssistantUrl;
    final String deviceId;
    final String deviceKey;

    AppConfig(String homeAssistantUrl, String deviceId, String deviceKey) {
        this.homeAssistantUrl = withoutTrailingSlashes(homeAssistantUrl == null ? "" : homeAssistantUrl.trim());
        this.deviceId = deviceId == null ? "" : deviceId.trim().toLowerCase(Locale.ROOT);
        this.deviceKey = deviceKey == null ? "" : deviceKey.trim();
    }

    private static String withoutTrailingSlashes(String value) {
        String trimmed = value.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    boolean isConfigured() {
        return !homeAssistantUrl.trim().isEmpty()
                && !deviceId.trim().isEmpty()
                && !deviceKey.trim().isEmpty();
    }

    static AppConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new AppConfig(
                preferences.getString(URL, ""),
                preferences.getString(DEVICE_ID, ""),
                preferences.getString(DEVICE_KEY, ""));
    }

    static void save(Context context, AppConfig config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(URL, config.homeAssistantUrl)
                .putString(DEVICE_ID, config.deviceId)
                .putString(DEVICE_KEY, config.deviceKey)
                .apply();
    }
}
