[Setup]
AppName=LocoDrive
AppVersion=1.0.0
DefaultDirName={autopf}\LocoDrive
DefaultGroupName=LocoDrive
UninstallDisplayIcon={app}\locodrive.exe
Compression=lzma2
SolidCompression=yes
OutputDir=..\..\dist
OutputBaseFilename=LocoDrive-Setup
PrivilegesRequired=lowest

[Files]
Source: "..\..\build\locodrive.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\LocoDrive"; Filename: "{app}\locodrive.exe"
Name: "{autodesktop}\LocoDrive"; Filename: "{app}\locodrive.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"
