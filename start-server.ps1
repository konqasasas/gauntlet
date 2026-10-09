[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$testDir = Join-Path $PSScriptRoot 'run\paper-26.3'
$taskJdk = $env:GAUNTLET_JAVA_HOME
if ([string]::IsNullOrWhiteSpace($taskJdk)) { $taskJdk = 'C:\Program Files\Java\jdk-25.0.4.1' }
$java = Join-Path $taskJdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'Set GAUNTLET_JAVA_HOME to JDK 25.' }
$jar = Join-Path $testDir 'paper-26.3-166.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run .\prepare-server.ps1 first.' }
if (-not (Select-String -LiteralPath (Join-Path $testDir 'eula.txt') -Pattern '^\s*eula=true\s*$' -Quiet)) {
    throw 'Read https://aka.ms/MinecraftEULA and, if you agree, set eula=true in run/paper-26.3/eula.txt.'
}
if (Get-NetTCPConnection -State Listen -LocalPort 25575 -ErrorAction SilentlyContinue) { throw 'Port 25575 is already in use.' }
Push-Location $testDir
try {
    & $java -Xms512M -Xmx2G '-Dterminal.jline=false' '-Dterminal.ansi=false' -jar $jar --nogui
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
