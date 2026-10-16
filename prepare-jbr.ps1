[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
# JetBrains Runtime (JBRSDK 25) for hotswap development; installs into run\jbr-25.
$name = 'jbrsdk-25.0.4.1-windows-x64-b623.69'
$sha512 = 'd8178ebc4d56674a579f5343e2413e1bbb61adf820a0c7e302cb21c1effa9322e08bb75a0f2b0def700d3af03fdaf24c6cfd45865986c02214e7234e447cff56'
$runDir = Join-Path $PSScriptRoot 'run'
$target = Join-Path $runDir 'jbr-25'
if (Test-Path -LiteralPath (Join-Path $target 'bin\java.exe')) { Write-Host "JBR already installed at $target."; return }
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$partial = Join-Path $runDir 'jbr.zip.download'
Invoke-WebRequest -Uri "https://cache-redirector.jetbrains.com/intellij-jbr/$name.zip" -OutFile $partial
if ((Get-FileHash -LiteralPath $partial -Algorithm SHA512).Hash -ine $sha512) { Remove-Item -LiteralPath $partial; throw 'JBR download checksum mismatch.' }
$extract = Join-Path $runDir 'jbr.extract.tmp'
if (Test-Path -LiteralPath $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
New-Item -ItemType Directory -Path $extract | Out-Null
& tar.exe -xf $partial -C $extract
if ($LASTEXITCODE -ne 0) { throw 'JBR extraction failed.' }
$java = Get-ChildItem -LiteralPath $extract -Recurse -Filter 'java.exe' | Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
if (-not $java) { throw 'java.exe not found in JBR archive.' }
Move-Item -LiteralPath $java.Directory.Parent.FullName -Destination $target
Remove-Item -LiteralPath $extract -Recurse -Force
Remove-Item -LiteralPath $partial
Write-Host "Installed JBR at $target. Use start-server.ps1 -Hotswap."
