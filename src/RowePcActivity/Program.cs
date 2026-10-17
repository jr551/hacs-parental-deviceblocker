using System.Diagnostics;
using System.Globalization;
using System.Net.Http.Json;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // Use physical virtual-screen bounds across mixed-DPI monitors. Without
        // PerMonitorV2 awareness Windows can shrink Bounds to one logical
        // display, leaving a second monitor outside the protected kiosk.
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        ApplicationConfiguration.Initialize();
        Application.Run(new AgentApplicationContext(AgentConfiguration.Load()));
    }
}

internal sealed class AgentApplicationContext : ApplicationContext
{
    private readonly AgentConfiguration _configuration;
    private readonly HttpClient _http;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly BlockOverlayForm _overlay;
    private readonly List<SecondaryBlockOverlayForm> _secondaryOverlays;
    private string _lastApplication = "";
    private string _lastTitle = "";
    private DateTimeOffset _lastActivitySent = DateTimeOffset.MinValue;
    // Portal state comes from the kiosk overlay; the two failure fields are
    // transient and MUST be cleared on the next success, otherwise a single
    // failed request latches a scary status in Home Assistant forever.
    private string _portalStatus = "idle";
    private string _portalError = "";
    private string _policyFailure = "";
    private string _policyFailureError = "";
    private string _activityFailure = "";
    private string _activityFailureError = "";
    private bool _uiTelemetryDirty = true;
    private bool _busy;
    // The policy is read by hand so the family block can be parsed from the same
    // document. HttpClientJsonExtensions.GetFromJsonAsync used web defaults, so
    // keep them: a property added later without an explicit JsonPropertyName
    // must not silently stop deserializing.
    private static readonly JsonSerializerOptions PolicyJsonOptions = new(JsonSerializerDefaults.Web);

    public AgentApplicationContext(AgentConfiguration configuration)
    {
        _configuration = configuration;
        _http = new HttpClient
        {
            BaseAddress = new Uri(configuration.HomeAssistantUrl.TrimEnd('/') + "/"),
            // Keep a stalled Home Assistant connection from stretching the 2 s
            // tick cadence to the HttpClient default of 100 s.
            Timeout = TimeSpan.FromSeconds(10)
        };
        _http.DefaultRequestHeaders.Add("X-Device-Blocker-Key", configuration.DeviceApiKey);
        _portalStatus = configuration.PortalEnabled ? "idle" : "disabled";
        _overlay = new BlockOverlayForm(configuration);
        _overlay.ExtensionRequested += async (_, _) => await RequestExtensionAsync();
        _overlay.PortalTelemetryChanged += (_, telemetry) =>
        {
            _portalStatus = telemetry.Status;
            _portalError = telemetry.Error;
            _uiTelemetryDirty = true;
        };
        _secondaryOverlays = Screen.AllScreens
            .Where(screen => !screen.Primary)
            .Select(screen => new SecondaryBlockOverlayForm(screen))
            .ToList();
        _timer = new System.Windows.Forms.Timer { Interval = 2_000 };
        _timer.Tick += async (_, _) => await TickAsync();
        _timer.Start();
        _ = TickAsync();
    }

    private async Task TickAsync()
    {
        if (_busy)
            return;
        _busy = true;
        try
        {
            try
            {
                // Blocking is the safety-critical path. Fetch and apply policy
                // before optional activity reporting so a telemetry failure can
                // never prevent the kiosk from appearing.
                AgentPolicy? policy = await GetPolicyAsync();
                if (_configuration.UserInterfaceEnabled && policy is not null)
                {
                    _overlay.Apply(policy);
                    foreach (SecondaryBlockOverlayForm overlay in _secondaryOverlays)
                        overlay.Apply(policy);
                }
                else
                {
                    foreach (SecondaryBlockOverlayForm overlay in _secondaryOverlays)
                        overlay.HideOverlay();
                    _overlay.HideOverlay();
                    if (_portalStatus != "idle" || _portalError.Length != 0)
                    {
                        _portalStatus = "idle";
                        _portalError = "";
                        _uiTelemetryDirty = true;
                    }
                }

                if (_policyFailure.Length != 0)
                {
                    // Recovered: stop reporting the stale policy failure.
                    _policyFailure = "";
                    _policyFailureError = "";
                    _uiTelemetryDirty = true;
                }
            }
            catch (Exception exception)
            {
                _policyFailure = "policy_or_kiosk_failed";
                _policyFailureError = DescribeAgentException(exception);
                _uiTelemetryDirty = true;
            }

            try
            {
                await SendActivityIfNeededAsync();
            }
            catch (Exception exception)
            {
                // Activity reporting is useful diagnostics, but it is never
                // allowed to short-circuit policy enforcement.
                _activityFailure = "activity_telemetry_failed";
                _activityFailureError = DescribeAgentException(exception);
                _uiTelemetryDirty = true;
            }
        }
        finally
        {
            _busy = false;
        }
    }

    // A live failure outranks portal state; policy failures outrank telemetry ones.
    private string EffectiveUiStatus =>
        _policyFailure.Length != 0 ? _policyFailure
        : _activityFailure.Length != 0 ? _activityFailure
        : _portalStatus;

    private string EffectiveUiError =>
        _policyFailure.Length != 0 ? _policyFailureError
        : _activityFailure.Length != 0 ? _activityFailureError
        : _portalError;

