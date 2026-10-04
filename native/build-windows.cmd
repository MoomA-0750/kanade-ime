@echo off
rem to-fu で kanade_zenz.dll（Windows 版のエンジンが使う zenz）をビルドする（scripts/win.sh zenz から呼ぶ）
rem %1 はソースの置き場所（native と third_party がある）、%2 は jni.h のあるフォルダ
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cmake -S "%~1\native" -B "%~1\build-zenz" -G Ninja -DCMAKE_BUILD_TYPE=Release -DKANADE_JNI_INCLUDE="%~2" || exit /b 1
cmake --build "%~1\build-zenz" --target kanade_zenz
