# Starts SLT in development mode: the interface is served straight from .\web
# (edit and reload), data goes to .\data (or -Data). Run .\build.ps1 -NoPackage first.
#   .\run-dev.ps1                       the application
#   .\run-dev.ps1 -Data data2 -Port 47341   a second player on the same PC (tests)
#   .\run-dev.ps1 -Server               the SLT server alone (data in .\serveur)
param([switch]$NoWindow, [switch]$Server, [int]$Port = 47331, [string]$Data = 'data')

$Root = $PSScriptRoot
$cp = "$Root\build\app-classes;$Root\build\lib\upr-zx.jar"
if (-not (Test-Path "$Root\build\app-classes")) { & "$Root\build.ps1" -NoPackage }
if ($Server) {
    $javaArgs = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', '-cp', $cp, 'slt.Main', '--server', '--data=serveur')
} else {
    $javaArgs = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', "-Dslt.cores=$Root\build\cores", '-cp', $cp,
        'slt.Main', '--dev', "--port=$Port", "--data=$Data")
    if ($NoWindow) { $javaArgs += '--no-window' }
}
Push-Location $Root
try { & java @javaArgs } finally { Pop-Location }
