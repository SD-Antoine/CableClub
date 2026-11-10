<#
  Non-regression tests of the security fixes (audit of point 9). Compiles the Java tests of tests\security\java with
  the classes of src they need, and runs them. Requirements: JDK 21+ (javac, java).

    .\tests\security\security-tests.ps1
#>
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Classes = Join-Path $Root 'build\sec-test-classes'

# Locate the JDK as build.ps1 does (JAVA_HOME, or the home of the "java" found on PATH)
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $JavaHome = $Matches[1].Trim() }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK 21+ introuvable : installez-le et/ou definissez JAVA_HOME.'
}

# the client sources reach Rando, which needs the Universal Pokemon Randomizer jar (build.ps1 -NoPackage builds it)
$Upr = Join-Path $Root 'build\lib\upr-zx.jar'
if (-not (Test-Path $Upr)) { & (Join-Path $Root 'build.ps1') -NoPackage | Out-Null }

if (Test-Path $Classes) { Remove-Item -Recurse -Force $Classes }
$tests = Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot 'java') | ForEach-Object { $_.FullName }
$javacArgs = @('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-Xlint:-preview', '-nowarn', '-d', $Classes,
    '-cp', $Upr, '-sourcepath', (Join-Path $Root 'src')) + $tests
& (Join-Path $JavaHome 'bin\javac.exe') @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'Compilation des tests de sécurité impossible' }

$failed = 0
# the mode files (official modes, catalog of rules) are read from web\ as in development
foreach ($cls in @('slt.client.LocalApiHostTest', 'slt.JsonFuzz', 'slt.client.DsSavesFuzz', 'slt.server.ModeSpecFuzz', 'slt.server.RoomsLimitTest', 'slt.server.ModeGalleryTest', 'slt.server.AdapterManifestTest', 'slt.server.HttpLimitsTest',
        'slt.server.AccountsTest', 'slt.server.MetricsTest')) {
    & (Join-Path $JavaHome 'bin\java.exe') '--enable-preview' '-Dstdout.encoding=UTF-8' "-Dslt.web=$(Join-Path $Root 'web')" '-cp' "$Classes;$Upr" $cls
    if ($LASTEXITCODE -ne 0) { $failed++ }
}
exit $failed
