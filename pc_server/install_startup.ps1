$exePath = "$PSScriptRoot\dist\PPSSPP_Service.exe"
if (!(Test-Path $exePath)) {
    Write-Host "Error: No se encuentra PPSSPP_Service.exe en la carpeta dist." -ForegroundColor Red
    Write-Host "Por favor, ejecuta build.bat primero."
    exit
}

$shortcutPath = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\PPSSPP_Sync.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $exePath
$shortcut.WorkingDirectory = "$PSScriptRoot\dist"
$shortcut.Save()

Write-Host "¡Listo! El servidor de PPSSPP ahora se iniciara automaticamente con Windows." -ForegroundColor Green