    private static string DescribeAgentException(Exception exception) =>
        $"{exception.GetType().Name} (0x{exception.HResult:X8})";

    private async Task SendActivityIfNeededAsync()
    {
        (string application, string title) = ForegroundWindow.Read();
        bool changed = application != _lastApplication || title != _lastTitle;
        bool heartbeatDue = DateTimeOffset.UtcNow - _lastActivitySent >= TimeSpan.FromSeconds(60);
        if (!changed && !heartbeatDue && !_uiTelemetryDirty)
            return;

        using HttpResponseMessage response = await _http.PostAsJsonAsync(
            $"api/rowe_pc_blocker/{Uri.EscapeDataString(_configuration.DeviceId)}/activity",
            new
            {
                application,
                window_title = title,
                username = Environment.UserName,
                agent_version = typeof(Program).Assembly.GetName().Version?.ToString() ?? "0.1.0",
                ui_status = EffectiveUiStatus,
                ui_error = EffectiveUiError
            });
        response.EnsureSuccessStatusCode();
        _lastApplication = application;
        _lastTitle = title;
        _lastActivitySent = DateTimeOffset.UtcNow;
        if (_activityFailure.Length != 0)
        {
            // This send worked, so the previous failure is history. The next
            // cycle reports the real portal status again.
            _activityFailure = "";
            _activityFailureError = "";
            _uiTelemetryDirty = true;
        }
        _uiTelemetryDirty = false;
    }

    private async Task<AgentPolicy?> GetPolicyAsync()
    {
        using HttpResponseMessage response = await _http.GetAsync(
            $"api/rowe_pc_blocker/{Uri.EscapeDataString(_configuration.DeviceId)}/state");
        response.EnsureSuccessStatusCode();
        await using Stream body = await response.Content.ReadAsStreamAsync();
        using JsonDocument document = await JsonDocument.ParseAsync(body);
        AgentPolicy? policy = document.RootElement.Deserialize<AgentPolicy>(PolicyJsonOptions);
        // The family block is read separately so a malformed peer payload can
        // never fail the safety-critical policy read that drives the kiosk.
        return policy is null ? null : policy with { Family = FamilyState.Parse(document.RootElement) };
    }

    private async Task RequestExtensionAsync()
    {
        try
        {
            using HttpResponseMessage response = await _http.PostAsJsonAsync(
                $"api/rowe_pc_blocker/{Uri.EscapeDataString(_configuration.DeviceId)}/extension",
                new { });
            AgentPolicy? policy = await GetPolicyAsync();
            if (policy is not null)
                _overlay.Apply(policy);
        }
        catch
        {
            _overlay.ShowStatus("Could not contact Home Assistant. Please try again.");
        }
    }
}

internal sealed class BlockOverlayForm : Form
{
    private const int PhoneRowHeight = 46;
    private const int DismissRowHeight = 64;
    /// <summary>How long the child may keep working before the challenge notice becomes a hard kiosk.</summary>
    private static readonly TimeSpan ChallengeGrace = TimeSpan.FromMinutes(5);
    /// <summary>How long a phone may stay silent before the PC freezes.</summary>
    private static readonly TimeSpan PhoneSilenceLimit = TimeSpan.FromHours(24);

    private readonly AgentConfiguration _configuration;
    private readonly TableLayoutPanel _fullLayout;
    private readonly Label _title;
    private readonly Label _message;
    private readonly Label _countdown;
    private readonly Label _phone;
    private readonly RowStyle _phoneRow;
    private readonly Button _extension;
    private readonly Button _dismiss;
    private readonly RowStyle _dismissRow;
    private readonly Panel _banner;
    private readonly Label _bannerTitle;
    private readonly Label _bannerCountdown;
    private readonly Panel _portalHost;
    private readonly Label _portalStatus;
    private readonly WebView2 _portal;
    private readonly Uri _portalUri;
    private readonly string _allowedOrigin;
    private readonly string _allowedPath;
    private KeyboardKioskGuard? _keyboardGuard;
    private AgentPolicy? _policy;
    private bool _kioskActive;
    private string _challengeId = "";
    private DateTimeOffset _challengeFirstSeen;
    private bool _challengeDismissed;
    private bool _portalReady;
    private bool _portalInitialising;
    private string _reportedPortalStatus = "";
    private string _reportedPortalError = "";
    private DateTimeOffset _nextPortalAttempt = DateTimeOffset.MinValue;

    public event EventHandler? ExtensionRequested;
    public event EventHandler<PortalTelemetry>? PortalTelemetryChanged;

