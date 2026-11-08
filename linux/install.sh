#!/bin/bash
# Kanade の Linux 版を入れる（配布物を展開した中で動かす）。
#   エンジン: ~/.local/share/kanade（設定は ~/.config/kanade、記録は ~/.local/state/kanade/engine.log）
#   fcitx5 のアドオン: ~/.local/share/kanade/fcitx5/kanade.so と ~/.local/share/fcitx5/{addon,inputmethod}/kanade.conf（sudo は要らない。
#   アドオンの設定の Library に .so の場所をフルパスで書くと、fcitx5 は利用者のフォルダーからも読む）
#   設定の画面: ~/.local/share/kanade/bin/kanade-settings（選び役。アプリの一覧にも「Kanade の設定」を足す）。
#   本体に入るのは GTK 版（bin/kanade-settings-gtk。python-gobject と libadwaita が要る）
#   --voice: 音声入力のモデル（日本語の parakeet-ja 656MB・ReazonSpeech 73MB と、英語の parakeet-tdt v3 670MB）も落とす
#   --add-im: fcitx5 の入力方式の一覧に Kanade を足す（既定の入力方式は変えない）
#   --qt: 設定の画面の QML 版（~/.local/share/kanade/qt/kanade-settings-qt。qt6-base・qt6-declarative が要る）だけを入れる。
#     本体を入れたあとに別に動かす。入れると GNOME 以外（Hyprland・Omarchy・KDE など）では QML 版が開く。外すのは uninstall.sh --qt
set -euo pipefail
cd "$(dirname "$0")"
VOICE=0; ADD_IM=0; QT=0
for a in "$@"; do
  case "$a" in
    --voice) VOICE=1 ;;
    --add-im) ADD_IM=1 ;;
    --qt) QT=1 ;;
    *) echo "使い方: install.sh [--voice] [--add-im] | install.sh --qt"; exit 2 ;;
  esac
done
DEST=${XDG_DATA_HOME:-$HOME/.local/share}/kanade

if [ "$QT" = 1 ]; then
  # QML 版だけ（エンジンと fcitx5 には触らない）
  if [ ! -x qt/kanade-settings-qt ]; then
    echo "この配布物には QML 版が入っていません（組むときに Qt 6 の開発用のファイルが要ります）"; exit 1
  fi
  if [ ! -x "$DEST/bin/kanade-settings" ]; then
    echo "先に本体を入れてください（install.sh）"; exit 1
  fi
  rm -rf "$DEST/qt"
  mkdir -p "$DEST/qt"
  install -m755 qt/kanade-settings-qt "$DEST/qt/kanade-settings-qt"
  echo "設定の画面の QML 版 -> $DEST/qt/kanade-settings-qt"
  if ldd "$DEST/qt/kanade-settings-qt" 2>/dev/null | grep -q "not found"; then
    echo "QML 版には Qt 6（6.5 以上）の Qt Quick が要ります（Arch: pacman -S qt6-base qt6-declarative qt6-wayland）"
  fi
  exit 0
fi

# 動いているエンジンを止める（新しいものは、次のキーでアドオンが起こす）
pkill -f 'dev.mooma.kanade.desktop.MainKt serve' 2>/dev/null || true

mkdir -p "$DEST/data"
# 落とした音声のモデルは data に残す（入れ直しで消さない）
rm -rf "$DEST/bin" "$DEST/lib" "$DEST/native" "$DEST/runtime" "$DEST/settings" "$DEST/fcitx5"
cp -r bin lib native runtime settings "$DEST/"
cp data/* "$DEST/data/"
cp THIRD-PARTY.md "$DEST/"
[ -f LINUX-USAGE.md ] && cp LINUX-USAGE.md "$DEST/"
cp uninstall.sh "$DEST/"
echo "エンジン -> $DEST"
if [ -d "$DEST/qt" ] && [ -x qt/kanade-settings-qt ]; then
  echo "設定の画面の QML 版も入れ直すなら install.sh --qt"
elif [ ! -d "$DEST/qt" ] && [ -x qt/kanade-settings-qt ]; then
  echo "設定の画面の QML 版（Hyprland・Omarchy・KDE 向け）を入れるなら install.sh --qt"
fi
APPS=${XDG_DATA_HOME:-$HOME/.local/share}/applications
mkdir -p "$APPS"
sed "s|@BIN@|$DEST/bin|" settings/dev.mooma.kanade.Settings.desktop > "$APPS/dev.mooma.kanade.Settings.desktop"
if ! python3 -c 'import gi; gi.require_version("Adw", "1")' 2>/dev/null; then
  echo "設定の画面の GTK 版には python-gobject と libadwaita が要ります（Arch: pacman -S python-gobject libadwaita、Debian・Ubuntu: apt install python3-gi gir1.2-adw-1）"
fi

FCITX_USER=${XDG_DATA_HOME:-$HOME/.local/share}/fcitx5
install -Dm755 fcitx5/kanade.so "$DEST/fcitx5/kanade.so"
mkdir -p "$FCITX_USER/addon" "$FCITX_USER/inputmethod"
sed "s|^Library=kanade$|Library=$DEST/fcitx5/kanade|" fcitx5/kanade-addon.conf > "$FCITX_USER/addon/kanade.conf"
cp fcitx5/kanade.conf "$FCITX_USER/inputmethod/kanade.conf"
echo "fcitx5 のアドオン -> $DEST/fcitx5/kanade.so"
# 前の版は /usr に入れていた（利用者のフォルダーの設定が先に読まれるので、残っていても使われない）
for f in /usr/lib/fcitx5/kanade.so /usr/lib64/fcitx5/kanade.so /usr/share/fcitx5/addon/kanade.conf; do
  [ -e "$f" ] && echo "前の版のファイルが残っています（使われません。消すなら sudo rm $f）"
done

# fcitx5 が systemd のユーザーのサービスで動いていれば、その名前（ssh から起こすと Wayland の環境がないまま動くので、サービスで起こし直す）
FCITX_PID=$(pgrep -x fcitx5 | head -1 || true)
FCITX_UNIT=""; [ -n "$FCITX_PID" ] && FCITX_UNIT=$(ps -o uunit= -p "$FCITX_PID" | tr -d ' ')
case "$FCITX_UNIT" in *.service) ;; *) FCITX_UNIT="" ;; esac

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

PROFILE=${XDG_CONFIG_HOME:-$HOME/.config}/fcitx5/profile
if [ "$ADD_IM" = 1 ] && [ -f "$PROFILE" ] && ! grep -q '^Name=kanade$' "$PROFILE"; then
  # fcitx5 が動いたまま書き換えると、終了するときに上書きされる。止めてから足して、起こし直す
  cp "$PROFILE" "$PROFILE.before-kanade"
  pid=$FCITX_PID; unit=$FCITX_UNIT
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
  # アドオンを入れ替えたので、fcitx5 を起こし直して読ませる
  if [ -n "$FCITX_UNIT" ]; then
    systemctl --user restart "$FCITX_UNIT"
  elif [ -n "$FCITX_PID" ]; then
    echo "新しいアドオンを読ませるため、fcitx5 を起こし直してください（fcitx5 -r -d）"
  fi
  if [ -f "$PROFILE" ] && grep -q '^Name=kanade$' "$PROFILE"; then
    echo "fcitx5 の入力方式の一覧に Kanade はもうあります"
  else
    echo "fcitx5 の設定（fcitx5-configtool）で、入力方式に「Kanade」を足してください（または install.sh --add-im）"
  fi
fi
