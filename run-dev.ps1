# Starts SLT in development mode: the interface is served straight from .\web
# (edit and reload), data goes to .\data (or -Data). Run .\build.ps1 -NoPackage first.
#   .\run-dev.ps1                       the application
#   .\run-dev.ps1 -Data data2 -Port 47341   a second player on the same PC (tests)
#   .\run-dev.ps1 -Server               the SLT server alone (data in .\serveur)
param([switch]$NoWindow, [switch]$Server, [int]$Port = 47331, [string]$Data = 'data')

$Root = $PSScriptRoot
$cp = "$Root\build\app-classes;$Root\build\lib\upr-zx.jar"

# JAVA_HOME, then the java of PATH if it really runs (Oracle's javapath launcher crashes without a
# word when the registry names a JDK that was moved), then JDK 21 in Program Files of each local disk.
function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return "$env:JAVA_HOME\bin\java.exe" }
    if (Get-Command java -ErrorAction SilentlyContinue) {
        & cmd /c "java -version >nul 2>&1"
        if ($LASTEXITCODE -eq 0) { return 'java' }
    }
    foreach ($drive in [IO.DriveInfo]::GetDrives() | Where-Object { $_.DriveType -eq 'Fixed' -and $_.IsReady }) {
        $jdk = Get-ChildItem (Join-Path $drive.Name 'Program Files\Java\jdk-21*') -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Sort-Object Name | Select-Object -Last 1
        if ($jdk) { return "$($jdk.FullName)\bin\java.exe" }
    }
    throw 'JDK 21 introuvable : installez-le et/ou definissez JAVA_HOME.'
}
$java = Find-Java
if ($java -ne 'java') {
    # build.ps1 reads it too
    $env:JAVA_HOME = Split-Path (Split-Path $java)
    Write-Host "JDK : $env:JAVA_HOME"
}

if (-not (Test-Path "$Root\build\app-classes")) { & "$Root\build.ps1" -NoPackage }
if ($Server) {
    $javaArgs = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', '-cp', $cp, 'slt.Main', '--server', '--data=serveur')
} else {
    $javaArgs = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', "-Dslt.cores=$Root\build\cores", '-cp', $cp,
        'slt.Main', '--dev', "--port=$Port", "--data=$Data")
    if ($NoWindow) { $javaArgs += '--no-window' }
}
Push-Location $Root
try {
    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) { Write-Warning "Java s'est arrete avec le code $LASTEXITCODE." }
} finally { Pop-Location }
