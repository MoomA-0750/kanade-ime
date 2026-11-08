#!/bin/bash
# QML 版の各ページを、画面を出さずに（offscreen）撮る。設定などは一時的なフォルダーに作り、本人のファイルには触らない。
#   tests/shoot.sh <kanade-settings-qt> <colors.toml> <出力の頭> [--size WxH] [--shots 撮るもの] [LANG]
# ROUNDING=<px> を付けると、Hyprland の角の丸みがその値のときを撮る
# 撮るもの（カンマ区切り）は input・voice・dictionary・snippets・backup・usage・list と、ページ:見せるもの
# （input:clear・voice:key・voice:download・dictionary:edit・dictionary:add・dictionary:text）
set -euo pipefail
BIN=$(realpath "$1"); COLORS=$2; OUT=$3; shift 3
T=$(mktemp -d)
trap 'rm -rf "$T"; [ -n "${SERVER:-}" ] && kill "$SERVER" 2>/dev/null; true' EXIT
mkdir -p "$T/config/kanade" "$T/data/kanade/data/parakeet-ja" "$T/data/kanade/data/parakeet-v3" "$T/state/omarchy/current/theme" "$T/run" "$T/models"
chmod 700 "$T/run"
printf 'かなで Kanade\nでっくす DeX\nめーる mooma@example.com\nきょう 今日\n' > "$T/config/kanade/user_dictionary.txt"
printf -- '-> →\n<- ←\n=> ⇒\n@@ mooma@example.com\n' > "$T/config/kanade/snippets.txt"
# 落としてあるモデル（大きさだけ合わせた空のファイル）
D=$T/data/kanade/data
truncate -s 655542604 "$D/parakeet-ja/model.int8.onnx"; truncate -s 28557 "$D/parakeet-ja/tokens.txt"
truncate -s 652282294 "$D/parakeet-v3/encoder.int8.onnx"; truncate -s 11845274 "$D/parakeet-v3/decoder.int8.onnx"
truncate -s 6355277 "$D/parakeet-v3/joiner.int8.onnx"; truncate -s 93939 "$D/parakeet-v3/tokens.txt"
[ "$COLORS" != none ] && cp "$COLORS" "$T/state/omarchy/current/theme/colors.toml"
# ROUNDING=<px> なら、hyprctl の代わりにその丸みを返すものを置く
if [ -n "${ROUNDING:-}" ]; then
  mkdir -p "$T/bin"
  printf '#!/bin/sh\necho %s\n' "'{\"option\":\"decoration:rounding\",\"int\":$ROUNDING,\"set\":true}'" > "$T/bin/hyprctl"
  chmod +x "$T/bin/hyprctl"
  export PATH="$T/bin:$PATH"
fi
# 落とすところを撮るための、ゆっくり返す手元のサーバー
python3 -I "$(dirname "$0")/slow_server.py" "$T/models" 18765 & SERVER=$!
LANG_=ja_JP.UTF-8
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --size|--shots) ARGS+=("$1" "$2"); shift 2 ;;
    *) LANG_=$1; shift ;;
  esac
done
sleep 0.5
env -u HYPRLAND_INSTANCE_SIGNATURE QT_FORCE_STDERR_LOGGING=1 QT_QPA_PLATFORM=offscreen LANG="$LANG_" LC_ALL= XDG_CONFIG_HOME="$T/config" XDG_DATA_HOME="$T/data" \
  XDG_STATE_HOME="$T/state" XDG_RUNTIME_DIR="$T/run" KANADE_SOCKET="$T/run/none.sock" KANADE_MODEL_URL_BASE=http://127.0.0.1:18765/ \
  "$BIN" --screenshot "$OUT" "${ARGS[@]}"
# SHOW=1 なら、書いた設定と落としたモデルを見せる
if [ -n "${SHOW:-}" ]; then
  cat "$T/config/kanade/settings.properties" 2>/dev/null || true
  ls -l "$T/data/kanade/data/reazonspeech" 2>/dev/null || true
fi
