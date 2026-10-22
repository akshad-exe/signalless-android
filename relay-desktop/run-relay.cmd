@echo off
setlocal
cd /d "%~dp0"

rem Interactive launcher: keep the console open so a startup error is readable.
rem For Task Scheduler / unattended use, run SignalLessRelay.exe directly.
SignalLessRelay.exe --log-file "%~dp0relay.log" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo Relay exited with code %EXITCODE%.
echo Log file (default): %~dp0relay.log
pause
exit /b %EXITCODE%
