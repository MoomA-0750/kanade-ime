@echo off
rem to-fu で TSF の DLL をビルドする（scripts/win.sh tsf から呼ぶ）。引数は Debug か Release（Release は tsf-build-release に作る）
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
if /i "%1"=="Release" (set OUT=%~dp0..\tsf-build-release) else (set OUT=%~dp0..\tsf-build)
cmake -S "%~dp0." -B "%OUT%" -G Ninja -DCMAKE_BUILD_TYPE=%1 || exit /b 1
cmake --build "%OUT%"
