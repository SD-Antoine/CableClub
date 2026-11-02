<#
  Isolated TEST instance of the Cable Club server for the security audit (point 9a). It is dedicated to tests:
  127.0.0.1 only, plain HTTP, a throwaway data directory, entirely made-up data (no real account, save or ROM), and
  never exposed to the Internet. Any attack of point 9c targets THIS instance and nothing else.

    .\tests\security\start-test-server.ps1            start it and seed the fake data
    .\tests\security\start-test-server.ps1 -Stop      stop it
    .\tests\security\start-test-server.ps1 -Data D:\tmp\cc-test -Port 47399

  Safety: the bind address is forced to 127.0.0.1, the data directory must be outside the repository and outside the
  real data (dist\CableClub\data, data\, serveur\), and nothing opens a port on the box (no UPnP: that is the client,
  never the server). The test accounts and their generated passwords are written to <data>\comptes-de-test.txt, which
  stays out of git (it is in a throwaway directory).
#>
param(
    [int]$Port = 47399,
    [string]$Data = '',
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Data) { $Data = Join-Path $env:TEMP 'cableclub-test-server' }
$Data = [IO.Path]::GetFullPath($Data)
$pidFile = Join-Path $Data 'server.pid'

# ---- safety: refuse anything that is not the throwaway test instance
$forbidden = @(
    (Join-Path $Root 'dist\CableClub\data'), (Join-Path $Root 'data'), (Join-Path $Root 'serveur')
) | ForEach-Object { [IO.Path]::GetFullPath($_) }
if ($Data.StartsWith([IO.Path]::GetFullPath($Root), [StringComparison]::OrdinalIgnoreCase) -and
    -not $Data.StartsWith([IO.Path]::GetFullPath((Join-Path $Root 'build')), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refus : le dossier de données de test ne doit pas être dans le dépôt ($Data)."
}
foreach ($f in $forbidden) {
    if ($Data.StartsWith($f, [StringComparison]::OrdinalIgnoreCase)) { throw "Refus : $Data vise des données réelles." }
}

if ($Stop) {
    if (Test-Path $pidFile) {
        $serverPid = Get-Content $pidFile
        try { Stop-Process -Id $serverPid -ErrorAction Stop; Write-Host "Instance de test arrêtée (PID $serverPid)." }
        catch { Write-Host "Aucun processus $serverPid en cours." }
        Remove-Item $pidFile -ErrorAction SilentlyContinue
    } else { Write-Host 'Aucune instance de test en cours.' }
    return
}

# ---- JDK (as build.ps1)
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $JavaHome = $Matches[1].Trim() }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'JDK 21+ introuvable : installez-le et/ou definissez JAVA_HOME.'
}
$java = Join-Path $JavaHome 'bin\java.exe'

# ---- build if needed
$jar = Join-Path $Root 'build\lib\slt.jar'
$upr = Join-Path $Root 'build\lib\upr-zx.jar'
if (-not (Test-Path $jar)) { & (Join-Path $Root 'build.ps1') -NoPackage }

# ---- a fresh data directory every run
if (Test-Path $Data) { Remove-Item -Recurse -Force $Data }
New-Item -ItemType Directory -Force $Data | Out-Null

$cp = "$jar;$upr"
$args = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', '-cp', $cp, 'slt.Main', '--server',
    '--bind=127.0.0.1', "--port=$Port", "--data=$Data", '--http', '--name=CableClub-test')
$proc = Start-Process -FilePath $java -ArgumentList $args -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Data 'server.out.log') -RedirectStandardError (Join-Path $Data 'server.err.log')
Set-Content $pidFile $proc.Id
$base = "http://127.0.0.1:$Port"

# ---- wait for it to answer
$up = $false
for ($i = 0; $i -lt 50; $i++) {
    try { Invoke-RestMethod "$base/api/server" -TimeoutSec 2 | Out-Null; $up = $true; break } catch { Start-Sleep -Milliseconds 200 }
}
if (-not $up) { throw "L'instance de test n'a pas démarré (voir $Data\server.err.log)." }

& (Join-Path $PSScriptRoot 'seed-test-data.ps1') -Base $base -Data $Data

Write-Host ''
Write-Host "Instance de test Cable Club : $base  (PID $($proc.Id))"
Write-Host "Données jetables : $Data"
Write-Host "Comptes de test : $Data\comptes-de-test.txt"
Write-Host "Arrêt : .\tests\security\start-test-server.ps1 -Stop"
