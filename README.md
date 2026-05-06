# PPSSPP Local Sync Manager

A high-performance, cross-platform synchronization utility designed to seamlessly manage PPSSPP save data between Windows PCs and Android devices over a local network.

## 🚀 Overview

This project provides a bi-directional, automated synchronization solution for PPSSPP players who switch between mobile and desktop platforms. Unlike cloud-based solutions, this tool operates exclusively within your local network, ensuring privacy, speed, and zero reliance on third-party servers.

## ✨ Key Features

- **Bi-directional Synchronization:** Intelligent logic compares file modification timestamps. The most recent save always takes priority, ensuring your progress is consistent across devices.
- **Background Automation:**
  - **PC:** A lightweight system tray application that monitors save folder changes in real-time.
  - **Android:** A foreground service that polls for updates, allowing for hands-off synchronization while you play.
- **Local Network Security:**
  - Restricted to local IP addresses only.
  - Dynamic 4-digit PIN authentication for all data transfers.
- **Unified Executable:** The PC component is bundled into a single, self-contained `.exe` with an integrated setup wizard and system tray management.

## 🛠️ Technical Transparency

- **PC Server:** Built with **Python** and **FastAPI**. It uses the **Watchdog** library for low-overhead file system event monitoring.
- **Android App:** Native **Kotlin** application utilizing **Jetpack Compose** for the UI and **OkHttp** for robust networking. It implements a **Foreground Service** to maintain connectivity under Android's strict background limitations.
- **Sync Logic:** The system uses a "Last-Writer-Wins" strategy based on Unix timestamps (`mtime`). It specifically matches Game IDs to ensure only relevant game data is synchronized, preventing folder clutter.

## 🔒 Privacy & Security

- **No Cloud:** Your data never leaves your local WiFi network.
- **Open Communication:** Uses standard HTTP protocols on port 5000.
- **Auth:** All requests must include the `X-Sync-Pin` header matching the server-generated PIN.

## 📖 Setup Instructions

### Windows (Server)
1. Download and run `PPSSPP_Sync_Manager.exe`.
2. Follow the one-time setup to select your `SAVEDATA` folder.
3. The app will move to the System Tray. Right-click it to view your **IP** and **PIN**.

### Android (Client)
1. Install the `.apk` on your mobile device.
2. Select your PPSSPP `SAVEDATA` folder (usually in `PSP/SAVEDATA`).
3. Enter the IP and PIN provided by the PC app.
4. Toggle **Automatic Sync** and grant the required notification permissions.

---

*Developed for the PPSSPP Community. This tool is not affiliated with the official PPSSPP project.*
