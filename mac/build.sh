#!/bin/bash
# macOS 版の配布物を、使う Mac（arm64 / x86_64）の上で組み立てる。scripts/mac.sh bundle で作った束を展開した中で動かす。
# 要るもの: Xcode（swiftc・codesign）。JDK と cmake は scripts/mac.sh build が ~/.cache/kanade-build に置く
# 出力: dist/Kanade-mac-<arch>.pkg（全員分に入れる配布物）と .version（版）、開発用の dist/Kanade-mac-<arch>/・.tar.gz（install.sh つき。本人のフォルダーに入れる）
# 署名: Developer ID Application の証明書があれば .app をそれで、Developer ID Installer があれば pkg をそれで署名し、KANADE_NOTARY_PROFILE があれば pkg を公証に出す（なければ .app はこの Mac の Kanade 専用の証明書か ad-hoc、pkg は署名なし）
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

# 署名の証明書と公証の資格情報は、組む Mac の Kanade 専用のキーチェーン（~/.kanade-sign、scripts/mac-local-sign.sh が作る）に置く。
# ssh からはログインのキーチェーンを開けない（codesign が errSecInternalComponent、notarytool が keychainLocked）ため
LOCAL_KC="$HOME/.kanade-sign/kanade.keychain-db"
KC_ARGS=()
if [ -f "$LOCAL_KC" ]; then
  security unlock-keychain -p "$(cat "$HOME/.kanade-sign/password")" "$LOCAL_KC"
  KC_ARGS=(--keychain "$LOCAL_KC")
fi
# KANADE_SIGN_ID が無ければ、専用のキーチェーン、ログインのキーチェーンの順に Developer ID Application を探す（あれば Hardened Runtime・時刻印つき）
SIGN_ID=${KANADE_SIGN_ID:-}
SIGN_KC=()
if [ -z "$SIGN_ID" ] && [ -f "$LOCAL_KC" ]; then
  # 名前でなく指紋（SHA-1）で指す。ssh では LANG が無く、codesign が日本語の名前を読み違えて「no identity found」になるため
  SIGN_ID=$(security find-identity -v -p codesigning "$LOCAL_KC" 2>/dev/null | sed -n 's/.*) \([0-9A-F]\{40\}\) "Developer ID Application: .*/\1/p' | head -1)
  [ -n "$SIGN_ID" ] && SIGN_KC=(--keychain "$LOCAL_KC")
fi
if [ -z "$SIGN_ID" ]; then
  SIGN_ID=$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1)
fi
# Developer ID が無ければ、専用のキーチェーンの自己署名の証明書（Kanade Local Signing）で署名する。
# 配るには使えないが、組み直しても macOS が同じアプリと見なすので、アクセシビリティ・マイクの許可が消えない
# （ad-hoc は中身の hash で見分けるので、組み直すたびに許可が効かなくなる）
LOCAL_ID=""
if [ -z "$SIGN_ID" ] && [ -f "$LOCAL_KC" ]; then
  LOCAL_ID=$(security find-identity -p codesigning "$LOCAL_KC" | sed -n 's/.*) \([0-9A-F]\{40\}\) "Kanade Local Signing".*/\1/p' | head -1)
fi
sign() { # <entitlements か ""> <path...>
  local ent=$1; shift
  if [ -n "$SIGN_ID" ]; then
    codesign --force --timestamp --options runtime ${ent:+--entitlements "$ent"} ${SIGN_KC[@]+"${SIGN_KC[@]}"} --sign "$SIGN_ID" "$@"
  elif [ -n "$LOCAL_ID" ]; then
    codesign --force ${ent:+--entitlements "$ent"} --keychain "$LOCAL_KC" --sign "$LOCAL_ID" "$@"
  else
    codesign --force --sign - "$@"
  fi
}
what=${SIGN_ID:-ad-hoc}
[ -n "$LOCAL_ID" ] && what="Kanade Local Signing"
echo "署名する（$what）"
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

