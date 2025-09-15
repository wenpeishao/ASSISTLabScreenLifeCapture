@echo off
title Building UW Madison Screenomics - Final Release
color 0A

echo =====================================================
echo UW Madison Screenomics - FINAL RELEASE BUILD v1.0
echo =====================================================
echo.
echo Package: edu.wisc.chm.screenomics
echo Version: 1.0 (versionCode: 1)
echo.
echo CHANGES APPLIED:
echo [X] Package name changed to UW Madison
echo [X] MindPulse/Video recording HIDDEN (not IRB approved)
echo [X] Video reminders DISABLED on boot
echo [X] Only ScreenLife tab visible
echo.
echo The video code remains intact for future updates
echo when IRB approval is obtained.
echo.
pause

REM Set Java path for Android Studio
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

echo =====================================================
echo Step 1: Cleaning all previous builds
echo =====================================================
call gradlew.bat clean

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Clean failed!
    pause
    exit
)

echo.
echo =====================================================
echo Step 2: Building Release Bundle (AAB)
echo =====================================================
echo Building with video features HIDDEN...
echo This may take several minutes...
echo.

call gradlew.bat bundleRelease

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed!
    echo Check the error messages above.
    pause
    exit
)

echo.
echo =====================================================
echo BUILD SUCCESSFUL!
echo =====================================================
echo.
echo RELEASE READY FOR GOOGLE PLAY:
echo --------------------------------
echo File: app\build\outputs\bundle\release\app-release.aab
echo Package: edu.wisc.chm.screenomics
echo Version: 1.0
echo.
echo FEATURES STATUS:
echo ----------------
echo [✓] ScreenLife recording: ENABLED
echo [✗] MindPulse video: HIDDEN (code preserved)
echo [✗] Video reminders: DISABLED
echo.
echo TO RE-ENABLE VIDEO FEATURES LATER:
echo -----------------------------------
echo 1. Edit MainPagerAdapter.java
echo 2. Set MINDPULSE_ENABLED = true
echo 3. Uncomment VideoReminderScheduler.onReceive()
echo 4. Rebuild the app
echo.
echo UPLOAD TO GOOGLE PLAY:
echo ----------------------
echo 1. Go to: https://play.google.com/console
echo 2. Create NEW app (not an update)
echo 3. Upload the AAB file
echo 4. Complete store listing
echo.
pause