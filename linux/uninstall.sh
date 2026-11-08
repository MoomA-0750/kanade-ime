#!/bin/bash
# Kanade の Linux 版を外す。設定（~/.config/kanade）と、落とした音声のモデルは --all のときだけ消す（~/.local/share/kanade/.trash へ）
set -euo pipefail
DEST=${XDG_DATA_HOME:-$HOME/.local/share}/kanade
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true
for d in /usr/lib/fcitx5 /usr/lib64/fcitx5 /usr/lib/x86_64-linux-gnu/fcitx5 /usr/lib/aarch64-linux-gnu/fcitx5; do
  [ -f "$d/kanade.so" ] && sudo rm -f "$d/kanade.so"
done
sudo rm -f /usr/share/fcitx5/addon/kanade.conf /usr/share/fcitx5/inputmethod/kanade.conf
TRASH="$HOME/.local/share/Trash/kanade-$(date +%Y%m%d%H%M%S)"
mkdir -p "$TRASH"
for d in bin lib native runtime; do [ -e "$DEST/$d" ] && mv "$DEST/$d" "$TRASH/"; done
if [ "${1:-}" = "--all" ]; then
  [ -e "$DEST" ] && mv "$DEST" "$TRASH/share"
  [ -e "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" ] && mv "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" "$TRASH/config"
fi
fcitx5-remote -r 2>/dev/null || true
echo "外しました（消したものは $TRASH）。fcitx5 の入力方式の一覧から Kanade を外してください"
