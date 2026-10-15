<#
  Cable Club for Android: builds the APK (Windows, PowerShell 5.1+).

  Requirements: what build.ps1 needs (JDK 21+, git), the Android SDK with its NDK (Android Studio installs them;
  ANDROID_HOME or %LOCALAPPDATA%\Android\Sdk), and for a release the signing key:
  %USERPROFILE%\.cableclub\android-signing.properties (storeFile, storePassword, keyAlias, keyPassword), or the file
  named by CABLECLUB_SIGNING. Without it the APK is signed with the debug key (tests only: no update over it).

    .\build-android.ps1           APK into dist\CableClub-<version>-android.apk (+ .sha256)
    .\build-android.ps1 -Debug    debug APK (installable next to nothing else: tests)

  The app runs the same sources as the PC version (src, web): see android\app\build.gradle.kts.
#>
param(
    [switch]$Debug,
    [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Dist = if ($Out) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out) } else { Join-Path $Root 'dist' }
$Version = ([regex]'VERSION = "([^"]+)"').Match((Get-Content -Raw (Join-Path $Root 'src\slt\Version.java'))).Groups[1].Value
if (-not $Version) { throw 'Version introuvable dans src\slt\Version.java' }
function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# the JDK (as build.ps1)
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $env:JAVA_HOME = $Matches[1].Trim() }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'JDK 21+ introuvable : definissez JAVA_HOME.' }

# the Android SDK
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $Sdk 'platform-tools'))) { throw "SDK Android introuvable ($Sdk) : installez Android Studio ou definissez ANDROID_HOME." }
$env:ANDROID_HOME = $Sdk
"sdk.dir=$($Sdk.Replace('\', '\\'))" | Set-Content -Encoding ascii (Join-Path $Root 'android\local.properties')

# the randomizer engine, built by build.ps1
if (-not (Test-Path (Join-Path $Root 'build\lib\upr-zx.jar'))) {
    Step 'Moteur de randomisation (build.ps1 -NoPackage)'
    & (Join-Path $Root 'build.ps1') -NoPackage
}

$signing = if ($env:CABLECLUB_SIGNING) { $env:CABLECLUB_SIGNING } else { Join-Path $env:USERPROFILE '.cableclub\android-signing.properties' }
if (-not $Debug -and -not (Test-Path $signing)) {
    Write-Host "    attention : pas de cle de signature ($signing), APK signe avec la cle de debug (pas de mise a jour possible par-dessus)" -ForegroundColor Yellow
}

$task = if ($Debug) { 'assembleDebug' } else { 'assembleRelease' }
Step "Construction de l'APK ($task)"
Push-Location (Join-Path $Root 'android')
try {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat $task --console=plain 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'sha256|error|FAIL|What went wrong|BUILD' }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    if ($code -ne 0) { throw "gradlew $task a echoue (code $code)" }
} finally {
    Pop-Location
}

$kind = if ($Debug) { 'debug' } else { 'release' }
$apk = Get-ChildItem (Join-Path $Root "android\app\build\outputs\apk\$kind") -Filter '*.apk' | Select-Object -First 1
if (-not $apk) { throw 'APK introuvable apres la construction' }
New-Item -ItemType Directory -Force $Dist | Out-Null
$name = if ($Debug) { "CableClub-$Version-android-debug.apk" } else { "CableClub-$Version-android.apk" }
$target = Join-Path $Dist $name
Copy-Item $apk.FullName $target -Force
$hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLower()
[IO.File]::WriteAllText("$target.sha256", "$hash  $name`n")

# who signed it (the same key as the previous versions, or Android refuses the update)
$apksigner = Get-ChildItem (Join-Path $Sdk 'build-tools') -Recurse -Filter 'apksigner.bat' | Sort-Object FullName | Select-Object -Last 1
if ($apksigner) {
    $cert = & $apksigner.FullName verify --print-certs $target 2>&1 | Select-String 'certificate SHA-256 digest' | Select-Object -First 1
    Write-Host "    signature : $cert"
}
Write-Host ("==> OK : {0} ({1:N1} Mo)" -f $target, ((Get-Item $target).Length / 1MB)) -ForegroundColor Green
