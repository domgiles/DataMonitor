@echo off
rem Shows the dashboard in this console (q quits). Runs the jar directly rather than through
rem the launcher, whose child JVM has no console to draw on. Uses bin\datamonitor.yaml unless
rem -c names another file. Best in Windows Terminal.
cd /d "%~dp0..\bin"
java -Xmx512m -jar ..\lib\datamonitor.jar --tui %*
