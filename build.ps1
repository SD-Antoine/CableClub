<#
  Cable Club build script (Windows, PowerShell 5.1+).

  Requirements: JDK 21+ (javac, jar, jdeps, jpackage) and git.

    .\build.ps1             compile + produce dist\CableClub\CableClub.exe and a .zip
    .\build.ps1 -NoPackage  compile only (build\lib\*.jar), e.g. for .\run-dev.ps1
    .\build.ps1 -Out dossier   package into another folder than dist (tests)

  The version comes from src\slt\Version.java. A pushed tag vX.Y.Z builds and
  publishes the GitHub release (.github\workflows\release.yml).
#>
param(
    [switch]$NoPackage,
    [switch]$Clean,
    [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Build = Join-Path $Root 'build'
$Dist = if ($Out) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out) } else { Join-Path $Root 'dist' }
$Version = ([regex]'VERSION = "([^"]+)"').Match((Get-Content -Raw (Join-Path $Root 'src\slt\Version.java'))).Groups[1].Value
if (-not $Version) { throw 'Version introuvable dans src\slt\Version.java' }

# Universal Pokemon Randomizer ZX 4.6.1 (GPLv3) - engine compiled into SLT
$UprRepo = 'https://github.com/Ajarmar/universal-pokemon-randomizer-zx.git'
$UprCommit = '7f00eb866ed35c8fe3963f078b6a2e0979dc2b8c'
$UprSrc = Join-Path $Build 'upr-src'

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Exec([string]$exe, [string[]]$arguments) {
    # native tools write progress on stderr: never let PowerShell turn that into errors
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $exe @arguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    if ($code -ne 0) { throw "$exe a echoue (code $code)" }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git introuvable.' }

# Locate the JDK (JAVA_HOME, or the home of the "java" found on PATH)
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jar.exe'))) {
    $props = (cmd /c "java -XshowSettings:properties -version 2>&1") | Out-String
    if ($props -match 'java\.home = ([^\r\n]+)') { $JavaHome = $Matches[1].Trim() }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jar.exe'))) {
    throw 'JDK 21+ introuvable : installez-le et/ou definissez JAVA_HOME.'
}
function Tool($name) { Join-Path $JavaHome "bin\$name.exe" }
$javac = Tool 'javac'; $jar = Tool 'jar'; $java = Tool 'java'; $jdeps = Tool 'jdeps'; $jpackage = Tool 'jpackage'
Write-Host "    JDK : $JavaHome"

if ($Clean -and (Test-Path $Build)) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force $Build | Out-Null
$Lib = Join-Path $Build 'lib'
New-Item -ItemType Directory -Force $Lib | Out-Null

# ---------------------------------------------------------------- UPR engine
$UprJar = Join-Path $Lib 'upr-zx.jar'
if (-not (Test-Path $UprJar)) {
    if (-not (Test-Path (Join-Path $UprSrc 'src'))) {
        Step "Telechargement des sources de Universal Pokemon Randomizer ZX ($($UprCommit.Substring(0,7)))"
        Exec 'git' @('-c', 'core.longpaths=true', '-c', 'core.autocrlf=false', 'clone', '--depth', '1', $UprRepo, $UprSrc)
        $head = (& git -C $UprSrc rev-parse HEAD).Trim()
        if ($head -ne $UprCommit) {
            Exec 'git' @('-C', $UprSrc, '-c', 'core.longpaths=true', 'fetch', '--depth', '1', 'origin', $UprCommit)
            Exec 'git' @('-C', $UprSrc, '-c', 'core.longpaths=true', '-c', 'core.autocrlf=false', 'checkout', '--quiet', $UprCommit)
        }
    }
    Step 'Compilation du moteur de randomisation'
    $uprClasses = Join-Path $Build 'upr-classes'
    if (Test-Path $uprClasses) { Remove-Item -Recurse -Force $uprClasses }
    $srcRoot = Join-Path $UprSrc 'src'
    $list = Join-Path $Build 'upr-sources.txt'
    Get-ChildItem -Recurse -Filter *.java $srcRoot | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
        Set-Content -Encoding ascii $list
    Exec $javac @('-nowarn', '-encoding', 'UTF-8', '--release', '21', '-d', $uprClasses, "@$list")
    # resources (ROM offsets, patches, name lists, GUI strings)
    Get-ChildItem -Recurse -File $srcRoot | Where-Object { $_.Extension -ne '.java' -and $_.Extension -ne '.form' } | ForEach-Object {
        $rel = $_.FullName.Substring($srcRoot.Length + 1)
        $dest = Join-Path $uprClasses $rel
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        Copy-Item $_.FullName $dest
    }
    Exec $jar @('--create', '--file', $UprJar, '-C', $uprClasses, '.')
    Copy-Item (Join-Path $UprSrc 'LICENSE.txt') (Join-Path $Build 'UPR-LICENSE.txt') -Force
}

