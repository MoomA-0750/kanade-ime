#!/bin/bash
# to-fu（Windows）のエミュレータを ssh 越しに使う。手元に KVM がないため（Kite と同じやり方）
#   scripts/emu.sh adb <args...>     to-fu の adb を実行
#   scripts/emu.sh install           手元の debug APK を送って入れる
#   scripts/emu.sh shot <file.png>   画面を撮って手元に保存（半分の大きさの <file>-s.png も作る）
set -euo pipefail
cd "$(dirname "$0")/.."
ADB='C:\Users\MoomA\AppData\Local\Android\Sdk\platform-tools\adb.exe'
case "$1" in
  adb)
    shift
    ssh -o BatchMode=yes tofu "$ADB $*" | tr -d '\r' ;;
  install)
    ssh -o BatchMode=yes tofu "[IO.File]::WriteAllBytes('C:\\Users\\Public\\kanade.apk', [Convert]::FromBase64String([Console]::In.ReadToEnd()))" < <(base64 -w0 app/build/outputs/apk/debug/app-debug.apk)
    ssh -o BatchMode=yes tofu "$ADB install -r C:\\Users\\Public\\kanade.apk" | tr -d '\r' ;;
  shot)
    ssh -o BatchMode=yes tofu "$ADB shell 'screencap -p 2>/dev/null | base64'" | tr -d '\r\n' | base64 -d > "$2"
    python3 -c "
import sys
from PIL import Image
im = Image.open(sys.argv[1]); im.resize((im.size[0] // 2, im.size[1] // 2)).save(sys.argv[1][:-4] + '-s.png')" "$2" ;;
esac
