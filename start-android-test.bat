@echo off
pushd "%~dp0"

echo ClearTune Android one-click test
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\android-test.ps1" %*
set "TEST_EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%TEST_EXIT_CODE%"=="0" (
    echo Test startup failed. Review the error above.
) else (
    echo Command completed successfully.
)
echo.
pause
popd
exit /b %TEST_EXIT_CODE%
