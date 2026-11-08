#!/bin/bash
# Kanade の Linux 版を外す。設定（~/.config/kanade）と、落とした音声のモデルは --all のときだけ消す（ゴミ箱へ）
#   --qt: 設定の画面の QML 版だけを外す（入口の kanade-settings は GTK 版を開くようになる）
set -euo pipefail
DEST=${XDG_DATA_HOME:-$HOME/.local/share}/kanade
if [ "${1:-}" = "--qt" ]; then
  if [ -e "$DEST/qt" ]; then
    TRASH="$HOME/.local/share/Trash/kanade-qt-$(date +%Y%m%d%H%M%S)"
    mkdir -p "$TRASH"
    mv "$DEST/qt" "$TRASH/"
    echo "設定の画面の QML 版を外しました（$TRASH）。これからは GTK 版が開きます"
  else
    echo "設定の画面の QML 版は入っていません"
  fi
  exit 0
fi
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
for d in bin lib native runtime settings fcitx5 qt; do [ -e "$DEST/$d" ] && mv "$DEST/$d" "$TRASH/"; done
if [ "${1:-}" = "--all" ]; then
  [ -e "$DEST" ] && mv "$DEST" "$TRASH/share"
  [ -e "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" ] && mv "${XDG_CONFIG_HOME:-$HOME/.config}/kanade" "$TRASH/config"
fi
fcitx5-remote -r 2>/dev/null || true
echo "外しました（消したものは $TRASH）。fcitx5 の入力方式の一覧から Kanade を外してください"
