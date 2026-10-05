@echo off
setlocal
pushd "%~dp0.." || exit /b 1
if not exist ".build\server.jar" (
    echo Falta .build\server.jar. Ejecuta scripts\build.cmd primero. 1>&2
    popd
    exit /b 1
)
java -jar ".build\server.jar" %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
