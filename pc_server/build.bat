@echo off
echo 1. Preparing environment...
python -m venv venv

echo 2. Installing dependencies (this may take a moment)...
venv\Scripts\python.exe -m pip install -r requirements.txt

echo 3. Creating application icon...
venv\Scripts\python.exe -c "from PIL import Image; Image.open('icon.png').resize((256,256)).save('icon.ico')"

echo 4. Building Unified Executable (PPSSPP_Sync_Manager.exe)...
venv\Scripts\pyinstaller.exe --onefile --noconsole --name "PPSSPP_Sync_Manager" --icon="icon.ico" --add-data "icon.png;." main.py

echo.
echo ==================================================
echo ALL DONE! 
echo Look for 'PPSSPP_Sync_Manager.exe' in the 'dist' folder.
echo 
echo Note: A console will open for setup on the first run.
echo After that, the app will live in your system tray.
echo ==================================================
pause
