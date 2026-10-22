@echo off
rem Runs the datamonitor target in ..\launcher\launcher.xml from the bin directory, so
rem bin\datamonitor.yaml is used unless -c names another file.
cd /d "%~dp0..\bin"
rem --encrypt prompts for a password, which needs this console: the launcher's child JVM has none
if "%~1"=="--encrypt" (
    java -jar ..\lib\datamonitor.jar %*
    goto :eof
)
java -cp ..\launcher LauncherBootstrap -executablename datamonitor datamonitor %*
