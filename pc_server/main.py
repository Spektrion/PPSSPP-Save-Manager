import os
import sys
import socket
import random
import time
import shutil
import json
import threading
import ctypes
from pathlib import Path
from typing import Dict

import uvicorn
from fastapi import FastAPI, HTTPException, Request, UploadFile, File, Form
from fastapi.responses import FileResponse, JSONResponse
from colorama import init, Fore, Style
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler
import pystray
from PIL import Image, ImageDraw

def enable_ansi():
    kernel32 = ctypes.windll.kernel32
    kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)

app = FastAPI(title="PPSSPP Save Manager")

CONFIG_FILE = Path("config.json")
SAVEDATA_PATH = ""
SECURITY_PIN = ""
LAST_CHANGE_TIME = 0
observer = None
tray_icon = None

def get_startup_path():
    return Path(os.getenv("APPDATA")) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup" / "PPSSPP_Sync.lnk"

def is_startup_enabled():
    return get_startup_path().exists()

def toggle_startup(icon, item):
    startup_path = get_startup_path()
    if startup_path.exists():
        startup_path.unlink()
    else:
        try:
            from win32com.client import Dispatch
            import pythoncom
            pythoncom.CoInitialize()
            shell = Dispatch('WScript.Shell')
            shortcut = shell.CreateShortCut(str(startup_path))
            shortcut.Targetpath = sys.executable
            shortcut.WorkingDirectory = os.getcwd()
            shortcut.save()
        except Exception as e: pass

class SaveChangeHandler(FileSystemEventHandler):
    def on_modified(self, event):
        global LAST_CHANGE_TIME
        if not event.is_directory:
            LAST_CHANGE_TIME = time.time()

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except: return "127.0.0.1"

def is_local_ip(ip: str) -> bool:
    if ip == "127.0.0.1" or ip == "localhost": return True
    try:
        parts = ip.split('.')
        p0 = int(parts[0])
        if p0 == 10: return True
        if p0 == 172 and 16 <= int(parts[1]) <= 31: return True
        if p0 == 192 and int(parts[1]) == 168: return True
    except: return False
    return False

def load_config():
    global SAVEDATA_PATH, SECURITY_PIN
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r") as f:
                data = json.load(f)
                SAVEDATA_PATH = data.get("path", "")
                SECURITY_PIN = data.get("pin", "")
                return True
        except: return False
    return False

def save_config(path, pin):
    with open(CONFIG_FILE, "w") as f:
        json.dump({"path": path, "pin": pin}, f)

@app.middleware("http")
async def security_middleware(request: Request, call_next):
    client_ip = request.client.host
    if not is_local_ip(client_ip):
        return JSONResponse(status_code=403, content={"detail": "Only local network allowed."})
    provided_pin = request.headers.get("X-Sync-Pin") or request.query_params.get("pin")
    if provided_pin != SECURITY_PIN:
        return JSONResponse(status_code=401, content={"detail": "Invalid PIN."})
    return await call_next(request)

@app.get("/ping")
def ping(): return {"status": "ok", "last_change": LAST_CHANGE_TIME}

@app.get("/games")
def list_games():
    if not SAVEDATA_PATH or not os.path.isdir(SAVEDATA_PATH):
        raise HTTPException(status_code=500)
    games = {}
    base_dir = Path(SAVEDATA_PATH)
    for game_dir in base_dir.iterdir():
        if game_dir.is_dir():
            files = {f.name: int(f.stat().st_mtime) for f in game_dir.iterdir() if f.is_file()}
            if files: games[game_dir.name] = files
    return games

@app.get("/download/{game_id}/{filename}")
def download_file(game_id: str, filename: str):
    path = Path(SAVEDATA_PATH) / game_id / filename
    return FileResponse(path)

@app.post("/upload/{game_id}/{filename}")
async def upload_file(game_id: str, filename: str, file: UploadFile = File(...), mtime: int = Form(None)):
    target = Path(SAVEDATA_PATH) / game_id / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "wb") as f: shutil.copyfileobj(file.file, f)
    if mtime: os.utime(target, (mtime, mtime))
    return {"status": "success"}

def run_server():
    uvicorn.run(app, host="0.0.0.0", port=5000, log_config=None)

def main():
    global SAVEDATA_PATH, SECURITY_PIN, observer, tray_icon
    
    if not load_config():
        ctypes.windll.kernel32.AllocConsole()
        enable_ansi()
        init(autoreset=True)
        sys.stdout = open("CONOUT$", "w", encoding="utf-8")
        sys.stdin = open("CONIN$", "r", encoding="utf-8")
        
        print(f"{Fore.CYAN}{Style.BRIGHT}=== PPSSPP SYNC INITIAL SETUP ===\n")
        print(f"{Fore.WHITE}It seems this is the first time you are running the program.")
        print(f"{Fore.WHITE}Pleaseelect where you store your PPSSPP save files on this PC.\n")
        
        user_docs = Path(os.environ.get('USERPROFILE', '')) / 'Documents'
        def_path = user_docs / "PPSSPP" / "PSP" / "SAVEDATA"
        if def_path.exists():
            print(f"{Fore.YELLOW}Common path detected: {Fore.GREEN}{def_path}")
            if input(f"{Fore.WHITE}Do you want to use it? (Y/n): ").lower() in ["", "y", "s"]:
                SAVEDATA_PATH = str(def_path)
        
        while not SAVEDATA_PATH or not os.path.isdir(SAVEDATA_PATH):
            print(f"\n{Fore.YELLOW}Enter your SAVEDATA folder path (you can drag and drop the folder here):")
            SAVEDATA_PATH = input(f"{Fore.CYAN}> ").strip().strip('"').strip("'")
            
        SECURITY_PIN = str(random.randint(1000, 9999))
        save_config(SAVEDATA_PATH, SECURITY_PIN)
        
        print(f"\n{Fore.GREEN}[✓] Configuration saved successfully!")
        print(f"{Fore.WHITE}You can close this window Now. The app will run in the background.")
        print(f"{Fore.WHITE}See system tray for more config or info!")

        time.sleep(3)
        ctypes.windll.kernel32.FreeConsole()
    
    observer = Observer()
    observer.schedule(SaveChangeHandler(), SAVEDATA_PATH, recursive=True)
    observer.start()
    
    threading.Thread(target=run_server, daemon=True).start()
    
    icon_path = "icon.png"
    if hasattr(sys, '_MEIPASS'): icon_path = os.path.join(sys._MEIPASS, icon_path)
    try: icon_img = Image.open(icon_path)
    except: icon_img = Image.new('RGB', (64, 64), (0, 0, 0))
    
    ip = get_local_ip()
    menu = pystray.Menu(
        pystray.MenuItem(f"IP: {ip}", lambda: None, enabled=False),
        pystray.MenuItem(f"PIN: {SECURITY_PIN}", lambda: None, enabled=False),
        pystray.MenuItem("Start with Windows", toggle_startup, checked=lambda item: is_startup_enabled()),
        pystray.MenuItem("Exit", lambda icon, item: [observer.stop(), icon.stop(), os._exit(0)])
    )
    
    tray_icon = pystray.Icon("PPSSPP Sync", icon_img, "PPSSPP Save Sync", menu)
    tray_icon.run()

if __name__ == "__main__":
    if sys.stdout is None: sys.stdout = open(os.devnull, "w")
    if sys.stderr is None: sys.stderr = open(os.devnull, "w")
    main()
