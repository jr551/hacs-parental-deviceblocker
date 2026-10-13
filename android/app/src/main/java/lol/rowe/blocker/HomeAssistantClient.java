package lol.rowe.blocker;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

final class HomeAssistantClient {
    static final class MediaBackupConfig {
        final boolean enabled;
        final long maxFileBytes;
        final String configurationId;

        MediaBackupConfig(boolean enabled, long maxFileBytes, String configurationId) {
            this.enabled = enabled;
            this.maxFileBytes = maxFileBytes;
            this.configurationId = configurationId;
        }
    }

    static final class PresignedUpload {
        final String url;
        final long contentLength;

        PresignedUpload(String url, long contentLength) {
            this.url = url;
            this.contentLength = contentLength;
        }
    }

    static final class SetupResult {
        final boolean ok;
        final boolean pinRequired;
        final String deviceId;
        final String deviceKey;
        final String childName;
        final String message;

        SetupResult(
                boolean ok,
                boolean pinRequired,
                String deviceId,
                String deviceKey,
                String childName,
                String message) {
            this.ok = ok;
            this.pinRequired = pinRequired;
            this.deviceId = deviceId == null ? "" : deviceId;
            this.deviceKey = deviceKey == null ? "" : deviceKey;
            this.childName = childName == null ? "" : childName;
            this.message = message == null ? "" : message;
        }

        static SetupResult message(String message) {
            return new SetupResult(false, false, "", "", "", message);
        }
    }

