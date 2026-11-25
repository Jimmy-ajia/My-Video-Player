@echo off
set ANDROID_HOME=E:\android_sdk
gradlew buildDebugApp 2>&1 | tee C:\Users\boo\AppData\Local\build_log.txt
copy C:\Users\boo\AppData\Local\build_log.txt .\build_output.txt /Y >nul
