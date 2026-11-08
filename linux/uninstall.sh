#!/bin/bash
# Kanade の Linux 版を外す。設定（~/.config/kanade）と、落とした音声のモデルは --all のときだけ消す（~/.local/share/kanade/.trash へ）
set -euo pipefail
DEST=${XDG_DATA_HOME:-$HOME/.local/share}/kanade
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true
FCITX_USER=${XDG_DATA_HOME:-$HOME/.local/share}/fcitx5
rm -f "$FCITX_USER/addon/kanade.conf" "$FCITX_USER/inputmethod/kanade.conf"
# 前の版は /usr に入れていた
for f in /usr/lib/fcitx5/kanade.so /usr/lib64/fcitx5/kanade.so /usr/lib/x86_64-linux-gnu/fcitx5/kanade.so /usr/lib/aarch64-linux-gnu/fcitx5/kanade.so \
  /usr/share/fcitx5/addon/kanade.conf /usr/share/fcitx5/inputmethod/kanade.conf; do
  [ -e "$f" ] && sudo rm -f "$f"
done
TRASH="$HOME/.local/share/Trash/kanade-$(date +%Y%m%d%H%M%S)"
mkdir -p "$TRASH"
rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/applications/dev.mooma.kanade.Settings.desktop"
for d in bin lib native runtime settings fcitx5; do [ -e "$DEST/$d" ] && mv "$DEST/$d" "$TRASH/"; done
if [ "${1:-}" = "--all" ]; then
  [ -e "$DEST" ] && mv "$DEST" "$TRASH/share"
  [ -e "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" ] && mv "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" "$TRASH/config"
fi
fcitx5-remote -r 2>/dev/null || true
echo "外しました（消したものは $TRASH）。fcitx5 の入力方式の一覧から Kanade を外してください"
