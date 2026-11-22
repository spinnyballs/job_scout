@echo off
setlocal
cd /d "%~dp0"
title Job Scout Dashboard

set "PY_CMD="
where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 -c "import sys" >nul 2>nul
    if %ERRORLEVEL%==0 set "PY_CMD=py -3"
)
if not defined PY_CMD (
    where python >nul 2>nul
    if %ERRORLEVEL%==0 (
        python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)" >nul 2>nul
        if %ERRORLEVEL%==0 set "PY_CMD=python"
    )
)
if not defined PY_CMD (
    where python3 >nul 2>nul
    if %ERRORLEVEL%==0 set "PY_CMD=python3"
)

if not defined PY_CMD (
    echo.
    echo [!] Python 3.10+ was not found on your PATH.
    echo     Install Python from https://www.python.org/downloads/
    echo     (Make sure to check "Add python.exe to PATH" during installation.)
    echo.
    pause
    exit /b 1
)

%PY_CMD% "%~dp0launch.py" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    pause
)
