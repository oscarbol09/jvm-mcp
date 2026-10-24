<#
.SYNOPSIS
    Official Windows Uninstaller for JVM-MCP.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:LOCALAPPDATA\jvm-mcp",
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path
    exit 0
}

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

Write-Host "JVM-MCP has been completely uninstalled from Windows." -ForegroundColor Green
