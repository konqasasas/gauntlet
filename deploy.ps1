[CmdletBinding()]
param(
    # SSH destination: a Host alias from ~/.ssh/config or user@address. Defaults to $env:GAUNTLET_VPS, then 'gauntlet-vps'.
    [string] $Target,
    [switch] $SkipBuild,
    # Upload web/ to the site root instead of deploying the plugin.
    [switch] $Web,
    [string] $Domain = 'catenaonline.net',
    # Install config/display.yml and config/lang/*.yml (from the web editor) and reload them; no restart.
    [switch] $Display,
    # With -Display: copy into the local test server (run/paper-26.3) instead of the VPS.
    [switch] $Local
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = $env:GAUNTLET_VPS }
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = 'gauntlet-vps' }

if ($Display) {
    # The server ends up with exactly these files; one missing from config/ is removed so the bundled default applies.
    $config = Join-Path $PSScriptRoot 'config'
    $known = @('display.yml', 'lang/ja.yml', 'lang/en.yml')
    $files = @($known | Where-Object { Test-Path -LiteralPath (Join-Path $config $_) })
    if ($files.Count -eq 0) { throw 'config/ has no display.yml or lang/*.yml. Export them from web/editor first.' }
    if ($Local) {
        $data = Join-Path $PSScriptRoot 'run\paper-26.3\plugins\ParkourGauntlet'
        if (-not (Test-Path -LiteralPath $data)) { throw "$data not found. Start the local server once first." }
        foreach ($f in $known) {
            $src = Join-Path $config $f
            $dst = Join-Path $data $f
            if (Test-Path -LiteralPath $src) {
                New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
                Copy-Item -LiteralPath $src -Destination $dst -Force
                Write-Host "Copied $f"
            } elseif (Test-Path -LiteralPath $dst) {
                Remove-Item -LiteralPath $dst
                Write-Host "Removed $f (bundled default applies)"
            }
        }
        Write-Host 'In game (or the server console): /gauntlet reload display  and  /gauntlet reload lang'
        return
    }
    $tar = Join-Path ([IO.Path]::GetTempPath()) "gauntlet-display-$(Get-Date -Format 'yyyyMMdd-HHmmss').tar"
    try {
        & tar.exe -cf $tar -C $config @files
        if ($LASTEXITCODE -ne 0) { throw 'Could not pack config/.' }
        $remote = "/opt/gauntlet/incoming/$(Split-Path $tar -Leaf)"
        & scp -q $tar "${Target}:$remote"
        if ($LASTEXITCODE -ne 0) { throw "Upload to $Target failed." }
        & ssh $Target "sudo -n /usr/local/bin/gauntlet-display $remote"
        if ($LASTEXITCODE -ne 0) { throw "Install on $Target failed." }
    } finally {
        Remove-Item -LiteralPath $tar -ErrorAction SilentlyContinue
    }
    return
}

if ($Web) {
    $site = Join-Path $PSScriptRoot 'web'
    & scp -q -r "$site\*" "${Target}:/var/www/$Domain/"
    if ($LASTEXITCODE -ne 0) { throw "Upload of web/ to $Target failed." }
    Write-Host "Site uploaded: https://$Domain/"
    return
}

if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') build }
$jar = Join-Path $PSScriptRoot 'build\libs\parkour-gauntlet-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Build output not found: $jar" }
$remote = "/opt/gauntlet/incoming/parkour-gauntlet-$(Get-Date -Format 'yyyyMMdd-HHmmss').jar"
& scp -q $jar "${Target}:$remote"
if ($LASTEXITCODE -ne 0) { throw "Upload to $Target failed." }
& ssh $Target "sudo -n /usr/local/bin/gauntlet-deploy $remote"
if ($LASTEXITCODE -ne 0) { throw "Install on $Target failed." }
Write-Host "Deployed to $Target. Logs: ssh $Target journalctl -u gauntlet -f"
