#!/bin/bash
# 選び役（kanade-settings）が、環境変数と XDG_CURRENT_DESKTOP の組み合わせで正しい方を選ぶかを試す。画面は出さない（仮の実行ファイルを置く）
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/qt"
cp "$HERE/kanade-settings" "$T/bin/kanade-settings"
printf '#!/bin/sh\necho "gtk $*"\n' > "$T/bin/kanade-settings-gtk"
printf '#!/bin/sh\necho "qt $*"\n' > "$T/qt/kanade-settings-qt"
chmod +x "$T/bin/"* "$T/qt/"*
fail=0
check() { # <期待> <KANADE_UI> <XDG_CURRENT_DESKTOP>
  got=$(env -u KANADE_UI -u XDG_CURRENT_DESKTOP ${2:+KANADE_UI=$2} ${3:+XDG_CURRENT_DESKTOP=$3} "$T/bin/kanade-settings" voice 2>&1)
  if [ "$got" = "$1" ]; then echo "ok   UI=${2:-なし} DESKTOP=${3:-なし} -> $got"; else echo "失敗 UI=${2:-なし} DESKTOP=${3:-なし} -> $got（期待 $1）"; fail=1; fi
}
echo "-- 両方あるとき"
check "qt voice" "" "Hyprland"
check "qt voice" "" "KDE"
check "qt voice" "" ""
check "gtk voice" "" "GNOME"
check "gtk voice" "" "ubuntu:GNOME"
check "gtk voice" "" "GNOME-Classic:GNOME"
check "gtk voice" "" "gnome"
check "gtk voice" "gtk" "Hyprland"
check "qt voice" "qt" "GNOME"
check "qt voice" "other" "Hyprland"
check "gtk voice" "other" "GNOME"
echo "-- QML 版が無いとき"
mv "$T/qt/kanade-settings-qt" "$T/qt-off"
check "gtk voice" "" "Hyprland"
check "gtk voice" "qt" "Hyprland"
echo "-- GTK 版が無いとき"
mv "$T/qt-off" "$T/qt/kanade-settings-qt"
rm "$T/bin/kanade-settings-gtk"
check "qt voice" "gtk" "GNOME"
check "qt voice" "" "GNOME"
echo "-- どちらも無いとき"
rm "$T/qt/kanade-settings-qt"
"$T/bin/kanade-settings" >/dev/null 2>&1 && { echo "失敗: どちらも無いのに成功した"; fail=1; } || echo "ok   どちらも無ければ失敗する"
exit $fail
