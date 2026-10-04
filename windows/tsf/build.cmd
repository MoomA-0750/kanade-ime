@echo off
rem to-fu で TSF の DLL をビルドする（scripts/win.sh tsf から呼ぶ）。引数は Debug か Release、2つ目に x86 を付けると 32bit 版（tsf-build-x86）
if /i "%2"=="x86" (set ARCH=vcvars32.bat) else (set ARCH=vcvars64.bat)
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\%ARCH%" >nul || exit /b 1
if /i "%2"=="x86" (set OUT=%~dp0..\tsf-build-x86) else if /i "%1"=="Release" (set OUT=%~dp0..\tsf-build-release) else (set OUT=%~dp0..\tsf-build)
cmake -S "%~dp0." -B "%OUT%" -G Ninja -DCMAKE_BUILD_TYPE=%1 || exit /b 1
cmake --build "%OUT%"
