@echo off
rem Runs the datamonitor target in ..\launcher\launcher.xml from the bin directory, so
rem bin\datamonitor.yaml is used unless -c names another file.
setlocal
cd /d "%~dp0..\bin"
rem --cl draws on this console and --encrypt prompts in it, but the launcher's child JVM has
rem no console, so both run the jar directly. --cl is best in Windows Terminal.
set DIRECT=
for %%a in (%*) do (
    if /i "%%~a"=="--cl" set DIRECT=1
    if /i "%%~a"=="--encrypt" set DIRECT=1
)
if defined DIRECT (
    java -Xmx512m -jar ..\lib\datamonitor.jar %*
    goto :eof
)
java -cp ..\launcher LauncherBootstrap -executablename datamonitor datamonitor %*