# ---------------------------------------------------------------- emulator cores
# Official libretro builds: mGBA (MPL-2.0) for GB/GBC/GBA, DeSmuME (GPL-2.0) for the DS
$Cores = Join-Path $Build 'cores'
New-Item -ItemType Directory -Force $Cores | Out-Null
foreach ($core in 'mgba', 'desmume') {
    $dll = Join-Path $Cores "${core}_libretro.dll"
    if (-not (Test-Path $dll)) {
        Step "Telechargement du coeur d'emulation $core (libretro)"
        $zipCore = Join-Path $Cores "$core.zip"
        Invoke-WebRequest -UseBasicParsing -Uri "https://buildbot.libretro.com/nightly/windows/x86_64/latest/${core}_libretro.dll.zip" -OutFile $zipCore
        Expand-Archive -Force $zipCore $Cores
        Remove-Item $zipCore
    }
    Write-Host ("    {0} sha256 {1}" -f (Split-Path $dll -Leaf), (Get-FileHash -Algorithm SHA256 $dll).Hash.ToLower())
}

# ---------------------------------------------------------------- SLT
Step 'Compilation de Cable Club'
$appClasses = Join-Path $Build 'app-classes'
if (Test-Path $appClasses) { Remove-Item -Recurse -Force $appClasses }
$appSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $Root 'src') | ForEach-Object { $_.FullName }
# the game player uses the foreign function API (preview in Java 21)
Exec $javac (@('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-Xlint:all,-serial,-preview', '-cp', $UprJar, '-d', $appClasses) + $appSources)
Copy-Item -Recurse (Join-Path $Root 'web') (Join-Path $appClasses 'web')
# the patch notes, shown in Réglages > Autres > Mise à jour
Copy-Item (Join-Path $Root 'CHANGELOG.md') (Join-Path $appClasses 'changelog.md')

# ---------------------------------------------------------------- checks
Step "Verification de l'interface"
# every module the interface imports must exist: one missing file leaves the window on "Chargement de SLT"
$missing = @()
Get-ChildItem -Recurse -Filter *.js (Join-Path $Root 'web\js') | ForEach-Object {
    $text = Get-Content -Raw -Encoding UTF8 $_.FullName
    $specs = @([regex]::Matches($text, '(?m)^\s*(?:import|export)\b[^''"]*?\bfrom\s+[''"](\.[^''"]+)[''"]')) +
             @([regex]::Matches($text, '(?m)^\s*import\s+[''"](\.[^''"]+)[''"]')) +
             @([regex]::Matches($text, '\bimport\(\s*[''"](\.[^''"]+)[''"]\s*\)'))
    foreach ($m in $specs) {
        $target = [IO.Path]::GetFullPath((Join-Path $_.DirectoryName $m.Groups[1].Value))
        if (-not (Test-Path -LiteralPath $target)) { $missing += "$($_.Name) -> $($m.Groups[1].Value)" }
    }
}
if ($missing.Count -gt 0) { throw ('Modules de l''interface introuvables : ' + ($missing -join ', ')) }
# a file of the program ignored by git would be missing from the GitHub release
if (Test-Path (Join-Path $Root '.git')) {
    $ignored = @(& git -C $Root ls-files --others --ignored --exclude-standard -- web src | Where-Object { $_ })
    if ($ignored.Count -gt 0) { throw ('Fichiers ignores par git (absents de la release GitHub) : ' + ($ignored -join ', ')) }
    $untracked = @(& git -C $Root ls-files --others --exclude-standard -- web src | Where-Object { $_ })
    if ($untracked.Count -gt 0) { Write-Host ('    attention, pas encore dans git : ' + ($untracked -join ', ')) -ForegroundColor Yellow }
}

