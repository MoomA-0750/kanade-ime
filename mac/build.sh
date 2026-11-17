#!/bin/bash
# macOS 版の配布物を、使う Mac（arm64 / x86_64）の上で組み立てる。scripts/mac.sh bundle で作った束を展開した中で動かす。
# 要るもの: Xcode（swiftc・codesign）。JDK と cmake は scripts/mac.sh build が ~/.cache/kanade-build に置く
# 出力: dist/Kanade-mac-<arch>.dmg（Kanade.app を開くだけで入る）と、開発用の dist/Kanade-mac-<arch>/・.tar.gz（install.sh つき）
# 署名: Developer ID Application の証明書があればそれで署名し、KANADE_NOTARY_PROFILE があれば公証も出す（なければ ad-hoc）
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
case "$(uname -m)" in
  arm64) ARCH=aarch64 ;;
  x86_64) ARCH=x64 ;;
  *) echo "この CPU には対応していません: $(uname -m)"; exit 1 ;;
esac
JOBS=$(sysctl -n hw.ncpu)
CACHE=${KANADE_BUILD_CACHE:-$HOME/.cache/kanade-build}
export JAVA_HOME=${JAVA_HOME:-$CACHE/jdk-21.0.12.1+1/Contents/Home}
CMAKE=$(command -v cmake || echo "$CACHE/cmake-4.4.5-macos-universal/CMake.app/Contents/bin/cmake")
test -x "$JAVA_HOME/bin/jlink" || { echo "JDK がない: $JAVA_HOME（scripts/mac.sh build が置く）"; exit 1; }
test -x "$CMAKE" || { echo "cmake がない"; exit 1; }

echo "zenz・whisper のネイティブをビルドする"
for lib in zenz whisper; do
  src=native; [ "$lib" = whisper ] && src=native/whisper
  "$CMAKE" -S "$src" -B "$CACHE/build-$lib" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$JAVA_HOME" >/dev/null
  "$CMAKE" --build "$CACHE/build-$lib" --target "kanade_$lib" -j"$JOBS" 2>&1 | grep -E "error|Built target kanade" || true
  test -f "$CACHE/build-$lib/libkanade_$lib.dylib"
done

