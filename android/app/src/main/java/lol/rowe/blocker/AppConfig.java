package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

final class AppConfig {
    private static final String PREFS = "rowe_blocker";
    private static final String URL = "ha_url";
    private static final String DEVICE_ID = "device_id";

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

    /**
     * HTTPS is always accepted. Plain HTTP is accepted only for local network
     * names so a shared Wi-Fi cannot observe the device key on a public host.
     */
    static boolean acceptsHomeAssistantUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        return "http".equalsIgnoreCase(scheme) && isLocalNetworkHost(host);
    }

    static boolean isLocalNetworkHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equals(normalized)
                || normalized.endsWith(".local")
                || normalized.endsWith(".home")
                || normalized.endsWith(".lan")
                || normalized.endsWith(".internal")
                || normalized.endsWith(".home.arpa")
                || normalized.endsWith(".localdomain")) {
            return true;
        }
        if (normalized.contains(":")) {
            return isLocalIpv6(normalized);
        }
        if (!normalized.contains(".")) {
            if (normalized.startsWith("0x")) {
                return false;
            }
            for (int index = 0; index < normalized.length(); index++) {
                if (!Character.isDigit(normalized.charAt(index))) {
                    return true;
                }
            }
            return false;
        }
        return isLocalIpv4(normalized);
    }

    private static boolean isLocalIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (octets[index] < 0 || octets[index] > 255 || !parts[index].equals(Integer.toString(octets[index]))) {
                return false;
            }
        }
        if (octets[0] == 10 || octets[0] == 127) {
            return true;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        if (octets[0] == 169 && octets[1] == 254) {
            return true;
        }
        return octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31;
    }

    private static boolean isLocalIpv6(String host) {
        if ("::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }
        return host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd");
    }

    static AppConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new AppConfig(
                preferences.getString(URL, ""),
                preferences.getString(DEVICE_ID, ""),
                DeviceKeyStore.read(context));
    }

    static boolean save(Context context, AppConfig config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(URL, config.homeAssistantUrl)
                .putString(DEVICE_ID, config.deviceId)
                .apply();
        return DeviceKeyStore.write(context, config.deviceKey);
    }
}
