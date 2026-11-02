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
$javacArgs = @('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-Xlint:-preview', '-nowarn', '-d', $Classes,
    '-sourcepath', (Join-Path $Root 'src'), (Join-Path $PSScriptRoot 'java\slt\server\RoomReplay.java'))
& (Join-Path $JavaHome 'bin\javac.exe') @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'Compilation du harnais impossible' }

$javaArgs = @('--enable-preview', '-Dstdout.encoding=UTF-8', '-cp', $Classes, 'slt.server.RoomReplay',
    (Join-Path $PSScriptRoot 'golden'))
if ($Update) { $javaArgs += '--update' }
& (Join-Path $JavaHome 'bin\java.exe') @javaArgs
exit $LASTEXITCODE
