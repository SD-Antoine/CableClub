<#
  Starts the packaged SLT (no window, own data and port) and checks that it
  answers and serves every file of the interface. Run by the release workflow
  before publishing; also usable by hand:  .\.github\smoke-test.ps1 -Image dist\SLT
#>
param(
    [string]$Image = 'dist\SLT',
    [string]$Version = '',
    [int]$Port = 47399
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$exe = Join-Path (Resolve-Path $Image) 'SLT.exe'
$data = Join-Path ([IO.Path]::GetTempPath()) ('slt-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$base = "http://127.0.0.1:$Port"
$p = Start-Process -FilePath $exe -ArgumentList '--no-window', "--port=$Port", "--data=$data" -PassThru
try {
    $state = $null
    for ($i = 0; $i -lt 90 -and -not $state; $i++) {
        Start-Sleep -Seconds 1
        try { $state = (Invoke-WebRequest -UseBasicParsing "$base/api/state" -Headers @{ 'X-SLT' = '1' }).Content | ConvertFrom-Json } catch { }
    }
    if (-not $state) { throw "SLT ne repond pas sur $base" }
    if ($Version -and $state.version -ne $Version) { throw "SLT annonce la version $($state.version) au lieu de $Version" }
    $web = Join-Path $root 'web'
    $files = @(Get-ChildItem -Recurse -File $web | ForEach-Object { $_.FullName.Substring($web.Length + 1).Replace('\', '/') })
    $missing = @()
    foreach ($f in $files) {
        try { Invoke-WebRequest -UseBasicParsing "$base/$f" | Out-Null } catch { $missing += $f }
    }
    if ($missing.Count -gt 0) { throw ('Fichiers de l''interface absents de SLT : ' + ($missing -join ', ')) }
    Write-Host "SLT $($state.version) demarre et sert les $($files.Count) fichiers de l'interface."
} finally {
    Get-Process SLT -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
    Start-Sleep -Seconds 1
    Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
}
