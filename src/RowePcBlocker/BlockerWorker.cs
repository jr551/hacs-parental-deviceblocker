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
                    // Monitor-only must not leave the child locked out if the
                    // account was previously disabled with enforcement on.
                    if (_lastAppliedBlocked == true)
                    {
                        var ensureUnblocked = new BlockState(false, state.Message, state.WindowsUsername);
                        await accounts.ApplyAsync(ensureUnblocked, stoppingToken);
                    }
                    else if (_lastAppliedBlocked is null)
                    {
                        // First poll in monitor-only: ensure account is enabled
                        // so a prior manual disable does not persist.
                        var ensureUnblocked = new BlockState(false, state.Message, state.WindowsUsername);
                        await accounts.ApplyAsync(ensureUnblocked, stoppingToken);
                    }
                    if (_lastAppliedBlocked != state.IsBlocked)
                        logger.LogInformation("Monitor-only mode: requested blocked state is {Blocked}", state.IsBlocked);
                    _lastAppliedBlocked = false;
                    continue;
                }
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
