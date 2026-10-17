using Microsoft.Extensions.Options;

namespace ParentalDeviceBlocker;

public sealed class BlockerWorker(
    HomeAssistantClient homeAssistant,
    WindowsAccountBlocker accounts,
    ChallengeGrace challengeGrace,
    WindowsUpdater updater,
    IOptions<BlockerOptions> options,
    ILogger<BlockerWorker> logger) : BackgroundService
{
    private bool? _lastAppliedBlocked;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        logger.LogInformation(
            "Parental Device Blocker {Version} starting ({Mode}).",
            WindowsUpdater.CurrentVersion(),
            options.Value.EnforcementEnabled ? "enforcement enabled" : "monitor only");
        using PeriodicTimer timer = new(TimeSpan.FromSeconds(Math.Max(5, options.Value.PollIntervalSeconds)));
        do
        {
            try
            {
                BlockState state = await homeAssistant.GetStateAsync(stoppingToken);
                // Both of these are best effort and must never stop the poll:
                // the grace stamp is what keeps the activity agent's five-minute
                // challenge grace across restarts, and the update check refuses
                // to run while the PC is blocked.
                challengeGrace.Record(state);
                updater.Check(state, stoppingToken);
                if (!options.Value.EnforcementEnabled)
                {
                    // Monitor-only must not leave the child locked out if the
                    // account was previously disabled with enforcement on.
                    if (_lastAppliedBlocked == true)
                    {
                        var ensureUnblocked = new BlockState(false, false, state.Message, state.WindowsUsername, state.Family);
                        await accounts.ApplyAsync(ensureUnblocked, stoppingToken);
                    }
                    else if (_lastAppliedBlocked is null)
                    {
                        // First poll in monitor-only: ensure account is enabled
                        // so a prior manual disable does not persist.
                        var ensureUnblocked = new BlockState(false, false, state.Message, state.WindowsUsername, state.Family);
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
