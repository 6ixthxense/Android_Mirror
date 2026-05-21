$ErrorActionPreference = "Stop"

Write-Host "=========================" -ForegroundColor Green
Write-Host "Phone-Mirror Setup Script" -ForegroundColor Green
Write-Host "=========================" -ForegroundColor Green

# 1. Install Rust
if (!(Get-Command rustc -ErrorAction SilentlyContinue)) {
    Write-Host "[*] Rust not found. Downloading Rust Installer..." -ForegroundColor Yellow
    $rustupUrl = "https://win.rustup.rs/x86_64"
    $rustupPath = "$env:TEMP\rustup-init.exe"
    Invoke-WebRequest -Uri $rustupUrl -OutFile $rustupPath
    Write-Host "[*] Running Rust installer (silent, using defaults)..." -ForegroundColor Yellow
    Start-Process -FilePath $rustupPath -ArgumentList "-y" -Wait
    Write-Host "[+] Rust installed successfully! Please reload environment variables or restart your shell to use 'cargo'." -ForegroundColor Green
} else {
    Write-Host "[+] Rust is already installed: $(rustc --version)" -ForegroundColor Green
}

# 2. Setup Directories
$binDir = "c:\Mickey\Phone-Mirror\bin"
if (!(Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir | Out-Null
    Write-Host "[+] Created bin directory at $binDir" -ForegroundColor Green
}

# 3. Download ADB
$adbPath = "$binDir\platform-tools\adb.exe"
if (!(Test-Path $adbPath)) {
    Write-Host "[*] ADB not found. Downloading Google Platform Tools..." -ForegroundColor Yellow
    $adbUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    $adbZip = "$env:TEMP\platform-tools.zip"
    Invoke-WebRequest -Uri $adbUrl -OutFile $adbZip
    Write-Host "[*] Extracting Platform Tools..." -ForegroundColor Yellow
    Expand-Archive -Path $adbZip -DestinationPath $binDir -Force
    Write-Host "[+] ADB set up at $adbPath" -ForegroundColor Green
} else {
    Write-Host "[+] ADB is already set up at $adbPath" -ForegroundColor Green
}

# 4. Setup Portable JDK 21
$jdkDir = "$binDir\jdk-21"
if (!(Test-Path "$jdkDir\bin\javac.exe")) {
    Write-Host "[*] Downloading portable JDK 21 for Gradle compatibility..." -ForegroundColor Yellow
    $jdkUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip"
    $jdkZip = "$env:TEMP\jdk-21.zip"
    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip
    Write-Host "[*] Extracting JDK 21..." -ForegroundColor Yellow
    $tempJdkExtract = "$env:TEMP\jdk-21-extract"
    if (Test-Path $tempJdkExtract) { Remove-Item -Recurse -Force $tempJdkExtract }
    Expand-Archive -Path $jdkZip -DestinationPath $tempJdkExtract -Force
    $extractedFolder = Get-ChildItem -Path $tempJdkExtract -Directory | Select-Object -First 1
    if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    Move-Item -Path $extractedFolder.FullName -Destination $jdkDir -Force
    Write-Host "[+] Portable JDK 21 set up at $jdkDir" -ForegroundColor Green
} else {
    Write-Host "[+] Portable JDK 21 is already set up at $jdkDir" -ForegroundColor Green
}

# 5. Setup Portable Android SDK
$sdkDir = "$binDir\android-sdk"
if (!(Test-Path $sdkDir)) {
    New-Item -ItemType Directory -Path $sdkDir | Out-Null
}

$sdkmanager = "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat"
if (!(Test-Path $sdkmanager)) {
    Write-Host "[*] Downloading Android Command Line Tools..." -ForegroundColor Yellow
    $cmdlineUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $cmdlineZip = "$env:TEMP\cmdline-tools.zip"
    Invoke-WebRequest -Uri $cmdlineUrl -OutFile $cmdlineZip
    Write-Host "[*] Extracting Command Line Tools..." -ForegroundColor Yellow
    $tempExtract = "$env:TEMP\cmdline-tools-extract"
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    Expand-Archive -Path $cmdlineZip -DestinationPath $tempExtract -Force
    
    $latestDir = "$sdkDir\cmdline-tools\latest"
    if (!(Test-Path "$sdkDir\cmdline-tools")) { New-Item -ItemType Directory -Path "$sdkDir\cmdline-tools" | Out-Null }
    if (Test-Path $latestDir) { Remove-Item -Recurse -Force $latestDir }
    Move-Item -Path "$tempExtract\cmdline-tools" -Destination $latestDir -Force
    Write-Host "[+] Android Command Line Tools set up at $latestDir" -ForegroundColor Green
} else {
    Write-Host "[+] Android Command Line Tools are already set up at $sdkDir\cmdline-tools\latest" -ForegroundColor Green
}

# Run sdkmanager to install platforms & build tools
Write-Host "[*] Installing Android SDK platforms & build-tools (Platform 34, Build-Tools 34.0.0)..." -ForegroundColor Yellow

# Temporarily set JAVA_HOME and path for execution
$origJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdkDir
$origPath = $env:PATH
$env:PATH = "$jdkDir\bin;$origPath"

$sdkmanagerLatest = "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat"
$sdkOpts = "--sdk_root=$sdkDir"

# Create empty repositories.cfg to suppress warnings
$dotAndroid = [System.IO.Path]::Combine($env:USERPROFILE, ".android")
if (!(Test-Path $dotAndroid)) { New-Item -ItemType Directory -Path $dotAndroid | Out-Null }
$reposCfg = [System.IO.Path]::Combine($dotAndroid, "repositories.cfg")
if (!(Test-Path $reposCfg)) { New-Item -ItemType File -Path $reposCfg -Force | Out-Null }

# Automatically accept licenses by piping 'y' to sdkmanager --licenses
Write-Host "[*] Accepting Android SDK licenses..." -ForegroundColor Yellow
$licensesCmd = "echo y | & `"$sdkmanagerLatest`" $sdkOpts --licenses"
Invoke-Expression $licensesCmd | Out-Null

# Install build-tools and platforms
Write-Host "[*] Running sdkmanager to download dependencies..." -ForegroundColor Yellow
& $sdkmanagerLatest $sdkOpts "platforms;android-34" "build-tools;34.0.0"

# Restore original env
$env:JAVA_HOME = $origJavaHome
$env:PATH = $origPath

Write-Host "====================================" -ForegroundColor Green
Write-Host "Environment Setup Completed successfully!" -ForegroundColor Green
Write-Host "====================================" -ForegroundColor Green
