@echo off
cd /d "%~dp0"
echo Starting Job Scout Dashboard on http://127.0.0.1:8765 ...
".venv\Scripts\python.exe" cli.py serve --open
pause
