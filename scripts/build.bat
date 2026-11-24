@echo off
REM Windows: compile main + tests into out\ (needs JDK 17+ on PATH)
cd /d "%~dp0.."
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\*.java src\test\*.java > sources.txt
javac -d out @sources.txt
del sources.txt
echo Build OK
