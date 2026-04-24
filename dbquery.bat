@echo off
rem dbquery.bat — Windows launcher for the dbquery fat-JAR
rem
rem Usage: dbquery.bat [flags]
rem   --host, --port, --database, --user, --password, --query, ...
rem   (or set DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, DB_QUERY env vars)
rem
rem Requirements: Java 11+ on PATH

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem Try to find dbquery.jar next to this script
if exist "%SCRIPT_DIR%dbquery.jar" (
    set "JAR_PATH=%SCRIPT_DIR%dbquery.jar"
) else if exist "%SCRIPT_DIR%target\dbquery.jar" (
    set "JAR_PATH=%SCRIPT_DIR%target\dbquery.jar"
) else (
    echo [ERROR] dbquery.jar not found next to dbquery.bat 1>&2
    echo [ERROR] Expected: %SCRIPT_DIR%dbquery.jar 1>&2
    exit /b 1
)

java -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
