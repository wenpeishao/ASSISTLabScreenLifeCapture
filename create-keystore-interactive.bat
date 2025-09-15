@echo off
color 0A
echo =========================================
echo NDScreenomics - Create Release Keystore
echo =========================================
echo.
echo This script will create your app signing keystore.
echo.
pause

REM Find keytool
echo Searching for Java keytool...
echo.

set KEYTOOL=
if exist "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" (
    set KEYTOOL="C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
    echo [FOUND] Android Studio JDK at:
    echo C:\Program Files\Android\Android Studio\jbr\bin\
    echo.
) else (
    echo [ERROR] Could not find keytool!
    echo.
    echo Please ensure Android Studio is installed, or
    echo install Java JDK from: https://adoptium.net/
    echo.
    pause
    exit
)

REM Check if keystore exists
if exist "app\release-keystore.jks" (
    echo [WARNING] Keystore already exists!
    echo Location: app\release-keystore.jks
    echo.
    echo If you continue, you'll overwrite the existing keystore.
    echo Press Ctrl+C to cancel, or...
    pause
    del "app\release-keystore.jks"
)

echo =========================================
echo STEP 1: Enter Your Information
echo =========================================
echo.
echo Press Enter to use [default values]
echo.

set /p NAME=Your name or company [NDScreenomics]: 
if "%NAME%"=="" set NAME=NDScreenomics

set /p UNIT=Department [Development]: 
if "%UNIT%"=="" set UNIT=Development

set /p ORG=Organization [University of Notre Dame]: 
if "%ORG%"=="" set ORG=University of Notre Dame

set /p CITY=City [Notre Dame]: 
if "%CITY%"=="" set CITY=Notre Dame

set /p STATE=State [Indiana]: 
if "%STATE%"=="" set STATE=Indiana

set /p COUNTRY=Country Code - 2 letters [US]: 
if "%COUNTRY%"=="" set COUNTRY=US

echo.
echo =========================================
echo STEP 2: Set Passwords (IMPORTANT!)
echo =========================================
echo.
echo Choose strong passwords (minimum 6 characters)
echo You'll need these for EVERY release!
echo.

:password1
set /p PASS1=Enter keystore password: 
if "%PASS1%"=="" (
    echo Password cannot be empty! Try again.
    goto password1
)

set /p PASS2=Confirm keystore password: 
if not "%PASS1%"=="%PASS2%" (
    echo Passwords don't match! Try again.
    echo.
    goto password1
)

echo.
echo Keystore password set!
echo.

:password2
set /p KEYPASS1=Enter key password (can be same as above): 
if "%KEYPASS1%"=="" (
    echo Password cannot be empty! Try again.
    goto password2
)

set /p KEYPASS2=Confirm key password: 
if not "%KEYPASS1%"=="%KEYPASS2%" (
    echo Passwords don't match! Try again.
    echo.
    goto password2
)

echo.
echo =========================================
echo STEP 3: Creating Keystore
echo =========================================
echo.
echo Generating with your information...
echo Name: %NAME%
echo Organization: %ORG%
echo Location: %CITY%, %STATE%, %COUNTRY%
echo.

%KEYTOOL% -genkey -v -keystore "app\release-keystore.jks" -keyalg RSA -keysize 2048 -validity 10000 -alias release-key -storepass "%PASS1%" -keypass "%KEYPASS1%" -dname "CN=%NAME%, OU=%UNIT%, O=%ORG%, L=%CITY%, ST=%STATE%, C=%COUNTRY%"

if exist "app\release-keystore.jks" (
    echo.
    echo [SUCCESS] Keystore created!
    echo.
    echo =========================================
    echo STEP 4: Creating signing.properties
    echo =========================================
    echo.
    
    (
        echo # Signing configuration for release builds
        echo # DO NOT commit this file to version control!
        echo.
        echo storeFile=release-keystore.jks
        echo storePassword=%PASS1%
        echo keyAlias=release-key
        echo keyPassword=%KEYPASS1%
    ) > signing.properties
    
    echo [SUCCESS] signing.properties created!
    echo.
    echo =========================================
    echo IMPORTANT - SAVE THIS INFORMATION!
    echo =========================================
    echo.
    echo Keystore Location: app\release-keystore.jks
    echo Keystore Password: %PASS1%
    echo Key Password: %KEYPASS1%
    echo Key Alias: release-key
    echo.
    echo WRITE THESE DOWN AND STORE SECURELY!
    echo You'll need them for all future updates!
    echo.
    echo =========================================
    echo NEXT STEPS:
    echo =========================================
    echo.
    echo 1. Back up app\release-keystore.jks
    echo 2. Run: gradlew clean
    echo 3. Run: gradlew bundleRelease
    echo 4. Find AAB at: app\build\outputs\bundle\release\
    echo.
) else (
    echo.
    echo [ERROR] Failed to create keystore!
    echo Please try again or create manually.
    echo.
)

echo Press any key to exit...
pause >nul