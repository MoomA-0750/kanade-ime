#!/bin/bash
# 組む Mac の Kanade 専用のキーチェーン（~/.kanade-sign、scripts/mac-local-sign.sh が作る）に、Developer ID の証明書を入れる。
# ssh からはログインのキーチェーンを開けないので、鍵はこのキーチェーンに置き、mac/build.sh もここから署名する。
#   1. ssh <組む Mac> 'bash -s csr' < scripts/mac-devid.sh
#        ~/.kanade-sign/devid/ に application.csr と installer.csr を作る（鍵は同じ場所。キーチェーンへ入れたら消す）
#   2. developer.apple.com/account/resources/certificates/add で「Developer ID Application」「Developer ID Installer」を選び（G2 Sub-CA）、
#      それぞれの CSR を上げて .cer を落とし、組む Mac の ~/.kanade-sign/devid/ に application.cer・installer.cer として置く
#   3. ssh <組む Mac> 'bash -s import' < scripts/mac-devid.sh（.cer の名前は問わない。鍵と組になるものを探して入れる）
#   CSR を作り直すときは 'bash -s csr <名前>'（例 installer2。Apple は一度使った CSR を受け付けない）
set -euo pipefail
D=~/.kanade-sign
KC="$D/kanade.keychain-db"
W="$D/devid"
[ -f "$KC" ] || { echo "先に scripts/mac-local-sign.sh で専用のキーチェーンを作る" >&2; exit 1; }
P=$(cat "$D/password")
case "${1:-}" in
  csr)
    mkdir -p "$W" && chmod 700 "$W"
    for k in application installer ${2:-}; do
      if [ -f "$W/$k.key" ]; then echo "もうある: $W/$k.csr"; continue; fi
      openssl req -new -newkey rsa:2048 -nodes -keyout "$W/$k.key" -out "$W/$k.csr" \
        -subj "/emailAddress=reiya0914@gmail.com/CN=Kanade $k/C=JP" 2>/dev/null
      chmod 600 "$W/$k.key"
      echo "作った: $W/$k.csr"
    done ;;
  import)
    # .cer は名前を問わず、組になる鍵（同じ modulus）を探して入れる（どの CSR をどちらに上げたか取り違えても入る）
    security unlock-keychain -p "$P" "$KC"
    shopt -s nullglob
    for c in "$W"/*.cer; do
      pem="${c%.cer}.pem"
      openssl x509 -inform DER -in "$c" -out "$pem" 2>/dev/null || cp "$c" "$pem"
      m=$(openssl x509 -noout -modulus -in "$pem" | openssl md5)
      key=""
      for k in "$W"/*.key; do
        [ "$(openssl rsa -noout -modulus -in "$k" 2>/dev/null | openssl md5)" = "$m" ] && key=$k
      done
      [ -n "$key" ] || { echo "組になる鍵が無い: $c" >&2; rm -f "$pem"; continue; }
      p12="${c%.cer}.p12"
      # macOS の security は新しい openssl の既定（AES の p12）を読めないので、古い形式で書く
      openssl pkcs12 -export -legacy -inkey "$key" -in "$pem" -out "$p12" -passout "pass:$P" 2>/dev/null \
        || openssl pkcs12 -export -inkey "$key" -in "$pem" -out "$p12" -passout "pass:$P"
      security import "$p12" -k "$KC" -P "$P" -T /usr/bin/codesign -T /usr/bin/productbuild -T /usr/bin/productsign >/dev/null
      rm -f "$p12" "$pem" "$key" "${key%.key}.csr"
      echo "入れた: $(openssl x509 -noout -subject -in "$c" 2>/dev/null || openssl x509 -inform DER -noout -subject -in "$c")"
    done
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$P" "$KC" >/dev/null
    security find-identity -v "$KC" ;;
  *) echo "使い方: bash -s csr | bash -s import" >&2; exit 2 ;;
esac
