# Upload Stress Test (100,000 files)

This procedure creates a large synthetic backlog on a connected Android device and verifies upload drain behavior.

## Prerequisites

- ADB installed (`platform-tools`) and device visible in `adb devices`
- App installed and enrolled on device
- Device on charger and stable network
- Recommended: run against staging backend first

## 1) Generate Backlog + Trigger Upload + Monitor

From repo root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\stress\run_upload_stress.ps1 `
  -PackageName "edu.wisc.chm.screenomics" `
  -Count 100000 `
  -ChunkSize 2000 `
  -FilePrefix "stress" `
  -TriggerUpload `
  -ContinueWithoutWifi $true `
  -MonitorMinutes 180 `
  -PollSeconds 60
```

Notes:

- Files are created in `/sdcard/Android/data/<package>/files/encrypt`
- Generated filenames are `stress_<timestamp>_image.jpg` so they flow through current upload path
- Script retriggers `UploadService` automatically if drain progress stalls

## 2) Observe Upload Logs

```powershell
adb logcat -d -s SCREENOMICS_UPLOAD A11yCaptureService
```

Look for:

- `Backlog drain mode enabled`
- `Progress: ...`
- Decreasing remaining synthetic file count in script output

## 3) Cleanup Synthetic Files

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\stress\cleanup_stress_files.ps1 `
  -PackageName "edu.wisc.chm.screenomics" `
  -FilePrefix "stress"
```

## Optional Parameters

- `-AdbPath "C:\Android\platform-tools\adb.exe"` if `adb` is not in PATH
- `-DeviceSerial "<serial>"` if multiple devices are connected
