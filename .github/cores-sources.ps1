<#
  The source of the emulator cores shipped by a release (point 7: the GPL asks that the source matching each binary
  can be given). Reads the coeurs.json written by the builds (build\cores for Windows, build\cores-android for
  Android), downloads from GitHub the source archive of each exact commit or tag, and puts them in one zip next to
  the release files, with the two lists:
    .\.github\cores-sources.ps1 -Version 0.12.0      dist\CableClub-0.12.0-sources-coeurs.zip (+ .sha256)
  GH_TOKEN, when set, is used for the GitHub API (full commit of a short one).
#>
param([Parameter(Mandatory)][string]$Version)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $Root "dist\CableClub-$Version-sources-coeurs"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$headers = @{ 'User-Agent' = 'CableClub-release' }
if ($env:GH_TOKEN) { $headers['Authorization'] = "Bearer $env:GH_TOKEN" }

$lists = @('build\cores', 'build\cores-android') | ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path (Join-Path $_ 'coeurs.json') }
if (-not $lists) { throw 'Aucune liste de coeurs (coeurs.json) : lancez d abord build.ps1 / build-android.ps1' }
$done = @{}
foreach ($dir in $lists) {
    Copy-Item (Join-Path $dir 'coeurs.txt') (Join-Path $out ("coeurs-" + (Split-Path $dir -Leaf) + '.txt'))
    # (assigned first: PowerShell 5.1 gives a JSON array as one object down a pipeline)
    $list = Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'coeurs.json') | ConvertFrom-Json
    foreach ($c in $list) {
        $key = "$($c.repo)@$($c.ref)"
        if ($done.ContainsKey($key)) { continue }
        # a short commit (read in a binary) becomes the full one; a tag stays a tag
        $ref = $c.ref
        if ($ref -match '^[0-9a-f]{7,39}$') {
            $ref = (Invoke-RestMethod -Headers $headers "https://api.github.com/repos/$($c.repo)/commits/$ref").sha
        }
        $name = ($c.repo -replace '/', '-') + "-$ref.zip"
        Write-Host "    source de $($c.core) : $($c.repo) $ref"
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "https://codeload.github.com/$($c.repo)/zip/$ref" -OutFile (Join-Path $out $name)
        $done[$key] = $name
    }
}
# what a core downloads at its own build and names by a branch (melonDS for melonDS DS): the commit fixed in
# cores.lock.json; the dependencies named by a commit or a tag are given by its build files
$lock = Get-Content -Raw -Encoding UTF8 (Join-Path $Root 'cores.lock.json') | ConvertFrom-Json
$repos = $done.Keys | ForEach-Object { $_.Split('@')[0] }
foreach ($c in @($lock.cores | Where-Object { $_.dependencies -and $repos -contains $_.repo })) {
    foreach ($d in $c.dependencies) {
        $name = ($d.repo -replace '/', '-') + "-$($d.ref).zip"
        Write-Host "    dependance de $($c.name) : $($d.repo) $($d.ref)"
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "https://codeload.github.com/$($d.repo)/zip/$($d.ref)" -OutFile (Join-Path $out $name)
    }
}
@(
    "Sources des coeurs d'emulation livres avec Cable Club $Version (GPL : la source correspondant a chaque binaire).",
    'Chaque archive est celle que GitHub produit pour le commit ou le tag exact du binaire (voir coeurs-*.txt).',
    'melonDS DS telecharge ses dependances a sa compilation (cmake/FetchDependencies.cmake) : elles y sont',
    'designees par commit ou par tag, sauf melonDS (une branche), dont le commit est fourni ici (cores.lock.json).'
) | Set-Content -Encoding ascii (Join-Path $out 'LISEZMOI.txt')

$zip = "$out.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $out -DestinationPath $zip
$hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
[IO.File]::WriteAllText("$zip.sha256", "$hash  $(Split-Path $zip -Leaf)`n")
Write-Host "==> $zip"
