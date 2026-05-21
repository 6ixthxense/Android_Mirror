use std::io::Write;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use tauri::State;

// Global state holding the persistent input injector process and active stream
struct InjectorState {
    child: Mutex<Option<Child>>,
    stream: Arc<Mutex<Option<std::net::TcpStream>>>,
    audio_stream: Arc<Mutex<Option<std::net::TcpStream>>>,
}

// Helper to run ADB with a specific device
fn run_adb(args: &[&str], device_id: &Option<String>) -> Result<String, String> {
    let adb_path = "c:/Mickey/Phone-Mirror/bin/platform-tools/adb.exe";
    let mut cmd = Command::new(adb_path);
    
    if let Some(id) = device_id {
        if !id.trim().is_empty() {
            cmd.arg("-s").arg(id);
        }
    }
    cmd.args(args);

    #[cfg(target_os = "windows")]
    use std::os::windows::process::CommandExt;
    #[cfg(target_os = "windows")]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW: prevent console window popups on Windows

    match cmd.output() {
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout).to_string();
            let stderr = String::from_utf8_lossy(&output.stderr).to_string();
            if output.status.success() {
                Ok(stdout)
            } else {
                Err(format!("ADB error: {}\n{}", stderr, stdout))
            }
        }
        Err(e) => Err(format!("Failed to execute ADB (path: {}): {}", adb_path, e)),
    }
}

// Command: Get list of connected devices
#[tauri::command]
fn get_devices() -> Result<Vec<String>, String> {
    let output = run_adb(&["devices"], &None)?;
    let mut devices = Vec::new();
    
    // Parse adb devices output
    for line in output.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() == 2 && parts[1] == "device" {
            devices.push(parts[0].to_string());
        }
    }
    Ok(devices)
}

