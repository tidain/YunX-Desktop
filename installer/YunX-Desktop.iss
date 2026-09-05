; YunX-Desktop installer script (Inno Setup 6)
; Compiled by installer-package.ps1 with /DAppVersion=x.y.z
; Source: the portable output folder (release\YunX-Desktop\) which already contains
; the custom launcher and skiko native libs.

#define AppName "云析"
#define AppNameEn "YunX-Desktop"
#define AppExe "YunX-Desktop.exe"
#ifndef AppVersion
#define AppVersion "1.1.2"
#endif

[Setup]
AppId={{7C1E5F9A-3B2D-4E68-9C4F-A1D2B3C4E5F6}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppNameEn}
; lowest = no UAC prompt; {autopf} then maps to %LOCALAPPDATA%\Programs (ASCII-safe path
; for the bundled launcher). Remove PrivilegesRequired line to install per-machine instead.
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppNameEn}
DisableProgramGroupPage=yes
WizardStyle=modern
SetupIconFile=..\YunX-Desktop.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
; 输出到 release\（相对本 .iss 所在目录）
OutputDir=..\release
OutputBaseFilename=YunX-Desktop-setup-{#AppVersion}
ShowLanguageDialog=no

[Languages]
; 中文语言包随仓库分发（installer\ChineseSimplified.isl），构建不依赖外网
Name: "chs"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\release\YunX-Desktop\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
