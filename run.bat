@echo off
REM Windows launcher - starts the desktop app + web portal (http://localhost:8080)
cd /d %~dp0
mvn -q javafx:run
pause
