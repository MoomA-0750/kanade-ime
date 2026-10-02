#!/bin/bash
# エミュレータの画面要素を文字列で探して操作する（scripts/emu.sh 経由）
#   scripts/ui.sh tap <文字列>     text か hint がその文字列のノードの中心を押す
#   scripts/ui.sh text <文字列>    その文字列を含むノードの text を出す
set -euo pipefail
cd "$(dirname "$0")/.."
dump() { scripts/emu.sh adb shell uiautomator dump /sdcard/ui.xml >/dev/null; scripts/emu.sh adb shell cat /sdcard/ui.xml; }
case "$1" in
  tap)
    b=$(dump | python3 -c '
import re,sys
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0)
    if f"text=\"{t}\"" in n or f"hint=\"{t}\"" in n:
        a=list(map(int,re.findall(r"\d+",re.search(r"bounds=\"([^\"]*)\"",n).group(1))))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break' "$2")
    [ -n "$b" ] || { echo "見つからない: $2" >&2; exit 1; }
    scripts/emu.sh adb shell input tap $b ;;
  text)
    dump | python3 -c '
import re,sys,html
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r"text=\"([^\"]*)\"", x):
    if t in html.unescape(m.group(1)): print(html.unescape(m.group(1)))' "$2" ;;
esac
