#!/bin/bash
# Windows 版を to-fu（Windows 11）で作って試す。手元（Linux）から ssh で投げる（emu.sh と同じやり方）
#   scripts/win.sh engine           エンジン（Kotlin）を手元でビルドして to-fu へ送る
#   scripts/win.sh data             辞書・zenz のモデルを to-fu の %LOCALAPPDATA%\Kanade\data へ送る
#   scripts/win.sh tsf [Release]    TSF の DLL（windows/tsf）を to-fu の MSVC でビルドする
#   scripts/win.sh eng <args...>    to-fu でエンジンを動かす（例: eng type '{Henkan}nihongo'）
#   scripts/win.sh ps '<command>'   to-fu で PowerShell の命令を実行
# to-fu の置き場所: C:\Users\MoomA\kanade（engine・tsf-src・tsf-build）。JDK は Android Studio 同梱の JBR 21 を使う
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}
REMOTE='C:\Users\MoomA\kanade'
JBR='C:\Program Files\Android\Android Studio\jbr'
VCVARS='C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ps() { ssh -o BatchMode=yes tofu "$1" | tr -d '\r'; }

case "$1" in
  engine)
    ./gradlew -q :desktop:installDist
    tar -C desktop/build/install -czf "$TMP/engine.tgz" kanade-engine
    ps "New-Item -ItemType Directory -Force $REMOTE | Out-Null"
    scp -q -o BatchMode=yes "$TMP/engine.tgz" "tofu:kanade/engine.tgz"
    ps "Set-Location $REMOTE; if (Test-Path engine) { Remove-Item -Recurse -Force engine }; tar -xzf engine.tgz; Rename-Item kanade-engine engine; Remove-Item engine.tgz"
    echo "engine -> $REMOTE\\engine" ;;
  data)
    A=app/build/generated/kanadeAssets
    ps "New-Item -ItemType Directory -Force \$env:LOCALAPPDATA\\Kanade\\data | Out-Null"
    scp -q -o BatchMode=yes $A/system.kdic.kgz $A/connection.kcon.kgz $A/pos.kpos $A/symbol.ksym $A/emoji.ksym $A/zenz.gguf "tofu:AppData/Local/Kanade/data/"
    echo "data -> %LOCALAPPDATA%\\Kanade\\data" ;;
  tsf)
    CONFIG=${2:-Debug}
    tar -C windows -czf "$TMP/tsf.tgz" tsf
    ps "New-Item -ItemType Directory -Force $REMOTE | Out-Null"
    scp -q -o BatchMode=yes "$TMP/tsf.tgz" "tofu:kanade/tsf.tgz"
    ps "Set-Location $REMOTE; if (Test-Path tsf-src) { Remove-Item -Recurse -Force tsf-src }; tar -xzf tsf.tgz; Rename-Item tsf tsf-src; Remove-Item tsf.tgz"
    ssh -o BatchMode=yes tofu "cmd /c \"\"$VCVARS\" >nul && cmake -S $REMOTE\\tsf-src -B $REMOTE\\tsf-build -G Ninja -DCMAKE_BUILD_TYPE=$CONFIG && cmake --build $REMOTE\\tsf-build\"" | tr -d '\r' ;;
  eng)
    shift
    args=""
    for a in "$@"; do args+=" '${a//\'/\'\'}'"; done
    ps "\$env:JAVA_HOME='$JBR'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; & $REMOTE\\engine\\bin\\kanade-engine.bat$args" ;;
  ps)
    ps "$2" ;;
  *)
    sed -n 2,9p "$0"; exit 2 ;;
esac
