$ErrorActionPreference = "Stop"

Write-Host "============================" -ForegroundColor Green
Write-Host "Android Build & Install Script" -ForegroundColor Green
Write-Host "============================" -ForegroundColor Green

$binDir = "c:\Mickey\Phone-Mirror\bin"
$jdkDir = "$binDir\jdk-21"
$sdkDir = "$binDir\android-sdk"
$gradleDir = "$binDir\gradle-8.2.1"

# 1. Verify Prerequisites
if (!(Test-Path "$jdkDir\bin\javac.exe")) {
    Write-Error "Portable JDK 21 not found at $jdkDir. Please run setup_env.ps1 first."
}
if (!(Test-Path "$sdkDir\platforms\android-34")) {
    Write-Error "Android SDK Platform 34 not found at $sdkDir. Please run setup_env.ps1 first."
}

# 2. Download and Setup Gradle if needed
if (!(Test-Path "$gradleDir\bin\gradle.bat")) {
    Write-Host "[*] Gradle not found. Downloading Gradle 8.2.1..." -ForegroundColor Yellow
    $gradleUrl = "https://services.gradle.org/distributions/gradle-8.2.1-bin.zip"
    $gradleZip = "$env:TEMP\gradle-8.2.1.zip"
    Invoke-WebRequest -Uri $gradleUrl -OutFile $gradleZip
    Write-Host "[*] Extracting Gradle..." -ForegroundColor Yellow
    Expand-Archive -Path $gradleZip -DestinationPath $binDir -Force
    Write-Host "[+] Gradle set up at $gradleDir" -ForegroundColor Green
} else {
    Write-Host "[+] Gradle is already set up at $gradleDir" -ForegroundColor Green
}

# 3. Configure Environments
$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$origPath = $env:PATH
$env:PATH = "$jdkDir\bin;$gradleDir\bin;$binDir\platform-tools;$origPath"

# 4. Build APK
Write-Host "[*] Building Android project in c:\Mickey\Phone-Mirror\android..." -ForegroundColor Yellow
Push-Location "c:\Mickey\Phone-Mirror\android"
try {
    & "$gradleDir\bin\gradle.bat" clean assembleDebug
    Write-Host "[+] Android build successful!" -ForegroundColor Green
} catch {
    Pop-Location
    $env:PATH = $origPath
    Write-Error "Android build failed."
}
Pop-Location

# 5. Install APK to connected device
Write-Host "[*] Looking for connected Android devices..." -ForegroundColor Yellow
$adbOut = & "$binDir\platform-tools\adb.exe" devices
Write-Host $adbOut

$hasDevice = $false
foreach ($line in $adbOut) {
    if ($line -match "\bdevice\b" -and $line -notmatch "List of devices attached") {
        $hasDevice = $true
        break
    }
}

$apkPath = "c:\Mickey\Phone-Mirror\android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    if ($hasDevice) {
        Write-Host "[*] Installing APK to device..." -ForegroundColor Yellow
        & "$binDir\platform-tools\adb.exe" install -r $apkPath
        Write-Host "[+] APK installed successfully!" -ForegroundColor Green
    } else {
        Write-Warning "No connected Android devices found. APK built successfully but not installed."
        Write-Host "Please connect your Android device with USB Debugging enabled, then run:" -ForegroundColor Cyan
        Write-Host "  c:\Mickey\Phone-Mirror\bin\platform-tools\adb.exe install -r $apkPath" -ForegroundColor Cyan
    }
} else {
    Write-Error "Compiled APK not found at $apkPath."
}

# Restore Path
$env:PATH = $origPath
Write-Host "=============================" -ForegroundColor Green
Write-Host "Android Build & Install Done!" -ForegroundColor Green
Write-Host "=============================" -ForegroundColor Green
