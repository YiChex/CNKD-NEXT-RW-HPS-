@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
java -jar "%SCRIPT_DIR%tools\cnkd-plugin-tool.jar" %*
exit /b %ERRORLEVEL%
