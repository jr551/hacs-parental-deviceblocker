using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using ParentalDeviceBlocker;

HostApplicationBuilder builder = Host.CreateApplicationBuilder(args);
string commonData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
string configurationDirectory = Path.Combine(commonData, "RowePcBlocker");
builder.Configuration
    .SetBasePath(configurationDirectory)
    .AddJsonFile("appsettings.Local.json", optional: false, reloadOnChange: true);
builder.Services.Configure<BlockerOptions>(builder.Configuration.GetSection(BlockerOptions.SectionName));
builder.Services.AddHttpClient<HomeAssistantClient>();
builder.Services.AddSingleton<WindowsAccountBlocker>();
builder.Services.AddHostedService<BlockerWorker>();
builder.Services.AddWindowsService(options => options.ServiceName = "Parental Device Blocker");

await builder.Build().RunAsync();
