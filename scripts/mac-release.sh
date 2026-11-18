#!/bin/bash
# Mac 版の pkg を GitHub の Release に載せ、Homebrew の tap（MoomA-0750/homebrew-tap）の cask を新しい版に書き換える。
#   scripts/mac-release.sh            dist/Kanade-mac-aarch64.pkg と .version（scripts/mac.sh fetch が持ってくる）を使う
#   TAP=<tap の手元の複製>（既定 ../homebrew-tap）
# 外に出す操作（Release の作成・tap の push）なので、本人の了承を得てから動かす
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=Kanade-mac-aarch64
PKG=dist/$NAME.pkg
VERSION=$(cat "dist/$NAME.version")
TAP=${TAP:-../homebrew-tap}
TAG=mac-v$VERSION
test -f "$PKG"
SHA=$(sha256sum "$PKG" | cut -d' ' -f1)
echo "版 $VERSION / $TAG / sha256 $SHA"
gh release create "$TAG" "$PKG" --title "Kanade for Mac $VERSION" --target "$(git rev-parse HEAD)" \
  --notes "Mac 版の pkg（Apple Silicon・macOS 14 以上）。開くと全員分（/Library/Input Methods）に入り、入力ソースへの登録まで済みます。Homebrew: \`brew install --cask MoomA-0750/tap/kanade\`"
sed -i -E "s/^  version \".*\"/  version \"$VERSION\"/; s/^  sha256 \".*\"/  sha256 \"$SHA\"/" "$TAP/Casks/kanade.rb"
git -C "$TAP" add Casks/kanade.rb
git -C "$TAP" commit -q -m "kanade $VERSION"
git -C "$TAP" push -q origin main
echo "tap を $VERSION にした"
