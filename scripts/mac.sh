#!/bin/bash
# macOS 版を Mac の上で組み立てて入れる（開発用）。ネイティブと入力方式のアプリは Mac の上でビルドする。
#   scripts/mac.sh bundle            エンジンの jar・辞書・ネイティブと mac/ のソースを .cache/mac-bundle.tgz にまとめる
#   scripts/mac.sh build <host>      束を送って、相手で mac/build.sh を動かす（配布物は ~/kanade-build/dist）
#   scripts/mac.sh install <host> [--voice]   相手で組み立てた配布物を入れる
#   scripts/mac.sh fetch <host>      相手で作った配布物の tar.gz を dist/ へ持ってくる
#   scripts/mac.sh log <host>        エンジンの記録の末尾
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}
REMOTE=kanade-build
JDK_TAG=jdk-21.0.12.1+1
CMAKE_VER=4.4.5

target_of() { # ssh の相手の CPU から、sherpa-onnx の native の jar の名前を決める
  case "$(ssh -o BatchMode=yes "$1" uname -m)" in
    arm64) echo osx-aarch64 ;;
    x86_64) echo osx-x64 ;;
    *) echo "対応していない CPU" >&2; exit 1 ;;
  esac
}

bundle() { # <kanade.target>
  local target=$1 tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  ./gradlew -q :desktop:installDist -Pkanade.target="$target"
  A=app/build/generated/kanadeAssets
  mkdir -p "$tmp/b/engine" "$tmp/b/data" "$tmp/b/docs" .cache
  cp -r desktop/build/install/kanade-engine/lib "$tmp/b/engine/lib"
  cp $A/system.kdic.kgz $A/connection.kcon.kgz $A/pos.kpos $A/symbol.ksym $A/emoji.ksym $A/english.ksym $A/zenz.gguf "$tmp/b/data/"
  cp docs/THIRD-PARTY.md "$tmp/b/docs/"
  [ -f docs/MAC-USAGE.md ] && cp docs/MAC-USAGE.md "$tmp/b/docs/"
  tar -czf .cache/mac-bundle.tgz --exclude=.git --exclude=.build \
    --exclude='third_party/llama.cpp/models' --exclude='third_party/llama.cpp/docs' \
    --exclude='third_party/whisper.cpp/models' --exclude='third_party/whisper.cpp/examples' \
    --exclude='third_party/whisper.cpp/bindings' --exclude='third_party/whisper.cpp/samples' \
    native third_party/llama.cpp third_party/whisper.cpp mac -C "$tmp/b" engine data docs
  ls -la .cache/mac-bundle.tgz
}

# JDK（JNI のヘッダーと jlink）と cmake を手元で gh から落として送る（Mac に Homebrew がなくても組めるように）
send_tools() { # <host> <arch>
  local host=$1 arch=$2
  mkdir -p .cache/mac
  if ! ssh -o BatchMode=yes "$host" "test -x ~/.cache/kanade-build/$JDK_TAG/Contents/Home/bin/jlink"; then
    local jdk=OpenJDK21U-jdk_${arch}_mac_hotspot_21.0.12.1_1.tar.gz
    [ -f ".cache/mac/$jdk" ] || gh release download "$JDK_TAG" -R adoptium/temurin21-binaries -p "$jdk" -D .cache/mac
    ssh -o BatchMode=yes "$host" "mkdir -p ~/.cache/kanade-build"
    scp -q -o BatchMode=yes ".cache/mac/$jdk" "$host:.cache/kanade-build/jdk.tar.gz"
    ssh -o BatchMode=yes "$host" "cd ~/.cache/kanade-build && tar -xzf jdk.tar.gz && rm jdk.tar.gz"
  fi
  if ! ssh -o BatchMode=yes "$host" "command -v cmake >/dev/null || test -x ~/.cache/kanade-build/cmake-$CMAKE_VER-macos-universal/CMake.app/Contents/bin/cmake"; then
    local cm=cmake-$CMAKE_VER-macos-universal.tar.gz
    [ -f ".cache/mac/$cm" ] || gh release download "v$CMAKE_VER" -R Kitware/CMake -p "$cm" -D .cache/mac
    scp -q -o BatchMode=yes ".cache/mac/$cm" "$host:.cache/kanade-build/cmake.tar.gz"
    ssh -o BatchMode=yes "$host" "cd ~/.cache/kanade-build && tar -xzf cmake.tar.gz && rm cmake.tar.gz"
  fi
}

case "${1:-}" in
  bundle)
    bundle "${2:-osx-aarch64}" ;;
  build)
    HOST=$2
    TARGET=$(target_of "$HOST")
    bundle "$TARGET"
    ARCH=${TARGET#osx-}
    send_tools "$HOST" "$ARCH"
    ssh -o BatchMode=yes "$HOST" "mkdir -p ~/$REMOTE && cd ~/$REMOTE && rm -rf native third_party mac engine data docs"
    scp -q -o BatchMode=yes .cache/mac-bundle.tgz "$HOST:$REMOTE/bundle.tgz"
    ssh -o BatchMode=yes "$HOST" "cd ~/$REMOTE && tar -xzf bundle.tgz && rm bundle.tgz && bash mac/build.sh" ;;
  install)
    HOST=$2; shift 2
    ssh -o BatchMode=yes "$HOST" "bash -c 'cd ~/$REMOTE/dist/Kanade-mac-*/ && ./install.sh $*'" ;;
  fetch)
    mkdir -p dist
    scp -o BatchMode=yes "$2:$REMOTE/dist/Kanade-mac-*.tar.gz" dist/ ;;
  log)
    ssh -o BatchMode=yes "$2" "tail -n ${3:-40} ~/Library/Logs/Kanade/engine.log" ;;
  *)
    sed -n 2,8p "$0"; exit 2 ;;
esac
