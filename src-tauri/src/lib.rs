use tauri::Manager;
use std::sync::{Arc, Mutex};
use tokio::time::{sleep, Duration};
use std::io::{BufRead, BufReader};

/// Shared state: holds the server URL once it is ready and the child process
struct ServerState {
    url: Option<String>,
    child: Option<std::process::Child>,
}

/// Wait for the Java server to respond on /api/health
async fn wait_for_server(url: &str, timeout_secs: u64) -> bool {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
        .unwrap_or_default();
    let health_url = format!("{}/api/health", url);
    let deadline = tokio::time::Instant::now() + Duration::from_secs(timeout_secs);
    while tokio::time::Instant::now() < deadline {
        if let Ok(resp) = client.get(&health_url).send().await {
            if resp.status().is_success() {
                return true;
            }
        }
        sleep(Duration::from_millis(300)).await;
    }
    false
}

#[tauri::command]
fn get_server_url(state: tauri::State<Arc<Mutex<ServerState>>>) -> Option<String> {
    state.lock().unwrap().url.clone()
}

/// Locate the bundled locodrive-server.jar
/// On macOS, Tauri places resources at: <App>.app/Contents/Resources/
/// On Windows/Linux: next to the binary.
fn find_jar(app_handle: &tauri::AppHandle) -> std::path::PathBuf {
    let resource_dir = app_handle
        .path()
        .resource_dir()
        .expect("Failed to resolve resource directory");

    // Tauri 2.x places bundled resources directly in the resource dir
    let direct = resource_dir.join("locodrive-server.jar");
    if direct.exists() {
        return direct;
    }

    // Fallback: inside a "resources/" subdirectory (some platforms/versions)
    let sub = resource_dir.join("resources").join("locodrive-server.jar");
    if sub.exists() {
        return sub;
    }

    // Last resort: beside the binary (useful for `cargo tauri dev`)
    let exe = std::env::current_exe().expect("Failed to get exe path");
    let beside = exe
        .parent()
        .expect("Failed to get exe directory")
        .join("resources")
        .join("locodrive-server.jar");
    if beside.exists() {
        return beside;
    }

    // Return the direct path even if it doesn't exist (will fail with a clear message)
    direct
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(Arc::new(Mutex::new(ServerState { url: None, child: None })))
        .setup(|app| {
            let app_handle = app.handle().clone();
            let state: tauri::State<Arc<Mutex<ServerState>>> = app.state();
            let state_arc = Arc::clone(&state);

            // Check if Java is installed
            let java_check = std::process::Command::new("java")
                .arg("-version")
                .stderr(std::process::Stdio::null())
                .output();

            let java_available = match java_check {
                Ok(output) => output.status.success(),
                Err(_) => false,
            };

            if !java_available {
                let _ = tauri::WebviewWindowBuilder::new(
                    app,
                    "main",
                    tauri::WebviewUrl::App("error.html".into())
                )
                .title("LocoDrive — Java Required")
                .inner_size(600.0, 400.0)
                .resizable(false)
                .build()?;
                return Ok(());
            }

            // Locate the bundled JAR
            let jar_path = find_jar(&app_handle);
            eprintln!("[LocoDrive] JAR path: {:?}", jar_path);

            if !jar_path.exists() {
                eprintln!("[LocoDrive] ERROR: JAR not found at {:?}", jar_path);
                // Show error in window
                let _ = tauri::WebviewWindowBuilder::new(
                    app,
                    "main",
                    tauri::WebviewUrl::App("error.html".into())
                )
                .title("LocoDrive — Server Error")
                .inner_size(600.0, 400.0)
                .build()?;
                return Ok(());
            }

            // Spawn java -jar <path>
            let mut child = std::process::Command::new("java")
                .arg("-jar")
                .arg(&jar_path)
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .spawn()
                .expect("Failed to spawn Java server process");

            let stdout = child.stdout.take().expect("Failed to get stdout");
            let stderr = child.stderr.take().expect("Failed to get stderr");

            // Store the child process so we can kill it on quit
            {
                let mut st = state_arc.lock().unwrap();
                st.child = Some(child);
            }

            let state_arc_clone = Arc::clone(&state_arc);
            let handle_clone = app_handle.clone();

            // Listen for stdout from the Java server in a background thread
            std::thread::spawn(move || {
                let reader = BufReader::new(stdout);
                for line in reader.lines() {
                    if let Ok(text) = line {
                        println!("[Java Server] {}", text);
                        // Java server prints "READY http://ip:port" when ready
                        if let Some(url) = text.strip_prefix("READY ") {
                            let server_url = url.trim().to_string();
                            {
                                let mut st = state_arc_clone.lock().unwrap();
                                st.url = Some(server_url.clone());
                            }

                            // Poll until HTTP is actually responding, then open window
                            let url_clone = server_url.clone();
                            let h_clone = handle_clone.clone();
                            tauri::async_runtime::spawn(async move {
                                let ready = wait_for_server(&url_clone, 30).await;
                                if ready {
                                    eprintln!("[LocoDrive] Server ready at {}", url_clone);
                                    let _ = tauri::WebviewWindowBuilder::new(
                                        &h_clone,
                                        "main",
                                        tauri::WebviewUrl::App("index.html".into())
                                    )
                                    .title("LocoDrive")
                                    .inner_size(1100.0, 700.0)
                                    .min_inner_size(900.0, 600.0)
                                    .visible(true)
                                    .build();
                                } else {
                                    eprintln!("[LocoDrive] Java server did not respond in 30s");
                                }
                            });
                        }
                    }
                }
            });

            // Collect stderr for debugging
            std::thread::spawn(move || {
                let reader = BufReader::new(stderr);
                for line in reader.lines() {
                    if let Ok(text) = line {
                        eprintln!("[Java Server ERR] {}", text);
                    }
                }
            });

            // Setup system tray
            setup_tray(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![get_server_url])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                // Hide to tray instead of closing
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    use tauri::tray::{TrayIconBuilder, MouseButton, MouseButtonState, TrayIconEvent};
    use tauri::menu::{MenuBuilder, MenuItemBuilder};

    let show = MenuItemBuilder::with_id("show", "Show LocoDrive").build(app)?;
    let open_browser = MenuItemBuilder::with_id("open_browser", "Open in Browser").build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "Exit").build(app)?;
    let menu = MenuBuilder::new(app)
        .items(&[&show, &open_browser, &quit])
        .build()?;

    let handle = app.handle().clone();
    TrayIconBuilder::new()
        .icon(app.default_window_icon().cloned().unwrap())
        .menu(&menu)
        .on_menu_event(move |_app, event| {
            match event.id().as_ref() {
                "show" => {
                    // If window exists, show it; if not, it may still be loading
                    if let Some(win) = handle.get_webview_window("main") {
                        let _ = win.show();
                        let _ = win.set_focus();
                    }
                }
                "open_browser" => {
                    let state: tauri::State<Arc<Mutex<ServerState>>> = handle.state();
                    let url = state.lock().unwrap().url.clone();
                    if let Some(url) = url {
                        let _ = open::that(format!("{}/browse/", url));
                    }
                }
                "quit" => {
                    // Kill the Java child process before exiting
                    let state: tauri::State<Arc<Mutex<ServerState>>> = handle.state();
                    let mut st = state.lock().unwrap();
                    if let Some(child) = st.child.as_mut() {
                        let _ = child.kill();
                    }
                    handle.exit(0);
                }
                _ => {}
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event {
                let app = tray.app_handle();
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
        })
        .build(app)?;
    Ok(())
}
