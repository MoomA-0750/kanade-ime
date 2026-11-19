#!/bin/bash
# 組む Mac の Kanade 専用のキーチェーン（~/.kanade-sign、scripts/mac-local-sign.sh が作る）に、Developer ID の証明書を入れる。
# ssh からはログインのキーチェーンを開けないので、鍵はこのキーチェーンに置き、mac/build.sh もここから署名する。
#   1. ssh <組む Mac> 'bash -s csr' < scripts/mac-devid.sh
#        ~/.kanade-sign/devid/ に application.csr と installer.csr を作る（鍵は同じ場所。キーチェーンへ入れたら消す）
#   2. developer.apple.com/account/resources/certificates/add で「Developer ID Application」「Developer ID Installer」を選び（G2 Sub-CA）、
#      それぞれの CSR を上げて .cer を落とし、組む Mac の ~/.kanade-sign/devid/ に application.cer・installer.cer として置く
#   3. ssh <組む Mac> 'bash -s import' < scripts/mac-devid.sh
set -euo pipefail
D=~/.kanade-sign
KC="$D/kanade.keychain-db"
W="$D/devid"
[ -f "$KC" ] || { echo "先に scripts/mac-local-sign.sh で専用のキーチェーンを作る" >&2; exit 1; }
P=$(cat "$D/password")
case "${1:-}" in
  csr)
    mkdir -p "$W" && chmod 700 "$W"
    for k in application installer; do
      if [ -f "$W/$k.key" ]; then echo "もうある: $W/$k.csr"; continue; fi
      openssl req -new -newkey rsa:2048 -nodes -keyout "$W/$k.key" -out "$W/$k.csr" \
        -subj "/emailAddress=reiya0914@gmail.com/CN=Kanade $k/C=JP" 2>/dev/null
      chmod 600 "$W/$k.key"
      echo "作った: $W/$k.csr"
    done ;;
  import)
    security unlock-keychain -p "$P" "$KC"
    for k in application installer; do
      [ -f "$W/$k.cer" ] || { echo "無い: $W/$k.cer（飛ばす）"; continue; }
      [ -f "$W/$k.key" ] || { echo "鍵が無い: $W/$k.key" >&2; exit 1; }
      openssl x509 -inform DER -in "$W/$k.cer" -out "$W/$k.pem" 2>/dev/null || cp "$W/$k.cer" "$W/$k.pem"
      # 鍵と証明書が組か（違う CSR の証明書を入れないため）
      [ "$(openssl x509 -noout -modulus -in "$W/$k.pem" | openssl md5)" = "$(openssl rsa -noout -modulus -in "$W/$k.key" 2>/dev/null | openssl md5)" ] \
        || { echo "鍵と証明書が組でない: $k" >&2; exit 1; }
      # macOS の security は新しい openssl の既定（AES の p12）を読めないので、古い形式で書く
      openssl pkcs12 -export -legacy -inkey "$W/$k.key" -in "$W/$k.pem" -out "$W/$k.p12" -passout "pass:$P" 2>/dev/null \
        || openssl pkcs12 -export -inkey "$W/$k.key" -in "$W/$k.pem" -out "$W/$k.p12" -passout "pass:$P"
      security import "$W/$k.p12" -k "$KC" -P "$P" -T /usr/bin/codesign -T /usr/bin/productbuild -T /usr/bin/productsign >/dev/null
      rm -f "$W/$k.p12" "$W/$k.pem" "$W/$k.key"
      echo "入れた: $k"
    done
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$P" "$KC" >/dev/null
    security find-identity -v "$KC" ;;
  *) echo "使い方: bash -s csr | bash -s import" >&2; exit 2 ;;
esac
