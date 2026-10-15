# Installs or upgrades the kinuvo command (CLI and Server) on Windows:
#   irm https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.ps1 | iex
# $env:KINUVO_VERSION = 'v1.2.3' pins a release; $env:KINUVO_INSTALL_DIR chooses where it goes.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = if ($env:KINUVO_REPO) { $env:KINUVO_REPO } else { 'FeernandoOFF/kinuvo' }
$installDir = if ($env:KINUVO_INSTALL_DIR) { $env:KINUVO_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'kinuvo' }
$base = if ($env:KINUVO_DOWNLOAD_URL) { $env:KINUVO_DOWNLOAD_URL }
    elseif ($env:KINUVO_VERSION) { "https://github.com/$repo/releases/download/$($env:KINUVO_VERSION)" }
    else { "https://github.com/$repo/releases/latest/download" }
# Only an x64 build exists; Windows on Arm runs it emulated.
$archive = 'kinuvo-windows-x64.zip'

$tmp = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null
try {
    Write-Host "Downloading $archive..."
    Invoke-WebRequest "$base/$archive" -OutFile (Join-Path $tmp $archive)
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS')

    $line = Get-Content (Join-Path $tmp 'SHA256SUMS') | Where-Object { ($_ -split '\s+\*?')[1] -eq $archive }
    if (-not $line) { throw "No checksum for $archive" }
    $expected = ($line -split '\s+')[0]
    $actual = (Get-FileHash (Join-Path $tmp $archive) -Algorithm SHA256).Hash
    if ($expected -ne $actual) { throw "Checksum mismatch for $archive" }

    Expand-Archive (Join-Path $tmp $archive) -DestinationPath $tmp
    if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
    New-Item -ItemType Directory (Split-Path $installDir) -Force | Out-Null
    Move-Item (Join-Path $tmp 'kinuvo') $installDir
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

$bin = Join-Path $installDir 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $bin) {
    [Environment]::SetEnvironmentVariable('Path', "$bin;$userPath", 'User')
    $env:Path = "$bin;$env:Path"
    Write-Host "Added $bin to your PATH; open a new terminal to use it."
}
Write-Host "Installed $(& (Join-Path $bin 'kinuvo.cmd') --version) to $installDir"
Write-Host "Next: 'kinuvo login' to sign in to a model provider, then 'kinuvo' to run the Server."
