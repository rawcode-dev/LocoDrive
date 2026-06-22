# 🗄️ LocoDrive

**A cross-platform local network file sharing server with a beautiful GUI — no technical knowledge required.**

> Works on **Windows** and **Linux**. Built with **Java 17 + JavaFX 21**.

---

## Features

| Feature | Details |
|---|---|
| 🧙 6-Step Wizard | Guided setup: Network → Folders → Users → Review → Dashboard |
| 📁 File Browser | Browse and download shared folders from any device on LAN |
| 👤 User Accounts | Create Admin/User accounts with SHA-256 hashed passwords |
| 🔓 Guest Access | Per-folder: mark folders as public (no login) or private (login required) |
| 🔒 Session Auth | Cookie-based sessions — no browser password caching |
| 📱 QR Code | Scan from phone to instantly open the file browser |
| 📊 Live Dashboard | Real-time access log, uptime timer, session count |
| 💾 Config Persistence | Settings saved to `~/.locodrive/config.json` |
| 🖥️ System Tray | Minimize to tray — server keeps running in background |
| 🌐 LAN Only | Server binds to your local network IP — NOT internet-accessible |

---

## Quick Start

### Prerequisites

- **Java 17+** installed (JDK, not JRE)
- **Maven 3.6+** installed

### Run (Development)

```bash
# Navigate to project folder
cd "Project localServer"

# Run the app
mvn javafx:run
```

### Build Native Installer (Production)

Java 17's `jpackage` tool allows you to bundle a lightweight Java Runtime Environment (JRE) with the application, meaning users don't need to install Java themselves.

**Note:** You must build the installer on the target operating system (build `.deb` on Linux, `.exe` on Windows). First, ensure you have compiled the fat JAR by running `mvn clean package`.

#### 🐧 Build for Linux / Raspberry Pi OS (`.deb`)
1. Ensure the packaging tool `fakeroot` is installed:
   ```bash
   sudo apt install fakeroot -y
   ```
2. Build the `.deb` package using the JDK's `jpackage` tool:
   ```bash
   jpackage --type deb --name LocoDrive --input target/ --main-jar locodrive-1.0.0.jar --main-class com.locodrive.Launcher --app-version 1.0.0
   ```
3. Find the output `.deb` file in your project folder. Install it using:
   ```bash
   sudo apt install ./locodrive_1.0.0-1_arm64.deb
   ```

#### 🪟 Build for Windows (`.exe`)
1. Ensure you have the **WiX Toolset v3** installed (required by `jpackage` to make `.exe` installers). You can install it quickly using PowerShell/Command Prompt:
   ```cmd
   winget install WiX.WiX
   ```
2. Build the `.exe` package using the JDK's `jpackage` tool:
   ```cmd
   jpackage --type exe --name LocoDrive --input target/ --main-jar locodrive-1.0.0.jar --main-class com.locodrive.Launcher --app-version 1.0.0 --win-shortcut --win-menu
   ```
3. The `.exe` installer will be generated in your project folder. Double-click it to install!

---

## How to Use

### Step 1 — Welcome
Click **"Get Started"** to begin the wizard, or **"Load Saved Configuration"** if you've run the app before.

### Step 2 — Network Setup
- Select your **local IP address** from the dropdown (auto-detected from your network)
- Set the **port** (default 8080)
- Port must be free (not used by another app)

### Step 3 — Shared Folders
- Click **"+ Add Folder"** to select folders from your computer
- Each folder gets a display name (editable)
- Toggle **"Public (No Login)"** per folder to allow guest access
- ⚠️ Public folders are accessible to anyone on your Wi-Fi without a password

### Step 4 — User Accounts
- Create at least **one Admin** user
- Set username and password (6+ characters)
- Password strength indicator shows WEAK / FAIR / STRONG
- User accounts needed to access non-public folders

### Step 5 — Security Review
- Review all your settings before launching
- Security warnings are shown if guest access is enabled
- Click **"🚀 Launch Server"** to start

### Step 6 — Dashboard
- See your server URL and QR code
- Scan QR code with phone to open the file browser
- Click **"🌐 Open in Browser"** to open on this computer
- Watch the **Real-Time Access Log** for who's browsing what
- Click **"⏹ Stop Server"** to stop; **"▶ Start Server"** to restart

---

## Security Notes

- ✅ **LAN Only** — The server binds to your local network IP. It is NOT accessible from the internet.
- ✅ **Passwords** are stored as SHA-256 + salt hashes. Never stored in plain text.
- ✅ **Path traversal protection** — Users cannot access files outside the shared folders.
- ✅ **Session cookies** — Secure HttpOnly cookie-based auth. No browser password caching.
- ⚠️ **HTTP only** — Connection is not encrypted (no TLS). Do not use on untrusted public Wi-Fi.
- ⚠️ **Guest folders** — Public folders expose files to all devices on your network without login.

---

## File Structure

```
Project localServer/
├── pom.xml                     # Maven build
└── src/
    └── main/
        ├── java/com/locodrive/
        │   ├── App.java                   # JavaFX Application (system tray)
        │   ├── AppContext.java            # Singleton shared state
        │   ├── Launcher.java              # Entry point
        │   ├── model/
        │   │   ├── User.java              # User account model
        │   │   ├── SharedFolder.java      # Shared folder model
        │   │   └── ServerConfig.java      # Master config
        │   ├── server/
        │   │   ├── LocalFileServer.java   # Embedded HTTP server
        │   │   ├── FileHandler.java       # File browsing + download
        │   │   ├── AuthHandler.java       # Login/logout (HTML form)
        │   │   ├── ApiHandler.java        # REST API for dashboard
        │   │   └── SessionStore.java      # In-memory session store
        │   ├── ui/
        │   │   ├── MainController.java    # Wizard navigation + animations
        │   │   ├── WelcomeController.java
        │   │   ├── NetworkSetupController.java
        │   │   ├── FolderSetupController.java
        │   │   ├── UserManagementController.java
        │   │   ├── SecurityReviewController.java
        │   │   └── DashboardController.java
        │   └── util/
        │       ├── NetworkDetector.java   # Auto-detect LAN IPs
        │       ├── QRCodeGenerator.java   # ZXing QR code
        │       ├── ConfigManager.java     # JSON config persistence
        │       └── PasswordUtils.java     # SHA-256 + salt hashing
        └── resources/
            ├── fxml/                      # One FXML per wizard step
            └── css/app.css               # Dark mode design system
```

---

## Config File Location

Settings are auto-saved to:
- **Windows:** `C:\Users\<YourName>\.locodrive\config.json`
- **Linux:** `~/.locodrive/config.json`

---

## Troubleshooting

| Issue | Solution |
|---|---|
| "Port already in use" | Change port to another number (e.g. 8081, 9000) |
| "No IP addresses found" | Click ↻ Refresh. Make sure you're connected to a network. |
| Can't access from phone | Make sure phone is on same Wi-Fi network |
| App closes when I click X | It minimizes to system tray — right-click tray icon to exit |
