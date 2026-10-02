@echo off
rem Starts tsundoku with the Java runtime and the tsundoku-WebUI that came in this folder (Windows).
cd /d "%~dp0"

set "DATA=%LOCALAPPDATA%\Tachidesk"
if not exist "%DATA%" mkdir "%DATA%"

rem The bundled interface replaces the copy in the data folder on every start, so an update also updates the interface.
if exist "%DATA%\webUI" rmdir /s /q "%DATA%\webUI"
xcopy /e /i /q /y "webUI" "%DATA%\webUI" >nul

"jre\bin\java.exe" -Dsuwayomi.tachidesk.config.server.webUIFlavor=CUSTOM -jar bin	sundoku.jar %*
pause
