param(
    [string]$PackageName = "edu.wisc.chm.screenomics",
    [int]$Count = 100000,
    [int]$ChunkSize = 2000,
    [string]$FilePrefix = "stress",
    [string]$AdbPath = "",
    [string]$DeviceSerial = "",
    [switch]$TriggerUpload = $true,
    [bool]$ContinueWithoutWifi = $true,
    [int]$MonitorMinutes = 120,
    [int]$PollSeconds = 60
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

    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "adb not found. Install Android platform-tools or pass -AdbPath."
}

function Invoke-Adb {
    param(
        [string]$Adb,
        [string[]]$CommandArgs
    )
    $full = @()
    if ($DeviceSerial) {
        $full += "-s"
        $full += $DeviceSerial
    }
    $full += $CommandArgs
    $output = & $Adb @full
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $cmdText = ($full -join " ")
        throw "adb command failed (exit $exitCode): $cmdText"
    }
    return $output
}

function Invoke-AdbShell {
    param(
        [string]$Adb,
        [string]$ShellCommand
    )
    return Invoke-Adb -Adb $Adb -CommandArgs @("shell", $ShellCommand)
}

function Get-RemoteFileCount {
    param(
        [string]$Adb,
        [string]$RemoteDir,
        [string]$Prefix
    )
    $countCmd = "if [ -d '$RemoteDir' ]; then find '$RemoteDir' -maxdepth 1 -type f -name '${Prefix}_*_image.jpg' | wc -l; else echo 0; fi"
    $raw = Invoke-AdbShell -Adb $Adb -ShellCommand $countCmd
    $n = 0
    [void][int]::TryParse(($raw | Out-String).Trim(), [ref]$n)
    return $n
}

$adb = Resolve-Adb -RequestedPath $AdbPath
Write-Host "Using adb: $adb"

$devices = Invoke-Adb -Adb $adb -CommandArgs @("devices")
$deviceLines = ($devices | Select-String "device$").Count
if ($deviceLines -lt 1) {
    throw "No device connected. Connect a phone/emulator and enable USB debugging."
}

$encryptDir = "/sdcard/Android/data/$PackageName/files/encrypt"
$remoteScript = "/data/local/tmp/ndscreenomics_stress_gen.sh"
$localScript = Join-Path $env:TEMP "ndscreenomics_stress_gen.sh"

$scriptBody = @(
    "#!/system/bin/sh",
    'DIR="$1"',
    'START="$2"',
    'END="$3"',
    'PREFIX="$4"',
    'if [ ! -d "$DIR" ]; then',
    '  mkdir -p "$DIR"',
    'fi',
    'TPL="$DIR/.stress_template.jpg"',
    'if [ ! -f "$TPL" ]; then',
    '  printf "\\377\\330\\377\\331" > "$TPL"',
    'fi',
    'i="$START"',
    'while [ "$i" -le "$END" ]; do',
    '  ts=$(printf "%013d" "$i")',
    '  cp "$TPL" "$DIR/${PREFIX}_${ts}_image.jpg"',
    '  i=$((i + 1))',
    'done'
) -join "`n"

[System.IO.File]::WriteAllText($localScript, $scriptBody, [System.Text.Encoding]::ASCII)
Invoke-Adb -Adb $adb -CommandArgs @("push", $localScript, $remoteScript) | Out-Null
Invoke-Adb -Adb $adb -CommandArgs @("shell", "chmod", "755", $remoteScript) | Out-Null
Invoke-AdbShell -Adb $adb -ShellCommand "mkdir -p '$encryptDir'; find '$encryptDir' -maxdepth 1 -type f -name '${FilePrefix}_*_image.jpg' -delete; rm -f '$encryptDir/.stress_template.jpg'" | Out-Null

Write-Host "Generating $Count synthetic backlog files in $encryptDir"
$start = 1
while ($start -le $Count) {
    $end = [Math]::Min($start + $ChunkSize - 1, $Count)
    $genCmd = "sh '$remoteScript' '$encryptDir' $start $end '$FilePrefix'"
    Invoke-AdbShell -Adb $adb -ShellCommand $genCmd | Out-Null
    Write-Host "Generated files: $end / $Count"
    $start = $end + 1
}

$created = Get-RemoteFileCount -Adb $adb -RemoteDir $encryptDir -Prefix $FilePrefix
Write-Host "Synthetic files present: $created"
if ($created -lt $Count) {
    throw "Generation incomplete: expected $Count files, found $created."
}

if ($TriggerUpload) {
    $wifiFlag = if ($ContinueWithoutWifi) { "true" } else { "false" }
    Write-Host "Triggering foreground upload service..."
    try {
        Invoke-Adb -Adb $adb -CommandArgs @(
            "shell", "am", "start-foreground-service",
            "-n", "$PackageName/com.screenomics.UploadService",
            "--es", "dirPath", $encryptDir,
            "--ez", "continueWithoutWifi", $wifiFlag
        ) | Out-Null
    } catch {
        Write-Warning "Direct UploadService start is blocked (service not exported). Launching app as fallback."
        Invoke-Adb -Adb $adb -CommandArgs @(
            "shell", "am", "start",
            "-n", "$PackageName/com.screenomics.MainActivity"
        ) | Out-Null
        Write-Warning "Open the app and tap Upload (or wait for auto-upload)."
    }
}

if ($MonitorMinutes -gt 0) {
    Write-Host "Monitoring drain for up to $MonitorMinutes minute(s)..."
    $deadline = (Get-Date).AddMinutes($MonitorMinutes)
    $last = Get-RemoteFileCount -Adb $adb -RemoteDir $encryptDir -Prefix $FilePrefix
    $stagnant = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        $current = Get-RemoteFileCount -Adb $adb -RemoteDir $encryptDir -Prefix $FilePrefix
        $delta = $last - $current
        Write-Host ("[{0}] remaining={1} drained={2}" -f (Get-Date -Format "HH:mm:ss"), $current, $delta)

        if ($current -le 0) {
            Write-Host "Backlog drained successfully."
            break
        }

        if ($delta -le 0) {
            $stagnant++
        } else {
            $stagnant = 0
        }

        if ($TriggerUpload -and $stagnant -ge 3) {
            Write-Host "No drain progress for 3 polls; retriggering upload service."
            $wifiFlag = if ($ContinueWithoutWifi) { "true" } else { "false" }
            try {
                Invoke-Adb -Adb $adb -CommandArgs @(
                    "shell", "am", "start-foreground-service",
                    "-n", "$PackageName/com.screenomics.UploadService",
                    "--es", "dirPath", $encryptDir,
                    "--ez", "continueWithoutWifi", $wifiFlag
                ) | Out-Null
            } catch {
                Write-Warning "UploadService cannot be started directly from adb on this build. Use in-app Upload button."
            }
            $stagnant = 0
        }

        $last = $current
    }
}

Write-Host "Done."
Write-Host "Tip: inspect logs with:"
Write-Host "$adb logcat -d -s SCREENOMICS_UPLOAD A11yCaptureService"
