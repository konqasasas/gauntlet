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
    # With -Display or -Menu: copy into the local test server (run/paper-26.3) instead of the VPS.
    [switch] $Local,
    # Install config/menu (Dialog editor templates and datapack) and reload; no restart.
    [switch] $Menu,
    # With -Menu: publish the VPS editor's latest export instead, then copy it and the editor data back into git.
    [switch] $FromEditor,
    # With -Menu -FromEditor: overwrite uncommitted local changes in config/menu and dialog-editor/data.
    [switch] $Force,
    # Upload dialog-editor/ (committed code, plus data/vanilla) to the VPS editor and restart it.
    [switch] $DialogEditor
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = $env:GAUNTLET_VPS }
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = 'gauntlet-vps' }

function Send-Tar([string] $tar, [string] $command) {
    $remote = "/opt/gauntlet/incoming/$(Split-Path $tar -Leaf)"
    & scp -q $tar "${Target}:$remote"
    if ($LASTEXITCODE -ne 0) { throw "Upload to $Target failed." }
    & ssh $Target "sudo -n $command $remote"
    if ($LASTEXITCODE -ne 0) { throw "$command on $Target failed." }
}
function Sync-Folder([string] $from, [string] $to, [string] $filter) {
    New-Item -ItemType Directory -Force -Path $to | Out-Null
    $keep = @(Get-ChildItem -LiteralPath $from -Filter $filter -File | ForEach-Object Name)
    Get-ChildItem -LiteralPath $to -Filter $filter -File | Where-Object { $keep -notcontains $_.Name } | ForEach-Object { Remove-Item -LiteralPath $_.FullName; Write-Host "Removed $($_.Name)" }
    Get-ChildItem -LiteralPath $from -Filter $filter -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $to $_.Name) -Force }
}