// Command: Install and start mirror capture on Android, then launch the input injector and stream video frames
#[tauri::command]
fn start_mirroring(
    device_id: Option<String>,
    bitrate: i32,
    fps: i32,
    max_res: i32,
    on_frame: tauri::ipc::Channel<Vec<u8>>,
    on_audio: tauri::ipc::Channel<Vec<u8>>,
    state: State<'_, InjectorState>,
) -> Result<String, String> {
    let dev_id = device_id.clone();
    
    // 1. Forward TCP port 8080 (video) and 8081 (audio)
    run_adb(&["forward", "tcp:8080", "tcp:8080"], &dev_id)?;
    run_adb(&["forward", "tcp:8081", "tcp:8081"], &dev_id)?;
    println!("[Rust] Port forwards established: tcp:8080, tcp:8081");

    // 2. Launch the MainActivity with quality configurations passed as intent extras
    run_adb(&[
        "shell", "am", "start",
        "-n", "com.mickey.phonemirror/.MainActivity",
        "--ei", "bitrate", &bitrate.to_string(),
        "--ei", "fps", &fps.to_string(),
        "--ei", "max_res", &max_res.to_string()
    ], &dev_id)?;
    println!("[Rust] MainActivity launched to request screen projection permission with config: bitrate={}, fps={}, max_res={}", bitrate, fps, max_res);

    // 3. Find the path of the installed APK to run the input injector
    let path_output = run_adb(&["shell", "pm", "path", "com.mickey.phonemirror"], &dev_id)?;
    let apk_path = path_output
        .trim()
        .strip_prefix("package:")
        .ok_or_else(|| format!("Failed to parse APK path from: {}", path_output))?;
    println!("[Rust] Found APK path: {}", apk_path);

    // 4. Kill previous injector and stream if any
    let _ = stop_mirroring(state.clone());

    // 5. Spawn the persistent adb shell app_process injector
    let adb_path = "c:/Mickey/Phone-Mirror/bin/platform-tools/adb.exe";
    let mut cmd = Command::new(adb_path);
    if let Some(ref id) = dev_id {
        if !id.trim().is_empty() {
            cmd.arg("-s").arg(id);
        }
    }
    cmd.args(&[
        "shell",
        &format!("CLASSPATH={} app_process / com.mickey.phonemirror.InputInjector", apk_path)
    ]);
    cmd.stdin(Stdio::piped());
    cmd.stdout(Stdio::piped()); // We can read logs if needed, but piped avoids block
    cmd.stderr(Stdio::null());

    #[cfg(target_os = "windows")]
    use std::os::windows::process::CommandExt;
    #[cfg(target_os = "windows")]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    let child = cmd.spawn().map_err(|e| format!("Failed to spawn InputInjector process: {}", e))?;
    
    // Save injector child
    {
        let mut guard = state.child.lock().unwrap();
        *guard = Some(child);
    }
    println!("[Rust] InputInjector process spawned successfully");

    // Clone the Arc<Mutex> for the stream so the background thread can store
    // the connected stream (State is not Send, so we can't move it into the thread).
    let stream_state = Arc::clone(&state.stream);

    // 6+7. Spawn background thread for TCP connection retry + stream reading.
    // This prevents the UI from freezing while waiting for the user to approve
    // the MediaProjection permission dialog on the phone (up to 20 seconds).
    std::thread::spawn(move || {
        // 6. Connect to the local TCP port 8080 forwarded over ADB
        let mut stream = None;
        let mut resolution_buf = [0u8; 8];
        for attempt in 1..=20 {
            println!("[Rust] Connection attempt {}/20 to localhost:8080...", attempt);
            match std::net::TcpStream::connect("127.0.0.1:8080") {
                Ok(mut s) => {
                    let _ = s.set_read_timeout(Some(std::time::Duration::from_millis(1500)));

                    use std::io::Read;
                    match s.read_exact(&mut resolution_buf) {
                        Ok(_) => {
                            println!("[Rust] Real connection established, resolution header received: {:?}", resolution_buf);
                            let _ = s.set_read_timeout(None);
                            stream = Some(s);
                            break;
                        }
                        Err(e) => {
                            println!("[Rust] Connection check failed (ADB dummy connect or not ready): {}. Retrying...", e);
                            let _ = s.shutdown(std::net::Shutdown::Both);
                            std::thread::sleep(std::time::Duration::from_millis(1000));
                        }
                    }
                }
                Err(e) => {
                    if attempt == 20 {
                        println!("[Rust] Failed to connect to forwarded TCP port 8080 after 20 attempts. Error: {}", e);
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(1000));
                }
            }
        }

        let stream = match stream {
            Some(s) => s,
            None => {
                println!("[Rust] Failed to establish connection: Phone mirror service did not respond.");
                return;
            }
        };
        println!("[Rust] Connected to TCP socket on localhost:8080");

        // Save a clone of the TCP stream so stop_mirroring can shut it down
        {
            if let Ok(cloned) = stream.try_clone() {
                let mut stream_guard = stream_state.lock().unwrap();
                *stream_guard = Some(cloned);
            }
        }

        // Send the resolution header to the React channel first
        if let Err(e) = on_frame.send(resolution_buf.to_vec()) {
            println!("[Rust] Failed to send resolution header to channel: {}", e);
            return;
        }

        // 7. Stream bytes to React frontend via Channel
        use std::io::Read;
        let mut thread_stream = stream;
        let mut buf = vec![0u8; 65536]; // 64KB read buffer
        loop {
            match thread_stream.read(&mut buf) {
                Ok(0) => {
                    println!("[Rust] TCP stream EOF. Exiting stream thread.");
                    break;
                }
                Ok(n) => {
                    if let Err(e) = on_frame.send(buf[..n].to_vec()) {
                        println!("[Rust] Channel closed or send failed: {}. Exiting stream thread.", e);
                        break;
                    }
                }
                Err(e) => {
                    println!("[Rust] TCP read error: {}. Exiting stream thread.", e);
                    break;
                }
            }
        }
        let _ = thread_stream.shutdown(std::net::Shutdown::Both);
    });

    // Spawn background thread for Audio TCP connection retry + stream reading
    let audio_stream_state = Arc::clone(&state.audio_stream);
    std::thread::spawn(move || {
        let mut stream = None;
        for attempt in 1..=20 {
            println!("[Rust] Audio connection attempt {}/20 to localhost:8081...", attempt);
            match std::net::TcpStream::connect("127.0.0.1:8081") {
                Ok(s) => {
                    println!("[Rust] Audio connection established on localhost:8081");
                    stream = Some(s);
                    break;
                }
                Err(e) => {
                    if attempt == 20 {
                        println!("[Rust] Failed to connect to forwarded audio TCP port 8081 after 20 attempts. Error: {}", e);
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(1000));
                }
            }
        }

        let stream = match stream {
            Some(s) => s,
            None => {
                println!("[Rust] Failed to establish audio connection.");
                return;
            }
        };

        // Save a clone of the TCP stream so stop_mirroring can shut it down
        {
            if let Ok(cloned) = stream.try_clone() {
                let mut stream_guard = audio_stream_state.lock().unwrap();
                *stream_guard = Some(cloned);
            }
        }

        // Stream audio bytes to React frontend via Channel
        use std::io::Read;
        let mut thread_stream = stream;
        let mut buf = vec![0u8; 16384]; // 16KB read buffer for low latency
        loop {
            match thread_stream.read(&mut buf) {
                Ok(0) => {
                    println!("[Rust] Audio TCP stream EOF. Exiting audio stream thread.");
                    break;
                }
                Ok(n) => {
                    if let Err(e) = on_audio.send(buf[..n].to_vec()) {
                        println!("[Rust] Audio channel closed or send failed: {}. Exiting audio stream thread.", e);
                        break;
                    }
                }
                Err(e) => {
                    println!("[Rust] Audio TCP read error: {}. Exiting audio stream thread.", e);
                    break;
                }
            }
        }
        let _ = thread_stream.shutdown(std::net::Shutdown::Both);
    });

    Ok("Mirroring initialization started".to_string())
}

