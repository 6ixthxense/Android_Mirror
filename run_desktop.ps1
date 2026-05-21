$ErrorActionPreference = "Stop"

Write-Host "==========================" -ForegroundColor Green
Write-Host "Tauri Desktop Launcher" -ForegroundColor Green
Write-Host "==========================" -ForegroundColor Green

# 1. Update PATH to include Rust binaries if not visible yet
$cargoPath = "$env:USERPROFILE\.cargo\bin"
if (Test-Path $cargoPath) {
    if ($env:PATH -notlike "*$cargoPath*") {
        $env:PATH = "$cargoPath;$env:PATH"
        Write-Host "[+] Added $cargoPath to environment PATH for this session." -ForegroundColor Green
    }
}

# Verify cargo works
if (!(Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Error "Rust/Cargo is not detected. Please run setup_env.ps1 first and make sure it completes."
} else {
    Write-Host "[+] Cargo is ready: $(cargo --version)" -ForegroundColor Green
}

# 2. Add local platform-tools (ADB) to PATH for this session
$adbDir = "c:\Mickey\Phone-Mirror\bin\platform-tools"
if (Test-Path $adbDir) {
    if ($env:PATH -notlike "*$adbDir*") {
        $env:PATH = "$adbDir;$env:PATH"
        Write-Host "[+] Added $adbDir to environment PATH." -ForegroundColor Green
    }
}

# 3. Launch Tauri
Write-Host "[*] Launching Tauri Desktop Dev Server..." -ForegroundColor Yellow
Push-Location "c:\Mickey\Phone-Mirror\desktop"
try {
    # Run tauri dev inside the MSVC x86_x64 developer environment to bypass corrupted HostX64 compiler
    cmd.exe /c 'call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x86_amd64 && npm run tauri dev'
} finally {
    Pop-Location
}