if ($DialogEditor) {
    $dirty = & git -C $PSScriptRoot status --porcelain -- dialog-editor ':!dialog-editor/data'
    if ($dirty) { Write-Warning "Uncommitted changes in dialog-editor/ are not uploaded (only the last commit is):`n$($dirty -join "`n")" }
    $tar = Join-Path ([IO.Path]::GetTempPath()) "dialog-editor-$(Get-Date -Format 'yyyyMMdd-HHmmss').tar"
    try {
        & git -C $PSScriptRoot archive --format=tar --prefix=app/ -o $tar HEAD:dialog-editor
        if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'dialog-editor\data\vanilla')) {
            & tar.exe -rf $tar -C (Join-Path $PSScriptRoot 'dialog-editor') data/vanilla
            if ($LASTEXITCODE -ne 0) { throw 'Could not add data/vanilla.' }
        } else { Write-Warning 'dialog-editor/data/vanilla not found; game-font previews need it (node tools/fetch-vanilla.mjs).' }
        Send-Tar $tar '/usr/local/bin/gauntlet-dialog-update'
    } finally { Remove-Item -LiteralPath $tar -ErrorAction SilentlyContinue }
    return
}

if ($Menu) {
    $menuDir = Join-Path $PSScriptRoot 'config\menu'
    if ($FromEditor) {
        # The VPS editor is the source of truth: publish its latest export, then mirror it into git.
        $dirty = & git -C $PSScriptRoot status --porcelain -- config/menu dialog-editor/data
        if ($dirty -and -not $Force) { throw "Uncommitted changes would be overwritten (use -Force to discard them):`n$($dirty -join "`n")" }
        & ssh $Target 'sudo -n /usr/local/bin/gauntlet-menu --from-editor'
        if ($LASTEXITCODE -ne 0) { throw "Publishing on $Target failed." }
        $pull = Join-Path ([IO.Path]::GetTempPath()) "dialog-pull-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        New-Item -ItemType Directory -Path $pull | Out-Null
        try {
            & scp -q -r "${Target}:/opt/dialog-editor/exports/latest/templates" "${Target}:/opt/dialog-editor/exports/latest/datapack.zip" `
                "${Target}:/opt/dialog-editor/exports/latest/resourcepack.zip" "${Target}:/opt/dialog-editor/exports/latest/manifest.json" "$pull\"
            if ($LASTEXITCODE -ne 0) { throw 'Copying the export back failed.' }
            & scp -q "${Target}:/opt/dialog-editor/data/project.json" (Join-Path $PSScriptRoot 'dialog-editor\data\project.json')
            if ($LASTEXITCODE -ne 0) { throw 'Copying the editor data back failed.' }
            & ssh $Target 'test -d /opt/dialog-editor/data/assets'
            if ($LASTEXITCODE -eq 0) {
                & scp -q -r "${Target}:/opt/dialog-editor/data/assets" "$pull\"
                if ($LASTEXITCODE -ne 0) { throw 'Copying the editor images back failed.' }
                Sync-Folder (Join-Path $pull 'assets') (Join-Path $PSScriptRoot 'dialog-editor\data\assets') '*'
            }
            Sync-Folder (Join-Path $pull 'templates') (Join-Path $menuDir 'templates') '*.json'
            foreach ($f in 'datapack.zip', 'resourcepack.zip', 'manifest.json') { Copy-Item -LiteralPath (Join-Path $pull $f) -Destination (Join-Path $menuDir $f) -Force }
        } finally { Remove-Item -LiteralPath $pull -Recurse -Force -ErrorAction SilentlyContinue }
        & git -C $PSScriptRoot status --short -- config/menu dialog-editor/data
        Write-Host 'Published on the VPS and copied back. Review the changes above and commit them.'
        return
    }
    $templates = Join-Path $menuDir 'templates'
    if (-not (Get-ChildItem -LiteralPath $templates -Filter '*.json' -ErrorAction SilentlyContinue)) { throw 'config/menu/templates has no screens.' }
    if ($Local) {
        $runtime = Join-Path $PSScriptRoot 'run\paper-26.3'
        Sync-Folder $templates (Join-Path $runtime 'plugins\ParkourGauntlet\menu') '*.json'
        Write-Host "Copied $(@(Get-ChildItem -LiteralPath $templates -Filter '*.json').Count) templates"
        # Each screen's default states (Sync-Folder removed the old one with the other *.json).
        $manifest = Join-Path $menuDir 'manifest.json'
        if (Test-Path -LiteralPath $manifest) { Copy-Item -LiteralPath $manifest -Destination (Join-Path $runtime 'plugins\ParkourGauntlet\menu\manifest.json') -Force }
        $datapack = Join-Path $menuDir 'datapack.zip'
        $target = Join-Path $runtime 'world\datapacks\dialog-custom-gui.zip'
        $reloadDatapack = $false
        if ((Test-Path -LiteralPath $datapack) -and (-not (Test-Path -LiteralPath $target) -or (Get-FileHash $datapack).Hash -ne (Get-FileHash $target).Hash)) {
            New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
            Copy-Item -LiteralPath $datapack -Destination $target -Force
            $reloadDatapack = $true
            Write-Host 'Copied the datapack'
        }
        Write-Host ('In game (or the server console): /gauntlet reload menu' + $(if ($reloadDatapack) { '  and  /minecraft:reload' } else { '' }))
        $pack = Join-Path $menuDir 'resourcepack.zip'
        if (Test-Path -LiteralPath $pack) {
            # The plugin sends packs only where resource-packs.publish-dir exists; on this PC put it into your client by hand.
            Copy-Item -LiteralPath $pack -Destination (Join-Path $runtime 'plugins\ParkourGauntlet\menu\resourcepack.zip') -Force
            Write-Host "Resource pack for your client: $pack"
        }
        return
    }
    $tar = Join-Path ([IO.Path]::GetTempPath()) "gauntlet-menu-$(Get-Date -Format 'yyyyMMdd-HHmmss').tar"
    try {
        $items = @('templates') + @('datapack.zip', 'resourcepack.zip', 'manifest.json' | Where-Object { Test-Path -LiteralPath (Join-Path $menuDir $_) })
        & tar.exe -cf $tar -C $menuDir @items
        if ($LASTEXITCODE -ne 0) { throw 'Could not pack config/menu.' }
        Send-Tar $tar '/usr/local/bin/gauntlet-menu'
    } finally { Remove-Item -LiteralPath $tar -ErrorAction SilentlyContinue }
    return
}

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
    # Folders copied from Windows can arrive unreadable for nginx (403); make everything world-readable.
    & ssh $Target "chmod -R u=rwX,go=rX /var/www/$Domain"
    if ($LASTEXITCODE -ne 0) { throw "Fixing permissions on $Target failed." }
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
