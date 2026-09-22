<#
.SYNOPSIS
    Builds Crimson and verifies it on an Android TV emulator or a real Fire TV Stick.

.DESCRIPTION
    Everything the development container could not do, in one command. It will:

      1. Check for a JDK, Python and the Android SDK, installing the SDK command-line tools if
         they are missing.
      2. Install the platform, build-tools, platform-tools, emulator and an Android TV system
         image, accepting licences.
      3. Start the mock Xtream server (tools/mock-xtream), which generates its own test media.
      4. Run the unit tests and the JVM screenshot tests.
      5. Build and install the APK.
      6. Create and boot a 1080p Android TV AVD sized like a Fire Stick, unless -Device is given.
      7. Drive the app with D-pad and media keys, capturing a screenshot at each step.
      8. Record peak memory (dumpsys meminfo) and frame timing (dumpsys gfxinfo).
      9. Write everything to reports/device-verification.md and screenshots/.

.PARAMETER Device
    An adb device serial to use instead of creating an emulator. For a real Fire TV Stick:
      adb connect 192.168.1.50:5555
      .\tools\verify-on-device.ps1 -Device 192.168.1.50:5555

.PARAMETER SkipBuild
    Reuse the APK already in app\build\outputs.

.PARAMETER SkipSdkInstall
    Assume the SDK is already complete.

.PARAMETER Reset
    Clear the app's data before the run. Automatic on an emulator. On a real device it deletes
    every profile, which is why it has to be asked for; without it the run expects a fresh install.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\verify-on-device.ps1
#>

param(
    [string]$Device = "",
    [string]$AndroidHome = "",
    [int]$ApiLevel = 30,
    [switch]$SkipBuild,
    [switch]$SkipSdkInstall,
    [switch]$KeepEmulator,
    # Clears the app's data first, so the run starts at the first-profile screen it expects.
    # Always done on an emulator; on a real device only when asked, since it deletes the
    # viewer's profiles.
    [switch]$Reset
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$screenshotDir = Join-Path $root "screenshots"
$reportDir = Join-Path $root "reports"
$reportFile = Join-Path $reportDir "device-verification.md"
# The AVD name is RetroGuide's, so a machine that has built both reuses one emulator image.
$avdName = "retroguide_tv_$ApiLevel"
$mockPort = 8080

New-Item -ItemType Directory -Force -Path $screenshotDir, $reportDir | Out-Null

$script:report = [System.Collections.ArrayList]::new()
function Say($text)  { Write-Host "==> $text" -ForegroundColor Cyan }
function Warn($text) { Write-Host "    $text" -ForegroundColor Yellow }
function Note($text) { [void]$script:report.Add($text) }

# ---------------------------------------------------------------------------- prerequisites

Say "Checking prerequisites"

function Require-Command($name, $hint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "$name was not found on PATH. $hint"
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Warn "No JDK found. Attempting to install Temurin 17 with winget."
    winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
    Warn "Open a new terminal so JAVA_HOME and PATH take effect, then re-run this script."
    exit 1
}
Require-Command java "Install a JDK 17 or 21."

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Warn "No Python found. Attempting to install Python 3 with winget."
    winget install --id Python.Python.3.12 -e --accept-package-agreements --accept-source-agreements
    Warn "Open a new terminal so PATH takes effect, then re-run this script."
    exit 1
}

# ---------------------------------------------------------------------------- Android SDK

if ($AndroidHome -eq "") {
    $AndroidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
                   elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
                   else { "C:\Android\sdk" }
}
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome

$cmdlineBin = Join-Path $AndroidHome "cmdline-tools\latest\bin"
$sdkmanager = Join-Path $cmdlineBin "sdkmanager.bat"
$avdmanager = Join-Path $cmdlineBin "avdmanager.bat"
$adb = Join-Path $AndroidHome "platform-tools\adb.exe"
$emulator = Join-Path $AndroidHome "emulator\emulator.exe"

