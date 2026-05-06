@echo off
echo Cerrando el servidor de PPSSPP en segundo plano...
taskkill /f /im PPSSPP_Service.exe
echo.
echo ==================================================
echo El servidor ha sido cerrado correctamente.
echo ==================================================
pause
