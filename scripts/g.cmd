@echo off
rem ReadIt gradle build wrapper.
rem ASCII only on purpose: cmd.exe reads .cmd in the console ANSI codepage (CP936 here),
rem so non-ASCII comment bytes can desync line parsing.
set "JAVA_HOME=<LOCAL_TOOLS>\jdk\jdk-17"
set "ANDROID_HOME=<LOCAL_HOME>\AppData\Local\Android\Sdk"
set "ANDROID_SDK_ROOT=<LOCAL_HOME>\AppData\Local\Android\Sdk"
call "<LOCAL_HOME>\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" -p "<LOCAL_HOME>\WorkBuddy\ReadIt" --console=plain :app:testDebugUnitTest :app:assembleDebug > "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\build.log" 2>&1
if errorlevel 1 exit 1