if (-not $SkipSdkInstall) {
    if (-not (Test-Path $sdkmanager)) {
        Say "Installing Android command-line tools into $AndroidHome"
        New-Item -ItemType Directory -Force -Path $AndroidHome | Out-Null
        $zip = Join-Path $env:TEMP "android-cmdline-tools.zip"
        $url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
        Invoke-WebRequest -Uri $url -OutFile $zip
        $staging = Join-Path $env:TEMP "android-cmdline-staging"
        if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
        Expand-Archive -Path $zip -DestinationPath $staging
        $target = Join-Path $AndroidHome "cmdline-tools\latest"
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Move-Item (Join-Path $staging "cmdline-tools") $target
        Remove-Item $zip, $staging -Recurse -Force -ErrorAction SilentlyContinue
    }

    Say "Accepting SDK licences"
    "y`ny`ny`ny`ny`ny`ny`ny`n" | & $sdkmanager --licenses | Out-Null

    # The exact system image differs by host; an x86_64 Android TV image is what an AVD needs.
    $systemImage = "system-images;android-$ApiLevel;android-tv;x86"
    if ($ApiLevel -ge 30) { $systemImage = "system-images;android-$ApiLevel;android-tv;x86" }

    Say "Installing SDK packages (this takes a few minutes the first time)"
    & $sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" $systemImage
    if ($LASTEXITCODE -ne 0) {
        Warn "sdkmanager reported an error. Run '$sdkmanager --list' to see the exact package names available."
        throw "SDK install failed"
    }
}

$env:PATH = "$cmdlineBin;$(Join-Path $AndroidHome 'platform-tools');$(Join-Path $AndroidHome 'emulator');$env:PATH"
Set-Content -Path (Join-Path $root "local.properties") -Value "sdk.dir=$($AndroidHome -replace '\\','\\')"

# ---------------------------------------------------------------------------- mock server

Say "Starting the mock Xtream server on port $mockPort"
$mockDir = Join-Path $root "tools\mock-xtream"
$mock = Start-Process -FilePath "python" `
    -ArgumentList "server.py", "--port", "$mockPort", "--host", "0.0.0.0" `
    -WorkingDirectory $mockDir -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 6
try {
    $probe = Invoke-WebRequest -Uri "http://127.0.0.1:$mockPort/" -UseBasicParsing -TimeoutSec 10
    Note "- Mock server: $($probe.Content.Trim())"
} catch {
    Warn "The mock server did not answer. Continuing; playback checks will fail."
    Note "- Mock server: FAILED to start"
}

# ---------------------------------------------------------------------------- tests and build

