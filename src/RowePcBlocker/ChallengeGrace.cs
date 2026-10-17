using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Options;

namespace ParentalDeviceBlocker;

/// <summary>
/// Records when this PC first saw the phone's current chore challenge.
///
/// The child-facing activity agent shows a five-minute grace before the
/// challenge notice becomes a hard kiosk, and restarting that agent must not
/// restart the grace. The service owns the file because the activity agent
/// runs as the child, who must not be able to reset the grace by editing state.
/// </summary>
public sealed class ChallengeGrace(IOptions<BlockerOptions> options, ILogger<ChallengeGrace> logger)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private string? _recordedChallengeId;
    private string? _failedChallengeId;

    public static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "RowePcBlocker",
        "challenge-grace.json");

    /// <summary>
    /// Writes the first-seen stamp whenever the phone's challenge changes. A
    /// challenge already recorded by an earlier run of the service keeps its
    /// original stamp, so a service restart cannot restart the grace either.
    /// Failures are logged and ignored: the notice still works from the
    /// activity agent's own first sighting.
    /// </summary>
    public void Record(BlockState state)
    {
        string challengeId = state.Family?.Phone?.Challenge?.Id ?? string.Empty;
        if (string.Equals(challengeId, _recordedChallengeId, StringComparison.Ordinal))
            return;
        if (challengeId.Length == 0 || AlreadyRecorded(challengeId))
        {
            _recordedChallengeId = challengeId;
            return;
        }
        try
        {
            ChallengeGraceState payload = new(options.Value.DeviceId, challengeId, DateTimeOffset.UtcNow);
            string path = FilePath;
            string temporary = path + ".tmp";
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(temporary, JsonSerializer.Serialize(payload, JsonOptions));
            File.Move(temporary, path, overwrite: true);
            _recordedChallengeId = challengeId;
        }
        catch (Exception exception)
        {
            // Leave the id unrecorded so the next poll retries, and log once
            // per challenge so a persistent failure cannot flood the log.
            if (!string.Equals(challengeId, _failedChallengeId, StringComparison.Ordinal))
            {
                _failedChallengeId = challengeId;
                logger.LogWarning(exception, "Could not record the chore challenge grace for {ChallengeId}; retrying.", challengeId);
            }
        }
    }

    private bool AlreadyRecorded(string challengeId)
    {
        try
        {
            if (!File.Exists(FilePath))
                return false;
            using JsonDocument document = JsonDocument.Parse(File.ReadAllText(FilePath));
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
                return false;
            return string.Equals(ReadString(root, "device_id"), options.Value.DeviceId, StringComparison.OrdinalIgnoreCase)
                && string.Equals(ReadString(root, "challenge_id"), challengeId, StringComparison.Ordinal);
        }
        catch
        {
            // An unreadable file is rewritten with a fresh stamp.
            return false;
        }
    }

    private static string ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private sealed record ChallengeGraceState(
        [property: JsonPropertyName("device_id")] string DeviceId,
        [property: JsonPropertyName("challenge_id")] string ChallengeId,
        [property: JsonPropertyName("first_seen")] DateTimeOffset FirstSeen);
}