# pkg（全員分に入れる。入れるときに Mac のパスワードを1回求める）
#   /Library/Input Methods/Kanade.app と /Applications/Kanade Settings.app を置き、postinstall がログインしている人として登録する
#   版はエンジンの VERSION（年月日時分秒）から 年.月.日.時分 にする（Homebrew の cask の version と同じ）
STAMP=$(cat "$RES/engine/VERSION")
PKG_VERSION="${STAMP:0:4}.${STAMP:4:2}.${STAMP:6:2}.${STAMP:8:4}"
echo "pkg を作る（$PKG_VERSION）"
PKG_TMP=$(mktemp -d)
mkdir -p "$PKG_TMP/root/Library/Input Methods" "$PKG_TMP/root/Applications"
ditto "$APP" "$PKG_TMP/root/Library/Input Methods/Kanade.app"
ditto "$RES/Kanade Settings.app" "$PKG_TMP/root/Applications/Kanade Settings.app"
# 組んだ Mac が付ける拡張属性（com.apple.provenance）は、pkgbuild が ._ のファイルにして入れてしまうので外す（署名は中に埋まっているので影響しない）
xattr -cr "$PKG_TMP/root"
# 同じ bundle id の .app がほかの場所（前に入れた ~/Library/Input Methods など）にあっても、そちらへ動かさない
pkgbuild --analyze --root "$PKG_TMP/root" "$PKG_TMP/component.plist" >/dev/null
i=0
while plutil -replace "$i.BundleIsRelocatable" -bool NO "$PKG_TMP/component.plist" 2>/dev/null; do i=$((i + 1)); done
pkgbuild --quiet --root "$PKG_TMP/root" --component-plist "$PKG_TMP/component.plist" --scripts mac/pkg/scripts \
  --identifier dev.mooma.pkg.Kanade --version "$PKG_VERSION" --install-location / "$PKG_TMP/Kanade-component.pkg"
case "$ARCH" in aarch64) HOST_ARCH=arm64 ;; *) HOST_ARCH=x86_64 ;; esac
sed -e "s/@ARCH@/$HOST_ARCH/" -e "s/@VERSION@/$PKG_VERSION/" mac/pkg/distribution.xml > "$PKG_TMP/distribution.xml"
# 署名: Developer ID Installer の証明書があればそれで（無ければ署名なし。開くときに止められる）
#   専用のキーチェーン、ログインのキーチェーンの順に探す
INSTALLER_ID=${KANADE_INSTALLER_ID:-}
INSTALLER_KC=()
if [ -z "$INSTALLER_ID" ] && [ -f "$LOCAL_KC" ]; then
  INSTALLER_ID=$(security find-identity -v "$LOCAL_KC" 2>/dev/null | sed -n 's/.*) \([0-9A-F]\{40\}\) "Developer ID Installer: .*/\1/p' | head -1)
  [ -n "$INSTALLER_ID" ] && INSTALLER_KC=(--keychain "$LOCAL_KC")
fi
[ -z "$INSTALLER_ID" ] && INSTALLER_ID=$(security find-identity -v 2>/dev/null | sed -n 's/.*"\(Developer ID Installer: [^"]*\)".*/\1/p' | head -1)
PB_SIGN=()
[ -n "$INSTALLER_ID" ] && PB_SIGN=(--sign "$INSTALLER_ID" --timestamp ${INSTALLER_KC[@]+"${INSTALLER_KC[@]}"})
rm -f "$ROOT/dist/$NAME.pkg" "$ROOT/dist/$NAME.dmg"
productbuild --quiet --distribution "$PKG_TMP/distribution.xml" --package-path "$PKG_TMP" \
  ${PB_SIGN[@]+"${PB_SIGN[@]}"} "$ROOT/dist/$NAME.pkg"
rm -rf "$PKG_TMP"
echo "$PKG_VERSION" > "$ROOT/dist/$NAME.version"
# 公証: KANADE_NOTARY_PROFILE（xcrun notarytool store-credentials で作った名前）があり、Developer ID Installer で署名したときだけ。
#   資格情報は専用のキーチェーンにあればそこから（store-credentials に --keychain ~/.kanade-sign/kanade.keychain-db を付けて作る）
if [ -n "${KANADE_NOTARY_PROFILE:-}" ] && [ -n "$INSTALLER_ID" ]; then
  echo "公証に出す（$KANADE_NOTARY_PROFILE）"
  xcrun notarytool submit "$ROOT/dist/$NAME.pkg" --keychain-profile "$KANADE_NOTARY_PROFILE" ${KC_ARGS[@]+"${KC_ARGS[@]}"} --wait
  xcrun stapler staple "$ROOT/dist/$NAME.pkg"
fi
du -sh "$D" "$ROOT/dist/$NAME.tar.gz" "$ROOT/dist/$NAME.pkg"
