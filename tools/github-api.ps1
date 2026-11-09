param(
    [Parameter(Mandatory = $true)]
    [string]$Method,
    [Parameter(Mandatory = $true)]
    [string]$Path,
    [string]$Body,
    [string]$ContentType = 'application/json',
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'

# Retrieve the stored GitHub credential from Git Credential Manager.
# The token is piped straight into the HTTP header and never written to disk or stdout.
$credentialInput = "protocol=https`nhost=github.com`n`n"
$credentialLines = $credentialInput | & 'C:\Program Files\Git\cmd\git.exe' credential fill

$username = $null
$token = $null
foreach ($line in $credentialLines) {
    if ($line -like 'username=*') { $username = $line.Substring(9) }
    if ($line -like 'password=*') { $token = $line.Substring(9) }
}

if (-not $token) {
    Write-Error 'No GitHub credential available from Git Credential Manager.'
    exit 1
}

$headers = @{
    'Authorization'  = "Bearer $token"
    'Accept'         = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'     = 'AntiESPFreecam-setup'
}

$uri = "https://api.github.com$Path"

if ($Body -and $Method -eq 'GET') {
    $response = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers
    $response | ConvertTo-Json -Depth 6
    exit 0
}

if ($Body) {
    $response = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers `
        -ContentType $ContentType -Body $Body
} else {
    $response = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers
}

if ($OutFile) {
    # Binary download: write the raw bytes to the target path.
    Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -OutFile $OutFile
    Write-Output "Downloaded to $OutFile"
} else {
    $response | ConvertTo-Json -Depth 6
}
