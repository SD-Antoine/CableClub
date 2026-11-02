<#
  Fills the test instance (point 9a) with entirely made-up data through the real HTTP API: test accounts, friends,
  a room in each mode, chat messages, a profile picture, a running solo room fed with simulated events, and a cloud
  save of random bytes. Nothing here is a real account, a real save or a ROM. Called by start-test-server.ps1.

    .\tests\security\seed-test-data.ps1 -Base http://127.0.0.1:47399 -Data <dossier>
#>
param(
    [Parameter(Mandatory)][string]$Base,
    [Parameter(Mandatory)][string]$Data
)

$ErrorActionPreference = 'Stop'

function Api($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $args = @{ Method = $method; Uri = "$Base$path"; Headers = $headers; TimeoutSec = 15 }
    if ($null -ne $body) { $args['Body'] = ($body | ConvertTo-Json -Depth 20 -Compress); $args['ContentType'] = 'application/json' }
    return Invoke-RestMethod @args
}

function NewPassword { -join ((1..20) | ForEach-Object { [char](Get-Random -InputObject (([char[]]'abcdefghijkmnpqrstuvwxyz23456789ABCDEFGHJKMNPQRSTUVWXYZ'))) }) }

# ---- test accounts (generated passwords, written to a file in the throwaway directory)
$users = @('test_alice', 'test_bob', 'test_carol', 'test_dave')
$accounts = @{}
$lines = @('Comptes de test Cable Club (instance de test locale, données factices).', '')
foreach ($u in $users) {
    $pw = NewPassword
    $res = Api POST '/api/auth/register' $null @{ username = $u; password = $pw; name = $u; terms = $true }
    $accounts[$u] = @{ token = $res.token; id = $res.user.id; password = $pw }
    $lines += "$u : $pw"
}
Set-Content (Join-Path $Data 'comptes-de-test.txt') $lines

$alice = $accounts['test_alice']; $bob = $accounts['test_bob']; $carol = $accounts['test_carol']

# ---- friends (alice <-> bob, alice <-> carol)
Api POST '/api/friends/request' $alice.token @{ username = 'test_bob' } | Out-Null
Api POST '/api/friends/request' $bob.token @{ username = 'test_alice' } | Out-Null
Api POST '/api/friends/request' $alice.token @{ username = 'test_carol' } | Out-Null
Api POST '/api/friends/request' $carol.token @{ username = 'test_alice' } | Out-Null

# ---- a profile picture (a 1x1 PNG, redrawn by the server into a 128x128 PNG)
$png1x1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
Api POST '/api/me/avatar' $alice.token @{ data = "data:image/png;base64,$png1x1" } | Out-Null

# ---- a lobby room in each mode
$games = @{
    soullink  = @{ gameId = 'rubisfr'; name = 'Pokémon Rubis'; gen = 3 }
    nuzlocke  = @{ gameId = 'rougefr'; name = 'Pokémon Rouge'; gen = 1 }
    sololocke = @{ gameId = 'platinefr'; name = 'Pokémon Platine'; gen = 4 }
    shinylocke = @{ gameId = 'cristalfr'; name = 'Pokémon Cristal'; gen = 2 }
}
foreach ($mode in $games.Keys) {
    $g = $games[$mode]
    Api POST '/api/rooms' $alice.token @{ mode = $mode; name = "Salle de test $mode"; maxPlayers = 2
        game = @{ gameId = $g.gameId; name = $g.name; gen = $g.gen; platform = 'gba'; lang = 'fr'; crc = 'testcrc0' } } | Out-Null
}

# ---- chat: general channel and a direct message between friends
Api POST '/api/chat/global' $alice.token @{ text = 'Message de test dans le chat général.' } | Out-Null
Api POST '/api/chat/global' $bob.token @{ text = 'Autre message de test.' } | Out-Null
Api POST "/api/chat/dm-$($bob.id)" $alice.token @{ text = 'Message privé de test.' } | Out-Null

# ---- a running solo Nuzlocke room fed with a few simulated events, plus a cloud save of random bytes
$room = Api POST '/api/rooms' $alice.token @{ mode = 'nuzlocke'; name = 'Salle de test en cours'; maxPlayers = 1
    game = @{ gameId = 'rougefr'; name = 'Pokémon Rouge'; gen = 1; platform = 'gb'; lang = 'fr'; crc = 'testcrc1' } }
$rid = $room.id
Api POST "/api/rooms/$rid/rom" $alice.token @{ status = 'ready'; crc = 'testcrc1'; gameId = 'rougefr'; lang = 'fr' } | Out-Null
Api POST "/api/rooms/$rid/ready" $alice.token @{ ready = $true } | Out-Null
Api POST "/api/rooms/$rid/launch" $alice.token @{} | Out-Null
$events = @(
    @{ type = 'capture'; uid = '1001'; species = 1; speciesName = 'Bulbizarre'; level = 5; nickname = 'BULBIZARRE'; zone = 'Bourg Palette'; origin = 'new'; inParty = $true; fainted = $false; shiny = $false },
    @{ type = 'balls'; has = $true },
    @{ type = 'location'; map = 12; zone = 'Route 1' },
    @{ type = 'encounter'; phase = 'start'; uid = '1002'; species = 16; speciesName = 'Roucool'; level = 3; zone = 'Route 1'; shiny = $false; balls = 5 },
    @{ type = 'capture'; uid = '1002'; species = 16; speciesName = 'Roucool'; level = 3; nickname = 'ROUCOOL'; zone = 'Route 1'; origin = 'wild'; inParty = $true; fainted = $false; shiny = $false }
)
Api POST "/api/rooms/$rid/events" $alice.token @{ events = $events } | Out-Null

$save = New-Object byte[] 32768
(New-Object Random).NextBytes($save)
Invoke-RestMethod -Method POST -Uri "$Base/api/rooms/$rid/save?name=test.sav" -Headers @{ Authorization = "Bearer $($alice.token)" } `
    -Body $save -ContentType 'application/octet-stream' -TimeoutSec 15 | Out-Null

Write-Host "Données factices en place : $($users.Count) comptes, 5 salles (dont une en cours), chat, photo, sauvegarde."
