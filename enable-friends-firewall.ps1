[CmdletBinding()]
param([switch] $Remove)
$ErrorActionPreference = 'Stop'
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    $elevationArgs = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    if ($Remove) { $elevationArgs += ' -Remove' }
    $elevatedProcess = Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $elevationArgs -Wait -PassThru
    if ($elevatedProcess.ExitCode -ne 0) { throw 'Firewall configuration failed in the administrator process.' }
    Write-Host 'Firewall configuration completed.'
    exit 0
}
$ruleName = 'ParkourGauntlet-Java-TCP-25575'
if ($Remove) {
    Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
} else {
    $javaPath = 'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaPath)) { throw 'Expected Java 25 executable was not found.' }
    if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) {
        Set-NetFirewallRule -Name $ruleName -Enabled True -Direction Inbound -Action Allow -Profile Any
    } else {
        New-NetFirewallRule -Name $ruleName -DisplayName 'Parkour Gauntlet TCP 25575' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 25575 -Program $javaPath -Profile Any | Out-Null
    }
}
