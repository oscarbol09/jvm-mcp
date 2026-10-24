<#
.SYNOPSIS
    Official Windows Installer for JVM-MCP.
.DESCRIPTION
    Downloads the pre-compiled standalone distribution from GitHub Releases,
    verifies SHA-256 cryptographic integrity, unpacks into local app data,
    configures user PATH, and verifies installation.
.PARAMETER Version
    Specific release version to install (e.g. "1.1.1" or "v1.1.1"). Defaults to "latest".
.PARAMETER InstallDir
    Target directory for installation. Defaults to "$env:LOCALAPPDATA\jvm-mcp".
.PARAMETER Uninstall
    Completely remove JVM-MCP from the system and clean User PATH.
#>
[CmdletBinding()]
param(
    [string]$Version = "latest",
    [string]$InstallDir = "$env:LOCALAPPDATA\jvm-mcp",
    [switch]$Uninstall,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path
    exit 0
}

$Repo = "oscarbol09/jvm-mcp"
$ExeName = "jvm-mcp.exe"

if ($Uninstall) {
    Write-Host "Uninstalling JVM-MCP..." -ForegroundColor Yellow
    if (Test-Path $InstallDir) {
        Remove-Item -Recurse -Force $InstallDir
        Write-Host "Removed $InstallDir" -ForegroundColor Green
    }
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath) {
        $paths = $userPath -split ';' | Where-Object { $_ -and $_ -ne $InstallDir }
        $newPath = $paths -join ';'
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "Cleaned User PATH environment variable." -ForegroundColor Green
    }
    Write-Host "JVM-MCP uninstalled successfully." -ForegroundColor Green
    exit 0
}

# 1. Architecture check (x86_64/AMD64 only)
$arch = $env:PROCESSOR_ARCHITECTURE
if ($arch -ne "AMD64") {
    Write-Error "Unsupported processor architecture: '$arch'. Windows distribution currently supports x86_64/AMD64 only."
    exit 1
}

# 2. Build URLs
$Asset = "jvm-mcp-windows-amd64.zip"
if ($Version -eq "latest") {
    $DownloadUrl = "https://github.com/$Repo/releases/latest/download/$Asset"
    $ShaUrl = "https://github.com/$Repo/releases/latest/download/$Asset.sha256"
} else {
    $Tag = if ($Version.StartsWith("v")) { $Version } else { "v$Version" }
    $DownloadUrl = "https://github.com/$Repo/releases/download/$Tag/$Asset"
    $ShaUrl = "https://github.com/$Repo/releases/download/$Tag/$Asset.sha256"
}

$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("jvm-mcp-install-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $TmpDir | Out-Null

try {
    Write-Host "Downloading JVM-MCP Windows standalone distribution..." -ForegroundColor Cyan
    $ZipPath = Join-Path $TmpDir $Asset
    $ShaPath = Join-Path $TmpDir "$Asset.sha256"

    Invoke-WebRequest -Uri $DownloadUrl -OutFile $ZipPath -UseBasicParsing
    Invoke-WebRequest -Uri $ShaUrl -OutFile $ShaPath -UseBasicParsing

    Write-Host "Verifying SHA-256 cryptographic checksum..." -ForegroundColor Cyan
    $expectedHash = (Get-Content $ShaPath).Split()[0].Trim().ToLower()
    $actualHash = (Get-FileHash -Algorithm SHA256 $ZipPath).Hash.ToLower()

    if ($expectedHash -ne $actualHash) {
        throw "SHA-256 verification failed! Expected: $expectedHash, Actual: $actualHash"
    }
    Write-Host "Cryptographic integrity verified." -ForegroundColor Green

    # 3. Extract to staging directory and verify executable exists before touching existing installation
    Write-Host "Extracting package to temporary stage..." -ForegroundColor Cyan
    $ExtractTmp = Join-Path $TmpDir "extracted"
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractTmp

    $stagedRoot = Join-Path $ExtractTmp "jvm-mcp"
    $candidateExe = if (Test-Path $stagedRoot) { Join-Path $stagedRoot $ExeName } else { Join-Path $ExtractTmp $ExeName }
    if (-not (Test-Path $candidateExe)) {
        throw "Executable $ExeName not found in unpacked package."
    }

    # 4. Atomic replacement of existing installation with rollback capability
    Write-Host "Deploying to $InstallDir..." -ForegroundColor Cyan
    $backupDir = $null
    if (Test-Path $InstallDir) {
        $backupDir = Join-Path ([System.IO.Path]::GetTempPath()) ("jvm-mcp-backup-" + [System.Guid]::NewGuid().ToString("N"))
        Move-Item -Path $InstallDir -Destination $backupDir -Force
    }

    try {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
        if (Test-Path $stagedRoot) {
            Copy-Item -Path "$stagedRoot\*" -Destination $InstallDir -Recurse -Force
        } else {
            Copy-Item -Path "$ExtractTmp\*" -Destination $InstallDir -Recurse -Force
        }
        if ($backupDir -and (Test-Path $backupDir)) {
            Remove-Item -Recurse -Force $backupDir -ErrorAction SilentlyContinue
        }
    } catch {
        if ($backupDir -and (Test-Path $backupDir)) {
            Write-Host "Installation failed. Restoring previous installation..." -ForegroundColor Yellow
            if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
            Move-Item -Path $backupDir -Destination $InstallDir -Force
        }
        throw
    }

    $InstalledExe = Join-Path $InstallDir $ExeName

    # 5. Update User PATH
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $pathParts = if ($userPath) { $userPath -split ';' } else { @() }
    if ($pathParts -notcontains $InstallDir) {
        $newUserPath = if ($userPath) { "$userPath;$InstallDir" } else { $InstallDir }
        [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
        $env:Path = "$env:Path;$InstallDir"
        Write-Host "Added $InstallDir to User PATH." -ForegroundColor Green
    }

    Write-Host "`nJVM-MCP installed successfully!" -ForegroundColor Green
    Write-Host "Running initial system diagnostic (jvm-mcp doctor)..." -ForegroundColor Cyan
    & $InstalledExe doctor

    Write-Host "`nTo get started, restart your terminal or run: jvm-mcp --help" -ForegroundColor Cyan
}
finally {
    Remove-Item -Recurse -Force $TmpDir -ErrorAction SilentlyContinue
}
