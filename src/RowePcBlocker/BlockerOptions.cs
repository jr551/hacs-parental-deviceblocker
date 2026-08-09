namespace ParentalDeviceBlocker;

public sealed class BlockerOptions
{
    public const string SectionName = "Blocker";

    public required string HomeAssistantUrl { get; init; }
    public required string DeviceId { get; init; }
    public required string DeviceApiKey { get; init; }
    public int PollIntervalSeconds { get; init; } = 15;
    public bool EnforcementEnabled { get; init; }
    public bool KeepSessionForPortal { get; init; }
    public int GraceSeconds { get; init; } = 30;
    public required string ChildLocalUsername { get; init; }
}
