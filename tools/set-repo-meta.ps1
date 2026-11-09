$ErrorActionPreference = 'Stop'

$body = @{
    description = 'Anti-ESP packet occlusion and anti-freecam enforcement for Paper/Purpur 1.20.2-1.21.x. Removes entities and containers the client has no line of sight to, and validates interactions against the authoritative server-side eye position.'
    homepage    = 'https://github.com/taolaadminshopok1/vfesp-plugin'
    website     = 'https://github.com/taolaadminshopok1/vfesp-plugin'
    has_wiki    = $false
    has_issues  = $true
    has_projects = $false
    has_discussions = $false
} | ConvertTo-Json

& "$PSScriptRoot\github-api.ps1" -Method PATCH -Path "/repos/taolaadminshopok1/vfesp-plugin" -Body $body
