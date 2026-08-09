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
            configuration.ChildLocalUsername);
    }
}

public sealed record BlockState(bool IsBlocked, string Message, string WindowsUsername);
