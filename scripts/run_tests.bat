@echo off
cd /d "%~dp0.."
if not exist out call scripts\build.bat
if not exist results mkdir results
java -cp out logan.TestRunner
