#!/bin/bash
# 設定の画面 Kanade Settings.app を組み立てる（mac/build.sh が呼ぶ）: build-app.sh <配布物のフォルダ>
set -euo pipefail
cd "$(dirname "$0")"
APP="$1/Kanade Settings.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
echo "設定の画面をビルドする"
swiftc -O -parse-as-library -target "$(uname -m)-apple-macos14.0" -framework SwiftUI -framework AppKit -framework Carbon \
  *.swift -o "$APP/Contents/MacOS/KanadeSettings"
cp Info.plist "$APP/Contents/Info.plist"
# 署名は mac/build.sh がまとめてする（中にエンジンを入れてから）
