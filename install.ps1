# Installs (or removes) IMDB-TV on an Android TV over the network. No adb binary needed.
# Usage:   .\install.ps1 -TvIp 192.168.0.50
#          .\install.ps1 -TvIp 192.168.0.50 -Uninstall
# First, on the TV: turn on "USB debugging" (or "ADB debugging") in Developer options.
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = Join-Path $Here 'tools'
$Venv = Join-Path $Tools 'venv'
$Py = Join-Path $Venv 'Scripts\python.exe'
$Apk = Join-Path $Here 'apk\IMDB-TV.apk'
$ApkUrl = 'https://github.com/mirtera157/IMDB-TV/releases/latest/download/IMDB-TV.apk'
$Package = 'br.tvvibe.imdbbadge'

function Step($t) { Write-Host "`n== $t" -ForegroundColor Cyan }

# 1. Python + libraries (first run only)
if (-not (Test-Path $Py)) {
    Step 'Setting up Python (first run only)'
    $base = $null
    foreach ($c in @('py', 'python', 'python3')) {
        if (Get-Command $c -ErrorAction SilentlyContinue) { $base = $c; break }
    }
    if (-not $base) { throw 'Python 3 not found. Install it with: winget install Python.Python.3.12  (then open a new terminal)' }
    $pyArgs = @(); if ($base -eq 'py') { $pyArgs = @('-3') }
    & $base @pyArgs -m venv $Venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Python environment.' }
    & $Py -m pip install --quiet -r (Join-Path $Tools 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the Python libraries (needs internet).' }
}

# 2. The app itself: a local build if there is one, else the latest release
if (-not $Uninstall -and -not (Test-Path $Apk)) {
    Step 'Downloading the latest IMDB-TV.apk'
    New-Item -ItemType Directory -Force (Split-Path $Apk) | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $ApkUrl -OutFile $Apk
}

$env:TV_HOST = $TvIp
function Tv([string]$cmd) { & $Py -I (Join-Path $Tools 'tv.py') $cmd }

# 3. Connect
Step "Connecting to the TV at $TvIp (watch the TV: accept 'Allow USB debugging?' and tick 'Always allow')"
& $Py -I (Join-Path $Tools 'tv.py') --probe
if ($LASTEXITCODE -ne 0) { throw 'Could not reach the TV. Check the IP, that USB debugging is on, and that the PC and TV are on the same network.' }

if ($Uninstall) {
    Step 'Removing IMDB-TV'
    Tv "settings delete secure enabled_accessibility_services; pm uninstall $Package"
    Write-Host 'Removed.' -ForegroundColor Green
    return
}

# 4. Send and install
Step 'Sending the app to the TV (takes a few minutes)'
& $Py -I (Join-Path $Tools 'b64push.py') $Apk '/data/local/tmp/imdbtv.apk'
if ($LASTEXITCODE -ne 0) { throw 'Sending the APK failed.' }
Step 'Installing'
Tv 'pm install -r /data/local/tmp/imdbtv.apk; rm -f /data/local/tmp/imdbtv.apk'

# 5. Permission + switch the accessibility service on
# WRITE_SECURE_SETTINGS: the app switches accessibility off and on to wake up the Apple TV app.
Step 'Switching it on'
Tv "pm grant $Package android.permission.WRITE_SECURE_SETTINGS; settings put secure enabled_accessibility_services $Package/.BadgeService; settings put secure accessibility_enabled 1"

Write-Host "`nDone! Open the Apple TV app: the IMDb rating appears in the top-right corner of the selected title (about 10 s the first time)." -ForegroundColor Green
Write-Host 'You can turn USB debugging off on the TV now.'
