$ErrorActionPreference = 'Stop'

# GitHub accepts up to 20 topics, each lowercase, max 35 characters.
$topics = @(
    'minecraft',
    'paper',
    'purpur',
    'spigot',
    'minecraft-server',
    'anticheat',
    'anti-esp',
    'esp',
    'freecam',
    'anti-cheat',
    'packet',
    'protocollib',
    'raytracing',
    'plugin',
    'gaming',
    'java'
)

$body = @{ names = $topics } | ConvertTo-Json

& "$PSScriptRoot\github-api.ps1" -Method PUT -Path "/repos/taolaadminshopok1/vfesp-plugin/topics" `
    -Body $body -ContentType 'application/json'
