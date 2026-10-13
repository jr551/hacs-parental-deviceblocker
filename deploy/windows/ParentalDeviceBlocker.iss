; Inno Setup script — built in CI with /DMyAppVersion=x.y.z
; Packages the publish output from scripts/build-release.sh (payload + PowerShell helpers).

#define MyAppName "Parental Device Blocker"
#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif
#define MyAppPublisher "Parental Device Blocker"
#define MyAppURL "https://github.com/jr551/hacs-parental-deviceblocker"
#define PayloadDir "..\..\artifacts\ParentalDeviceBlocker-" + MyAppVersion + "-win-x64"

[Setup]
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\ParentalDeviceBlocker
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\..\artifacts
OutputBaseFilename=ParentalDeviceBlocker-{#MyAppVersion}-win-x64-setup
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=admin

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Parental Device Blocker README"; Filename: "{app}\Install-RowePcBlocker.ps1"; Parameters: "-?"

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\Install-RowePcBlocker.ps1"""; Description: "Open install helper (requires parameters)"; Flags: postinstall skipifsilent unchecked
