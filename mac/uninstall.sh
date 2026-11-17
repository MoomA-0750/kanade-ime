#!/bin/bash
# Kanade の macOS 版を外す。設定・ユーザー辞書・学習と落とした音声のモデルは --all のときだけ消す（消したものはゴミ箱へ）
set -euo pipefail
SUPPORT="$HOME/Library/Application Support/Kanade"
IM="$HOME/Library/Input Methods/Kanade.app"
[ -x "$IM/Contents/MacOS/Kanade" ] && "$IM/Contents/MacOS/Kanade" --uninstall || true
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true
pkill -x Kanade 2>/dev/null || true
TRASH="$HOME/.Trash/Kanade-$(date +%Y%m%d%H%M%S)"
mkdir -p "$TRASH"
[ -e "$IM" ] && mv "$IM" "$TRASH/"
[ -e "$HOME/Applications/Kanade Settings.app" ] && mv "$HOME/Applications/Kanade Settings.app" "$TRASH/"
if [ "${1:-}" = "--all" ]; then
  [ -e "$SUPPORT" ] && mv "$SUPPORT" "$TRASH/support"
else
  for d in bin lib native runtime VERSION; do [ -e "$SUPPORT/engine/$d" ] && mv "$SUPPORT/engine/$d" "$TRASH/"; done
fi
echo "外しました（消したものは $TRASH）。入力ソースの一覧に残っていれば、システム設定のキーボードから外してください"
