@echo off
rem Runs the datamonitorweb target in ..\launcher\launcher.xml from the bin directory, so
rem bin\datamonitor.yaml is used unless -c names another file.
cd /d "%~dp0..\bin"
java -cp ..\launcher LauncherBootstrap -executablename datamonitorweb datamonitorweb %*
