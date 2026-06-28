$ErrorActionPreference = "Stop"

$Binary = Get-ChildItem -Path "target\gluonfx" -Recurse -Filter "locodrive.exe" | Select-Object -First 1
if (-not $Binary) {
    Write-Error "❌ Error: Native binary not found!"
    exit 1
}

Write-Host "Creating mock configuration..."
$ConfigDir = "$env:USERPROFILE\.locodrive"
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
$ConfigJson = @"
{
  "bindAddress": "127.0.0.1",
  "port": 8080,
  "guestEnabled": true,
  "users": [
    {
      "username": "admin",
      "hashedPassword": "fake",
      "role": "ADMIN",
      "enabled": true
    }
  ],
  "sharedFolders": [
    {
      "alias": "TestFolder",
      "path": "C:\\",
      "guestAccessible": true,
      "readOnly": true
    }
  ]
}
"@
Set-Content -Path "$ConfigDir\config.json" -Value $ConfigJson

Write-Host "Starting LocoDrive native binary: $($Binary.FullName)"
$Process = Start-Process -FilePath $Binary.FullName -PassThru

Write-Host "Waiting 5 seconds for server to initialize..."
Start-Sleep -Seconds 5

Write-Host "Testing HTTP endpoint..."
try {
    $Response = Invoke-WebRequest -Uri "http://127.0.0.1:8080" -UseBasicParsing
    Write-Host "✅ Validation passed! Server responded to HTTP request."
    Stop-Process -Id $Process.Id -Force
    exit 0
} catch {
    Write-Error "❌ Validation failed! Server did not respond."
    Stop-Process -Id $Process.Id -Force
    exit 1
}
