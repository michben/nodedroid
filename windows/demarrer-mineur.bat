@echo off
chcp 65001 >nul
setlocal
title Parano1d - demarrage

rem ============================================================
rem  Demarre le noeud Parano1d (mode extminer) puis le mineur du PC.
rem  Les telephones (app Paramo1d, onglet Miner) se connectent ensuite
rem  a http://IP-DE-CE-PC:9601 avec le jeton mining.key.
rem ============================================================

rem --- A REGLER --------------------------------------------------
rem Dossier ou vous avez extrait parano1d-core-v2.1.0-windows-x86_64.zip
set "PARANO1D_DIR=C:\parano1d"
rem Votre adresse de paiement o1... (laisser vide = wallet du noeud)
set "PAYOUT="
rem Coeurs pour le mineur du PC (0 = tous). Le noeud garde la priorite.
set "THREADS=0"
rem ---------------------------------------------------------------

set "KEY=%USERPROFILE%\.parano1d\mining.key"

if not exist "%PARANO1D_DIR%\parano1d.exe" (
    echo [ERREUR] parano1d.exe introuvable dans %PARANO1D_DIR%
    echo Modifiez PARANO1D_DIR en haut de ce fichier.
    pause
    exit /b 1
)
if not exist "%KEY%" (
    echo [ERREUR] Jeton de minage introuvable : %KEY%
    echo Creez-le avec l'etape 3 du guide, puis relancez ce fichier.
    pause
    exit /b 1
)

set "ADDR_ARG="
if defined PAYOUT (
    if /I not "%PAYOUT:~0,2%"=="o1" (
        echo [ERREUR] L'adresse de paiement doit commencer par o1
        pause
        exit /b 1
    )
    set "ADDR_ARG=--miner-address %PAYOUT%"
)

cd /d "%PARANO1D_DIR%"

rem 1. Noeud : prepare les preuves et distribue les gabarits aux mineurs.
tasklist /FI "IMAGENAME eq parano1d.exe" 2>nul | find /I "parano1d.exe" >nul
if errorlevel 1 (
    echo Demarrage du noeud...
    start "Parano1d - noeud" /D "%PARANO1D_DIR%" cmd /k parano1d.exe --mode extminer --rpc-listen 0.0.0.0:9601 --mining-key-file "%KEY%" %ADDR_ARG%
) else (
    echo Le noeud tourne deja.
)

rem 2. Attente du RPC local (le noeud peut mettre un moment a demarrer).
echo Attente du noeud sur 127.0.0.1:9601...
set /a TRIES=0
:wait
"%PARANO1D_DIR%\parano1d-cli.exe" status >nul 2>&1
if not errorlevel 1 goto ready
set /a TRIES+=1
if %TRIES% GEQ 120 (
    echo [ERREUR] Le noeud ne repond pas apres 10 minutes. Regardez sa fenetre.
    pause
    exit /b 1
)
timeout /t 5 /nobreak >nul
goto wait

:ready
echo Noeud pret.

rem 3. Mineur du PC, en priorite basse pour ne pas ralentir les preuves du noeud.
tasklist /FI "IMAGENAME eq parano1d-miner.exe" 2>nul | find /I "parano1d-miner.exe" >nul
if errorlevel 1 (
    echo Demarrage du mineur du PC...
    start "Parano1d - mineur PC" /BELOWNORMAL /D "%PARANO1D_DIR%" cmd /k parano1d-miner.exe --rpc http://127.0.0.1:9601 --key-file "%KEY%" --threads %THREADS%
) else (
    echo Le mineur du PC tourne deja.
)

echo.
echo Tout est lance. Adresse a saisir sur les telephones :
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /C:"IPv4"') do for /f "tokens=*" %%b in ("%%a") do echo    http://%%b:9601
echo.
echo Etat du noeud :  "%PARANO1D_DIR%\parano1d-cli.exe" status
echo Minage       :  "%PARANO1D_DIR%\parano1d-cli.exe" mining
echo.
timeout /t 30
