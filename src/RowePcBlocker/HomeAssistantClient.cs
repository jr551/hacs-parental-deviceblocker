using System.Globalization;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace ParentalDeviceBlocker;

public sealed class HomeAssistantClient(HttpClient httpClient, IOptions<BlockerOptions> options)
{
    public async Task<BlockState> GetStateAsync(CancellationToken cancellationToken)
    {
        BlockerOptions configuration = options.Value;
        Uri baseUri = new(configuration.HomeAssistantUrl.TrimEnd('/') + "/");
        using HttpRequestMessage request = new(
            HttpMethod.Get,
            new Uri(baseUri, $"api/rowe_pc_blocker/{Uri.EscapeDataString(configuration.DeviceId)}/state"));
        request.Headers.Add("X-Device-Blocker-Key", configuration.DeviceApiKey);
        using HttpResponseMessage response = await httpClient.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();

        await using Stream body = await response.Content.ReadAsStreamAsync(cancellationToken);
        using JsonDocument document = await JsonDocument.ParseAsync(body, cancellationToken: cancellationToken);
        JsonElement root = document.RootElement;
        return new BlockState(
            root.GetProperty("blocked").GetBoolean(),
            root.GetProperty("message").GetString() ?? string.Empty,
            configuration.ChildLocalUsername,
            ParseFamily(root));
    }

    /// <summary>
    /// Reads the optional <c>family</c> block describing the child's other
    /// devices. Every field is optional: an older Home Assistant build, or a
    /// peer that has never checked in, must never fail the state poll that
    /// drives blocking.
    /// </summary>
    private static FamilyState ParseFamily(JsonElement root)
    {
        if (!root.TryGetProperty("family", out JsonElement family) || family.ValueKind != JsonValueKind.Object)
            return FamilyState.None;
        return new FamilyState(ParsePeer(family, "phone"), ParsePeer(family, "pc"));
    }

    private static FamilyPeerState? ParsePeer(JsonElement family, string name)
    {
        if (!family.TryGetProperty(name, out JsonElement peer) || peer.ValueKind != JsonValueKind.Object)
            return null;
        return new FamilyPeerState(
            ReadString(peer, "device_id"),
            ReadString(peer, "device_type"),
            ReadBoolean(peer, "blocked"),
            ReadString(peer, "message"),
            ReadBoolean(peer, "agent_online"),
            ReadTimestamp(peer, "last_seen"),
            ReadString(peer, "active_application"));
    }

    private static string ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private static bool ReadBoolean(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.True;

    private static DateTimeOffset? ReadTimestamp(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value)
        && value.ValueKind == JsonValueKind.String
        && DateTimeOffset.TryParse(
            value.GetString(),
            CultureInfo.InvariantCulture,
            DateTimeStyles.None,
            out DateTimeOffset parsed)
            ? parsed
            : null;
}

public sealed record BlockState(bool IsBlocked, string Message, string WindowsUsername, FamilyState Family);

/// <summary>Status of the child's other devices, as reported by Home Assistant.</summary>
public sealed record FamilyState(FamilyPeerState? Phone, FamilyPeerState? Pc)
{
    public static readonly FamilyState None = new(null, null);
}

/// <summary>One peer device of the same child, for example the child's phone.</summary>
public sealed record FamilyPeerState(
    string DeviceId,
    string DeviceType,
    bool Blocked,
    string Message,
    bool AgentOnline,
    DateTimeOffset? LastSeen,
    string ActiveApplication);
