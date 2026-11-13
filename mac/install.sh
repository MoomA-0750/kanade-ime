#!/bin/bash
# Kanade の macOS 版を入れる（sudo は要らない）。配布物のフォルダの中で動かす。
#   エンジン（実行環境・辞書）は ~/Library/Application Support/Kanade/engine、入力方式は ~/Library/Input Methods/Kanade.app
#   --voice: 音声入力のモデル（日本語の parakeet-ja 656MB・ReazonSpeech 73MB と、英語の parakeet-tdt v3 670MB）も落とす
set -euo pipefail
cd "$(dirname "$0")"
VOICE=0
for a in "$@"; do
  case "$a" in
    --voice) VOICE=1 ;;
    *) echo "使い方: install.sh [--voice]"; exit 2 ;;
  esac
done
SUPPORT="$HOME/Library/Application Support/Kanade"
DEST="$SUPPORT/engine"
IM="$HOME/Library/Input Methods"

# 動いているものを止める（新しい版を読み込ませる）
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true
pkill -x Kanade 2>/dev/null || true

mkdir -p "$DEST/data" "$IM"
# 落とした音声のモデル（data の下のフォルダ）は残す
for d in bin lib native runtime; do rm -rf "${DEST:?}/$d"; done
cp -R engine/bin engine/lib engine/native engine/runtime "$DEST/"
cp engine/data/* "$DEST/data/"
# 前の Kanade.app はゴミ箱へ
if [ -e "$IM/Kanade.app" ]; then
  mkdir -p "$HOME/.Trash"
  mv "$IM/Kanade.app" "$HOME/.Trash/Kanade-$(date +%Y%m%d%H%M%S).app"
fi
cp -R Kanade.app "$IM/"
[ -d "Kanade Settings.app" ] && rm -rf "$HOME/Applications/Kanade Settings.app" && mkdir -p "$HOME/Applications" && cp -R "Kanade Settings.app" "$HOME/Applications/"
cp MAC-USAGE.md "$SUPPORT/" 2>/dev/null || true
# 入力ソースに登録して有効にする
"$IM/Kanade.app/Contents/MacOS/Kanade" --install || true
if [ "$VOICE" = 1 ]; then
  # 一覧は desktop/…/VoiceModels.kt・windows/settings/VoiceModels.cs と同じ版
  fetch() { # <置き場所> <URL>
    if [ -s "$1" ]; then return; fi
    mkdir -p "$(dirname "$1")"
    curl -fsSL --retry 3 -C - -o "$1.part" "$2" && mv "$1.part" "$1"
  }
  P=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8
  for f in model.int8.onnx tokens.txt; do fetch "$DEST/data/parakeet-ja/$f" "$P/$f"; done
  E=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8
  for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do fetch "$DEST/data/parakeet-v3/$f" "$E/$f"; done
  R=https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main
  for f in encoder-epoch-35-avg-1.int8.onnx decoder-epoch-35-avg-1.int8.onnx joiner-epoch-35-avg-1.int8.onnx tokens.txt; do
    fetch "$DEST/data/reazonspeech/$f" "$R/$f"
  done
  echo "音声のモデル -> $DEST/data"
fi
echo "入れました。入力ソース（メニューバー）で Kanade を選ぶと使えます。"
echo "音声入力には、初めて使うときにマイクと、アクセシビリティ（システム設定 → プライバシーとセキュリティ）の許可が要ります。"
