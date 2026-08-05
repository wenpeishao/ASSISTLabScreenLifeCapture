param(
    [string]$PackageName = "edu.wisc.chm.screenomics",
    [string]$FilePrefix = "stress",
    [string]$AdbPath = "",
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    param([string]$RequestedPath)
    if ($RequestedPath -and (Test-Path $RequestedPath)) {
        return (Resolve-Path $RequestedPath).Path
    }
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) {
        return $adbCmd.Source
    }
    if ($env:ANDROID_HOME) {
        $candidate = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidate = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    throw "adb not found. Install Android platform-tools or pass -AdbPath."
}

function Invoke-Adb {
    param([string]$Adb, [string[]]$CommandArgs)
    $full = @()
    if ($DeviceSerial) {
        $full += "-s"
        $full += $DeviceSerial
    }
    $full += $CommandArgs
    $output = & $Adb @full
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($full -join ' ')"
    }
    return $output
}

function Invoke-AdbShell {
    param([string]$Adb, [string]$ShellCommand)
    return Invoke-Adb -Adb $Adb -CommandArgs @("shell", $ShellCommand)
}

$adb = Resolve-Adb -RequestedPath $AdbPath
$encryptDir = "/sdcard/Android/data/$PackageName/files/encrypt"
$cmd = "if [ -d '$encryptDir' ]; then find '$encryptDir' -maxdepth 1 -type f -name '${FilePrefix}_*_image.jpg' -delete; rm -f '$encryptDir/.stress_template.jpg'; fi"
Invoke-AdbShell -Adb $adb -ShellCommand $cmd | Out-Null

Write-Host "Removed stress files from $encryptDir"
