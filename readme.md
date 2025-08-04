# ScreenLife Capture Android App

This repo contains the Android Application used in the ScreenLife Capture study. The application allows participants to record and upload screenshots taken every X number of seconds. The general layout of the code is explained below.

### Mindpulse Endpoint Integration (Updated)

This version of the application has been updated to integrate with the Mindpulse Endpoint POC server. The following changes have been made:

*   **Server URL Update**: The application now sends data to new URL.

*   **Server Response Logging**: The application now logs the server's response after each upload attempt, which can be viewed by clicking the "Logs" button in the app.

**Note**: Currently experiencing server error 500 responses. Issue appears to be server-side.



### Activities

| Activity Name    | Purpose                                                      |
| ---------------- | ------------------------------------------------------------ |
| RegisterActivity | Handles registration for new users.                          |
| MainActivity     | Contains the main interface of the app, allowing participants to start/stop the screen capture. |
| DevToolsActivity | Contains tools to tweak how the app works, including the number of images to send per batch, the number of batches to send in parallel etc. |

### Service

| Service Name   | Purpose                                                      |
| -------------- | ------------------------------------------------------------ |
| CaptureService | Responsible for capturing screenshots and foreground app name every X number of seconds. Runs continously throughout the duration of the study. |
| UploadService  | Responsible for uploading of screenshots to the cloud functions. Is triggered at certain times by `UploadScheduler` |
| LocationService| Responsible for capturing GPS coordinates every 10 seconds, recording them in JSON files.  |

### Other Files

| File Name          | Purpose                                                      |
| ------------------ | ------------------------------------------------------------ |
| Constants          | Contains the constants used throughout the application.      |
| Batch              | Contains a "batch" of files, used by `UploadService`.        |
| Encryptor          | Used during the encryption process by `CaptureService`.      |
| InfoDialog         | The dialog that is shown when the "information" button is pressed on the main activity. |
| InternetConnection | A set of functions to check if the device is connected to the internet, and through what type of connection (WiFi vs mobile data). |
| Logger             | Utility functions to save logs to SharedPreferences.         |
| UploadScheduler    | Schedules the `UploadService` at certain times a day.        |



## Constants

Most app-related constants are located in the `Constants` file. The constants are explained below.

| Constant Name       | Explanation                                   |
| ------------------- | --------------------------------------------- |
| REGISTER_ADDRESS    | The address of the "register" cloud function. |
| UPLOAD_ADDRESS      | The address of the "upload" cloud function.   |
| COUNT_ADDRESS       | The address of the "count" cloud function.    |
| BATCH_SIZE_DEFAULT  | TODO                                          |
| MAX_TO_SEND_DEFAULT | TODO                                          |
| MAX_BATCHES_TO_SEND | TODO                                          |
| REQ_TIMEOUT         | TODO                                          |

