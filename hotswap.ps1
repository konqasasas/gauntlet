[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
# Compiles and pushes changed classes into a server started with start-server.ps1 -Hotswap.
$jdk = $env:GAUNTLET_JAVA_HOME
if ([string]::IsNullOrWhiteSpace($jdk)) { $jdk = 'C:\Program Files\Java\jdk-25.0.4.1' }
$runtime = Join-Path $PSScriptRoot 'run\paper-26.3'
$state = Join-Path $PSScriptRoot 'build\hotswap'
$listener = Get-NetTCPConnection -State Listen -LocalPort 25575 -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $listener) { throw 'Server is not running on port 25575.' }
$commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)").CommandLine
if ($commandLine -notmatch 'AllowEnhancedClassRedefinition') { throw 'Server was not started with start-server.ps1 -Hotswap.' }

& (Join-Path $PSScriptRoot 'build.ps1') classes

$agentSource = Join-Path $PSScriptRoot 'tools\ClassReloadAgent.java'
$agentJar = Join-Path $state 'class-reload-agent.jar'
if (-not (Test-Path -LiteralPath $agentJar) -or (Get-Item -LiteralPath $agentSource).LastWriteTime -gt (Get-Item -LiteralPath $agentJar).LastWriteTime) {
    $agentClasses = Join-Path $state 'agent-classes'
    if (Test-Path -LiteralPath $agentClasses) { Remove-Item -LiteralPath $agentClasses -Recurse -Force }
    New-Item -ItemType Directory -Path $agentClasses -Force | Out-Null
    & (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -d $agentClasses $agentSource
    if ($LASTEXITCODE -ne 0) { throw 'Hotswap agent compilation failed.' }
    $manifest = Join-Path $state 'MANIFEST.MF'
    [IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nAgent-Class: ClassReloadAgent`nCan-Redefine-Classes: true`n`n",(New-Object Text.UTF8Encoding($false)))
    & (Join-Path $jdk 'bin\jar.exe') cfm $agentJar $manifest -C $agentClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Hotswap agent packaging failed.' }
}

& (Join-Path $jdk 'bin\java.exe') -cp $agentJar ClassReloadAgent $listener.OwningProcess $agentJar (Join-Path $PSScriptRoot 'build\classes\java\main') (Join-Path $runtime 'plugins\parkour-gauntlet.jar') $state
if ($LASTEXITCODE -ne 0) { throw 'Hotswap failed. Restart the server with prepare-server.ps1 if the change cannot be hotswapped.' }
