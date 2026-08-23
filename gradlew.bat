@rem
@rem Gradle start-up script for Windows generated from Gradle 8.11.1.
@rem
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################

@setlocal
set "APP_HOME=%~dp0"
set "CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
goto fail

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=%~n0" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@endlocal
exit /b %ERRORLEVEL%

:fail
@endlocal
exit /b 1
