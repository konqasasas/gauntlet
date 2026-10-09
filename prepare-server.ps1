[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (Get-NetTCPConnection -State Listen -LocalPort 25575 -ErrorAction SilentlyContinue) { throw 'Stop the server on port 25575 before deploying.' }
& (Join-Path $PSScriptRoot 'build.ps1') build
$runtime = Join-Path $PSScriptRoot 'run\paper-26.3'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $runtime 'paper-26.3-166.jar'))) {
    $serverUrl = 'https://fill-data.papermc.io/v1/objects/dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd/paper-26.3-166.jar'
    $partial = Join-Path $runtime 'paper.jar.download'
    Invoke-WebRequest -Uri $serverUrl -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd') { throw 'Paper download checksum mismatch.' }
    Move-Item -LiteralPath $partial -Destination (Join-Path $runtime 'paper-26.3-166.jar')
}
$pluginsDir = Join-Path $runtime 'plugins'
New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build\libs\parkour-gauntlet-0.1.0.jar') -Destination (Join-Path $pluginsDir 'parkour-gauntlet.jar')
$propertiesPath = Join-Path $runtime 'server.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    [IO.File]::WriteAllText($propertiesPath, "server-ip=127.0.0.1`nserver-port=25575`nonline-mode=true`ngamemode=adventure`nlevel-type=minecraft:flat`nspawn-protection=0`nallow-flight=false`nview-distance=6`nsimulation-distance=4`n")
}
$eulaPath = Join-Path $runtime 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath)) { [IO.File]::WriteAllText($eulaPath, "# Review https://aka.ms/MinecraftEULA`neula=false`n") }
Write-Host "Prepared $runtime. Use start-server.ps1 to start the new plugin server."
