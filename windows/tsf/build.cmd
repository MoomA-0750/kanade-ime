@echo off
rem to-fu で TSF の DLL をビルドする（scripts/win.sh tsf から呼ぶ）。引数は Debug か Release
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cmake -S "%~dp0." -B "%~dp0..\tsf-build" -G Ninja -DCMAKE_BUILD_TYPE=%1 || exit /b 1
cmake --build "%~dp0..\tsf-build"
