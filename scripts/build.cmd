@echo off
setlocal
pushd "%~dp0.." || exit /b 1
call mvn package %* -Dgigapixel.buildDirectory=.build
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
