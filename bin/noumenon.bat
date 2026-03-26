@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%..\target\noumenon-0.1.0.jar"
if not exist "%JAR%" set "JAR=%SCRIPT_DIR%noumenon.jar"
if not exist "%JAR%" (echo noumenon: cannot find noumenon JAR >&2 & exit /b 1)
REM Note: %* is not individually quoted. Arguments containing special
REM characters (& | < > ^ %) may be misinterpreted by cmd.exe.
REM A robust fix requires enabledelayedexpansion which is fragile.
java -jar "%JAR%" %*