NAME="Kanade-mac-$ARCH"
D="$ROOT/dist/$NAME"
E="$D/engine"
rm -rf "$D"
mkdir -p "$E/bin" "$E/native" "$E/data"
echo "実行環境を作る（jlink）"
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 --output "$E/runtime"
cp -r engine/lib "$E/lib"
cp "$CACHE/build-zenz/libkanade_zenz.dylib" "$CACHE/build-whisper/libkanade_whisper.dylib" "$E/native/"
cp data/* "$E/data/"
cat > "$E/bin/kanade-engine" <<'SH'
#!/bin/sh
# Kanade のエンジン（入力方式のアプリ Kanade.app が「serve」で起こす）。KANADE_JAVA_OPTS で JVM に設定を足せる（試験用）
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec "$ROOT/runtime/bin/java" $KANADE_JAVA_OPTS -Xmx512m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Dapple.awt.UIElement=true -Djava.awt.headless=true \
  -Dkanade.settings="$ROOT/bin/kanade-settings" -cp "$ROOT/lib/*" dev.mooma.kanade.desktop.MainKt "$@"
SH
chmod +x "$E/bin/kanade-engine"

echo "$(date +%Y%m%d%H%M%S)" > "$E/VERSION"
# 設定の画面の入口（エンジンが、入力メニューで選んだページを引数にして呼ぶ）
cat > "$E/bin/kanade-settings" <<'SH'
#!/bin/sh
# Kanade の設定の画面を開く。引数は開くページ（input・voice・dictionary・snippets・backup）
# kanade-settings://<ページ>。開いていなければ起こし、開いていればそのページへ移る
exec open "kanade-settings://${1:-input}"
SH
chmod +x "$E/bin/kanade-settings"

bash mac/ime/build-app.sh "$D"
bash mac/settings/build-app.sh "$D"
# Kanade.app を開くだけで入るように、エンジンと設定の画面を中に入れる（開くと Setup.swift が置く）
APP="$D/Kanade.app"
mv "$E" "$APP/Contents/Resources/engine"
mv "$D/Kanade Settings.app" "$APP/Contents/Resources/"

# 署名: KANADE_SIGN_ID に Developer ID Application の名前があればそれで（Hardened Runtime・時刻印つき）、なければ ad-hoc（この Mac だけで使う）
SIGN_ID=${KANADE_SIGN_ID:-}
if [ -z "$SIGN_ID" ]; then
  SIGN_ID=$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1)
fi
sign() { # <entitlements か ""> <path...>
  local ent=$1; shift
  if [ -n "$SIGN_ID" ]; then
    codesign --force --timestamp --options runtime ${ent:+--entitlements "$ent"} --sign "$SIGN_ID" "$@"
  else
    codesign --force --sign - "$@"
  fi
}
echo "署名する（${SIGN_ID:-ad-hoc}）"
RES="$APP/Contents/Resources"
# 公証は jar の中のネイティブ（sherpa-onnx の dylib）も署名を求めるので、取り出して署名し、jar に戻す
if [ -n "$SIGN_ID" ]; then
  for j in "$RES"/engine/lib/*.jar; do
    unzip -Z1 "$j" | grep -E '\.(dylib|jnilib)$' > /tmp/kanade-jar-libs.txt || continue
    t=$(mktemp -d)
    (cd "$t" && unzip -q "$j" $(cat /tmp/kanade-jar-libs.txt))
    while IFS= read -r f; do sign "" "$t/$f"; done < /tmp/kanade-jar-libs.txt
    (cd "$t" && zip -q "$j" $(cat /tmp/kanade-jar-libs.txt))
    rm -rf "$t"
  done
fi
# 中のネイティブ（実行環境・dylib）を先に、次に設定の画面、最後に外側
find "$RES/engine" -type f \( -name '*.dylib' -o -perm -u+x \) -print0 | while IFS= read -r -d '' f; do
  if file -b "$f" | grep -q Mach-O; then
    case "$f" in
      */runtime/bin/*) sign mac/engine.entitlements "$f" ;;
      *) sign "" "$f" ;;
    esac
  fi
done 2>&1 | grep -v "replacing existing signature" || true
sign mac/app.entitlements "$RES/Kanade Settings.app"
sign mac/app.entitlements "$APP"
codesign --verify --deep --strict "$APP"

cp mac/install.sh mac/uninstall.sh docs/THIRD-PARTY.md "$D/" 2>/dev/null || true
[ -f docs/MAC-USAGE.md ] && cp docs/MAC-USAGE.md "$D/"
chmod +x "$D"/*.sh 2>/dev/null || true
tar -C "$ROOT/dist" -czf "$ROOT/dist/$NAME.tar.gz" "$NAME"

# ディスクイメージ（開いて Kanade.app を開くだけで入る）
echo "ディスクイメージを作る"
DMG_SRC=$(mktemp -d)
cp -R "$APP" "$DMG_SRC/"
cp docs/THIRD-PARTY.md "$DMG_SRC/" 2>/dev/null || true
[ -f docs/MAC-USAGE.md ] && cp docs/MAC-USAGE.md "$DMG_SRC/"
rm -f "$ROOT/dist/$NAME.dmg"
hdiutil create -quiet -volname Kanade -srcfolder "$DMG_SRC" -fs HFS+ -format UDZO "$ROOT/dist/$NAME.dmg"
rm -rf "$DMG_SRC"
if [ -n "$SIGN_ID" ]; then
  codesign --force --timestamp --sign "$SIGN_ID" "$ROOT/dist/$NAME.dmg"
fi
# 公証: KANADE_NOTARY_PROFILE（xcrun notarytool store-credentials で作った名前）があり、Developer ID で署名したときだけ
if [ -n "${KANADE_NOTARY_PROFILE:-}" ] && [ -n "$SIGN_ID" ]; then
  echo "公証に出す（$KANADE_NOTARY_PROFILE）"
  xcrun notarytool submit "$ROOT/dist/$NAME.dmg" --keychain-profile "$KANADE_NOTARY_PROFILE" --wait
  xcrun stapler staple "$ROOT/dist/$NAME.dmg"
fi
du -sh "$D" "$ROOT/dist/$NAME.tar.gz" "$ROOT/dist/$NAME.dmg"
