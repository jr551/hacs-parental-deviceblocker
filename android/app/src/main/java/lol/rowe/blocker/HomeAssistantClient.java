package lol.rowe.blocker;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class HomeAssistantClient {
    /**
     * POST /api/rowe_pc_blocker/provision. A parent approves the request in Home
     * Assistant; the API key only comes back after that.
     */
    static ProvisionFlow.Outcome startProvision(String homeAssistantUrl, String childName) {
        if (!AppConfig.acceptsHomeAssistantUrl(homeAssistantUrl)) {
            return ProvisionFlow.Outcome.error(
                    "That Home Assistant address needs to start with https://.");
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
            byte[] bytes = new JSONObject()
                    .put("child_name", childName.trim())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            JSONObject body = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            return readProvision(body, status, false);
        } catch (Exception exception) {
            return ProvisionFlow.Outcome.error("Home Assistant could not be reached.");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** GET /api/rowe_pc_blocker/provision/{request_id}. */
    static ProvisionFlow.Outcome pollProvision(String homeAssistantUrl, String requestId) {
        if (!AppConfig.acceptsHomeAssistantUrl(homeAssistantUrl)) {
            return ProvisionFlow.Outcome.error(
                    "That Home Assistant address needs to start with https://.");
        }
        AppConfig address = new AppConfig(homeAssistantUrl, "setup", "setup");
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(address.homeAssistantUrl
                    + "/api/rowe_pc_blocker/provision/"
                    + URLEncoder.encode(requestId, StandardCharsets.UTF_8.name()));
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            JSONObject body = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            return readProvision(body, status, true);
        } catch (Exception exception) {
            return ProvisionFlow.Outcome.error("Home Assistant could not be reached.");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ProvisionFlow.Outcome readProvision(
            JSONObject body, int status, boolean polling) {
        String state = body.optString("status", "").trim();
        if ("approved".equals(state)) {
            String deviceId = body.optString("device_id", "").trim();
            String deviceKey = body.optString("api_key", "").trim();
            if (deviceId.isEmpty() || deviceKey.isEmpty()) {
                return ProvisionFlow.Outcome.error("Home Assistant could not set up this phone.");
            }
            return ProvisionFlow.Outcome.approved(
                    deviceId, deviceKey, body.optString("child_name", "").trim());
        }
        if ("pending".equals(state)) {
            String requestId = body.optString("request_id", "").trim();
            if (requestId.isEmpty()) {
                return ProvisionFlow.Outcome.error("Home Assistant could not set up this phone.");
            }
            return ProvisionFlow.Outcome.pending(
                    requestId, body.optString("child_name", "").trim());
        }
        if ("rejected".equals(state) || status == 403) {
            return ProvisionFlow.Outcome.rejected(
                    provisionError(body, "A parent declined this setup."));
        }
        if (status == 404) {
            return polling
                    ? ProvisionFlow.Outcome.expired(
                            provisionError(body, "That setup request has expired."))
                    : ProvisionFlow.Outcome.error(
                            provisionError(body, "That name is not a child in Home Assistant."));
        }
        return ProvisionFlow.Outcome.error(
                provisionError(body, "Home Assistant could not set up this phone."));
    }

    private static String provisionError(JSONObject body, String fallback) {
        String error = body.optString("error", "").trim();
        if (error.isEmpty() || error.length() > 160) {
            return fallback;
        }
        return error;
    }

    /** Home Assistant's error text, or "" when the body carries none. */
    private static String errorText(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            return provisionError(new JSONObject(body), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The two calls the setup flow makes, bound to one Home Assistant address. */
    static final class ProvisionTransport implements ProvisionFlow.Transport {
        private final String homeAssistantUrl;

        ProvisionTransport(String homeAssistantUrl) {
            this.homeAssistantUrl = homeAssistantUrl;
        }

        @Override
        public ProvisionFlow.Outcome start(String childName) {
            return startProvision(homeAssistantUrl, childName);
        }

        @Override
        public ProvisionFlow.Outcome poll(String requestId) {
            return pollProvision(homeAssistantUrl, requestId);
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
                    readWakeAlarm(body),
                    readStringList(body, "allowed_apps"),
                    readFamilyPeer(body, "phone"),
                    readFamilyPeer(body, "pc"),
                    readChallenge(body));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * A cheap liveness check-in for the watchdog. The state read refreshes the
     * server's {@code last_seen} and changes nothing else, so a phone whose
     * policy poller has died silently still looks alive to a parent.
     */
    void postHeartbeat() throws Exception {
        HttpURLConnection connection = open("state", "GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Home Assistant returned " + status);
            }
            InputStream stream = connection.getInputStream();
            if (stream != null) {
                readAll(stream);
            }
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

    /** What Home Assistant said about a parent override. */
    static final class OverrideOutcome {
        /** True when Home Assistant accepted the PIN and granted the hour. */
        final boolean granted;
        /** A message for the block screen. */
        final String message;

        private OverrideOutcome(boolean granted, String message) {
            this.granted = granted;
            this.message = message == null ? "" : message;
        }

        static OverrideOutcome granted(String message) {
            return new OverrideOutcome(true, message);
        }

        static OverrideOutcome refused(String message) {
            return new OverrideOutcome(false, message);
        }
    }

    /**
     * Ask Home Assistant for a parent-PIN override. Home Assistant verifies the
     * PIN, counts attempts, and applies the lockout, so this method only relays
     * the outcome; it never decides whether the PIN was right. A refusal is the
     * caller's cue to fall back to the phone's own parent PIN.
     */
    OverrideOutcome requestParentOverride(String pin) throws Exception {
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
                return OverrideOutcome.granted("Override accepted. Waiting for the device to unlock.");
            }
            String error = errorText(body);
            return OverrideOutcome.refused(error.isEmpty()
                    ? "Home Assistant refused the override (" + status + ")."
                    : error);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * POST /api/rowe_pc_blocker/{device_id}/challenge/evidence. The photo is the
     * raw JPEG body; Home Assistant stores it as the child's evidence.
     */
    Challenge.Outcome uploadChallengeEvidence(byte[] jpeg) throws Exception {
        HttpURLConnection connection = open("challenge/evidence", "POST");
        try {
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            connection.setFixedLengthStreamingMode(jpeg.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(jpeg);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : readAll(stream);
            if (status == 200) {
                return Challenge.Outcome.accepted("Photo sent.");
            }
            String error = errorText(body);
            return Challenge.Outcome.failed(error.isEmpty()
                    ? "Home Assistant refused the photo (" + status + ")."
                    : error);
        } finally {
            connection.disconnect();
        }
    }

    /** POST /api/rowe_pc_blocker/{device_id}/challenge/accept. */
    Challenge.Outcome acceptChallenge(String challengeId) throws Exception {
        HttpURLConnection connection = open("challenge/accept", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject().put("id", challengeId).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : readAll(stream);
            if (status == 200) {
                return Challenge.Outcome.accepted("Challenge accepted.");
            }
            String error = errorText(body);
            return Challenge.Outcome.failed(error.isEmpty()
                    ? "Home Assistant refused the answer (" + status + ")."
                    : error);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * POST /api/rowe_pc_blocker/{device_id}/unblock_request. Asks a parent to
     * add this app to the phone's allow-list; the request expires on Home
     * Assistant's side, and the phone sees the result on its next policy poll.
     */
    UnblockRequest.Outcome requestUnblock(String packageName, String label) throws Exception {
        HttpURLConnection connection = open("unblock_request", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject()
                    .put("package", packageName)
                    .put("label", label)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String body = stream == null ? "" : readAll(stream);
            return UnblockRequest.fromResponse(status, errorText(body), label);
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
        postActivity(application, windowTitle, location, uiStatus, uiError, null);
    }

    /**
     * @param installedApps the launchable apps to report, or null when the set
     *     has not changed since the last successful report.
     */
    void postActivity(
            String application,
            String windowTitle,
            android.location.Location location,
            String uiStatus,
            String uiError,
            List<InstalledApps.App> installedApps) throws Exception {
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
            if (installedApps != null) {
                JSONArray apps = new JSONArray();
                for (InstalledApps.App app : installedApps) {
                    apps.put(new JSONObject()
                            .put("package", app.packageName)
                            .put("label", app.label));
                }
                body.put("installed_apps", apps);
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

    /**
     * POST /api/rowe_pc_blocker/{device}/notifications. The body is the day's
     * dump, already serialized by {@link NotificationLog#payload}.
     */
    void postNotifications(String payload) throws Exception {
        HttpURLConnection connection = open("notifications", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream stream = connection.getErrorStream();
                String body = stream == null ? "" : readAll(stream);
                String detail = errorText(body);
                throw new IOException(detail.isEmpty()
                        ? "Home Assistant returned " + status
                        : "Home Assistant returned " + status + ": " + detail);
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

    /** Home Assistant's allow-list; a missing or malformed field means "none". */
    private static List<String> readStringList(JSONObject body, String name) {
        JSONArray array = body.optJSONArray(name);
        if (array == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /** The active chore challenge, or null when there is none. */
    private static Challenge readChallenge(JSONObject body) {
        JSONObject challenge = body.optJSONObject("challenge");
        if (challenge == null) {
            return null;
        }
        String id = challenge.optString("id", "").trim();
        String phrase = challenge.optString("phrase", "").trim();
        if (id.isEmpty() || phrase.isEmpty()) {
            return null;
        }
        return new Challenge(id, phrase, optionalString(challenge, "sent_at"));
    }

    /** The child's other device; null when Home Assistant did not send one. */
    private static Policy.FamilyPeer readFamilyPeer(JSONObject body, String name) {
        JSONObject family = body.optJSONObject("family");
        if (family == null) {
            return null;
        }
        JSONObject peer = family.optJSONObject(name);
        if (peer == null) {
            return null;
        }
        String deviceId = peer.optString("device_id", "").trim();
        if (deviceId.isEmpty()) {
            return null;
        }
        return new Policy.FamilyPeer(
                deviceId,
                peer.optString("device_type", "").trim(),
                peer.optBoolean("blocked", false),
                peer.optString("message", "").trim(),
                peer.optBoolean("agent_online", false),
                optionalString(peer, "last_seen"),
                peer.optString("active_application", "").trim());
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
