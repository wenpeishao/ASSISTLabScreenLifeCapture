@echo off
title Building UW Madison Screenomics - Final Release
color 0A

echo =====================================================
echo UW Madison Screenomics - RELEASE BUILD v1.15
echo =====================================================
echo.
echo Package: edu.wisc.chm.screenomics
echo Version: 1.15 (versionCode: 17)
echo.
echo CHANGES IN THIS VERSION:
echo [X] Fixed screenshot stopping after 10 minutes
echo [X] Added WakeLock for reliable background capture
echo [X] Added battery optimization bypass prompt
echo [X] Added Usage Access permission prompt
echo [X] Added notification when capture stops
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
echo File: app\build\outputs\bundle\standardRelease\app-standard-release.aab
echo Package: edu.wisc.chm.screenomics
echo Version: 1.15 (Build 17)
echo.
echo UPLOAD TO GOOGLE PLAY:
echo ----------------------
echo 1. Go to: https://play.google.com/console
echo 2. Select your app
echo 3. Production ^> Create new release
echo 4. Upload the AAB file
echo 5. Add release notes
echo.
pause