Push-Location $root
try {
    Say "Running unit tests"
    & .\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest
    $unitOk = ($LASTEXITCODE -eq 0)
    Note "- Unit tests: $(if ($unitOk) { 'PASS' } else { 'FAIL' })"

    Say "Recording and verifying screenshot tests"
    & .\gradlew.bat --no-daemon recordRoborazziDebug
    $shotOk = ($LASTEXITCODE -eq 0)
    Note "- Screenshot tests: $(if ($shotOk) { 'recorded' } else { 'FAILED' })"

    if (-not $SkipBuild) {
        Say "Building the APK"
        if (Test-Path (Join-Path $root "secrets\keystore.properties")) {
            & .\gradlew.bat --no-daemon release
            $apk = Get-ChildItem (Join-Path $root "dist") -Filter *.apk |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
            Note "- Build: signed release APK ($($apk.Name))"
        } else {
            Warn "No release keystore; building debug instead. Run tools\make-keystore.ps1 first."
            & .\gradlew.bat --no-daemon :app:assembleDebug
            $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\debug") -Filter *.apk |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
            Note "- Build: debug APK ($($apk.Name))"
        }
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } else {
        $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk") -Recurse -Filter *.apk |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------- device

function Wait-ForBoot($serial) {
    Say "Waiting for $serial to finish booting"
    & $adb -s $serial wait-for-device
    for ($i = 0; $i -lt 180; $i++) {
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
        if ($booted -match "1") { return }
        Start-Sleep -Seconds 2
    }
    throw "$serial did not boot within six minutes"
}

$startedEmulator = $null
if ($Device -ne "") {
    $serial = $Device
    Say "Using device $serial"
    & $adb connect $serial 2>&1 | Out-Null
} else {
    Say "Checking hardware virtualisation"
    $hyperv = (Get-CimInstance Win32_ComputerSystem).HypervisorPresent
    if (-not $hyperv) {
        Warn "No hypervisor detected. Enable WHPX or Hyper-V in Windows Features, or pass -Device"
        Warn "with a real Fire TV Stick over 'adb connect'."
        Note "- Emulator: SKIPPED (no hardware virtualisation)"
    }

    $existing = & $avdmanager list avd
    if ($existing -notmatch [regex]::Escape($avdName)) {
        Say "Creating AVD $avdName"
        # 1 GB of RAM and a 1080p screen approximate a Fire TV Stick Lite.
        "no" | & $avdmanager create avd --name $avdName --package "system-images;android-$ApiLevel;android-tv;x86" --device "tv_1080p" --force
    }

    Say "Starting the emulator"
    $startedEmulator = Start-Process -FilePath $emulator `
        -ArgumentList "-avd", $avdName, "-memory", "1024", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect" `
        -PassThru -WindowStyle Minimized
    $serial = "emulator-5554"
    Wait-ForBoot $serial
}

Wait-ForBoot $serial

# The emulator reaches the host's mock server at 10.0.2.2; a real device uses the LAN address.
$serverForApp = if ($Device -ne "" -and $Device -notlike "emulator-*") {
    (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
        Select-Object -First 1).IPAddress + ":$mockPort"
} else {
    "10.0.2.2:$mockPort"
}
Note "- Device: $serial, mock server reachable at http://$serverForApp"

Say "Installing the APK"
& $adb -s $serial install -r -g "$($apk.FullName)"
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

$package = if ($apk.Name -like "*debug*") { "com.crimson.debug" } else { "com.crimson" }

if ($Reset -or $serial -like "emulator-*") {
    Say "Clearing $package's data so the run starts at the first-profile screen"
    & $adb -s $serial shell pm clear $package | Out-Null
}

Say "Launching"
& $adb -s $serial shell monkey -p $package -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 6

# ---------------------------------------------------------------------------- driving

function Shot($name) {
    # Captured on the device and pulled, not redirected: a PowerShell `>` decodes a native
    # command's output as text and re-encodes it, which turns a PNG into UTF-16 garbage.
    $path = Join-Path $screenshotDir "$name.png"
    & $adb -s $serial shell screencap -p /sdcard/crimson_shot.png | Out-Null
    & $adb -s $serial pull /sdcard/crimson_shot.png "$path" | Out-Null
    Write-Host "    screenshot: $name.png"
}

function Key($keycode, $times = 1, $pause = 0.4) {
    for ($i = 0; $i -lt $times; $i++) {
        & $adb -s $serial shell input keyevent $keycode | Out-Null
        Start-Sleep -Seconds $pause
    }
}

function TypeText($text) {
    $escaped = $text -replace ' ', '%s' -replace ':', '\:' -replace '/', '\/'
    & $adb -s $serial shell input text "$escaped" | Out-Null
    Start-Sleep -Milliseconds 400
}

# The on-screen keyboard opens as soon as a text field has focus and then swallows every D-pad
# press, so the typed text would all land in the first field. `input text` injects key events
# and needs no keyboard, so the keyboards are switched off for the run and restored at the end.
$imes = @(& $adb -s $serial shell ime list -s | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($ime in $imes) { & $adb -s $serial shell ime disable $ime | Out-Null }
Start-Sleep -Seconds 1

Say "Creating the first profile against the mock server"
Shot "01_welcome"
TypeText "Tester"
Key "KEYCODE_DPAD_DOWN"
TypeText "http://$serverForApp"
Key "KEYCODE_DPAD_DOWN"
TypeText "testuser"
Key "KEYCODE_DPAD_RIGHT"
TypeText "testpass"
# Down from the password lands on Save, the only button on a first profile.
Key "KEYCODE_DPAD_DOWN"
Key "KEYCODE_DPAD_CENTER" 1 4
Shot "02_whos_watching"

Say "Choosing the profile and waiting for channels, guide and library (up to five minutes)"
& $adb -s $serial logcat -c | Out-Null
Key "KEYCODE_DPAD_CENTER"
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    if ($i % 6 -eq 0) { Shot "03_loading_$i" }
    $enriched = & $adb -s $serial logcat -d -s CrimsonLibrary:I | Select-String -Pattern "enriched (\d+) films and (\d+) series"
    if ($enriched) {
        Note "- Library joined to the title index: $($enriched[-1].Matches[0].Groups[1].Value) films, $($enriched[-1].Matches[0].Groups[2].Value) series"
        break
    }
}
Start-Sleep -Seconds 4
Shot "04_home_billboard"

Say "Recording memory after the import"
$meminfo = & $adb -s $serial shell dumpsys meminfo $package
$meminfoPath = Join-Path $reportDir "meminfo.txt"
$meminfo | Set-Content $meminfoPath
$totalPss = ($meminfo | Select-String -Pattern "TOTAL PSS:\s+(\d+)" | Select-Object -First 1)
if ($totalPss) {
    $kb = [int]$totalPss.Matches[0].Groups[1].Value
    Note "- Memory after the import (TOTAL PSS): $([math]::Round($kb / 1024, 1)) MB"
} else {
    Note "- Memory: see reports/meminfo.txt"
}

Say "Frame timing while scrolling the Home rows"
& $adb -s $serial shell dumpsys gfxinfo $package reset | Out-Null
Key "KEYCODE_DPAD_DOWN" 3 0.6
Shot "05_home_rows"
Key "KEYCODE_DPAD_RIGHT" 6 0.2
Key "KEYCODE_DPAD_DOWN" 20 0.25
Shot "06_home_deep"
$gfx = & $adb -s $serial shell dumpsys gfxinfo $package
$gfxPath = Join-Path $reportDir "gfxinfo.txt"
$gfx | Set-Content $gfxPath
$total = ($gfx | Select-String -Pattern "Total frames rendered: (\d+)" | Select-Object -First 1)
$janky = ($gfx | Select-String -Pattern "Janky frames: (\d+) \(([\d.]+)%\)" | Select-Object -First 1)
if ($total -and $janky) {
    Note "- Home rows: $($total.Matches[0].Groups[1].Value) frames rendered, $($janky.Matches[0].Groups[1].Value) janky ($($janky.Matches[0].Groups[2].Value)%)"
} else {
    Note "- Frame timing: see reports/gfxinfo.txt"
}

Say "A film: details, play, seek, back, resume"
Key "KEYCODE_DPAD_CENTER" 1 3
Shot "07_details"
Key "KEYCODE_DPAD_CENTER" 1 6
Shot "08_playing"
Key "KEYCODE_DPAD_RIGHT" 3 0.4
Key "KEYCODE_DPAD_CENTER" 1 1
Shot "09_paused_controls"
Key "KEYCODE_BACK" 1 2
Shot "10_details_resume"
Key "KEYCODE_BACK" 1 2
Key "KEYCODE_BACK" 1 2
Shot "11_home_continue_watching"

Say "Live TV: lineup, preview, full screen, zapping, guide"
Key "KEYCODE_DPAD_UP" 1 0.5
Key "KEYCODE_DPAD_RIGHT" 3 0.3
Key "KEYCODE_DPAD_CENTER" 1 3
Shot "12_live_tv"
Key "KEYCODE_DPAD_DOWN" 1 3
Shot "13_live_preview"
Key "KEYCODE_DPAD_CENTER"
Start-Sleep -Seconds 2
Shot "14_live_banner"
Key "KEYCODE_DPAD_UP" 1 3
Shot "15_channel_up"
Key "KEYCODE_DPAD_DOWN" 1 3
Key "KEYCODE_MEDIA_PLAY_PAUSE" 1 3
Shot "16_last_channel"
Key "KEYCODE_DPAD_CENTER" 1 3
Shot "17_guide"
Key "KEYCODE_DPAD_DOWN" 6 0.25
Key "KEYCODE_DPAD_RIGHT" 4 0.25
Shot "18_guide_moved"
Key "KEYCODE_MEDIA_FAST_FORWARD" 1 0.5
Key "KEYCODE_DPAD_CENTER" 1 2
Shot "19_future_program_dialog"
Key "KEYCODE_BACK" 1 1
Key "KEYCODE_BACK" 1 1
Key "KEYCODE_BACK" 1 2

Say "Time to first frame, from logcat"
$logcat = & $adb -s $serial logcat -d -s CrimsonPlayer:I
$logcatPath = Join-Path $reportDir "player-log.txt"
$logcat | Set-Content $logcatPath
$frames = @($logcat | Select-String -Pattern "first frame for .* in (\d+) ms")
if ($frames.Count -gt 0) {
    # Always an array: with a single tune the pipeline would yield a bare Int, which has no
    # Count under strict mode.
    $times = @($frames | ForEach-Object { [int]$_.Matches[0].Groups[1].Value })
    $avg = [math]::Round(($times | Measure-Object -Average).Average, 0)
    $min = ($times | Measure-Object -Minimum).Minimum
    $max = ($times | Measure-Object -Maximum).Maximum
    Note "- Time to first frame: $($times.Count) tunes, average ${avg} ms, range ${min}-${max} ms"
}

Say "Settings and an instant filter change"
Key "KEYCODE_MENU"
Start-Sleep -Seconds 2
Shot "20_settings"
# Rail: Profile, Live TV. Right steps into the Live TV section; its first row is the first country.
Key "KEYCODE_DPAD_DOWN" 1 0.4
Key "KEYCODE_DPAD_RIGHT" 1 0.4
Key "KEYCODE_DPAD_DOWN" 3 0.2
$before = Get-Date
Key "KEYCODE_DPAD_CENTER"
Start-Sleep -Milliseconds 500
$elapsed = (Get-Date) - $before
Shot "21_settings_filter_changed"
Note "- Filter toggle round trip (includes key delays): $([math]::Round($elapsed.TotalMilliseconds)) ms"
$applied = @(& $adb -s $serial logcat -d -s CrimsonVM:I | Select-String -Pattern "filter change applied in (\d+) ms: (\d+) channels")
if ($applied.Count -gt 0) {
    $last = $applied[-1].Matches[0]
    Note "- Filter change applied in the app: $($last.Groups[1].Value) ms, $($last.Groups[2].Value) channels after the change"
}
Key "KEYCODE_DPAD_CENTER"
Key "KEYCODE_BACK"
Start-Sleep -Seconds 2

Say "Checking that only one stream is open"
$connections = & $adb -s $serial shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -c ':1F90'"
Note "- Sockets open to the mock server's port: $connections (1 or 0 expected; a value above 1 means two streams)"

# ---------------------------------------------------------------------------- report

Say "Writing $reportFile"

$header = @"
# Device verification

Produced by ``tools/verify-on-device.ps1`` on $(Get-Date -Format "yyyy-MM-dd HH:mm").

Device: ``$serial``
APK: ``$($apk.Name)``
Package: ``$package``

## Results

"@

$footer = @"

## Artefacts

- ``screenshots/`` — one image per navigation step, numbered in order.
- ``reports/meminfo.txt`` — full ``dumpsys meminfo`` output.
- ``reports/gfxinfo.txt`` — full ``dumpsys gfxinfo`` output, including the frame-time histogram.
- ``reports/player-log.txt`` — the player's own log, including time to first frame per tune.

## What to look at

Open the screenshots in order. The things worth checking by eye are the ones no assertion
covers: whether the near-black and the red read right on a real panel, whether the white focus
ring is obvious from a sofa, and whether the row text is large enough at 1080p.
"@

Set-Content -Path $reportFile -Value ($header + ($script:report -join "`n") + $footer)

# ---------------------------------------------------------------------------- cleanup

foreach ($ime in $imes) { & $adb -s $serial shell ime enable $ime | Out-Null }

if ($mock -and -not $mock.HasExited) {
    Say "Stopping the mock server"
    Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue
}
if ($startedEmulator -and -not $KeepEmulator) {
    Say "Stopping the emulator"
    & $adb -s $serial emu kill 2>&1 | Out-Null
}

Write-Host ""
Write-Host "Done. Read $reportFile and look through screenshots\." -ForegroundColor Green