    /**
     * Ask Home Assistant to create or reuse this child's phone. The API key
     * comes back to the phone and is not shown to the parent.
     */
    static SetupResult provision(String homeAssistantUrl, String childName, String pin) {
        if (!AppConfig.acceptsHomeAssistantUrl(homeAssistantUrl)) {
            return SetupResult.message("That Home Assistant address needs to start with https://.");
        }
        AppConfig address = new AppConfig(homeAssistantUrl, "setup", "setup");
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(address.homeAssistantUrl + "/api/rowe_pc_blocker/provision");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            JSONObject request = new JSONObject().put("child_name", childName.trim());
            if (pin != null && !pin.trim().isEmpty()) {
                request.put("pin", pin.trim());
            }
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            JSONObject body = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (status == 200 && body.optBoolean("ok")) {
                String deviceId = body.optString("device_id", "").trim();
                String deviceKey = body.optString("api_key", "").trim();
                String name = body.optString("child_name", childName).trim();
                if (deviceId.isEmpty() || deviceKey.isEmpty()) {
                    return SetupResult.message("Home Assistant could not set up this phone.");
                }
                return new SetupResult(true, false, deviceId, deviceKey, name, "This phone is set up.");
            }
            if (body.optBoolean("pin_required") || status == 401) {
                return new SetupResult(
                        false, true, "", "", "", "Enter the parent PIN for this child.");
            }
            String error = body.optString("error", "").trim();
            if (error.isEmpty() || error.length() > 160) {
                return SetupResult.message("Home Assistant could not set up this phone.");
            }
            return SetupResult.message(error);
        } catch (Exception exception) {
            return SetupResult.message("Home Assistant could not be reached.");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private final AppConfig config;

    HomeAssistantClient(AppConfig config) {
        if (!AppConfig.acceptsHomeAssistantUrl(config.homeAssistantUrl)) {
            throw new IllegalArgumentException(
                    "Home Assistant URL must be HTTPS or HTTP on a local network address.");
        }
        this.config = config;
    }

    Policy getPolicy() throws Exception {
        HttpURLConnection connection = open("state", "GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Home Assistant returned " + status);
            }
            JSONObject body = new JSONObject(readAll(connection.getInputStream()));
            String deviceId = requiredString(body, "device_id");
            if (!config.deviceId.equals(deviceId)) {
                throw new IOException("Home Assistant returned a policy for another device");
            }
            boolean blocked = requiredBoolean(body, "blocked");
            boolean requested = requiredBoolean(body, "block_requested");
            boolean extensionAvailable = requiredBoolean(body, "extension_available");
            String message = requiredString(body, "message");
            String enforceAt = optionalInstant(body, "enforce_at");
            String extensionUntil = optionalInstant(body, "extension_until");
            validatePolicy(blocked, requested, enforceAt, extensionUntil);
            Integer pointsBalance = body.has("points_balance") && !body.isNull("points_balance")
                    ? body.optInt("points_balance")
                    : null;
            return new Policy(
                    blocked,
                    requested,
                    message,
                    extensionAvailable,
                    enforceAt,
                    extensionUntil,
                    pointsBalance,
                    body.optInt("points_hour_cost", 200),
                    readWakeAlarm(body));
        } finally {
            connection.disconnect();
        }
    }

    String requestPointsHour() throws Exception {
        HttpURLConnection connection = open("points_hour", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = stream == null ? "" : readAll(stream);
            if (status >= 200 && status < 300) {
                return "Unlocked for 1 hour.";
            }
            if (!body.isEmpty()) {
                JSONObject error = new JSONObject(body);
                String message = error.optString("error", "");
                if (!message.isEmpty()) {
                    return message;
                }
            }
            return "Could not buy an hour (" + status + ").";
        } finally {
            connection.disconnect();
        }
    }

    boolean requestExtension() throws Exception {
        HttpURLConnection connection = open("extension", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            return connection.getResponseCode() == 200;
        } finally {
            connection.disconnect();
        }
    }

    MediaBackupConfig getMediaBackupConfig() throws Exception {
        HttpURLConnection connection = open("backup/config", "GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Home Assistant returned " + status);
            }
            JSONObject body = new JSONObject(readAll(connection.getInputStream()));
            return new MediaBackupConfig(
                    requiredBoolean(body, "enabled"),
                    body.optLong("max_file_bytes", 0),
                    body.optString("configuration_id", ""));
        } finally {
            connection.disconnect();
        }
    }

    PresignedUpload requestMediaUpload(
            String relativePath, String displayName, long size) throws Exception {
        HttpURLConnection connection = open("backup/presign", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject()
                    .put("relative_path", truncate(relativePath, 1024))
                    .put("display_name", truncate(displayName, 255))
                    .put("size", size)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Home Assistant returned " + status);
            }
            JSONObject body = new JSONObject(readAll(connection.getInputStream()));
            String url = requiredString(body, "url");
            JSONObject headers = body.optJSONObject("headers");
            long signedLength = headers == null
                    ? 0
                    : Long.parseLong(headers.optString("Content-Length", "0"));
            if (!url.startsWith("https://") || signedLength != size) {
                throw new IOException("Home Assistant returned an invalid upload grant");
            }
            return new PresignedUpload(url, signedLength);
        } finally {
            connection.disconnect();
        }
    }

    void postMediaBackupStatus(
            String status,
            long uploaded,
            long skipped,
            boolean initialComplete,
            String lastSuccess,
            String error) throws Exception {
        HttpURLConnection connection = open("backup/status", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject()
                    .put("status", truncate(status, 32))
                    .put("uploaded", uploaded)
                    .put("skipped", skipped)
                    .put("initial_complete", initialComplete)
                    .put("last_success", truncate(lastSuccess, 64))
                    .put("error", truncate(error, 255))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IOException("Home Assistant returned " + response);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Ask Home Assistant for a parent-PIN override. Home Assistant verifies the
     * PIN, counts attempts, and applies the lockout, so this method only relays
     * the outcome; it never decides whether the PIN was right.
     *
     * @return a message suitable for showing on the block screen.
     */
    String requestParentOverride(String pin) throws Exception {
        HttpURLConnection connection = open("parent_override", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject().put("pin", pin).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : readAll(stream);
            if (status == 200) {
                return "Override accepted. Waiting for the device to unlock.";
            }
            try {
                String error = optionalString(new JSONObject(body), "error");
                if (error != null && !error.isEmpty()) {
                    return error;
                }
            } catch (Exception ignored) {
                // Fall through to the generic message below.
            }
            return "Home Assistant refused the override (" + status + ").";
        } finally {
            connection.disconnect();
        }
    }

    void postActivity(String application, String windowTitle) throws Exception {
        postActivity(application, windowTitle, null, null, null);
    }

    /**
     * @param location a cached position to piggy-back on this heartbeat, or null.
     */
    void postActivity(String application, String windowTitle, android.location.Location location)
            throws Exception {
        postActivity(application, windowTitle, location, null, null);
    }

    /**
     * @param uiStatus enforcement or portal status. Null omits the field so Home
     *     Assistant keeps the previous value.
     */
    void postActivity(
            String application,
            String windowTitle,
            android.location.Location location,
            String uiStatus,
            String uiError) throws Exception {
        HttpURLConnection connection = open("activity", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject()
                    .put("application", truncate(application, 255))
                    .put("window_title", truncate(windowTitle, 255))
                    .put("username", android.os.Build.MODEL)
                    .put("agent_version", BuildConfig.VERSION_NAME);
            if (uiStatus != null) {
                body.put("ui_status", truncate(uiStatus, 64))
                        .put("ui_error", truncate(uiError, 255));
            }
            if (location != null) {
                body.put("latitude", location.getLatitude())
                        .put("longitude", location.getLongitude())
                        .put("gps_accuracy", Math.round(location.getAccuracy()))
                        .put("location_age_seconds",
                                Math.max(0, (System.currentTimeMillis() - location.getTime()) / 1000))
                        .put("location_provider", location.getProvider());
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Home Assistant returned " + status);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String endpoint, String method) throws Exception {
        String id = URLEncoder.encode(config.deviceId, StandardCharsets.UTF_8.name());
        URI uri = URI.create(config.homeAssistantUrl + "/api/rowe_pc_blocker/" + id + "/" + endpoint);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-Device-Blocker-Key", config.deviceKey);
        return connection;
    }

    private static String readAll(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String optionalString(JSONObject body, String name) {
        return body.isNull(name) ? null : body.optString(name, null);
    }

    private static boolean requiredBoolean(JSONObject body, String name) throws IOException {
        Object value = body.opt(name);
        if (!(value instanceof Boolean)) {
            throw new IOException("Home Assistant policy field " + name + " is not a boolean");
        }
        return (Boolean) value;
    }

    private static String requiredString(JSONObject body, String name) throws IOException {
        Object value = body.opt(name);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException("Home Assistant policy field " + name + " is not a string");
        }
        return (String) value;
    }

    /**
     * A missing or unreadable alarm leaves the phone's last Home Assistant cache
     * in place. It must not fail the rest of the policy poll.
     */
    private static WakeSchedule.Update readWakeAlarm(JSONObject body) {
        if (!body.has("wake_alarm") || body.isNull("wake_alarm")) {
            return null;
        }
        JSONObject alarm = body.optJSONObject("wake_alarm");
        if (alarm == null || !(alarm.opt("enabled") instanceof Boolean)) {
            return null;
        }
        if (!alarm.has("hour") || !alarm.has("minute") || !alarm.has("days") || !alarm.has("volume")) {
            return null;
        }
        return WakeSchedule.Update.of(
                alarm.optBoolean("enabled"),
                alarm.optInt("hour", -1),
                alarm.optInt("minute", -1),
                alarm.optInt("days", 0),
                alarm.optInt("volume", -1));
    }

    private static String optionalInstant(JSONObject body, String name) throws IOException {
        if (!body.has(name) || body.isNull(name)) {
            return null;
        }
        Object value = body.opt(name);
        if (!(value instanceof String)) {
            throw new IOException("Home Assistant policy field " + name + " is not a timestamp");
        }
        try {
            Instant.parse((String) value);
        } catch (DateTimeParseException exception) {
            throw new IOException("Home Assistant policy field " + name + " is invalid", exception);
        }
        return (String) value;
    }

    static void validatePolicy(
            boolean blocked,
            boolean requested,
            String enforceAt,
            String extensionUntil) throws IOException {
        if (blocked && !requested) {
            throw new IOException("Home Assistant returned an inconsistent block policy");
        }
        if (requested != (enforceAt != null)) {
            throw new IOException("Home Assistant returned an incomplete block policy");
        }
        if (!requested && extensionUntil != null) {
            throw new IOException("Home Assistant returned an extension without a block request");
        }
        if (requested && !blocked && enforceAt != null
                && !Instant.now().isBefore(Instant.parse(enforceAt))) {
            throw new IOException("Home Assistant returned a stale grace policy");
        }
        if (blocked && extensionUntil != null
                && Instant.now().isBefore(Instant.parse(extensionUntil))) {
            throw new IOException("Home Assistant returned a block during an active extension");
        }
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
