<#
  Non-regression tests of the referee (src\slt\server\Room.java): replays the scenarios of tests\golden\*.json
  and compares what the room produces (journal, notices, directives, final state) with their references (.txt).

  Requirements: JDK 21+ (javac, java). The harness is compiled with the classes of src it needs (build\test-classes).

    .\tests\golden.ps1           compare with the references
    .\tests\golden.ps1 -Update   write the references again: only for a change of behaviour that was decided
#>
param([switch]$Update)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Classes = Join-Path $Root 'build\test-classes'

# Locate the JDK as build.ps1 does (JAVA_HOME, or the home of the "java" found on PATH)
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $JavaHome = $Matches[1].Trim() }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK 21+ introuvable : installez-le et/ou definissez JAVA_HOME.'
}

if (Test-Path $Classes) { Remove-Item -Recurse -Force $Classes }
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot 'java') | ForEach-Object { $_.FullName }
$javacArgs = @('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-Xlint:-preview', '-nowarn', '-d', $Classes,
    '-sourcepath', (Join-Path $Root 'src')) + $sources
& (Join-Path $JavaHome 'bin\javac.exe') @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'Compilation du harnais impossible' }

$java = Join-Path $JavaHome 'bin\java.exe'
# the event-schema check first (point 1b), then the scenarios
& $java '--enable-preview' '-Dstdout.encoding=UTF-8' '-cp' $Classes 'slt.server.EventSchemaTest'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$javaArgs = @('--enable-preview', '-Dstdout.encoding=UTF-8', '-cp', $Classes, 'slt.server.RoomReplay',
    (Join-Path $PSScriptRoot 'golden'))
if ($Update) { $javaArgs += '--update' }
& $java @javaArgs
exit $LASTEXITCODE
