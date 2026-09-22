<#
.SYNOPSIS
    Creates the release signing keystore, once.

.DESCRIPTION
    Writes secrets/crimson-release.jks and secrets/keystore.properties, both gitignored.

    THE SAME KEY MUST BE USED FOR EVERY BUILD. Android identifies an app by its package name and
    its signing certificate together, so an APK signed with a different key will not install over
    an existing one — the user has to uninstall first, losing their settings and channel list.
    Back this file up somewhere safe. If it is lost, there is no way to produce an update that
    installs over what is already on the device.

    Run once:  powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1
#>

param(
    [string]$Alias = "crimson",
    [int]$ValidityDays = 10950   # 30 years
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$secrets = Join-Path $root "secrets"
$keystore = Join-Path $secrets "crimson-release.jks"
$propsFile = Join-Path $secrets "keystore.properties"

if (-not (Test-Path $secrets)) { New-Item -ItemType Directory -Path $secrets | Out-Null }

if (Test-Path $keystore) {
    Write-Host "A keystore already exists at $keystore." -ForegroundColor Yellow
    Write-Host "Not overwriting it: replacing the key would break updates for anyone who already"
    Write-Host "has the app installed. Delete it by hand if you are certain."
    exit 1
}

# keytool ships with the JDK.
$keytool = "keytool"
if (-not (Get-Command $keytool -ErrorAction SilentlyContinue)) {
    if ($env:JAVA_HOME) { $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe" }
}
if (-not (Get-Command $keytool -ErrorAction SilentlyContinue)) {
    Write-Error "keytool not found. Install a JDK 17 or 21, or set JAVA_HOME."
}

$securePassword = Read-Host -AsSecureString "Choose a keystore password (you will need it for every release build)"
$plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword))

if ($plain.Length -lt 6) { Write-Error "A keystore password must be at least 6 characters." }

& $keytool -genkeypair `
    -keystore $keystore `
    -alias $Alias `
    -keyalg RSA -keysize 4096 `
    -validity $ValidityDays `
    -storepass $plain -keypass $plain `
    -dname "CN=Crimson, OU=Crimson, O=Crimson, L=, ST=, C=US"

if ($LASTEXITCODE -ne 0) { Write-Error "keytool failed." }

# Gradle reads storeFile relative to the project root.
@"
storeFile=secrets/crimson-release.jks
storePassword=$plain
keyAlias=$Alias
keyPassword=$plain
"@ | Set-Content -Path $propsFile -Encoding ASCII

Write-Host ""
Write-Host "Created:" -ForegroundColor Green
Write-Host "  $keystore"
Write-Host "  $propsFile"
Write-Host ""
Write-Host "Both are gitignored and must never be committed." -ForegroundColor Yellow
Write-Host "Back up the .jks file. Losing it means no future build can update an installed app."
Write-Host ""
Write-Host "Now run:  .\gradlew.bat release"
