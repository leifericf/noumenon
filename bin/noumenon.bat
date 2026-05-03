@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR="
for /f "delims=" %%i in ('dir /b /o-n "%SCRIPT_DIR%..\target\noumenon-*.jar" 2^>nul') do if not defined JAR set "JAR=%SCRIPT_DIR%..\target\%%i"
if not defined JAR if exist "%SCRIPT_DIR%noumenon.jar" set "JAR=%SCRIPT_DIR%noumenon.jar"
if not defined JAR (echo noumenon: cannot find noumenon JAR >&2 & exit /b 1)
REM Note: %* is not individually quoted. Arguments containing special
REM characters (& | < > ^ %) may be misinterpreted by cmd.exe.
REM A robust fix requires enabledelayedexpansion which is fragile.
java -jar "%JAR%" %*
