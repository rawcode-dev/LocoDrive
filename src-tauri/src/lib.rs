use tauri::Manager;
use tauri_plugin_shell::ShellExt;
use tauri_plugin_shell::process::CommandEvent;
use std::sync::{Arc, Mutex};
use tokio::time::{sleep, Duration};

/// Shared state: holds the server URL once it is ready
struct ServerState {
    url: Option<String>,
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

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(Arc::new(Mutex::new(ServerState { url: None })))
        .setup(|app| {
            let app_handle = app.handle().clone();
            let state: tauri::State<Arc<Mutex<ServerState>>> = app.state();
            let state_arc = Arc::clone(&state);

            // Check if Java is installed
            let java_check = std::process::Command::new("java").arg("-version").output();
            if java_check.is_err() || !java_check.unwrap().status.success() {
                tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::App("error.html".into()))
                    .title("LocoDrive — Java Required")
                    .inner_size(600.0, 400.0)
                    .resizable(false)
                    .build()?;
                return Ok(());
            }

            // Spawn the Java server sidecar
            let sidecar_cmd = app_handle.shell()
                .sidecar("locodrive-server")
                .expect("locodrive-server sidecar not found — check tauri.conf.json");

            let (mut rx, _child) = sidecar_cmd.spawn().expect("Failed to spawn Java server sidecar");

            // Listen for stdout from the Java server
            tauri::async_runtime::spawn(async move {
                let mut server_url = String::new();
                while let Some(event) = rx.recv().await {
                    match event {
                        CommandEvent::Stdout(line) => {
                            let text = String::from_utf8_lossy(&line).to_string();
                            println!("[Java Server] {}", text);
                            // Java server prints "READY http://ip:port" when ready
                            if let Some(url) = text.strip_prefix("READY ") {
                                server_url = url.trim().to_string();
                                let mut st = state_arc.lock().unwrap();
                                st.url = Some(server_url.clone());
                                drop(st);

                                // Poll until HTTP server is actually accepting connections
                                let url_clone = server_url.clone();
                                let handle_clone = app_handle.clone();
                                tauri::async_runtime::spawn(async move {
                                    let ready = wait_for_server(&url_clone, 30).await;
                                    if ready {
                                        // Open the main window pointing to the Tauri UI
                                        let _ = tauri::WebviewWindowBuilder::new(
                                            &handle_clone,
                                            "main",
                                            tauri::WebviewUrl::App("index.html".into())
                                        )
                                        .title("LocoDrive")
                                        .inner_size(1100.0, 700.0)
                                        .min_inner_size(900.0, 600.0)
                                        .build();
                                    } else {
                                        eprintln!("Java server failed to respond in time");
                                    }
                                });
                            }
                        }
                        CommandEvent::Stderr(line) => {
                            eprintln!("[Java Server ERR] {}", String::from_utf8_lossy(&line));
                        }
                        _ => {}
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
                window.hide().unwrap();
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
                    if let Some(win) = handle.get_webview_window("main") {
                        let _ = win.show();
                        let _ = win.set_focus();
                    }
                }
                "open_browser" => {
                    let url = {
                        let state: tauri::State<Arc<Mutex<ServerState>>> = handle.state();
                        state.lock().unwrap().url.clone()
                    };
                    if let Some(url) = url {
                        let _ = open::that(format!("{}/browse/", url));
                    }
                }
                "quit" => {
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