    public BlockOverlayForm(AgentConfiguration configuration)
    {
        _configuration = configuration;
        _portalUri = BuildPortalUri(configuration);
        _allowedOrigin = _portalUri.GetLeftPart(UriPartial.Authority);
        _allowedPath = _portalUri.AbsolutePath.TrimEnd('/');
        if (_allowedPath.Length == 0)
            _allowedPath = "/";

        BackColor = Color.FromArgb(20, 24, 31);
        ForeColor = Color.White;
        TopMost = true;
        ShowInTaskbar = false;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;

        _title = CreateLabel(28, FontStyle.Bold);
        _message = CreateLabel(16, FontStyle.Regular);
        _countdown = CreateLabel(19, FontStyle.Bold);
        _phone = CreateLabel(15, FontStyle.Regular);
        _phone.Visible = false;
        _extension = new Button
        {
            Anchor = AnchorStyles.None,
            Width = 350,
            Height = 64,
            Font = new Font("Segoe UI", 13, FontStyle.Bold),
            Text = "Get another 5 mins to save my work",
            BackColor = Color.FromArgb(65, 132, 228),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            TabStop = true
        };
        _extension.FlatAppearance.BorderSize = 0;
        _extension.Click += (_, _) => ExtensionRequested?.Invoke(this, EventArgs.Empty);

        // Shown only while the phone's chore challenge is inside its grace
        // period: the notice is informational then, and the child may dismiss
        // it and keep working until the grace lapses.
        _dismiss = new Button
        {
            Anchor = AnchorStyles.None,
            Width = 350,
            Height = 64,
            Font = new Font("Segoe UI", 13, FontStyle.Bold),
            Text = "Keep working for now",
            BackColor = Color.FromArgb(65, 132, 228),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            TabStop = true,
            Visible = false
        };
        _dismiss.FlatAppearance.BorderSize = 0;
        _dismiss.Click += (_, _) => DismissChallengeNotice();

        TableLayoutPanel left = new()
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(20, 24, 31),
            Padding = new Padding(34),
            RowCount = 6,
            ColumnCount = 1
        };
        left.RowStyles.Add(new RowStyle(SizeType.Percent, 26));
        left.RowStyles.Add(new RowStyle(SizeType.Percent, 24));
        left.RowStyles.Add(new RowStyle(SizeType.Percent, 25));
        // The phone status row collapses to nothing when Home Assistant
        // reports no phone peer for this child.
        _phoneRow = new RowStyle(SizeType.Absolute, 0);
        left.RowStyles.Add(_phoneRow);
        left.RowStyles.Add(new RowStyle(SizeType.Percent, 25));
        // The dismiss row collapses to nothing outside the challenge grace.
        _dismissRow = new RowStyle(SizeType.Absolute, 0);
        left.RowStyles.Add(_dismissRow);
        left.Controls.Add(_title, 0, 0);
        left.Controls.Add(_message, 0, 1);
        left.Controls.Add(_countdown, 0, 2);
        left.Controls.Add(_phone, 0, 3);
        left.Controls.Add(_extension, 0, 4);
        left.Controls.Add(_dismiss, 0, 5);

