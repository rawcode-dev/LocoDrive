# LocoDrive Architecture & Developer Guide

This document provides a comprehensive overview of the LocoDrive project architecture, the technology stack, and the highly optimized native packaging pipeline. You can share this document with other developers to quickly get them up to speed.

## 1. Project Overview
LocoDrive is a cross-platform, local network file-sharing server with a modern GUI. It allows users to quickly share files across their local network (e.g., from a MacBook to a phone) using a built-in HTTP server, while providing QR codes for easy mobile access. 

**Key Constraints & Goals:**
- **Zero Configuration:** Must run instantly without requiring the user to install Java.
- **Micro Footprint:** The final installer must be extremely small (under 15MB) and consume minimal RAM.
- **Cross-Platform:** Must support Windows (`.exe`), macOS (`.pkg` / `.dmg`), and Linux (`.deb`).

## 2. Technology Stack
- **Language:** Java 17
- **UI Framework:** JavaFX
- **Backend Server:** Native `com.sun.net.httpserver.HttpServer` (No heavy frameworks like Spring Boot or Tomcat).
- **Build Tool:** Maven
- **AOT Compiler:** GraalVM Native Image (via GluonHQ)
- **Binary Compressor:** UPX (Ultimate Packer for eXecutables)

## 3. Core Architecture

The application is structured into the following key components:

### Entry Point
- `com.locodrive.Launcher`: A standard Java class with a `public static void main` method. This acts as a wrapper to launch the JavaFX application (`App.java`). This pattern is used to bypass Java module-path restrictions that occur when launching `Application` classes directly from a Fat JAR.

### User Interface
- `com.locodrive.App`: The core JavaFX application. Handles the `primaryStage`, loads FXML views, and initializes the system tray icon for background operation.
- **FXML Controllers:** Connect the UI layouts to backend logic (e.g., `FolderSetupController`).

### Backend & Networking
- `com.locodrive.server.FileHandler`: The heart of the network sharing logic. It manages HTTP requests, handles chunked file uploads (configured for 5MB chunks and 4 concurrent threads to maximize speed over 2.4Ghz/5Ghz Wi-Fi), and calculates real-time ETA and transfer speeds.
- `com.locodrive.util.QRCodeGenerator`: Generates QR codes representing the server's local IP address. To save space and RAM, this was custom-written using JavaFX's native `WritableImage` and `PixelWriter` APIs, entirely eliminating heavy legacy `AWT` or `Swing` dependencies.

## 4. The Native Packaging Pipeline (Sub-15MB Goal)

Standard Java applications usually bundle a massive 40-100MB Java Runtime Environment (JRE). To meet the strict <15MB file size and zero-RAM overhead constraints, LocoDrive abandons standard JVM packaging and uses **Ahead-of-Time (AOT) Compilation**.

### Step 1: GraalVM Native Image (GluonFX)
In `pom.xml`, the `gluonfx-maven-plugin` is configured to compile the Java and JavaFX code directly into C-style native machine code. 
- We pass the `<arg>-Os</arg>` flag to the compiler to strictly optimize the output binary for minimum file size rather than peak execution speed.
- The resulting executable contains NO Java Runtime. It is a standalone binary that starts instantly (in milliseconds) and uses negligible RAM.

### Step 2: UPX Binary Compression
Even after AOT compilation, statically linked JavaFX binaries can be 30MB+. We integrate **UPX** into the build pipeline.
- UPX wraps the native executable in a decompression stub and heavily compresses the binary payload using LZMA (`-9` flag). 
- This slashes the executable size by 50-70%. When the user runs the app, UPX instantly decompresses the code directly into RAM.

### Step 3: CI/CD Automation (GitHub Actions)
The entire pipeline is automated in `.github/workflows/build-installers.yml`.
Because compiling Java to machine code requires complex C++ toolchains (MSVC for Windows, XCode for Mac, GCC for Linux), the builds run exclusively on GitHub Actions cloud runners.

**The Workflow Sequence:**
1. **Runner Setup:** We specifically use `ubuntu-20.04` for Linux (to avoid a known GraalVM `CgroupV2` bug present in Ubuntu 24.04).
2. **GraalVM Setup:** Uses `gluonhq/setup-graalvm` to download a locked, stable version of GraalVM (`22.1.0.1-Final` with JDK 17).
3. **Native Headers:** On Linux, `apt-get` installs required C headers (e.g., `libasound2-dev`, `libgtk-3-dev`). On Windows, `ilammy/msvc-dev-cmd` injects `cl.exe` (Microsoft Visual C++ compiler) into the environment.
4. **Compile:** Runs `mvn gluonfx:build` to produce the native executable.
5. **Compress:** Runs `crazy-max/ghaction-upx` to crush the binary size across all OS targets.
6. **Package Installer:** Runs `mvn gluonfx:package` to wrap the compressed executable into standard native installers (Windows MSI/EXE, macOS PKG/DMG, Linux DEB).
7. **Deploy:** The tiny native installers are automatically uploaded to GitHub Releases.
