#!/bin/bash
# macOS 版の配布物を、使う Mac（arm64 / x86_64）の上で組み立てる。scripts/mac.sh bundle で作った束を展開した中で動かす。
# 要るもの: Xcode（swiftc・codesign）。JDK と cmake は scripts/mac.sh build が ~/.cache/kanade-build に置く
# 出力: dist/Kanade-mac-<arch>/ と dist/Kanade-mac-<arch>.tar.gz
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

bash mac/ime/build-app.sh "$D"
bash mac/settings/build-app.sh "$D"
# 設定の画面の入口（エンジンが、入力メニューで選んだページを引数にして呼ぶ）
cat > "$E/bin/kanade-settings" <<'SH'
#!/bin/sh
# Kanade の設定の画面を開く。引数は開くページ（input・voice・dictionary・snippets・backup）
# kanade-settings://<ページ>。開いていなければ起こし、開いていればそのページへ移る
exec open "kanade-settings://${1:-input}"
SH
chmod +x "$E/bin/kanade-settings"
cp mac/install.sh mac/uninstall.sh docs/THIRD-PARTY.md "$D/" 2>/dev/null || true
[ -f docs/MAC-USAGE.md ] && cp docs/MAC-USAGE.md "$D/"
chmod +x "$D"/*.sh 2>/dev/null || true
tar -C "$ROOT/dist" -czf "$ROOT/dist/$NAME.tar.gz" "$NAME"
du -sh "$D" "$ROOT/dist/$NAME.tar.gz"
