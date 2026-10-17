using System.Diagnostics;
using System.Globalization;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace ParentalDeviceBlocker;

/// <summary>
/// Keeps the Windows agent up to date from the public GitHub release.
///
/// The service owns the update because it runs as SYSTEM: the child must not
/// be able to influence it, and the swap replaces the service binary and
/// restarts the service. The check rides the existing state-poll cadence, at
/// most once an hour, and never on the poll thread: a large download must not
/// delay enforcement. Every failure is logged and retried on the next hourly
/// check.
/// </summary>
public sealed class WindowsUpdater(
    HttpClient httpClient,
    HomeAssistantClient homeAssistant,
    ILogger<WindowsUpdater> logger)
{
    private const string ReleasesUrl = "https://api.github.com/repos/jr551/hacs-parental-deviceblocker/releases/latest";
    private const string ChecksumsAsset = "SHA256SUMS";
    private const string InstallHelper = "Install-RowePcBlocker.ps1";
    private const string UpgradeScript = "Upgrade-Installed-RowePcBlocker.ps1";
    // The release archive is about 92 MiB today (two self-contained .NET
    // payloads); the installer is smaller. Anything past this is not ours.
    private const long MaxAssetBytes = 300L * 1024 * 1024;
    private const int MaxMetadataBytes = 1024 * 1024;
    private static readonly TimeSpan CheckInterval = TimeSpan.FromHours(1);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly Regex InstallerAsset = new(
        @"^ParentalDeviceBlocker-(?<version>\d+(?:\.\d+)*)-win-x64-setup\.exe$",
        RegexOptions.IgnoreCase);
    private static readonly Regex ZipAsset = new(
        @"^ParentalDeviceBlocker-(?<version>\d+(?:\.\d+)*)-win-x64\.zip$",
        RegexOptions.IgnoreCase);

    private DateTimeOffset? _lastCheckAttempt;
    private int _running;

    private static string InstallRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "RowePcBlocker");

    private static string UpdatesRoot => Path.Combine(InstallRoot, "updates");

    private static string StatePath => Path.Combine(InstallRoot, "update-state.json");

    /// <summary>
    /// The version of the running service binary. The release build stamps
    /// <c>-p:Version</c>, so the file version is the release version.
    /// </summary>
    public static Version CurrentVersion()
    {
        string? path = Environment.ProcessPath;
        if (path is not null)
        {
            try
            {
                FileVersionInfo info = FileVersionInfo.GetVersionInfo(path);
                if (Version.TryParse(info.FileVersion, out Version? parsed))
                    return parsed;
            }
            catch (Exception)
            {
                // Fall through to the assembly version.
            }
        }
        return Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
    }

    /// <summary>
    /// Starts an update check when one is due and the PC is not blocked. The
    /// work runs on a background task: the download must never delay the state
    /// poll that drives blocking.
    /// </summary>
    public void Check(BlockState state, CancellationToken cancellationToken)
    {
        if (state.IsBlocked || state.BlockRequested)
        {
            // A child's kiosk must never be pulled off screen mid-block.
            return;
        }
        if (Interlocked.CompareExchange(ref _running, 1, 0) != 0)
            return;
        if (!CheckDue())
        {
            Interlocked.Exchange(ref _running, 0);
            return;
        }
        _ = RunCheckAsync(cancellationToken);
    }

    private async Task RunCheckAsync(CancellationToken cancellationToken)
    {
        try
        {
            CleanupOldUpdates();
            await CheckAndInstallAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // The service is stopping; the next run retries.
        }
        catch (Exception exception)
        {
            logger.LogWarning(exception, "Windows agent update check failed; the next hourly check retries.");
        }
        finally
        {
            Interlocked.Exchange(ref _running, 0);
        }
    }

    private async Task CheckAndInstallAsync(CancellationToken cancellationToken)
    {
        Version current = CurrentVersion();
        ReleaseInfo release = await ReadReleaseAsync(current, cancellationToken);
        if (release.Installer is null && release.Zip is null)
        {
            logger.LogInformation("Windows agent {Current} is up to date.", current);
            return;
        }
        if (release.ChecksumsUrl is null)
        {
            logger.LogWarning("The release has no {Asset}; refusing to update.", ChecksumsAsset);
            return;
        }
        // Prefer the installer, exactly as a parent would; fall back to the
        // archive when the release ships no installer or the installer path
        // fails before the swap starts.
        foreach (ReleaseAsset? candidate in new[] { release.Installer, release.Zip })
        {
            if (candidate is null)
                continue;
            string? file = await DownloadAsync(candidate, release.ChecksumsUrl, cancellationToken);
            if (file is null)
                continue;
            // Re-read the state immediately before the swap, exactly as
            // Deploy-ActivityOnly.ps1 does: the download can take minutes, and
            // the install helper stops the kiosk and the service.
            BlockState latest = await homeAssistant.GetStateAsync(cancellationToken);
            if (latest.IsBlocked || latest.BlockRequested)
            {
                logger.LogInformation("Not installing {Version}: the PC is blocked.", candidate.Version);
                return;
            }
            bool installed = candidate.IsInstaller
                ? await RunInstallerAsync(candidate, file, cancellationToken)
                : RunZipUpgrade(candidate, file);
            if (installed)
                return;
        }
    }

    private async Task<ReleaseInfo> ReadReleaseAsync(Version current, CancellationToken cancellationToken)
    {
        using HttpResponseMessage response = await SendAsync(ReleasesUrl, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            logger.LogInformation("No update check: GitHub returned {Status}.", (int)response.StatusCode);
            return new ReleaseInfo(null, null, null);
        }
        string body = await ReadTextAsync(response, MaxMetadataBytes, cancellationToken);
        using JsonDocument document = JsonDocument.Parse(body);
        JsonElement root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object
            || !root.TryGetProperty("assets", out JsonElement assets)
            || assets.ValueKind != JsonValueKind.Array)
            return new ReleaseInfo(null, null, null);

        ReleaseAsset? installer = null;
        ReleaseAsset? zip = null;
        string? checksums = null;
        foreach (JsonElement asset in assets.EnumerateArray())
        {
            if (asset.ValueKind != JsonValueKind.Object)
                continue;
            string name = ReadString(asset, "name");
            string url = ReadString(asset, "browser_download_url");
            if (name.Length == 0 || url.Length == 0)
                continue;
            if (string.Equals(name, ChecksumsAsset, StringComparison.OrdinalIgnoreCase))
            {
                checksums = url;
                continue;
            }
            Match match = InstallerAsset.Match(name);
            bool isInstaller = match.Success;
            if (!isInstaller)
                match = ZipAsset.Match(name);
            if (!match.Success
                || !Version.TryParse(match.Groups["version"].Value, out Version? version)
                || !IsNewer(current, version))
                continue;
            ReleaseAsset candidate = new(name, url, version, isInstaller);
            if (isInstaller)
                installer = Newest(installer, candidate);
            else
                zip = Newest(zip, candidate);
        }
        return new ReleaseInfo(installer, zip, checksums);
    }

    /// <summary>
    /// Downloads one release asset into the update folder and verifies it
    /// against the release's checksum list. A missing or mismatched checksum
    /// aborts the update before anything is executed.
    /// </summary>
    private async Task<string?> DownloadAsync(ReleaseAsset asset, string checksumsUrl, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(UpdatesRoot);
        string target = Path.Combine(UpdatesRoot, asset.Name);
        string temporary = target + ".part";
        try
        {
            using HttpResponseMessage response = await SendAsync(asset.Url, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                logger.LogWarning("Could not download {Asset}: GitHub returned {Status}.", asset.Name, (int)response.StatusCode);
                return null;
            }
            long written = 0;
            await using (Stream source = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (FileStream destination = File.Create(temporary))
            {
                byte[] buffer = new byte[81920];
                int read;
                while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    written += read;
                    if (written > MaxAssetBytes)
                    {
                        logger.LogWarning("{Asset} is larger than the {Cap} MiB cap; refusing to update.", asset.Name, MaxAssetBytes / (1024 * 1024));
                        return null;
                    }
                    await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                }
            }
            if (!await VerifyAsync(temporary, asset.Name, checksumsUrl, cancellationToken))
                return null;
            File.Move(temporary, target, overwrite: true);
            return target;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            logger.LogWarning(exception, "Could not download {Asset}.", asset.Name);
            return null;
        }
        finally
        {
            TryDelete(temporary);
        }
    }

    private async Task<bool> VerifyAsync(string path, string assetName, string checksumsUrl, CancellationToken cancellationToken)
    {
        string? expected = await ReadExpectedChecksumAsync(checksumsUrl, assetName, cancellationToken);
        if (expected is null)
        {
            logger.LogWarning("{Asset} has no entry in {Checksums}; refusing to update.", assetName, ChecksumsAsset);
            return false;
        }
        string actual = await ComputeSha256Async(path, cancellationToken);
        if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
        {
            logger.LogWarning("Checksum mismatch for {Asset}: expected {Expected}, got {Actual}.", assetName, expected, actual);
            return false;
        }
        return true;
    }

    private async Task<string?> ReadExpectedChecksumAsync(string url, string assetName, CancellationToken cancellationToken)
    {
        using HttpResponseMessage response = await SendAsync(url, cancellationToken);
        if (!response.IsSuccessStatusCode)
            return null;
        string text = await ReadTextAsync(response, MaxMetadataBytes, cancellationToken);
        foreach (string line in text.Split('\n'))
        {
            string[] parts = line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2)
                continue;
            if (string.Equals(parts[^1].TrimStart('*'), assetName, StringComparison.OrdinalIgnoreCase))
                return parts[0];
        }
        return null;
    }

    private static async Task<string> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        await using FileStream stream = File.OpenRead(path);
        using SHA256 sha = SHA256.Create();
        byte[] hash = await sha.ComputeHashAsync(stream, cancellationToken);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    /// <summary>
    /// Runs the verified Inno Setup installer silently, then the install helper
    /// it unpacks, with the live configuration — the same two steps a parent
    /// performs by hand. The helper stops and recreates the service, so this
    /// process is expected to die mid-update; the helper's output goes to the
    /// update log.
    /// </summary>
    private async Task<bool> RunInstallerAsync(ReleaseAsset asset, string installerPath, CancellationToken cancellationToken)
    {
        string log = LogPath(asset.Version);
        int exitCode = await RunAsync(installerPath, "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART", log, cancellationToken);
        if (exitCode != 0)
        {
            logger.LogWarning("The {Version} installer exited with {Code}; see {Log}.", asset.Version, exitCode, log);
            return false;
        }
        string helper = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            "ParentalDeviceBlocker",
            InstallHelper);
        if (!File.Exists(helper))
        {
            logger.LogWarning("The installer did not unpack {Helper}; refusing to swap binaries.", helper);
            return false;
        }
        BlockerConfiguration? configuration = ReadLiveConfiguration();
        if (configuration is null)
        {
            logger.LogWarning("The live configuration could not be read; refusing to swap binaries.");
            return false;
        }
        if (!StartPowerShell(WriteInstallWrapper(helper, configuration, asset.Version), string.Empty, log))
            return false;
        logger.LogInformation("Windows agent {Version} unpacked; the install helper is running.", asset.Version);
        return true;
    }

    /// <summary>
    /// Extracts the verified release archive and runs the upgrade script it
    /// contains, which re-reads the live configuration and performs the swap.
    /// </summary>
    private bool RunZipUpgrade(ReleaseAsset asset, string archivePath)
    {
        string log = LogPath(asset.Version);
        try
        {
            string extractRoot = Path.Combine(UpdatesRoot, asset.Version.ToString());
            Directory.CreateDirectory(extractRoot);
            ZipFile.ExtractToDirectory(archivePath, extractRoot, overwriteFiles: true);
            string? script = Directory
                .EnumerateFiles(extractRoot, UpgradeScript, SearchOption.AllDirectories)
                .FirstOrDefault();
            if (script is null)
            {
                logger.LogWarning("{Archive} does not contain {Script}.", asset.Name, UpgradeScript);
                return false;
            }
            if (!StartPowerShell(script, $"-ArchivePath \"{archivePath}\"", log))
                return false;
            logger.LogInformation("Windows agent {Version} archive verified; the upgrade script is running.", asset.Version);
            return true;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            logger.LogWarning(exception, "Could not extract {Archive}.", asset.Name);
            return false;
        }
    }

    /// <summary>
    /// Writes a one-off script that calls the install helper with the live
    /// configuration. The values are written as PowerShell literals rather than
    /// passed on the command line, so cmd.exe cannot expand a % inside a URL or
    /// an API key.
    /// </summary>
    private string WriteInstallWrapper(string helper, BlockerConfiguration configuration, Version version)
    {
        StringBuilder script = new();
        script.AppendLine("$ErrorActionPreference = 'Stop'");
        script.Append("& ").Append(QuotePowerShell(helper));
        script.Append(" -HomeAssistantUrl ").Append(QuotePowerShell(configuration.HomeAssistantUrl));
        script.Append(" -DeviceId ").Append(QuotePowerShell(configuration.DeviceId));
        script.Append(" -DeviceApiKey ").Append(QuotePowerShell(configuration.DeviceApiKey));
        script.Append(" -ChildUsername ").Append(QuotePowerShell(configuration.ChildUsername));
        if (configuration.EnforcementEnabled)
            script.Append(" -EnableEnforcement");
        if (configuration.UserInterfaceEnabled)
            script.Append(" -EnableUserInterface");
        if (configuration.PortalEnabled || configuration.PortalUrl.Length != 0)
            script.Append(" -EnablePortal");
        script.Append(" -PortalUrl ").Append(QuotePowerShell(configuration.PortalUrl));
        script.AppendLine();
        string path = Path.Combine(UpdatesRoot, $"install-{version}.ps1");
        File.WriteAllText(path, script.ToString());
        return path;
    }

    private static string QuotePowerShell(string value) => "'" + value.Replace("'", "''") + "'";

    /// <summary>
    /// The live Blocker configuration, as the install helper needs it. This
    /// mirrors the parameter block in
    /// deploy/windows/Upgrade-Installed-RowePcBlocker.ps1, which is the tested
    /// upgrade path used when the release ships an archive.
    /// </summary>
    private BlockerConfiguration? ReadLiveConfiguration()
    {
        string path = Path.Combine(InstallRoot, "appsettings.Local.json");
        try
        {
            using JsonDocument document = JsonDocument.Parse(File.ReadAllText(path));
            if (!document.RootElement.TryGetProperty("Blocker", out JsonElement blocker) || blocker.ValueKind != JsonValueKind.Object)
                return null;
            string homeAssistantUrl = ReadString(blocker, "HomeAssistantUrl");
            string deviceId = ReadString(blocker, "DeviceId");
            string deviceApiKey = ReadString(blocker, "DeviceApiKey");
            string childUsername = ReadString(blocker, "ChildLocalUsername");
            if (homeAssistantUrl.Length == 0 || deviceId.Length == 0 || deviceApiKey.Length == 0 || childUsername.Length == 0)
                return null;
            return new BlockerConfiguration(
                homeAssistantUrl,
                deviceId,
                deviceApiKey,
                childUsername,
                ReadBoolean(blocker, "EnforcementEnabled"),
                ReadBoolean(blocker, "UserInterfaceEnabled"),
                ReadBoolean(blocker, "PortalEnabled"),
                ReadString(blocker, "PortalUrl"));
        }
        catch (Exception exception)
        {
            logger.LogWarning(exception, "Could not read {Path}.", path);
            return null;
        }
    }

    /// <summary>
    /// Starts a PowerShell script detached, with its output appended to the
    /// update log. The script stops and recreates the service, so this process
    /// must not wait for it, and the log must be a file rather than a pipe: a
    /// pipe would break the moment the service is stopped.
    /// </summary>
    private bool StartPowerShell(string script, string arguments, string log)
    {
        string command = $"powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"{script}\"";
        if (arguments.Length != 0)
            command += " " + arguments;
        command += $" >> \"{log}\" 2>&1";
        ProcessStartInfo startInfo = new("cmd.exe")
        {
            Arguments = "/c " + command,
            CreateNoWindow = true,
            UseShellExecute = false
        };
        try
        {
            using Process? process = Process.Start(startInfo);
            if (process is not null)
                return true;
            logger.LogWarning("Could not start {Script}.", script);
        }
        catch (Exception exception)
        {
            logger.LogWarning(exception, "Could not start {Script}.", script);
        }
        return false;
    }

    private async Task<int> RunAsync(string fileName, string arguments, string log, CancellationToken cancellationToken)
    {
        ProcessStartInfo startInfo = new(fileName)
        {
            Arguments = arguments,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using Process process = Process.Start(startInfo) ?? throw new InvalidOperationException($"{fileName} did not start.");
        Task<string> output = process.StandardOutput.ReadToEndAsync(cancellationToken);
        Task<string> error = process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        await File.AppendAllTextAsync(
            log,
            $"--- {DateTimeOffset.UtcNow:O} {fileName} {arguments}\n{await output}{await error}",
            cancellationToken);
        return process.ExitCode;
    }

    /// <summary>
    /// Removes update artifacts from earlier runs. The upgrade script needs its
    /// archive for a minute or two, so anything older than a day is safe.
    /// </summary>
    private void CleanupOldUpdates()
    {
        try
        {
            if (!Directory.Exists(UpdatesRoot))
                return;
            DateTime cutoff = DateTime.UtcNow.AddDays(-1);
            foreach (string entry in Directory.EnumerateFileSystemEntries(UpdatesRoot))
            {
                try
                {
                    bool directory = Directory.Exists(entry);
                    DateTime lastWrite = directory ? Directory.GetLastWriteTimeUtc(entry) : File.GetLastWriteTimeUtc(entry);
                    if (lastWrite >= cutoff)
                        continue;
                    if (directory)
                        Directory.Delete(entry, recursive: true);
                    else
                        File.Delete(entry);
                }
                catch (Exception exception)
                {
                    logger.LogDebug(exception, "Could not remove the stale update artifact {Entry}.", entry);
                }
            }
        }
        catch (Exception exception)
        {
            logger.LogDebug(exception, "Could not clean up the update folder.");
        }
    }

    /// <summary>
    /// True when an hour has passed since the last check. The stamp is written
    /// before the check, exactly like the Android updater, so a failure cannot
    /// turn into a retry storm.
    /// </summary>
    private bool CheckDue()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        if (_lastCheckAttempt is DateTimeOffset attempted && now - attempted < CheckInterval)
            return false;
        DateTimeOffset? recorded = ReadCheckedAt();
        if (recorded is DateTimeOffset stamp && stamp <= now && now - stamp < CheckInterval)
        {
            _lastCheckAttempt = stamp;
            return false;
        }
        _lastCheckAttempt = now;
        WriteCheckedAt(now);
        return true;
    }

    private static DateTimeOffset? ReadCheckedAt()
    {
        try
        {
            if (!File.Exists(StatePath))
                return null;
            using JsonDocument document = JsonDocument.Parse(File.ReadAllText(StatePath));
            if (document.RootElement.ValueKind != JsonValueKind.Object
                || !document.RootElement.TryGetProperty("update_checked_at", out JsonElement value)
                || value.ValueKind != JsonValueKind.String)
                return null;
            return DateTimeOffset.TryParse(
                value.GetString(),
                CultureInfo.InvariantCulture,
                DateTimeStyles.None,
                out DateTimeOffset parsed)
                ? parsed
                : null;
        }
        catch
        {
            return null;
        }
    }

    private void WriteCheckedAt(DateTimeOffset now)
    {
        try
        {
            Directory.CreateDirectory(InstallRoot);
            string temporary = StatePath + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(new UpdateState(now), JsonOptions));
            File.Move(temporary, StatePath, overwrite: true);
        }
        catch (Exception exception)
        {
            logger.LogDebug(exception, "Could not persist the update check stamp.");
        }
    }

    private async Task<HttpResponseMessage> SendAsync(string url, CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, url);
        request.Headers.Add("Accept", "application/vnd.github+json");
        return await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
    }

    private static async Task<string> ReadTextAsync(HttpResponseMessage response, int limit, CancellationToken cancellationToken)
    {
        await using Stream stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using MemoryStream buffer = new();
        byte[] chunk = new byte[8192];
        int read;
        while (buffer.Length < limit && (read = await stream.ReadAsync(chunk, cancellationToken)) > 0)
            buffer.Write(chunk, 0, read);
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
                File.Delete(path);
        }
        catch
        {
            // A leftover .part file is removed by the next cleanup.
        }
    }

    private static string LogPath(Version version) => Path.Combine(UpdatesRoot, $"update-{version}.log");

    /// <summary>
    /// True when <paramref name="remote"/> is strictly newer than
    /// <paramref name="current"/>. Missing components count as zero, so
    /// "0.12.0" and "0.12.0.0" are the same version.
    /// </summary>
    private static bool IsNewer(Version current, Version remote)
    {
        int[] left = Components(current);
        int[] right = Components(remote);
        for (int index = 0; index < left.Length; index++)
        {
            if (right[index] != left[index])
                return right[index] > left[index];
        }
        return false;
    }

    private static int[] Components(Version version) =>
        new[] { version.Major, version.Minor, Math.Max(0, version.Build), Math.Max(0, version.Revision) };

    private static ReleaseAsset? Newest(ReleaseAsset? left, ReleaseAsset right) =>
        left is null || right.Version > left.Version ? right : left;

    private static string ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private static bool ReadBoolean(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.True;

    private sealed record ReleaseInfo(ReleaseAsset? Installer, ReleaseAsset? Zip, string? ChecksumsUrl);

    private sealed record ReleaseAsset(string Name, string Url, Version Version, bool IsInstaller);

    private sealed record UpdateState(
        [property: System.Text.Json.Serialization.JsonPropertyName("update_checked_at")] DateTimeOffset UpdateCheckedAt);

    private sealed record BlockerConfiguration(
        string HomeAssistantUrl,
        string DeviceId,
        string DeviceApiKey,
        string ChildUsername,
        bool EnforcementEnabled,
        bool UserInterfaceEnabled,
        bool PortalEnabled,
        string PortalUrl);
}
