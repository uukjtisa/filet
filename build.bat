@echo off
setlocal EnableDelayedExpansion
rem ============================================================================
rem  Filet build front-end.
rem
rem  Two ways in, one set of actions underneath:
rem
rem    build.bat                 a menu, with the phone detected and named
rem    build.bat <verb> [verb..] straight through, no prompts
rem
rem  Verbs:
rem    check      node tools/check-all.mjs
rem    test       unit tests
rem    apk        assemble the github debug APK
rem    install    adb install -r the APK that is already built
rem    go         test + apk + install          (the usual one)
rem    fast       apk + install                 (no tests - for a UI tweak)
rem    all        check + test + apk + install
rem    shot       screenshot the phone to .unlazy\shot.png
rem    logs       adb logcat, filtered to this app
rem    diag       ask the app to write a diagnostics dump
rem    clean      gradlew clean
rem    stop       stop the gradle daemons
rem    devices    list adb devices
rem
rem  Exit code is non-zero the moment a step fails, so chaining is safe.
rem ============================================================================

cd /d "%~dp0"

set "ADB=D:\Android-SDK\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "APK=app\build\outputs\apk\github\debug\app-github-debug.apk"
set "PKG=dev.niccc2007.filet.debug"
set "GRADLE=%~dp0gradlew.bat"

if "%~1"=="" goto :menu

:argloop
if "%~1"=="" goto :done
call :run %1
if errorlevel 1 exit /b 1
shift
goto :argloop
:done
exit /b 0

rem ---------------------------------------------------------------------------
rem  The phone. Detected rather than assumed, and named rather than serialled -
rem  "is my phone plugged in" is answered by seeing the model, not a hex string.
rem ---------------------------------------------------------------------------
:phone
set "PHONE="
set "PHONE_STATE="
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices 2^>nul') do (
    if not "%%a"=="" if "!PHONE_SERIAL!"=="" (
        set "PHONE_SERIAL=%%a"
        set "PHONE_STATE=%%b"
    )
)
if "%PHONE_SERIAL%"=="" (
    set "PHONE=no device"
    exit /b 0
)
if /i not "%PHONE_STATE%"=="device" (
    set "PHONE=%PHONE_SERIAL% (%PHONE_STATE% - unlock it, or allow USB debugging)"
    exit /b 0
)
for /f "delims=" %%m in ('"%ADB%" shell getprop ro.product.model 2^>nul') do set "MODEL=%%m"
for /f "delims=" %%v in ('"%ADB%" shell getprop ro.build.version.release 2^>nul') do set "ANDROID=%%v"
set "PHONE=%MODEL% - Android %ANDROID%"
exit /b 0

rem ---------------------------------------------------------------------------
:menu
cls
echo.
echo   FILET
echo   ============================================================
call :phone
echo   Phone:  %PHONE%
if exist "%APK%" (
    for %%f in ("%APK%") do echo   APK:    built %%~tf  (%%~zf bytes^)
) else (
    echo   APK:    not built yet
)
echo   ============================================================
echo.
echo     1  Build + install          (test, apk, install^)
echo     2  Build + install, FAST    (no tests^)
echo     3  Install the built APK
echo     4  Tests only
echo     5  Checkers only
echo     6  Everything               (checkers, tests, apk, install^)
echo.
echo     7  Screenshot the phone
echo     8  Watch the log
echo     9  Diagnostics dump
echo.
echo     c  Clean       s  Stop gradle       d  adb devices
echo     q  Quit
echo.
set "CHOICE="
set /p "CHOICE=  > "
if /i "%CHOICE%"=="1" (call :run go     & goto :after)
if /i "%CHOICE%"=="2" (call :run fast   & goto :after)
if /i "%CHOICE%"=="3" (call :run install& goto :after)
if /i "%CHOICE%"=="4" (call :run test   & goto :after)
if /i "%CHOICE%"=="5" (call :run check  & goto :after)
if /i "%CHOICE%"=="6" (call :run all    & goto :after)
if /i "%CHOICE%"=="7" (call :run shot   & goto :after)
if /i "%CHOICE%"=="8" (call :run logs   & goto :after)
if /i "%CHOICE%"=="9" (call :run diag   & goto :after)
if /i "%CHOICE%"=="c" (call :run clean  & goto :after)
if /i "%CHOICE%"=="s" (call :run stop   & goto :after)
if /i "%CHOICE%"=="d" (call :run devices& goto :after)
if /i "%CHOICE%"=="q" exit /b 0
goto :menu

:after
echo.
if errorlevel 1 (echo   ^>^> FAILED) else (echo   ^>^> done)
echo.
pause
goto :menu

rem ---------------------------------------------------------------------------
rem  One verb.
rem ---------------------------------------------------------------------------
:run
set "V=%~1"

if /i "%V%"=="go"   (call :run test && call :run apk && call :run install & exit /b !errorlevel!)
if /i "%V%"=="fast" (call :run apk && call :run install & exit /b !errorlevel!)
if /i "%V%"=="all"  (call :run check && call :run test && call :run apk && call :run install & exit /b !errorlevel!)

if /i "%V%"=="check" (
    echo [check] node tools/check-all.mjs
    node tools\check-all.mjs
    exit /b !errorlevel!
)
if /i "%V%"=="test" (
    echo [test] unit tests
    call "%GRADLE%" :app:testGithubDebugUnitTest
    exit /b !errorlevel!
)
if /i "%V%"=="apk" (
    echo [apk] assembleGithubDebug
    call "%GRADLE%" :app:assembleGithubDebug
    exit /b !errorlevel!
)
if /i "%V%"=="install" (
    if not exist "%APK%" (
        echo [install] nothing built yet - run "build.bat apk" first
        exit /b 1
    )
    call :phone
    if "!PHONE!"=="no device" (
        echo [install] no phone on adb. Plug it in and allow USB debugging.
        exit /b 1
    )
    echo [install] !PHONE!
    "%ADB%" install -r "%APK%"
    exit /b !errorlevel!
)
if /i "%V%"=="shot" (
    if not exist ".unlazy" mkdir ".unlazy"
    "%ADB%" shell screencap -p /sdcard/filet-shot.png
    "%ADB%" pull /sdcard/filet-shot.png ".unlazy\shot.png"
    "%ADB%" shell rm /sdcard/filet-shot.png
    echo [shot] .unlazy\shot.png
    exit /b !errorlevel!
)
if /i "%V%"=="logs" (
    echo [logs] Ctrl+C to stop
    set "PID="
    for /f %%p in ('"%ADB%" shell pidof -s %PKG% 2^>nul') do set "PID=%%p"
    if "!PID!"=="" (
        echo [logs] the app is not running - start it first
        exit /b 1
    )
    "%ADB%" logcat --pid=!PID!
    exit /b !errorlevel!
)
if /i "%V%"=="diag" (
    rem Writes the app's own diagnostics file. See `FiletLog.requestDump` and `DiagReceiver`.
    "%ADB%" shell am broadcast -a dev.niccc2007.filet.DIAG -n %PKG%/dev.niccc2007.filet.log.DiagReceiver
    echo [diag] written into the app's log folder
    exit /b !errorlevel!
)
if /i "%V%"=="clean" (
    call "%GRADLE%" clean
    exit /b !errorlevel!
)
if /i "%V%"=="stop" (
    call "%GRADLE%" --stop
    exit /b !errorlevel!
)
if /i "%V%"=="devices" (
    "%ADB%" devices -l
    exit /b 0
)

echo Unknown verb "%V%". Run build.bat with no arguments for the menu.
exit /b 2
