#!/bin/bash
# Linux 版の配布物を、使う機械（aarch64 / x86_64）の上で組み立てる。scripts/linux.sh bundle で作った束を展開した中で動かす。
# ネイティブ（zenz・whisper・fcitx5 のアドオン）はこの機械向けにビルドする（-mcpu=native。別の CPU の機械では遅いか動かない）。
# 要るもの: cmake・C/C++ のコンパイラ・fcitx5 の開発用のヘッダー（Arch は fcitx5 に入っている）・curl。JDK はなければ落とす
# 設定の画面の QML 版（qt/kanade-settings-qt）は、Qt 6.5 以上（qt6-base・qt6-declarative）と libxkbcommon の開発用のファイルがあるときだけ組む
# 出力: dist/Kanade-linux-<arch>/ と dist/Kanade-linux-<arch>.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
case "$(uname -m)" in
  aarch64|arm64) ARCH=aarch64 ;;
  x86_64) ARCH=x64 ;;
  *) echo "この CPU には対応していません: $(uname -m)"; exit 1 ;;
esac
JOBS=$(nproc)
CACHE=${KANADE_BUILD_CACHE:-$HOME/.cache/kanade-build}
mkdir -p "$CACHE"

# JNI のヘッダーと jlink に使う JDK（Temurin 21）。JAVA_HOME に jlink があればそれを使う
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jlink" ]; then
  JDK_TAG="jdk-21.0.12.1+1"
  JDK_DIR="$CACHE/$JDK_TAG"
  if [ ! -x "$JDK_DIR/bin/jlink" ]; then
    # 置いてあればそれを使う（scripts/linux.sh build が手元で gh から落として送る。curl では GitHub が 504 を返すことがある）
    if [ ! -f "$CACHE/jdk.tar.gz" ]; then
      echo "JDK を落とす（$JDK_TAG、$ARCH）"
      curl -fL --retry 3 -o "$CACHE/jdk.tar.gz" \
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_${ARCH}_linux_hotspot_21.0.12.1_1.tar.gz"
    fi
    tar -C "$CACHE" -xzf "$CACHE/jdk.tar.gz"
    rm "$CACHE/jdk.tar.gz"
  fi
  export JAVA_HOME="$JDK_DIR"
fi

echo "zenz・whisper のネイティブをビルドする"
for lib in zenz whisper; do
  src=native; [ "$lib" = whisper ] && src=native/whisper
  cmake -S "$src" -B "$CACHE/build-$lib" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$JAVA_HOME" >/dev/null
  cmake --build "$CACHE/build-$lib" --target "kanade_$lib" -j"$JOBS" 2>&1 | grep -E "error|Built target kanade" || true
  test -f "$CACHE/build-$lib/libkanade_$lib.so"
done

echo "fcitx5 のアドオンをビルドする"
cmake -S linux/fcitx5 -B "$CACHE/build-fcitx5" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr >/dev/null
cmake --build "$CACHE/build-fcitx5" -j"$JOBS" 2>&1 | grep -E "error|Built target" || true
test -f "$CACHE/build-fcitx5/kanade.so"

QT_BUILT=0
if cmake -S linux/settings/qt -B "$CACHE/build-settings-qt" -DCMAKE_BUILD_TYPE=Release -DKANADE_TESTS=OFF >/dev/null 2>&1; then
  echo "設定の画面の QML 版をビルドする"
  cmake --build "$CACHE/build-settings-qt" -j"$JOBS" 2>&1 | grep -E "error|Built target kanade-settings-qt" || true
  [ -x "$CACHE/build-settings-qt/kanade-settings-qt" ] && QT_BUILT=1
else
  echo "Qt 6 の開発用のファイルがないので、設定の画面の QML 版は組まない（GTK 版だけ）"
fi

NAME="Kanade-linux-$ARCH"
D="$ROOT/dist/$NAME"
rm -rf "$D"
mkdir -p "$D/bin" "$D/native" "$D/fcitx5" "$D/data" "$D/settings"
echo "実行環境を作る（jlink）"
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 --output "$D/runtime"
cp -r engine/lib "$D/lib"
cp "$CACHE/build-zenz/libkanade_zenz.so" "$CACHE/build-whisper/libkanade_whisper.so" "$D/native/"
cp "$CACHE/build-fcitx5/kanade.so" linux/fcitx5/kanade.conf "$D/fcitx5/"
cp linux/fcitx5/kanade-addon.conf "$D/fcitx5/"
cp linux/settings/kanade-settings-gtk.py linux/settings/dev.mooma.kanade.Settings.desktop "$D/settings/"
cp linux/settings/kanade-settings "$D/bin/kanade-settings"
if [ "$QT_BUILT" = 1 ]; then
  mkdir -p "$D/qt"
  install -m755 "$CACHE/build-settings-qt/kanade-settings-qt" "$D/qt/kanade-settings-qt"
fi
cp data/* "$D/data/"
cp linux/install.sh linux/uninstall.sh docs/THIRD-PARTY.md "$D/"
[ -f docs/LINUX-USAGE.md ] && cp docs/LINUX-USAGE.md "$D/"
cat > "$D/bin/kanade-engine" <<'SH'
#!/bin/sh
# Kanade のエンジン（fcitx5 のアドオンが、最初のキーで「serve」で起こす）。KANADE_JAVA_OPTS で JVM に設定を足せる（試験用）
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec "$ROOT/runtime/bin/java" $KANADE_JAVA_OPTS -Xmx512m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Dkanade.settings="$ROOT/bin/kanade-settings" -cp "$ROOT/lib/*" dev.mooma.kanade.desktop.MainKt "$@"
SH
# 設定の画面の入口は bin/kanade-settings（選び役。GTK 版と QML 版のどちらかに置き換わる）
cat > "$D/bin/kanade-settings-gtk" <<'SH'
#!/bin/sh
# 設定の画面の GTK 版（GTK4・libadwaita。python-gobject と libadwaita が要る）。引数は開くページ（input・voice・dictionary・snippets・backup・usage）
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$ROOT/settings/kanade-settings-gtk.py" "$@"
SH
chmod +x "$D/bin/kanade-engine" "$D/bin/kanade-settings" "$D/bin/kanade-settings-gtk" "$D/install.sh" "$D/uninstall.sh"
tar -C "$ROOT/dist" -czf "$ROOT/dist/$NAME.tar.gz" "$NAME"
du -sh "$D" "$ROOT/dist/$NAME.tar.gz"
