#!/bin/bash
# Kanade の Linux 版を入れる（配布物を展開した中で動かす）。
#   エンジン: ~/.local/share/kanade（設定は ~/.config/kanade、記録は ~/.local/state/kanade/engine.log）
#   fcitx5 のアドオン: /usr/lib/fcitx5・/usr/share/fcitx5（sudo を使う）
#   --voice: 音声入力のモデル（parakeet-ja 656MB・ReazonSpeech 73MB）も落とす
#   --add-im: fcitx5 の入力方式の一覧に Kanade を足す（既定の入力方式は変えない）
set -euo pipefail
cd "$(dirname "$0")"
VOICE=0; ADD_IM=0
for a in "$@"; do
  case "$a" in
    --voice) VOICE=1 ;;
    --add-im) ADD_IM=1 ;;
    *) echo "使い方: install.sh [--voice] [--add-im]"; exit 2 ;;
  esac
done
DEST=${XDG_DATA_HOME:-$HOME/.local/share}/kanade

# 動いているエンジンを止める（新しいものは、次のキーでアドオンが起こす）
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true

mkdir -p "$DEST/data"
# 落とした音声のモデルは data に残す（入れ直しで消さない）
rm -rf "$DEST/bin" "$DEST/lib" "$DEST/native" "$DEST/runtime"
cp -r bin lib native runtime "$DEST/"
cp data/* "$DEST/data/"
cp THIRD-PARTY.md "$DEST/"
[ -f LINUX-USAGE.md ] && cp LINUX-USAGE.md "$DEST/"
cp uninstall.sh "$DEST/"
echo "エンジン -> $DEST"

LIBDIR=/usr/lib/fcitx5
[ -d /usr/lib64/fcitx5 ] && [ ! -d /usr/lib/fcitx5 ] && LIBDIR=/usr/lib64/fcitx5
[ -d /usr/lib/x86_64-linux-gnu/fcitx5 ] && LIBDIR=/usr/lib/x86_64-linux-gnu/fcitx5
[ -d /usr/lib/aarch64-linux-gnu/fcitx5 ] && LIBDIR=/usr/lib/aarch64-linux-gnu/fcitx5
sudo install -Dm755 fcitx5/kanade.so "$LIBDIR/kanade.so"
sudo install -Dm644 fcitx5/kanade-addon.conf /usr/share/fcitx5/addon/kanade.conf
sudo install -Dm644 fcitx5/kanade.conf /usr/share/fcitx5/inputmethod/kanade.conf
echo "fcitx5 のアドオン -> $LIBDIR/kanade.so"

if [ "$VOICE" = 1 ]; then
  # 一覧は desktop/…/VoiceModels.kt・windows/settings/VoiceModels.cs と同じ版
  fetch() { # <置き場所> <URL>
    if [ -s "$1" ]; then return; fi
    mkdir -p "$(dirname "$1")"
    curl -fL --retry 3 -C - -o "$1.part" "$2" && mv "$1.part" "$1"
  }
  P=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8
  for f in model.int8.onnx tokens.txt; do fetch "$DEST/data/parakeet-ja/$f" "$P/$f"; done
  R=https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main
  for f in encoder-epoch-35-avg-1.int8.onnx decoder-epoch-35-avg-1.int8.onnx joiner-epoch-35-avg-1.int8.onnx tokens.txt; do
    fetch "$DEST/data/reazonspeech/$f" "$R/$f"
  done
  echo "音声のモデル -> $DEST/data"
fi

PROFILE=${XDG_CONFIG_HOME:-$HOME/.config}/fcitx5/profile
if [ "$ADD_IM" = 1 ] && [ -f "$PROFILE" ] && ! grep -q '^Name=kanade$' "$PROFILE"; then
  # fcitx5 が動いたまま書き換えると、終了するときに上書きされる。止めてから足して、起こし直す
  cp "$PROFILE" "$PROFILE.before-kanade"
  # systemd のユーザーのサービスで動いていれば、それを止めて起こす（ssh から起こすと Wayland の環境がないまま動く）
  pid=$(pgrep -x fcitx5 | head -1 || true)
  unit=""; [ -n "$pid" ] && unit=$(ps -o unit= -p "$pid" | tr -d ' ')
  case "$unit" in *.service) ;; *) unit="" ;; esac
  if [ -n "$unit" ]; then
    systemctl --user stop "$unit"
  elif [ -n "$pid" ]; then
    echo "fcitx5 を一度止めます。足したあと、デスクトップのセッションから起こし直してください"
    fcitx5-remote -e 2>/dev/null || kill "$pid"; sleep 1
  fi
  python3 - "$PROFILE" <<'PY'
import re, sys
path = sys.argv[1]
s = open(path, encoding="utf-8").read()
items = [int(m) for m in re.findall(r"^\[Groups/0/Items/(\d+)\]", s, re.M)]
n = max(items) + 1 if items else 0
block = f"[Groups/0/Items/{n}]\n# Name\nName=kanade\n# Layout\nLayout=\n\n"
m = re.search(r"^\[GroupOrder\]", s, re.M)
s = s[:m.start()] + block + s[m.start():] if m else s + "\n" + block
open(path, "w", encoding="utf-8").write(s)
PY
  [ -n "$unit" ] && systemctl --user start "$unit"
  echo "fcitx5 の入力方式に Kanade を足した（前のものは $PROFILE.before-kanade）"
else
  fcitx5-remote -r 2>/dev/null || true
  if [ -f "$PROFILE" ] && grep -q '^Name=kanade$' "$PROFILE"; then
    echo "fcitx5 の入力方式の一覧に Kanade はもうあります"
  else
    echo "fcitx5 の設定（fcitx5-configtool）で、入力方式に「Kanade」を足してください（または install.sh --add-im）"
  fi
fi
