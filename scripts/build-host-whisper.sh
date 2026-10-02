#!/bin/bash
# 手元（Linux x86_64）向けに libkanade_whisper.so をビルドする。dictool の whisper で使う
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
CMAKE=$SDK/cmake/3.31.6/bin
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}
"$CMAKE/cmake" -S native/whisper -B .cache/host-whisper -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE/ninja" -DCMAKE_BUILD_TYPE=Release
"$CMAKE/cmake" --build .cache/host-whisper --target kanade_whisper -j"$(nproc)"
ls -la .cache/host-whisper/libkanade_whisper.so
