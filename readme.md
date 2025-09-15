# UW Madison ASSIST Lab ScreenLife Capture Android App

This repository contains the Android application for the ScreenLife Capture research study conducted by the ASSIST Lab at University of Wisconsin-Madison. The application enables participants to automatically record screenshots, capture location data, and record video at specified intervals for behavioral research purposes.

## Project Overview

**App Name:** UW ASSIST Lab ScreenLife
**Package ID:** edu.wisc.chm.screenomics
**Current Version:** 1.14 (Build 16)
**Target SDK:** 33
**Min SDK:** 29  

### Key Features

- **Automated Screenshot Capture**: Takes screenshots at configurable intervals
- **Location Tracking**: Records GPS coordinates every 10 seconds
- **Video Recording**: *Currently disabled - will be re-enabled in future version*
- **Secure Data Upload**: Encrypted data transmission to research servers
- **Background Operation**: Runs continuously as a foreground service
- **Batch Processing**: Groups data for efficient network transmission

### Current Server Integration

The application integrates with the research server:
- **Server Response Logging**: Logs all server responses for debugging
- **Batch Upload**: Sends data in configurable batch sizes (default: 10 items)

**Note**: Currently experiencing server error 500 responses. Issue appears to be server-side.



## Architecture Overview

### Core Activities

| Activity | Purpose | Features |
|----------|---------|----------|
| `MainActivity` | Primary user interface with tabbed layout | Start/stop capture, view status, access logs |
| `RegisterActivity` | User registration and key generation | Participant name entry, encryption key creation |
| `DevToolsActivity` | Configuration and debugging tools | Batch size adjustment, parallel upload settings |

### Background Services

| Service | Type | Purpose |
|---------|------|---------|
| `CaptureService` | Foreground (MediaProjection) | Screenshot capture and app tracking |
| `LocationService` | Foreground (Location) | GPS coordinate recording |
| `VideoCaptureService` | Foreground (Camera) | *Disabled - to be re-enabled in future version* |
| `UploadService` | Background | Batch data upload to server |

### Core Components

| Component | Purpose |
|-----------|---------|
| `Constants` | Application-wide configuration constants |
| `Batch` | Data structure for grouped file uploads |
| `Encryptor` | Data encryption and security utilities |
| `Logger` | SharedPreferences-based logging system |
| `UploadScheduler` | Automated upload timing and scheduling |
| `SenderWorker` | Background work manager for data transmission |
| `LocationWorker` | Kotlin-based location tracking worker |
| `InternetConnection` | Network connectivity and type detection |
| `Converter` | Data format conversion utilities |

### User Interface Fragments

| Fragment | Purpose |
|----------|---------|
| `ScreenLifeFragment` | Screenshot capture controls and status |
| `MindPulseFragment` | *Currently hidden - to be re-enabled in future version* |



## Configuration

### Application Constants

The `Constants.java` file contains key configuration parameters:

| Constant | Value | Purpose |
|----------|--------|---------|
| `UPLOAD_ADDRESS` | *Configured in Constants.java* | Primary data upload endpoint |
| `BATCH_SIZE_DEFAULT` | `10` | Number of items per upload batch |
| `MAX_TO_SEND_DEFAULT` | `0` | Maximum items to send (0 = no limit) |
| `MAX_BATCHES_TO_SEND` | `10` | Maximum concurrent batch uploads |
| `REQ_TIMEOUT` | `1200` | Network request timeout (seconds) |

### Permissions

The application requires extensive permissions for data collection:

#### Core Permissions
- `INTERNET` - Network data upload
- `WAKE_LOCK` - Prevent device sleep during capture
- `FOREGROUND_SERVICE` - Background operation
- `SYSTEM_ALERT_WINDOW` - Overlay interface elements

#### Data Collection Permissions
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` - Screenshot capture
- ~~`FOREGROUND_SERVICE_CAMERA`~~ - *Video recording (disabled)*
- `ACCESS_FINE_LOCATION` / `ACCESS_BACKGROUND_LOCATION` - GPS tracking
- `PACKAGE_USAGE_STATS` - App usage tracking
- ~~`CAMERA` / `RECORD_AUDIO`~~ - *Video/audio capture (disabled)*

#### Storage & System
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` - File operations
- `RECEIVE_BOOT_COMPLETED` - Auto-start after device reboot
- `SCHEDULE_EXACT_ALARM` - Precise timing for data collection

## Development Setup

### Requirements
- Android Studio
- Gradle 7.0+
- Android SDK 29+
- Google Services (Firebase integration)

### Build Configuration
- **Compile SDK:** 34
- **Target SDK:** 33
- **Min SDK:** 29
- **Java Version:** 1.8
- **Kotlin Support:** Enabled

### Key Dependencies
- AndroidX libraries
- Google Play Services
- Firebase Crashlytics
- Joda Time for date/time operations
- WorkManager for background tasks

## Security & Privacy

- **Data Encryption**: All captured data is encrypted using the `Encryptor` class
- **No Backup**: Backup is disabled (`android:allowBackup="false"`)
- **Secure Storage**: Uses Android's secure storage mechanisms
- **Network Security**: HTTPS-only communication with research servers

## Version Notes

### v1.15 Changes (Development)
- **Video Recording Restored**: Full video recording functionality re-enabled
- **Recording Timer**: Added real-time duration display (MM:SS format)
- **Audio Volume Indicator**: Visual feedback showing microphone input levels
  - Green to yellow to red gradient indicating volume intensity
  - Real-time monitoring at 100ms intervals
- **Enhanced UI**: Improved recording interface with live feedback
- Camera and audio permissions restored
- MindPulse tab re-enabled with enhanced features

### v1.14 Changes (Previous)
- Video recording temporarily disabled for production release
- MindPulse features hidden from interface

