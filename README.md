# 🗄️ LocoDrive

**A cross-platform local network file sharing server with a beautiful desktop GUI.**
Share files on your Wi-Fi — no cloud, no internet, no technical knowledge required.

> Built with **Tauri 2.0** (Rust + WebView) + **Java 17** HTTP backend.

## 📥 Download

| Platform | Download |
|---|---|
| 🪟 Windows | [LocoDrive Setup.exe](https://github.com/rawcode-dev/LocoDrive/releases/latest) |
| 🐧 Linux | [locodrive_amd64.deb](https://github.com/rawcode-dev/LocoDrive/releases/latest) |
| 🍎 macOS | [LocoDrive.dmg](https://github.com/rawcode-dev/LocoDrive/releases/latest) |

> Files are automatically built and published to the [Releases](https://github.com/rawcode-dev/LocoDrive/releases) page by GitHub Actions on every push.

---

## ✨ Features

| Feature | Details |
|---|---|
| 🧙 Setup Wizard | 5-step guided setup: Network → Folders → Users → Review → Dashboard |
| 📁 File Browser | Browse and download shared folders from any device on LAN |
| 👤 User Accounts | Admin/User accounts with SHA-256 hashed passwords |
| 🔓 Guest Access | Per-folder: public (no login) or private (login required) |
| 🔒 Session Auth | Cookie-based sessions — no browser password caching |
| 📱 QR Code | Scan from phone to instantly open the file browser |
| 📊 Live Dashboard | Real-time access log, uptime timer, session count |
| 💾 Config Persistence | Settings saved to `~/.locodrive/config.json` |
| 🖥️ System Tray | Minimize to tray — server keeps running in background |
| 🌐 LAN Only | Binds to local network IP — NOT internet-accessible |

---

## 🏗️ Architecture

```
LocoDrive Desktop App (~5 MB)
├── Tauri Shell (Rust)       — window, system tray, lifecycle management
├── WebView UI               — HTML + CSS + JS wizard and dashboard
└── Java Server (sidecar)   — embedded HTTP server (locodrive-server.jar)
    ├── /browse/            — file browser for LAN devices
    ├── /login, /logout     — session-based authentication
    └── /api/*              — REST API consumed by the WebView UI
```

The Tauri shell starts the Java HTTP server as a background sidecar process. The WebView UI talks to the Java API to manage configuration and show the live dashboard. LAN devices (phones, PCs) access the file browser directly via a web browser — no app needed on their side.

---

## 🚀 Getting Started (Development)

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| **Java JDK** | 17+ | [adoptium.net](https://adoptium.net) |
| **Maven** | 3.8+ | [maven.apache.org](https://maven.apache.org) |
| **Rust** | stable | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| **Tauri CLI** | 2.x | `cargo install tauri-cli` |

> **Linux only:** also install WebKit: `sudo apt install libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev`

---

### Step 1 — Clone the repository

```bash
git clone https://github.com/rawcode-dev/LocoDrive.git
cd LocoDrive
```

### Step 2 — Build the Java server JAR

```bash
mvn clean package
```

This produces `target/locodrive-server.jar` — the headless HTTP server sidecar.

### Step 3 — Run in Tauri dev mode

```bash
cargo tauri dev
```

This compiles the Rust shell, places the JAR as a sidecar, and opens the app window. Hot-reload is active for the `ui/` frontend files.

---

## 🔨 Building a Production Installer

Run this on **each target OS** (or let GitHub Actions do it for you):

```bash
# Build Java JAR first
mvn clean package

# Then build the Tauri installer
cargo tauri build
```

Output locations:
- **Windows:** `src-tauri/target/release/bundle/nsis/LocoDrive Setup.exe`
- **Linux:** `src-tauri/target/release/bundle/deb/locodrive_amd64.deb`
- **macOS:** `src-tauri/target/release/bundle/dmg/LocoDrive.dmg`

---

## ⚙️ CI/CD — Automated GitHub Releases

The [.github/workflows/build-installers.yml](.github/workflows/build-installers.yml) workflow runs on every push to `main` or a version tag (`v*`). It:

1. Builds the headless Java JAR on each runner
2. Builds Tauri native installers in parallel for Windows, Linux, and macOS
3. Publishes all three installers to a GitHub Release automatically

To trigger a versioned release:
```bash
git tag v1.0.1
git push origin v1.0.1
```

---

## 🔧 How to Use

### 1 — Welcome
Launch LocoDrive. Click **"Get Started"** to run the setup wizard, or **"Load Saved Config"** if you have run the app before.

### 2 — Network Setup
- Select your **local IP address** from the dropdown (auto-detected)
- Set the **port** (default `8080`)

### 3 — Shared Folders
- Click **"+ Add Folder"** to pick folders from your computer
- Each folder gets a display name (editable)
- Toggle **"Public"** per folder to allow guest access without login

### 4 — User Accounts
- Create at least **one Admin** user (required to launch)
- Set username and password (min. 6 characters)

### 5 — Security Review
- Review all settings. Security warnings are shown if guest access is enabled.
- Click **"🚀 Launch Server"** to start

### 6 — Dashboard
- See your server **URL** and copy it with one click
- Click **"🌐 Open in Browser"** to open the file browser on this PC
- Watch the **Live Access Log** for connections
- Minimize to system tray — server keeps running
- Right-click the tray icon → **Exit** to stop the server

---

## 🔒 Security Notes

| | |
|---|---|
| ✅ | **LAN only** — binds to local network IP. Not internet-accessible. |
| ✅ | **Passwords** stored as SHA-256 + salt hashes. Never plain text. |
| ✅ | **Path traversal protection** — users cannot access files outside shared folders. |
| ✅ | **Session cookies** — HttpOnly cookie-based auth. |
| ⚠️ | **HTTP only** — traffic is not encrypted. Do not use on untrusted public Wi-Fi. |
| ⚠️ | **Public folders** — expose files to all devices on the network without login. |

### Installation Security Warnings

This app is open-source and unsigned (no paid Apple/Microsoft certificate). OS warnings are normal:

- **🪟 Windows SmartScreen:** Click **More info** → **Run anyway**
- **🍎 macOS Gatekeeper:** After a failed open, go to **System Settings → Privacy & Security** → click **Open Anyway**. Or run in Terminal: `xattr -d com.apple.quarantine ~/Downloads/LocoDrive.dmg`
- **🐧 Linux:** `sudo apt install ./locodrive_amd64.deb`

---

## 📁 Project Structure

```
LocoDrive/
├── pom.xml                         # Maven build — produces locodrive-server.jar
├── src/
│   └── main/java/com/locodrive/
│       ├── ServerMain.java         # 🚀 Headless entry point (no JavaFX)
│       ├── AppContext.java         # Singleton shared state
│       ├── model/                  # ServerConfig, User, SharedFolder
│       ├── server/                 # HTTP server (LocalFileServer, handlers)
│       └── util/                   # NetworkDetector, ConfigManager, QRCodeGenerator
├── ui/                             # Web frontend (HTML/CSS/JS)
│   ├── index.html
│   ├── styles/app.css              # Dark-mode design system
│   └── js/
│       ├── api.js                  # REST API client
│       ├── router.js               # SPA router
│       └── pages/                  # welcome, network, folders, users, review, dashboard
└── src-tauri/                      # Tauri shell (Rust)
    ├── Cargo.toml
    ├── tauri.conf.json
    ├── capabilities/default.json
    └── src/
        ├── main.rs
        └── lib.rs                  # Sidecar mgmt, system tray, window
```

---

## 📋 Config File

Settings are auto-saved to:
- **Windows:** `C:\Users\<Name>\.locodrive\config.json`
- **Linux / macOS:** `~/.locodrive/config.json`

---

## 🛠️ Troubleshooting

| Issue | Solution |
|---|---|
| "Port already in use" | Change port in Network Setup (e.g. 8081, 9000) |
| "No IP addresses found" | Make sure you are connected to a Wi-Fi or LAN network |
| Can't access from phone | Ensure phone is on the same Wi-Fi network |
| App closes when I click X | It minimizes to the system tray. Right-click tray icon → Exit to stop. |
| Java not found on startup | Install JDK 17+ from [adoptium.net](https://adoptium.net) |
