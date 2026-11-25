$env:ANDROID_HOME = "E:\android_sdk"
cd $env:PWD
$output = gradlew buildDebugApp 2>&1
$output | Out-File -FilePath ".\build_log.txt"
Write-Output "Build completed. Check .\build_log.txt for details."
