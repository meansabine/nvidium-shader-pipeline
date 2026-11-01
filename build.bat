@echo off
setlocal
echo =========================================
echo Building Nvidium Shader Pipeline Jar
echo =========================================
if exist "C:\Program Files\Java\jdk-21.0.12.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
    set "PATH=C:\Program Files\Java\jdk-21.0.12.1\bin;%PATH%"
)

echo Cleaning project...
call gradlew.bat clean
if %ERRORLEVEL% neq 0 goto error

echo Building Mod Jar...
call gradlew.bat build
if %ERRORLEVEL% neq 0 goto error

echo =========================================
echo Build Succeeded! Jar is in build/libs/
echo =========================================
exit /b 0

:error
echo =========================================
echo Build Failed!
echo =========================================
exit /b 1
