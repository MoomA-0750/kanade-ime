#!/bin/bash
# QML 版（store_cli）と GTK 版（kanade-settings-gtk.py の関数）が、同じ操作で同じファイルを書くかを比べる。
#   tests/compat.sh <store_cli のパス>     要るもの: python3・python-gobject・libadwaita（GTK 版を読み込むため）
set -euo pipefail
CLI=$(realpath "$1")
HERE=$(cd "$(dirname "$0")" && pwd)
GTK=$(realpath "$HERE/../../kanade-settings-gtk.py")
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

py() { # <設定のフォルダーの頭> <Python の文>
  XDG_CONFIG_HOME="$W/$1" python3 -I -c "
import importlib.util, sys
spec = importlib.util.spec_from_file_location('ks', sys.argv[1]); ks = importlib.util.module_from_spec(spec); spec.loader.exec_module(ks)
$2" "$GTK"
}
qt() { XDG_CONFIG_HOME="$W/$1" "$CLI" "${@:2}"; }

for side in py qt; do
  mkdir -p "$W/$side/kanade"
  printf '# x\n live_conversion = false \nfoo=bar\r\n' > "$W/$side/kanade/settings.properties"
  printf '# 説明\nかなで Kanade\n\nでっくす\t DeX\nひとつ\nあ い う\n' > "$W/$side/kanade/user_dictionary.txt"
done
mkdir -p "$W/py2" "$W/qt2"

# 設定
py py "ks.save_setting('live_conversion', 'true'); ks.save_setting('voice_toggle_key', 'Control+Alt+space'); ks.save_setting('punctuation', '，．')"
qt qt set live_conversion true; qt qt set voice_toggle_key Control+Alt+space; qt qt set punctuation '，．'
# ファイルが無いところから
py py2 "ks.save_setting('voice_hold_keys', 'F12,F9')"
qt qt2 set voice_hold_keys F12,F9
# ユーザー辞書（GTK 版の write_entry と同じ操作: 直す・消す・足す）
py py "
path = ks.DICTIONARY_FILE
def write_entry(entry, new_line):
    lines = ks.read_text(path).splitlines()
    if entry is None: lines.append(new_line)
    elif new_line is None: del lines[entry[0]]
    else: lines[entry[0]] = new_line
    ks.write_text(path, '\n'.join(lines) + '\n')
write_entry((3,), 'でっくす Galaxy DeX'); write_entry((1,), None); write_entry(None, 'えー A')
ks.write_text(ks.SNIPPETS_FILE, '-> →\n')"
qt qt put dictionary 3 でっくす 'Galaxy DeX'; qt qt remove dictionary 1; qt qt put dictionary -1 えー A; qt qt text snippets '-> →'

ok=0
for f in kanade/settings.properties kanade/user_dictionary.txt kanade/snippets.txt; do
  if cmp -s "$W/py/$f" "$W/qt/$f"; then echo "同じ: $f"; else echo "違う: $f"; diff "$W/py/$f" "$W/qt/$f" || true; ok=1; fi
done
if cmp -s "$W/py2/kanade/settings.properties" "$W/qt2/kanade/settings.properties"; then echo "同じ: 無いところからの settings.properties"; else echo "違う: 無いところから"; ok=1; fi
# 読み方（entries）も比べる
a=$(XDG_CONFIG_HOME="$W/py" python3 -I -c "
import sys
s = open(sys.argv[1], encoding='utf-8').read()
for i, raw in enumerate(s.splitlines()):
    t = raw.strip()
    if not t or t.startswith('#'): continue
    cut = next((j for j, c in enumerate(t) if c in ' \t'), -1)
    if cut <= 0: continue
    v = t[cut + 1:].lstrip()
    if v: print(f'{i}\t{t[:cut]}\t{v}')
" "$W/py/kanade/user_dictionary.txt")
b=$(qt qt entries dictionary)
if [ "$a" = "$b" ]; then echo "同じ: 一覧の読み方"; else echo "違う: 一覧の読み方"; echo "$a"; echo "$b"; ok=1; fi
exit $ok
