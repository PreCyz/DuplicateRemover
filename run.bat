@echo off
setlocal

if "%~1"=="" (
    echo Usage: run.bat [--disk=HDD^|NVMe] "C:\path\to\media" ["D:\20*"]
    exit /b 1
)

if not exist ".\target\DuplicateFileRemover-1.0-SNAPSHOT.jar" (
    where mvnd >nul 2>&1
    if errorlevel 1 (
        echo mvnd is not available; falling back to mvn.
        call mvn package
    ) else (
        call mvnd package
    )

    if errorlevel 1 exit /b 1
)

java -jar ".\target\DuplicateFileRemover-1.0-SNAPSHOT.jar" %*