// Command: Stop mirroring and stop the injector process and stream
#[tauri::command]
fn stop_mirroring(state: State<'_, InjectorState>) -> Result<String, String> {
    let mut guard = state.child.lock().unwrap();
    if let Some(mut child) = guard.take() {
        let _ = child.kill();
        println!("[Rust] Killed InputInjector process");
    }
    let mut stream_guard = state.stream.lock().unwrap();
    if let Some(stream) = stream_guard.take() {
        let _ = stream.shutdown(std::net::Shutdown::Both);
        println!("[Rust] Shutdown active TCP stream");
    }
    let mut audio_stream_guard = state.audio_stream.lock().unwrap();
    if let Some(audio_stream) = audio_stream_guard.take() {
        let _ = audio_stream.shutdown(std::net::Shutdown::Both);
        println!("[Rust] Shutdown active audio TCP stream");
    }
    Ok("Stopped successfully".to_string())
}

// Command: Write input command string to the injector process's stdin
#[tauri::command]
fn inject_event(event: String, state: State<'_, InjectorState>) -> Result<(), String> {
    let mut guard = state.child.lock().unwrap();
    if let Some(child) = guard.as_mut() {
        if let Some(stdin) = child.stdin.as_mut() {
            // Write event (e.g. "DOWN 0 100 200") to the process's standard input
            if let Err(e) = writeln!(stdin, "{}", event) {
                return Err(format!("Failed to write to injector stdin: {}", e));
            }
            let _ = stdin.flush();
            return Ok(());
        }
    }
    Err("Input injector is not running. Please start mirroring first.".to_string())
}

// Command: Enable TCP/IP mode and connect via WiFi
#[tauri::command]
async fn enable_tcpip(device_id: String) -> Result<String, String> {
    // 1. Enable tcpip 5555
    let _ = run_adb(&["tcpip", "5555"], &Some(device_id.clone()))?;
    
    // Wait for adbd to restart on tcp
    std::thread::sleep(std::time::Duration::from_millis(1500));
    
    // 2. Find IP address
    let output_str = run_adb(&["shell", "ip", "route"], &Some(device_id.clone()))?;
    let mut ip_address = String::new();
    
    for line in output_str.lines() {
        if line.contains("wlan0") && line.contains("src ") {
            let parts: Vec<&str> = line.split("src ").collect();
            if parts.len() > 1 {
                let after_src = parts[1];
                let ip = after_src.split_whitespace().next().unwrap_or("");
                ip_address = ip.to_string();
                break;
            }
        }
    }
    
    if ip_address.is_empty() {
        return Err("Could not find WiFi IP address (wlan0) on the device. Make sure it is connected to WiFi.".to_string());
    }
    
    let connect_target = format!("{}:5555", ip_address);
    
    // 3. Connect via ADB
    let _ = run_adb(&["connect", &connect_target], &None)?;
    
    Ok(connect_target)
}

// Command: Update config on the fly via ADB broadcast
#[tauri::command]
fn update_config(
    device_id: Option<String>,
    bitrate: i32,
    fps: i32,
    max_res: i32,
) -> Result<String, String> {
    println!("[Rust] Updating config: bitrate={}, fps={}, max_res={}", bitrate, fps, max_res);
    run_adb(&[
        "shell", "am", "broadcast",
        "-a", "com.mickey.phonemirror.UPDATE_CONFIG",
        "--ei", "bitrate", &bitrate.to_string(),
        "--ei", "fps", &fps.to_string(),
        "--ei", "max_res", &max_res.to_string()
    ], &device_id)?;
    Ok("Broadcast sent successfully".to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(InjectorState {
            child: Mutex::new(None),
            stream: Arc::new(Mutex::new(None)),
            audio_stream: Arc::new(Mutex::new(None)),
        })
        .invoke_handler(tauri::generate_handler![
            get_devices,
            start_mirroring,
            stop_mirroring,
            inject_event,
            enable_tcpip,
            update_config
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
