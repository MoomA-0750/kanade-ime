#!/bin/bash
# Linux 版（fcitx5）を別の Linux の機械で組み立てて入れる（開発用）。ネイティブは相手の機械の上でビルドする。
#   scripts/linux.sh bundle            エンジンの jar・辞書・ネイティブのソースを .cache/linux-bundle.tgz にまとめる
#   scripts/linux.sh build <host>      束を送って、相手で linux/build.sh を動かす（配布物は ~/kanade-build/dist）
#   scripts/linux.sh install <host> [--voice] [--add-im]   相手で組み立てた配布物を入れる
#   scripts/linux.sh fetch <host>      相手で作った配布物の tar.gz を dist/ へ持ってくる
#   scripts/linux.sh log <host>        エンジンの記録の末尾
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}
REMOTE=kanade-build

target_of() { # ssh の相手の CPU から、sherpa-onnx の native の jar の名前を決める
  case "$(ssh -o BatchMode=yes "$1" uname -m)" in
    aarch64|arm64) echo linux-aarch64 ;;
    x86_64) echo linux-x64 ;;
    *) echo "対応していない CPU" >&2; exit 1 ;;
  esac
}

bundle() { # <kanade.target>
  local target=$1 tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  ./gradlew -q :desktop:installDist -Pkanade.target="$target"
  A=app/build/generated/kanadeAssets
  mkdir -p "$tmp/b/engine" "$tmp/b/data" "$tmp/b/docs"
  cp -r desktop/build/install/kanade-engine/lib "$tmp/b/engine/lib"
  cp $A/system.kdic.kgz $A/connection.kcon.kgz $A/pos.kpos $A/symbol.ksym $A/emoji.ksym $A/english.ksym $A/zenz.gguf "$tmp/b/data/"
  cp docs/THIRD-PARTY.md "$tmp/b/docs/"
  [ -f docs/LINUX-USAGE.md ] && cp docs/LINUX-USAGE.md "$tmp/b/docs/"
  tar -czf .cache/linux-bundle.tgz --exclude=.git \
    --exclude='third_party/llama.cpp/models' --exclude='third_party/llama.cpp/docs' \
    --exclude='third_party/whisper.cpp/models' --exclude='third_party/whisper.cpp/examples' \
    --exclude='third_party/whisper.cpp/bindings' --exclude='third_party/whisper.cpp/samples' \
    native third_party/llama.cpp third_party/whisper.cpp linux -C "$tmp/b" engine data docs
  ls -la .cache/linux-bundle.tgz
}

case "${1:-}" in
  bundle)
    bundle "${2:-linux-aarch64}" ;;
  build)
    HOST=$2
    TARGET=$(target_of "$HOST")
    bundle "$TARGET"
    # JDK（JNI のヘッダーと jlink）を手元で gh から落として送る（相手の curl では GitHub が 504 を返すことがある）
    if ! ssh -o BatchMode=yes "$HOST" "test -x ~/.cache/kanade-build/jdk-21.0.12.1+1/bin/jlink"; then
      ARCH=${TARGET#linux-}
      mkdir -p .cache/jdk
      JDK=OpenJDK21U-jdk_${ARCH}_linux_hotspot_21.0.12.1_1.tar.gz
      [ -f ".cache/jdk/$JDK" ] || gh release download jdk-21.0.12.1+1 -R adoptium/temurin21-binaries -p "$JDK" -D .cache/jdk
      ssh -o BatchMode=yes "$HOST" "mkdir -p ~/.cache/kanade-build"
      scp -q -o BatchMode=yes ".cache/jdk/$JDK" "$HOST:.cache/kanade-build/jdk.tar.gz"
    fi
    ssh -o BatchMode=yes "$HOST" "mkdir -p ~/$REMOTE && cd ~/$REMOTE && rm -rf native third_party linux engine data docs"
    scp -q -o BatchMode=yes .cache/linux-bundle.tgz "$HOST:$REMOTE/bundle.tgz"
    ssh -o BatchMode=yes "$HOST" "cd ~/$REMOTE && tar -xzf bundle.tgz && rm bundle.tgz && bash linux/build.sh" ;;
  install)
    HOST=$2; shift 2
    ssh -o BatchMode=yes "$HOST" "bash -c 'cd ~/$REMOTE/dist/Kanade-linux-*/ && ./install.sh $*'" ;;
  fetch)
    mkdir -p dist
    scp -o BatchMode=yes "$2:$REMOTE/dist/Kanade-linux-*.tar.gz" dist/ ;;
  log)
    ssh -o BatchMode=yes "$2" "tail -n ${3:-40} \${XDG_STATE_HOME:-\$HOME/.local/state}/kanade/engine.log" ;;
  *)
    sed -n 2,8p "$0"; exit 2 ;;
esac