        _portalHost = new Panel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            BackColor = Color.FromArgb(10, 14, 20)
        };
        _portalStatus = CreateLabel(15, FontStyle.Regular);
        _portalStatus.Text = configuration.PortalEnabled
            ? "Opening your points portal…"
            : "The child portal is disabled by a parent.";
        _portal = new WebView2
        {
            Dock = DockStyle.Fill,
            Visible = false,
            DefaultBackgroundColor = Color.FromArgb(16, 23, 34)
        };
        _portalHost.Controls.Add(_portal);
        _portalHost.Controls.Add(_portalStatus);

        _fullLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 1,
            ColumnCount = 2,
            Margin = Padding.Empty,
            Padding = Padding.Empty
        };
        _fullLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
        _fullLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
        _fullLayout.Controls.Add(left, 0, 0);
        _fullLayout.Controls.Add(_portalHost, 1, 0);

        _bannerTitle = CreateLabel(15, FontStyle.Bold);
        _bannerCountdown = CreateLabel(14, FontStyle.Bold);
        _banner = new Panel { Dock = DockStyle.Fill, Visible = false };
        _bannerTitle.Dock = DockStyle.Top;
        _bannerTitle.Height = 62;
        _bannerCountdown.Dock = DockStyle.Fill;
        _banner.Controls.Add(_bannerCountdown);
        _banner.Controls.Add(_bannerTitle);

        Controls.Add(_banner);
        Controls.Add(_fullLayout);
    }

    protected override CreateParams CreateParams
    {
        get
        {
            const int CpNoCloseButton = 0x200;
            CreateParams parameters = base.CreateParams;
            parameters.ClassStyle |= CpNoCloseButton;
            return parameters;
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (_kioskActive && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            return;
        }
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _keyboardGuard?.Dispose();
            _portal.Dispose();
        }
        base.Dispose(disposing);
    }

    public void Apply(AgentPolicy policy)
    {
        _policy = policy;
        DateTimeOffset now = DateTimeOffset.UtcNow;
        DateTimeOffset enforceAt = policy.EnforceAt ?? now;
        TimeSpan remaining = enforceAt - now;
        bool extended = policy.ExtensionUntil is not null && policy.ExtensionUntil > now && remaining.TotalSeconds > 30;
        FamilyPeerState? phone = policy.Family?.Phone;
        TrackChallenge(phone?.Challenge, now);

        // The phone notices only apply while the PC itself is free: a parent
        // override suppresses them exactly as it suppresses the block screen,
        // and a parent-requested block keeps the block screen it already has.
        FamilyChallenge? challenge = policy.BlockRequested || extended ? null : phone?.Challenge;
        bool challengeDismissed = challenge is not null && _challengeDismissed && ChallengeGraceRemaining(now) > TimeSpan.Zero;
        TimeSpan? silence = policy.BlockRequested || extended ? null : PhoneSilence(phone);

        if (silence is TimeSpan silentFor && silentFor >= PhoneSilenceLimit)
        {
            // A phone that has not checked in for a day cannot answer a
            // challenge, so the freeze outranks the challenge notice.
            ApplyPhoneStaleFreeze(policy, silentFor);
        }
        else if (challenge is not null && !challengeDismissed)
        {
            ApplyChallengeNotice(policy, challenge, now);
        }
        else if (!policy.BlockRequested)
        {
            ReportPortalTelemetry("idle");
            HideOverlay();
            return;
        }
        else if (extended)
        {
            ReportPortalTelemetry("idle");
            ApplyCountdownBanner(remaining);
        }
        else
        {
            ApplyFullScreen(policy, remaining);
            if (!_configuration.PortalEnabled)
                ReportPortalTelemetry("disabled");
            else if (_portalReady)
                ReportPortalTelemetry("portal_ready");
        }

        bool wasVisible = Visible;
        if (!wasVisible)
            Show();
        if (_configuration.PortalEnabled && !_portalReady && DateTimeOffset.UtcNow >= _nextPortalAttempt)
            TryInitialisePortal();
        if (_kioskActive)
        {
            ForceKioskBounds();
            BringToFront();
            if (!wasVisible)
                Activate();
        }
    }

    /// <summary>
    /// Remembers when this PC first saw the phone's current challenge. The
    /// service records the same stamp under %ProgramData% so restarting this
    /// agent cannot restart the five-minute grace; the earlier of the two
    /// wins, because either side may have seen the challenge first.
    /// </summary>
    private void TrackChallenge(FamilyChallenge? challenge, DateTimeOffset now)
    {
        if (challenge is null)
        {
            _challengeId = "";
            _challengeDismissed = false;
            return;
        }
        if (string.Equals(challenge.Id, _challengeId, StringComparison.Ordinal))
            return;
        _challengeId = challenge.Id;
        _challengeDismissed = false;
        _challengeFirstSeen = ChallengeGraceFile.TryRead(_configuration.DeviceId, challenge.Id, out DateTimeOffset recorded)
            && recorded < now
            ? recorded
            : now;
    }

    private TimeSpan ChallengeGraceRemaining(DateTimeOffset now)
    {
        TimeSpan remaining = ChallengeGrace - (now - _challengeFirstSeen);
        // A clock that moved backwards must not extend the grace past five minutes.
        return remaining > ChallengeGrace ? ChallengeGrace : remaining;
    }

    /// <summary>
    /// Full-screen notice for the phone's active chore challenge. For the first
    /// five minutes it is informational and the child can dismiss it and keep
    /// working; after that it is a hard kiosk that stays up until the challenge
    /// is answered or a parent clears it.
    /// </summary>
    private void ApplyChallengeNotice(AgentPolicy policy, FamilyChallenge challenge, DateTimeOffset now)
    {
        TimeSpan grace = ChallengeGraceRemaining(now);
        bool hard = grace <= TimeSpan.Zero;
        string name = ChildName(policy);
        string phrase = challenge.Phrase.Trim();

        ReportPortalTelemetry("idle");
        FormBorderStyle = FormBorderStyle.None;
        Bounds = ResolveKioskBounds();
        _banner.Visible = false;
        _fullLayout.Visible = true;
        ShowNoticeLayout(false);
        _title.Text = name.Length == 0
            ? "Waiting for something on the phone"
            : $"Waiting for something on {name}'s phone";
        _message.Text = phrase.Length == 0
            ? "Your phone is asking you to do a chore."
            : $"Your phone is asking you to: {phrase}";
        _countdown.Text = hard
            ? "Finish it on your phone to carry on."
            : $"PC waits {FormatRemaining(grace)}\nFinish it on your phone to carry on.";
        _extension.Visible = false;
        _dismiss.Visible = !hard;
        _dismissRow.Height = hard ? 0 : DismissRowHeight;
        ApplyPhoneStatus(null);
        SetKioskMode(hard);
    }

    private void DismissChallengeNotice()
    {
        _challengeDismissed = true;
        HideOverlay();
    }

    /// <summary>
    /// Hard freeze for a phone that has stopped checking in. The child cannot
    /// dismiss it; it clears as soon as the phone checks in again.
    /// </summary>
    private void ApplyPhoneStaleFreeze(AgentPolicy policy, TimeSpan silence)
    {
        string name = ChildName(policy);
        int hours = (int)silence.TotalHours;

        ReportPortalTelemetry("idle");
        FormBorderStyle = FormBorderStyle.None;
        Bounds = ResolveKioskBounds();
        _banner.Visible = false;
        _fullLayout.Visible = true;
        ShowNoticeLayout(false);
        _title.Text = name.Length == 0
            ? $"The phone has not checked in for {hours} hours"
            : $"{name}'s phone has not checked in for {hours} hours";
        _message.Text = "Parental control cannot be verified. Please see a parent.";
        _countdown.Text = "This PC stays frozen until the phone checks in again.";
        _extension.Visible = false;
        _dismiss.Visible = false;
        _dismissRow.Height = 0;
        ApplyPhoneStatus(null);
        SetKioskMode(true);
    }

    /// <summary>
    /// How long the phone has been silent, or null when it is online, absent,
    /// or its last check-in time is unknown. An unknown age must never freeze
    /// the PC.
    /// </summary>
    private static TimeSpan? PhoneSilence(FamilyPeerState? phone)
    {
        if (phone is null || phone.AgentOnline || phone.LastSeen is null)
            return null;
        TimeSpan silence = DateTimeOffset.UtcNow - phone.LastSeen.Value;
        return silence > TimeSpan.Zero ? silence : TimeSpan.Zero;
    }

    /// <summary>
    /// The child's name as Home Assistant reports it, used to name the phone in
    /// the notices. Empty when the payload omits it.
    /// </summary>
    private static string ChildName(AgentPolicy policy) => policy.WindowsUsername?.Trim() ?? string.Empty;

    /// <summary>
    /// Switches the two-column block layout to a single centred column for the
    /// notice states, which have no points portal to show.
    /// </summary>
    private void ShowNoticeLayout(bool portalVisible)
    {
        _fullLayout.ColumnStyles[0].Width = portalVisible ? 38 : 100;
        _fullLayout.ColumnStyles[1].Width = portalVisible ? 62 : 0;
        _portalHost.Visible = portalVisible;
    }

    public void HideOverlay()
    {
        SetKioskMode(false);
        if (Visible)
            Hide();
    }

    public void ShowStatus(string message)
    {
        if (_banner.Visible)
            _bannerCountdown.Text = message;
        else
            _countdown.Text = message;
    }

    private void ApplyFullScreen(AgentPolicy policy, TimeSpan remaining)
    {
        FormBorderStyle = FormBorderStyle.None;
        Bounds = ResolveKioskBounds();
        _banner.Visible = false;
        _fullLayout.Visible = true;
        // The block screen keeps its two-column layout even when the portal is
        // disabled; only the notice states collapse to one column.
        _fullLayout.ColumnStyles[0].Width = 38;
        _fullLayout.ColumnStyles[1].Width = 62;
        _portalHost.Visible = _configuration.PortalEnabled;
        _title.Text = policy.Blocked ? "PC time is paused" : "PC time is ending";
        _message.Text = policy.Message;
        _countdown.Text = policy.Blocked
            ? "Use your points portal while you wait for a parent to unpause this PC."
            : $"Save your work — blocking in {FormatRemaining(remaining)}";
        _extension.Visible = !policy.Blocked && policy.ExtensionAvailable;
        _dismiss.Visible = false;
        _dismissRow.Height = 0;
        if (!_extension.Visible && !policy.Blocked && policy.ExtensionAvailableAt is not null)
            _countdown.Text += $"\nAnother 5-minute extension is available at {policy.ExtensionAvailableAt:HH:mm}.";
        ApplyPhoneStatus(policy.Family?.Phone);
        SetKioskMode(true);
    }

    /// <summary>
    /// Shows the child's phone status from the <c>family.phone</c> peer block.
    /// The line and its layout row are hidden when Home Assistant reports no
    /// phone peer for this child.
    /// </summary>
    private void ApplyPhoneStatus(FamilyPeerState? phone)
    {
        if (phone is null)
        {
            _phone.Text = string.Empty;
            _phone.Visible = false;
            _phoneRow.Height = 0;
            return;
        }

        _phone.Text = DescribePhone(phone);
        _phone.Visible = true;
        _phoneRow.Height = PhoneRowHeight;
    }

    private static string DescribePhone(FamilyPeerState phone)
    {
        // A phone that has stopped checking in cannot be trusted to still be
        // enforcing, so that outranks the last reported block state.
        if (!phone.AgentOnline)
            return "Phone: not checking in" + DescribeOfflineFor(phone.LastSeen);
        if (phone.Blocked)
            return "Phone: paused";
        string application = phone.ActiveApplication.Trim();
        return application.Length == 0 || IsIdleApplication(application)
            ? "Phone: open"
            : $"Phone: open — {application}";
    }

    private static string DescribeOfflineFor(DateTimeOffset? lastSeen)
    {
        if (lastSeen is null)
            return string.Empty;
        TimeSpan offline = DateTimeOffset.UtcNow - lastSeen.Value;
        if (offline.TotalHours >= 1)
            return $" ({(int)offline.TotalHours} h)";
        if (offline.TotalMinutes >= 1)
            return $" ({(int)offline.TotalMinutes} min)";
        return string.Empty;
    }

    private static bool IsIdleApplication(string application) =>
        application.Equals("idle", StringComparison.OrdinalIgnoreCase)
        || application.Equals("unknown", StringComparison.OrdinalIgnoreCase);

    private void ApplyCountdownBanner(TimeSpan remaining)
    {
        SetKioskMode(false);
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        Rectangle working = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
        Bounds = new Rectangle(working.Right - 460, working.Top + 20, 440, 150);
        _fullLayout.Visible = false;
        _banner.Visible = true;
        _bannerTitle.Text = "Extra save-work time";
        _bannerCountdown.Text = $"PC blocks again in {FormatRemaining(remaining)}";
    }

    private void SetKioskMode(bool enabled)
    {
        _kioskActive = enabled;
        if (enabled && _keyboardGuard is null)
            _keyboardGuard = new KeyboardKioskGuard();
        else if (!enabled && _keyboardGuard is not null)
        {
            _keyboardGuard.Dispose();
            _keyboardGuard = null;
        }
    }

    private void TryInitialisePortal()
    {
        if (_portalReady || _portalInitialising || IsDisposed || !IsHandleCreated)
            return;
        _portalInitialising = true;
        _ = InitialisePortalAsync();
    }

    private async Task InitialisePortalAsync()
    {
        try
        {
            string userDataFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "RowePcBlocker",
                "WebView2",
                new string(_configuration.DeviceId.Where(char.IsLetterOrDigit).ToArray()));
            Directory.CreateDirectory(userDataFolder);
            ReportPortalTelemetry("portal_initialising");
            CoreWebView2Environment environment;
            try
            {
                environment = await CoreWebView2Environment.CreateAsync(userDataFolder: userDataFolder);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                string? runtimeFolder = FindInstalledWebViewRuntime();
                if (runtimeFolder is null)
                    throw;
                environment = await CoreWebView2Environment.CreateAsync(
                    browserExecutableFolder: runtimeFolder,
                    userDataFolder: userDataFolder);
            }
            await _portal.EnsureCoreWebView2Async(environment);
            if (IsDisposed || _portal.CoreWebView2 is null)
                return;

            CoreWebView2 core = _portal.CoreWebView2;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.NavigationStarting += (_, args) =>
            {
                if (!NavigationAllowed(args.Uri))
                    args.Cancel = true;
            };
            core.NewWindowRequested += (_, args) => args.Handled = true;
            core.DownloadStarting += (_, args) => args.Cancel = true;
            core.PermissionRequested += (_, args) => args.State = CoreWebView2PermissionState.Deny;
            core.ProcessFailed += (_, _) => ReportPortalFailure("portal_process_failed");
            core.NavigationCompleted += (_, args) =>
            {
                if (args.IsSuccess)
                    ReportPortalTelemetry("portal_ready");
                else
                    ReportPortalFailure("portal_navigation_failed");
            };
            _portal.Source = _portalUri;
            _portal.Visible = true;
            _portalStatus.Visible = false;
            _portalReady = true;
        }
        catch (Exception exception)
        {
            _portalReady = false;
            _portal.Visible = false;
            _portalStatus.Visible = true;
            string status = ClassifyPortalException(exception);
            ReportPortalFailure(status, DescribePortalException(exception));
            _portalStatus.Text = "The points portal is reconnecting. The block remains active; a parent can see the reason in Home Assistant.";
            _nextPortalAttempt = DateTimeOffset.UtcNow.AddSeconds(30);
        }
        finally
        {
            _portalInitialising = false;
        }
    }

    private static string? FindInstalledWebViewRuntime()
    {
        const string runtimeRoot = @"C:\Program Files (x86)\Microsoft\EdgeWebView\Application";
        if (!Directory.Exists(runtimeRoot))
            return null;
        return Directory.EnumerateDirectories(runtimeRoot)
            .Where(path => File.Exists(Path.Combine(path, "msedgewebview2.exe")))
            .OrderByDescending(Path.GetFileName, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();
    }

    private static string ClassifyPortalException(Exception exception) => exception switch
    {
        WebView2RuntimeNotFoundException => "portal_runtime_unavailable",
        UnauthorizedAccessException => "portal_storage_access_denied",
        COMException => "portal_webview_initialisation_failed",
        _ => "portal_initialisation_failed"
    };

    private static string DescribePortalException(Exception exception) =>
        $"{exception.GetType().Name} (0x{exception.HResult:X8})";

    private void ReportPortalFailure(string status, string? error = null)
    {
        _portalReady = false;
        ReportPortalTelemetry(
            status,
            error ?? "A parent can review the portal status in Home Assistant.");
    }

    private void ReportPortalTelemetry(string status, string error = "")
    {
        if (status == _reportedPortalStatus && error == _reportedPortalError)
            return;
        _reportedPortalStatus = status;
        _reportedPortalError = error;
        PortalTelemetryChanged?.Invoke(this, new PortalTelemetry(status, error));
    }

    private bool NavigationAllowed(string candidateText)
    {
        if (!Uri.TryCreate(candidateText, UriKind.Absolute, out Uri? candidate))
            return false;
        if (!string.Equals(candidate.Scheme, _portalUri.Scheme, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(candidate.GetLeftPart(UriPartial.Authority), _allowedOrigin, StringComparison.OrdinalIgnoreCase))
            return false;
        string path = candidate.AbsolutePath.TrimEnd('/');
        if (path.Length == 0)
            path = "/";
        return string.Equals(path, _allowedPath, StringComparison.OrdinalIgnoreCase)
            || (_allowedPath != "/" && path.StartsWith(_allowedPath + "/", StringComparison.OrdinalIgnoreCase));
    }

    private static Uri BuildPortalUri(AgentConfiguration configuration)
    {
        if (!string.IsNullOrWhiteSpace(configuration.PortalUrl))
        {
            string custom = configuration.PortalUrl
                .Replace("{device_id}", Uri.EscapeDataString(configuration.DeviceId), StringComparison.OrdinalIgnoreCase)
                .Replace("{username}", Uri.EscapeDataString(Environment.UserName), StringComparison.OrdinalIgnoreCase);
            if (Uri.TryCreate(custom, UriKind.Absolute, out Uri? customUri)
                && customUri.Scheme is "https" or "http")
                return customUri;
        }

        string root = configuration.HomeAssistantUrl.TrimEnd('/');
        string path = $"{root}/api/rowe_pc_blocker/{Uri.EscapeDataString(configuration.DeviceId)}/portal";
        UriBuilder builder = new(path)
        {
            Query = "key=" + Uri.EscapeDataString(configuration.DeviceApiKey)
        };
        return builder.Uri;
    }

    private static Label CreateLabel(float size, FontStyle style) => new()
    {
        AutoSize = false,
        Dock = DockStyle.Fill,
        Font = new Font("Segoe UI", size, style),
        ForeColor = Color.White,
        TextAlign = ContentAlignment.MiddleCenter
    };

    private static string FormatRemaining(TimeSpan remaining)
    {
        int seconds = Math.Max(0, (int)Math.Ceiling(remaining.TotalSeconds));
        return $"{seconds / 60:00}:{seconds % 60:00}";
    }

    private static Rectangle ResolveKioskBounds()
    {
        Rectangle primary = Screen.PrimaryScreen?.Bounds ?? SystemInformation.VirtualScreen;
        // Some VNC/display-driver combinations expose a pair of equal
        // side-by-side physical panels as one logical WinForms screen. Cover
        // the adjacent panel as well so it cannot remain usable while blocked.
        if (Screen.AllScreens.Length == 1 && primary.Width <= 1920)
            return new Rectangle(primary.Left, primary.Top, primary.Width * 2, primary.Height);
        return SystemInformation.VirtualScreen;
    }

    private void ForceKioskBounds()
    {
        Rectangle bounds = ResolveKioskBounds();
        SetBounds(bounds.Left, bounds.Top, bounds.Width, bounds.Height, BoundsSpecified.All);
        if (IsHandleCreated)
        {
            SetWindowPos(
                Handle,
                new IntPtr(-1),
                bounds.Left,
                bounds.Top,
                bounds.Width,
                bounds.Height,
                0x0020 | 0x0040);
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(
        IntPtr window,
        IntPtr insertAfter,
        int x,
        int y,
        int width,
        int height,
        uint flags);

}

internal sealed record PortalTelemetry(string Status, string Error);

internal sealed class KeyboardKioskGuard : IDisposable
{
    private const int WhKeyboardLl = 13;
    private const int WmKeyDown = 0x0100;
    private const int WmSysKeyDown = 0x0104;
    private const int VkControl = 0x11;
    private const int VkShift = 0x10;
    private const int VkMenu = 0x12;
    private readonly HookProc _callback;
    private IntPtr _hook;

    public KeyboardKioskGuard()
    {
        _callback = HandleKey;
        _hook = SetWindowsHookEx(WhKeyboardLl, _callback, GetModuleHandle(null), 0);
    }

    public void Dispose()
    {
        if (_hook != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }

    private IntPtr HandleKey(int code, IntPtr message, IntPtr data)
    {
        if (code >= 0 && (message == (IntPtr)WmKeyDown || message == (IntPtr)WmSysKeyDown))
        {
            uint key = (uint)Marshal.ReadInt32(data);
            bool control = (GetAsyncKeyState(VkControl) & 0x8000) != 0;
            bool shift = (GetAsyncKeyState(VkShift) & 0x8000) != 0;
            bool alt = (GetAsyncKeyState(VkMenu) & 0x8000) != 0;
            bool blocked = key is 0x5B or 0x5C
                || (alt && key is 0x09 or 0x1B or 0x20 or 0x73)
                || (control && key == 0x1B)
                || (control && shift && key == 0x1B);
            if (blocked)
                return (IntPtr)1;
        }
        return CallNextHookEx(_hook, code, message, data);
    }

    private delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int hookId, HookProc callback, IntPtr module, uint threadId);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnhookWindowsHookEx(IntPtr hook);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? moduleName);
}

internal sealed class SecondaryBlockOverlayForm : Form
{
    private readonly Screen _screen;
    private readonly Label _message;

    public SecondaryBlockOverlayForm(Screen screen)
    {
        _screen = screen;
        BackColor = Color.FromArgb(20, 24, 31);
        ForeColor = Color.White;
        TopMost = true;
        ShowInTaskbar = false;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        _message = new Label
        {
            Dock = DockStyle.Fill,
            Font = new Font("Segoe UI", 22, FontStyle.Bold),
            ForeColor = Color.White,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "PC time is paused\n\nPlease use the points screen on your main display."
        };
        Controls.Add(_message);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            return;
        }
        base.OnFormClosing(e);
    }

    public void Apply(AgentPolicy policy)
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        DateTimeOffset enforceAt = policy.EnforceAt ?? now;
        TimeSpan remaining = enforceAt - now;
        bool extended = policy.ExtensionUntil is not null && policy.ExtensionUntil > now && remaining.TotalSeconds > 30;
        if (!policy.BlockRequested || extended)
        {
            HideOverlay();
            return;
        }
        Bounds = _screen.Bounds;
        _message.Text = policy.Blocked
            ? "PC time is paused\n\nPlease use the points screen on your main display."
            : "PC time is ending\n\nPlease save your work on your main display.";
        if (!Visible)
            Show();
        BringToFront();
    }

    public void HideOverlay()
    {
        if (Visible)
            Hide();
    }
}

