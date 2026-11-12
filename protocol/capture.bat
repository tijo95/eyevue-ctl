@echo off
REM ============================================================
REM  capture.bat - pieger les trames BLE/SPP dans le logcat
REM  (usage interop prive ; voir PROTOCOL.md)
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0"
if not exist captures mkdir captures

REM --- nom du fichier avec horodatage (PowerShell : wmic est depose sur Win11) ---
set TS=
for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"`) do set TS=%%a
if "%TS%"=="" set TS=%RANDOM%%RANDOM%
set OUT=captures\capture_%TS%.log

echo.
echo [1/4] Verif du device...
adb get-state >nul 2>&1
if errorlevel 1 (
  echo   !! Aucun device. Branche le telephone en USB et active le debogage USB.
  exit /b 1
)
adb devices

echo [2/4] Arret du serveur adb puis vidage du buffer de log...
adb logcat -c
if errorlevel 1 echo   !! "logcat -c" a echoue (buffer deja vide ? sans gravite).

echo.
echo ------------------------------------------------------------
echo [3/4] CAPTURE EN COURS
echo   Ne ferme PAS cette fenetre.
echo   Sur le telephone, avec l'app patchee :
echo     1. connecte les lunettes
echo     2. laisse la page ouverte ~10 s   ^(handshake, statut, batterie^)
echo     3. prends UNE photo                ^(TAKE_PHOTO^)
echo     4. declenche la traduction         ^(0x97/0x98/0x99^)
echo     5. change le volume + la luminosite^(0x70 / 0x01^)
echo     6. hoche la tete                   ^(ACTION_SYNC^)
echo     7. deconnecte les lunettes
echo   Puis reviens ici et appuie sur Ctrl+C
echo ------------------------------------------------------------
echo.

adb logcat -v time ^> "%OUT%"

echo.
echo [4/4] Capture terminee.
for %%F in ("%OUT%") do echo   fichier : %%~fnxF   ^(%%~zF octets^)
echo.
echo Decodage :
echo   python decode.py "%OUT%"
echo.
pause
