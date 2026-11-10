<#
  Load bench of the server (audit of 2026-11-10). Starts a throwaway server on 127.0.0.1 only (HTTPS with its own
  certificate, data in a temporary folder, deleted at the end), limited to a few processor cores, fills it with
  made-up players paired in rooms of two, and has each of them send what the application sends in game. Never run
  against another machine. Not part of the CI (it takes a few minutes and most of the processor).
  Fails (exit code 1) on any error or unopened event stream. Measured on 2 cores of a Ryzen 7800X3D (2026-11-10):
  500 players 0 error, median 4 ms, 0.71 core; with -MaxIdle 200, 196 errors and 0.8 s; 1000 players saturate
  (median 230 ms, 29 % of the sendings skipped, 1.35 core); -NoDelay brought nothing.

    .\tests\load\load.ps1                       # 500 players, 2 cores
    .\tests\load\load.ps1 -Players 1000
    .\tests\load\load.ps1 -MaxIdle 200          # the JDK's former default, to compare
    .\tests\load\load.ps1 -NoDelay              # TCP_NODELAY on the server's connections
    .\tests\load\load.ps1 -Keep                 # keeps the server's folder (its log) to look at afterwards

  Requirements: JDK 21+ (javac, java).
#>
param(
    [int]$Players = 500,
    [int]$Seconds = 20,
    [int]$Warmup = 10,
    [int]$Cores = 2,
    [int]$Port = 47398,
    [string]$Heap = '1g',
    [int]$MaxIdle = 0,
    [switch]$NoDelay,
    [switch]$Keep
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Locate the JDK as build.ps1 does (JAVA_HOME, or the home of the "java" found on PATH)
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $JavaHome = $Matches[1].Trim() }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK 21+ introuvable : installez-le et/ou definissez JAVA_HOME.'
}
$java = Join-Path $JavaHome 'bin\java.exe'
$logical = [Environment]::ProcessorCount
if ($Cores -lt 1 -or $Cores -ge $logical) { throw "-Cores doit laisser au moins un coeur au client (ce PC en a $logical)." }

# the bench and the server classes it reaches (Accounts, Rooms, SltServer), compiled from src
$Classes = Join-Path $Root 'build\load-classes'
if (Test-Path $Classes) { Remove-Item -Recurse -Force $Classes }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 --release 21 --enable-preview -Xlint:-preview -nowarn `
    -d $Classes -sourcepath (Join-Path $Root 'src') `
    (Join-Path $PSScriptRoot 'java\slt\server\LoadBench.java') (Join-Path $Root 'src\slt\server\SltServer.java')
if ($LASTEXITCODE -ne 0) { throw 'Compilation du banc impossible' }
$web = "-Dslt.web=$(Join-Path $Root 'web')"

$Data = Join-Path $env:TEMP ('cableclub-charge-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$server = $null
$code = 1
try {
    Write-Host "Joueurs factices : $Players (donnees dans $Data)"
    & $java '--enable-preview' '-Dstdout.encoding=UTF-8' $web '-cp' $Classes 'slt.server.LoadBench' 'seed' $Data $Players
    if ($LASTEXITCODE -ne 0) { throw 'Preparation impossible' }

    $serverArgs = @('--enable-preview', "-XX:ActiveProcessorCount=$Cores", "-Xmx$Heap", '-Dstdout.encoding=UTF-8', $web)
    if ($MaxIdle -gt 0) { $serverArgs += "-Dsun.net.httpserver.maxIdleConnections=$MaxIdle" }
    if ($NoDelay) { $serverArgs += '-Dsun.net.httpserver.nodelay=true' }
    $serverArgs += @('-cp', $Classes, 'slt.server.SltServer', '--bind=127.0.0.1', "--port=$Port", "--data=$Data",
        '--name=CableClub-charge')
    $server = Start-Process -FilePath $java -ArgumentList $serverArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Data 'serveur-sortie.txt') -RedirectStandardError (Join-Path $Data 'serveur-erreurs.txt')
    $server.ProcessorAffinity = [IntPtr]([long][math]::Pow(2, $Cores) - 1)
    $options = @()
    if ($MaxIdle -gt 0) { $options += "maxIdleConnections=$MaxIdle" }
    if ($NoDelay) { $options += 'nodelay' }
    Write-Host ("Serveur sur https://127.0.0.1:{0} (PID {1}, {2} coeur(s){3})" -f $Port, $server.Id, $Cores,
        $(if ($options) { ', ' + ($options -join ', ') } else { '' }))

    # the client on the other cores
    $clientMask = ([long][math]::Pow(2, $logical) - 1) -bxor ([long][math]::Pow(2, $Cores) - 1)
    $client = Start-Process -FilePath $java -NoNewWindow -PassThru -ArgumentList @('--enable-preview',
        '-Dstdout.encoding=UTF-8', '-Djdk.internal.httpclient.disableHostnameVerification=true', '-Xmx4g', '-cp', $Classes,
        'slt.server.LoadBench', 'run', $Port, $Data, $server.Id, $Warmup, $Seconds)
    $null = $client.Handle # keeps the exit code readable once the process is gone
    $client.ProcessorAffinity = [IntPtr]$clientMask
    $client.WaitForExit()
    $code = $client.ExitCode
    if ($server.HasExited) { Write-Host 'Le serveur a quitte pendant le test.' -ForegroundColor Red }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force; $server.WaitForExit() }
    if ($Keep) { Write-Host "Donnees gardees : $Data" } elseif (Test-Path $Data) { Remove-Item -Recurse -Force $Data -ErrorAction SilentlyContinue }
}
exit $code
