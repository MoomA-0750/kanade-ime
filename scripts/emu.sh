#!/bin/bash
# to-fu（Windows）のエミュレータを ssh 越しに使う。手元に KVM がないため（Kite と同じやり方）
#   scripts/emu.sh adb <args...>     to-fu の adb を実行
#   scripts/emu.sh install           手元の debug APK を送って入れる
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
esac