internal sealed record AgentPolicy(
    [property: System.Text.Json.Serialization.JsonPropertyName("blocked")] bool Blocked,
    [property: System.Text.Json.Serialization.JsonPropertyName("block_requested")] bool BlockRequested,
    [property: System.Text.Json.Serialization.JsonPropertyName("message")] string Message,
    [property: System.Text.Json.Serialization.JsonPropertyName("windows_username")] string WindowsUsername,
    [property: System.Text.Json.Serialization.JsonPropertyName("enforce_at")] DateTimeOffset? EnforceAt,
    [property: System.Text.Json.Serialization.JsonPropertyName("extension_available")] bool ExtensionAvailable,
    [property: System.Text.Json.Serialization.JsonPropertyName("extension_available_at")] DateTimeOffset? ExtensionAvailableAt,
    [property: System.Text.Json.Serialization.JsonPropertyName("extension_until")] DateTimeOffset? ExtensionUntil)
{
    /// <summary>
    /// The child's other devices, filled in by <see cref="AgentApplicationContext.GetPolicyAsync"/>
    /// rather than by the serializer so a malformed peer block cannot fail the
    /// policy read that drives blocking.
    /// </summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public FamilyState? Family { get; init; }
}

/// <summary>Status of the child's other devices, as reported by Home Assistant.</summary>
internal sealed record FamilyState(FamilyPeerState? Phone, FamilyPeerState? Pc)
{
    /// <summary>
    /// Reads the optional <c>family</c> block. Every field is optional: an
    /// older Home Assistant build, or a peer that has never checked in, must
    /// never fail the state poll that drives the kiosk.
    /// </summary>
    public static FamilyState? Parse(JsonElement root)
    {
        if (!root.TryGetProperty("family", out JsonElement family) || family.ValueKind != JsonValueKind.Object)
            return null;
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
            ReadString(peer, "active_application"),
            ParseChallenge(peer));
    }

    /// <summary>
    /// Reads the peer's active chore challenge. A missing key, an explicit
    /// null, or a challenge without an id all mean "no challenge", so a
    /// malformed payload can never freeze the PC.
    /// </summary>
    private static FamilyChallenge? ParseChallenge(JsonElement peer)
    {
        if (!peer.TryGetProperty("challenge", out JsonElement challenge) || challenge.ValueKind != JsonValueKind.Object)
            return null;
        string id = ReadString(challenge, "id");
        if (id.Length == 0)
            return null;
        return new FamilyChallenge(id, ReadString(challenge, "phrase"), ReadTimestamp(challenge, "sent_at"));
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

/// <summary>One peer device of the same child, for example the child's phone.</summary>
internal sealed record FamilyPeerState(
    string DeviceId,
    string DeviceType,
    bool Blocked,
    string Message,
    bool AgentOnline,
    DateTimeOffset? LastSeen,
    string ActiveApplication,
    FamilyChallenge? Challenge);

/// <summary>An active chore challenge on a peer device, or null when there is none.</summary>
internal sealed record FamilyChallenge(string Id, string Phrase, DateTimeOffset? SentAt);

/// <summary>
/// Reads the first-seen stamp the service records for the phone's current
/// chore challenge. The file lives under %ProgramData% because the activity
/// agent runs as the child, who must not be able to reset the grace by editing
/// their own state.
/// </summary>
internal static class ChallengeGraceFile
{
    public static bool TryRead(string deviceId, string challengeId, out DateTimeOffset firstSeen)
    {
        firstSeen = default;
        try
        {
            string path = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "RowePcBlocker",
                "challenge-grace.json");
            if (!File.Exists(path))
                return false;
            using JsonDocument document = JsonDocument.Parse(File.ReadAllText(path));
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
                return false;
            if (!string.Equals(ReadString(root, "device_id"), deviceId, StringComparison.OrdinalIgnoreCase))
                return false;
            if (!string.Equals(ReadString(root, "challenge_id"), challengeId, StringComparison.Ordinal))
                return false;
            if (!root.TryGetProperty("first_seen", out JsonElement seen)
                || seen.ValueKind != JsonValueKind.String
                || !DateTimeOffset.TryParse(
                    seen.GetString(),
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.None,
                    out DateTimeOffset parsed))
                return false;
            firstSeen = parsed;
            return true;
        }
        catch
        {
            // A missing or malformed file only costs the persisted grace; the
            // notice still works from the in-memory first sighting.
            return false;
        }
    }

    private static string ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;
}

