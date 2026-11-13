#!/bin/bash
# 入力方式のアプリ Kanade.app を組み立てる（mac/build.sh が呼ぶ）: build-app.sh <配布物のフォルダ>
set -euo pipefail
cd "$(dirname "$0")"
D=$1
APP="$D/Kanade.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
echo "入力方式のアプリをビルドする"
swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos13.0" \
  -framework AppKit -framework InputMethodKit -framework Carbon -framework ApplicationServices \
  Sources/*.swift -o "$APP/Contents/MacOS/Kanade"
cp Info.plist "$APP/Contents/Info.plist"
swift make-icon.swift "$APP/Contents/Resources/icon.tiff"
# 開発者の署名がないので、ad-hoc で署名する（この Mac だけで使う）
codesign --force --sign - "$APP"
