[CmdletBinding()]
param(
    # SSH destination: a Host alias from ~/.ssh/config or user@address. Defaults to $env:GAUNTLET_VPS, then 'gauntlet-vps'.
    [string] $Target,
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = $env:GAUNTLET_VPS }
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = 'gauntlet-vps' }
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') build }
$jar = Join-Path $PSScriptRoot 'build\libs\parkour-gauntlet-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Build output not found: $jar" }
$remote = "/opt/gauntlet/incoming/parkour-gauntlet-$(Get-Date -Format 'yyyyMMdd-HHmmss').jar"
& scp -q $jar "${Target}:$remote"
if ($LASTEXITCODE -ne 0) { throw "Upload to $Target failed." }
& ssh $Target "sudo -n /usr/local/bin/gauntlet-deploy $remote"
if ($LASTEXITCODE -ne 0) { throw "Install on $Target failed." }
Write-Host "Deployed to $Target. Logs: ssh $Target journalctl -u gauntlet -f"