internal sealed record AgentConfiguration(
    string HomeAssistantUrl,
    string DeviceId,
    string DeviceApiKey,
    bool UserInterfaceEnabled,
    bool PortalEnabled,
    string PortalUrl)
{
    public static AgentConfiguration Load()
    {
        string commonData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        string path = Path.Combine(commonData, "RowePcBlocker", "appsettings.Local.json");
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(path));
        JsonElement section = document.RootElement.GetProperty("Blocker");
        return new AgentConfiguration(
            section.GetProperty("HomeAssistantUrl").GetString()!,
            section.GetProperty("DeviceId").GetString()!,
            section.GetProperty("DeviceApiKey").GetString()!,
            section.TryGetProperty("UserInterfaceEnabled", out JsonElement ui) && ui.GetBoolean(),
            section.TryGetProperty("PortalEnabled", out JsonElement portal) && portal.GetBoolean(),
            section.TryGetProperty("PortalUrl", out JsonElement portalUrl) ? portalUrl.GetString() ?? "" : "");
    }
}

internal static class ForegroundWindow
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, char[] text, int count);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);

    public static (string Application, string Title) Read()
    {
        IntPtr handle = GetForegroundWindow();
        if (handle == IntPtr.Zero)
            return ("Idle", "Idle");
        char[] buffer = new char[512];
        int length = GetWindowText(handle, buffer, buffer.Length);
        string title = length > 0 ? new string(buffer, 0, length) : "Untitled";
        GetWindowThreadProcessId(handle, out uint processId);
        string application;
        try { application = Process.GetProcessById((int)processId).ProcessName + ".exe"; }
        catch { application = "Unknown"; }
        return (application[..Math.Min(application.Length, 255)], title[..Math.Min(title.Length, 255)]);
    }
}