$manifest = Join-Path $Build 'MANIFEST.MF'
"Main-Class: slt.Main`r`nClass-Path: upr-zx.jar`r`nImplementation-Version: $Version`r`n" | Set-Content -Encoding ascii $manifest
$AppJar = Join-Path $Lib 'slt.jar'
if (Test-Path $AppJar) { Remove-Item $AppJar }
Exec $jar @('--create', '--file', $AppJar, '--manifest', $manifest, '-C', $appClasses, '.')

$Ico = Join-Path $Build 'slt.ico'
Exec $java @('--enable-preview', '-cp', $appClasses, 'slt.Icon', $Ico, (Join-Path $Build 'slt.png'))

if ($NoPackage) { Step "OK : $AppJar"; return }

# ---------------------------------------------------------------- packaging
Step 'Calcul des modules Java necessaires'
$modules = ((& $jdeps --multi-release 21 --ignore-missing-deps --print-module-deps --class-path $UprJar $AppJar) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $modules) { throw 'jdeps a echoue' }
foreach ($m in 'jdk.charsets', 'jdk.crypto.ec', 'jdk.localedata', 'jdk.httpserver') {
    if (($modules -split ',') -notcontains $m) { $modules = "$modules,$m" }
}
Write-Host "    modules : $modules"

Step 'Creation de CableClub.exe (jpackage)'
$image = Join-Path $Dist 'CableClub'
# keep the user's data (rooms, saves, library, hosted server) across rebuilds, including the data of
# the folder of the program's former name (dist\SLT, until 0.6.1), which then goes away
$dataBackup = Join-Path $Build 'data-backup'
$oldImage = Join-Path $Dist 'SLT'
$dataFrom = if (Test-Path (Join-Path $image 'data')) { $image } elseif (Test-Path (Join-Path $oldImage 'data')) { $oldImage } else { $null }
if ($dataFrom) {
    if (Test-Path $dataBackup) { Remove-Item -Recurse -Force $dataBackup }
    # fails (and nothing is deleted) while that copy of Cable Club runs
    Move-Item (Join-Path $dataFrom 'data') $dataBackup
}
if (Test-Path $image) { Remove-Item -Recurse -Force $image }
if ((Test-Path $oldImage) -and -not (Test-Path (Join-Path $oldImage 'data'))) { Remove-Item -Recurse -Force $oldImage }
New-Item -ItemType Directory -Force $Dist | Out-Null
$pkgInput = Join-Path $Build 'package-input'
if (Test-Path $pkgInput) { Remove-Item -Recurse -Force $pkgInput }
New-Item -ItemType Directory -Force $pkgInput | Out-Null
Copy-Item $AppJar, $UprJar $pkgInput
Copy-Item -Recurse $Cores (Join-Path $pkgInput 'cores')
Exec $jpackage @(
    '--type', 'app-image',
    '--name', 'CableClub',
    '--app-version', $Version,
    '--vendor', 'Cable Club',
    '--description', 'Cable Club',
    '--input', $pkgInput,
    '--main-jar', 'slt.jar',
    '--main-class', 'slt.Main',
    '--icon', $Ico,
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-header-files --no-man-pages',
    '--java-options', '-Xmx2g',
    '--java-options', '--enable-preview',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dsun.java2d.dpiaware=true',
    '--dest', $Dist
)

Copy-Item (Join-Path $Root 'LICENSE') (Join-Path $image 'LICENSE.txt')
Copy-Item (Join-Path $Root 'LISEZMOI.txt') (Join-Path $image 'LISEZMOI.txt')
Copy-Item (Join-Path $Root 'THIRD-PARTY.txt') (Join-Path $image 'THIRD-PARTY.txt')

Step 'Archive zip'
$zip = Join-Path $Dist "CableClub-$Version-windows-x64.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $image -DestinationPath $zip
if (Test-Path $dataBackup) {
    Move-Item $dataBackup (Join-Path $image 'data')
    Write-Host "    donnees conservees : $image\data"
}
Step "Termine : $image\CableClub.exe"
Step "Archive : $zip"
