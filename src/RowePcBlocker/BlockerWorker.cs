using Microsoft.Extensions.Options;

namespace ParentalDeviceBlocker;

public sealed class BlockerWorker(
    HomeAssistantClient homeAssistant,
    WindowsAccountBlocker accounts,
    IOptions<BlockerOptions> options,
    ILogger<BlockerWorker> logger) : BackgroundService
{
    private bool? _lastAppliedBlocked;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using PeriodicTimer timer = new(TimeSpan.FromSeconds(Math.Max(5, options.Value.PollIntervalSeconds)));
        do
        {
            try
            {
                BlockState state = await homeAssistant.GetStateAsync(stoppingToken);
                if (!options.Value.EnforcementEnabled)
                {
                    if (_lastAppliedBlocked != state.IsBlocked)
                        logger.LogInformation("Monitor-only mode: requested blocked state is {Blocked}", state.IsBlocked);
                    _lastAppliedBlocked = state.IsBlocked;
                    continue;
                }
                // Reapply every poll so a missed command or a later sign-in cannot bypass the policy.
                await accounts.ApplyAsync(state, stoppingToken);
                _lastAppliedBlocked = state.IsBlocked;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                // Keep the previous state during an HA outage; never change account access on a failed poll.
                logger.LogError(exception, "Could not read Home Assistant state; retaining previous PC state.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
