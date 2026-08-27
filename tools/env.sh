#!/usr/bin/env bash
# Git Bash equivalent of env.ps1.  Usage:  source tools/env.sh
TOOL_ROOT="/c/Users/USER/toolchains"

export JAVA_HOME="$TOOL_ROOT/jdk/jdk-17.0.20.1+1"
export ANDROID_HOME="$TOOL_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TOOL_ROOT/gradle/gradle-8.11.1/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "JAVA_HOME    $JAVA_HOME"
echo "ANDROID_HOME $ANDROID_HOME"
java -version 2>&1 | head -1
