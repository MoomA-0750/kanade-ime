@echo off
rem to-fu で Windows 版のエンジンが使うネイティブ（kanade_zenz.dll・kanade_whisper.dll）をビルドする（scripts/win.sh zenz / whisper から呼ぶ）
rem %1 はソースの置き場所（native と third_party がある）、%2 は jni.h のあるフォルダ、%3 は zenz か whisper
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
if "%3"=="whisper" (set SRC=%~1\native\whisper) else (set SRC=%~1\native)
cmake -S "%SRC%" -B "%~1\build-%3" -G Ninja -DCMAKE_BUILD_TYPE=Release -DKANADE_JNI_INCLUDE="%~2" || exit /b 1
cmake --build "%~1\build-%3" --target kanade_%3
