<#
  Starts the packaged Cable Club (no window, own data and port) and checks that it
  answers and serves every file of the interface. Run by the release workflow
  before publishing; also usable by hand:  .\.github\smoke-test.ps1 -Image dist\CableClub
#>
param(
    [string]$Image = 'dist\CableClub',
    [string]$Version = '',
    [int]$Port = 47399
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$exe = Join-Path (Resolve-Path $Image) 'CableClub.exe'
if (-not (Test-Path $exe)) { throw "CableClub.exe introuvable dans $Image" }
if (Test-Path (Join-Path (Resolve-Path $Image) 'SLT.exe')) { throw "SLT.exe ne doit plus etre dans $Image" }
$data = Join-Path ([IO.Path]::GetTempPath()) ('slt-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$base = "http://127.0.0.1:$Port"
$p = Start-Process -FilePath $exe -ArgumentList '--no-window', "--port=$Port", "--data=$data" -PassThru
try {
    $state = $null
    for ($i = 0; $i -lt 90 -and -not $state; $i++) {
        Start-Sleep -Seconds 1
        try { $state = (Invoke-WebRequest -UseBasicParsing "$base/api/state" -Headers @{ 'X-SLT' = '1' }).Content | ConvertFrom-Json } catch { }
    }
    if (-not $state) { throw "Cable Club ne repond pas sur $base" }
    if ($Version -and $state.version -ne $Version) { throw "Cable Club annonce la version $($state.version) au lieu de $Version" }
    $web = Join-Path $root 'web'
    $files = @(Get-ChildItem -Recurse -File $web | ForEach-Object { $_.FullName.Substring($web.Length + 1).Replace('\', '/') })
    $missing = @()
    foreach ($f in $files) {
        try { Invoke-WebRequest -UseBasicParsing "$base/$f" | Out-Null } catch { $missing += $f }
    }
    if ($missing.Count -gt 0) { throw ('Fichiers de l''interface absents de Cable Club : ' + ($missing -join ', ')) }
    # the patch notes of this very version travel in the program (Réglages > Autres > Mise à jour)
    $notes = (Invoke-WebRequest -UseBasicParsing "$base/api/changelog" -Headers @{ 'X-SLT' = '1' }).Content | ConvertFrom-Json
    if (-not ($notes.sections | Where-Object { $_.version -eq $state.version -and $_.notes })) { throw "CHANGELOG.md n'a pas de notes pour la version $($state.version)" }
    Write-Host "Cable Club $($state.version) demarre et sert les $($files.Count) fichiers de l'interface."
} finally {
    Get-Process ([IO.Path]::GetFileNameWithoutExtension($exe)) -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
    Start-Sleep -Seconds 1
    Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
}
