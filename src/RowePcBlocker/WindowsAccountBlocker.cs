using System.Diagnostics;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;

namespace ParentalDeviceBlocker;

/// <summary>Enforces blocking by disabling named local child accounts and ending their sessions.</summary>
public sealed class WindowsAccountBlocker(
    IOptions<BlockerOptions> options,
    ILogger<WindowsAccountBlocker> logger)
{
    public async Task ApplyAsync(BlockState state, CancellationToken cancellationToken)
    {
        string username = state.WindowsUsername;
        if (!Regex.IsMatch(username, @"^[A-Za-z0-9_. -]{1,64}$"))
            throw new InvalidOperationException("The configured Windows username contains unsupported characters.");

        if (state.IsBlocked)
        {
            if (options.Value.KeepSessionForPortal)
            {
                // WinForms cannot run on Windows' secure sign-in/lock desktop.
                // Keep the child account able to sign in or unlock; the protected,
                // repeatedly restarted companion owns the full-screen kiosk in the
                // child session. Otherwise a locked PC can never reach the screen
                // that explains the pause and offers the points portal.
                await RunAsync("net", $"user \"{username}\" /active:yes", cancellationToken);
            }
            else
            {
                await RunAsync("net", $"user \"{username}\" /active:no", cancellationToken);
                await LogOffAsync(username, cancellationToken);
            }
        }
        else
        {
            await RunAsync("net", $"user \"{username}\" /active:yes", cancellationToken);
        }
    }

    private async Task LogOffAsync(string username, CancellationToken cancellationToken)
    {
        // `quser` returns one row per active session; a failed query simply means no active session.
        string sessions = await RunAsync("quser", username, cancellationToken, ignoreFailure: true);
        // quser columns are whitespace-padded but the session id can abut the
        // preceding column (e.g. "child rdp-tcp#0  4 Active"), so match a whole
        // number bounded by start/whitespace on the left and whitespace/end on
        // the right instead of requiring surrounding spaces on both sides.
        foreach (string sessionId in sessions.Split('\n')
                     .Skip(1)
                     .Select(line => Regex.Match(line, @"(?:^|\s)(\d+)(?:\s|$)"))
                     .Where(match => match.Success)
                     .Select(match => match.Groups[1].Value))
        {
            await RunAsync("logoff", sessionId, cancellationToken, ignoreFailure: true);
        }
    }

    private async Task<string> RunAsync(string fileName, string arguments, CancellationToken cancellationToken, bool ignoreFailure = false)
    {
        using Process process = new()
        {
            StartInfo = new ProcessStartInfo(fileName, arguments)
            {
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            }
        };
        process.Start();
        string output = await process.StandardOutput.ReadToEndAsync(cancellationToken);
        string error = await process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);

        if (process.ExitCode != 0 && !ignoreFailure)
            throw new InvalidOperationException($"{fileName} failed ({process.ExitCode}): {error}");
        if (process.ExitCode != 0)
            logger.LogDebug("{Command} failed: {Error}", fileName, error);
        return output;
    }
}
