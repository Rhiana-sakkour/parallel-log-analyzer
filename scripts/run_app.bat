@echo off
cd /d "%~dp0.."
if not exist out call scripts\build.bat
java -cp out logan.ui.LogAnalyzerApp
