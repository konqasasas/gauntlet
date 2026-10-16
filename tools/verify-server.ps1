[CmdletBinding()]
param([ValidateSet('check','features','stop')][string] $Action = 'check')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $projectRoot 'run\paper-26.3'
$jdk = 'C:\Program Files\Java\jdk-25.0.4.1'
$output = Join-Path $projectRoot 'build\server-checks'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$server = Join-Path $runtime 'versions\26.3\paper-26.3.jar'
$classpath = (@($server) + @(Get-ChildItem -LiteralPath (Join-Path $runtime 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)) -join ';'
$classpath = $classpath.Replace('\','/')
$argfile = Join-Path $output 'compile.args'
$source1 = (Join-Path $PSScriptRoot 'LocalServerAgent.java').Replace('\','/')
$source2 = (Join-Path $PSScriptRoot 'ServerChecks.java').Replace('\','/')
$source3 = (Join-Path $PSScriptRoot 'FeatureChecks.java').Replace('\','/')
[IO.File]::WriteAllLines($argfile,@('-cp',('"'+$classpath+'"'),'-d',('"'+$output.Replace('\','/')+'"'),('"'+$source1+'"'),('"'+$source2+'"'),('"'+$source3+'"')), (New-Object Text.UTF8Encoding($false)))
& (Join-Path $jdk 'bin\javac.exe') "@$argfile"
if ($LASTEXITCODE -ne 0) { throw 'Server check helper compilation failed.' }
$manifest = Join-Path $output 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nAgent-Class: LocalServerAgent`n`n",(New-Object Text.UTF8Encoding($false)))
$agent = Join-Path $projectRoot ('build\server-checks-' + [Guid]::NewGuid().ToString('N') + '.jar')
& (Join-Path $jdk 'bin\jar.exe') cfm $agent $manifest -C $output .
if ($LASTEXITCODE -ne 0) { throw 'Server check helper packaging failed.' }
$serverProcess = (Get-NetTCPConnection -State Listen -LocalPort 25575).OwningProcess
$logPath = Join-Path $runtime 'logs\latest.log'
$previousLines = @(Get-Content -LiteralPath $logPath).Count
& (Join-Path $jdk 'bin\java.exe') -cp $agent LocalServerAgent $serverProcess $agent $Action
if ($LASTEXITCODE -ne 0) { throw 'Server attach failed.' }
if ($Action -ne 'stop') {
    $marker = if ($Action -eq 'features') { 'FEATURE' } else { 'INTEGRATION' }
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $newLog = @(Get-Content -LiteralPath $logPath | Select-Object -Skip $previousLines) -join "`n"
        if ($newLog -match "${marker}_CLEANUP_PASS") {
            if ($newLog -notmatch "${marker}_PASS ") { throw "$Action check failed; inspect $logPath" }
            Write-Host "$Action checks and cleanup passed. Minecraft-client gameplay remains untested."
            exit 0
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Integration check did not finish; inspect $logPath"
}
