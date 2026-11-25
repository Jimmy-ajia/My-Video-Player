@echo off
setlocal enabledelayedexpansion
set ANDROID_HOME=E:\android_sdk
cd /d "%~dp0"
gradlew buildDebugApp 2>&1 | findstr "" > nul

copy app\build\debug\*.apk "C:\Users\boo\Desktop\debug_apk_%date:/=%_%.txt" 2>nul || echo "APK not found — check build_log.txt for errors"
