# Puts the portable toolchains on PATH for the current PowerShell session.
#
#   . .\tools\env.ps1      (note the leading dot - it must run in your own session)
#
# Nothing here is written to the machine's environment variables, so closing the
# window undoes it. Deleting C:\Users\USER\toolchains removes the toolchains entirely.

$ToolRoot = "C:\Users\USER\toolchains"

$env:JAVA_HOME        = Join-Path $ToolRoot "jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME     = Join-Path $ToolRoot "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$env:Path = @(
    (Join-Path $env:JAVA_HOME "bin")
    (Join-Path $ToolRoot "gradle\gradle-8.11.1\bin")
    (Join-Path $env:ANDROID_HOME "platform-tools")
    (Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin")
    $env:Path
) -join ";"

Write-Host "JAVA_HOME    $env:JAVA_HOME"
Write-Host "ANDROID_HOME $env:ANDROID_HOME"
Write-Host "java         $((& java -version 2>&1)[0])"
