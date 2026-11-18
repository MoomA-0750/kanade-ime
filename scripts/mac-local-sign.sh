#!/bin/bash
# 組む Mac に、Kanade の署名だけに使う自己署名の証明書と専用のキーチェーン（~/.kanade-sign）を作る。一度だけ。
#   ssh <組む Mac> 'bash -s' < scripts/mac-local-sign.sh
# mac/build.sh は Developer ID が無いときこれで署名する。組み直しても署名の主が同じなので、アクセシビリティ・マイクの許可が消えない
set -euo pipefail
D=~/.kanade-sign
if [ -f "$D/kanade.keychain-db" ]; then echo "もうある: $D"; exit 0; fi
mkdir -p "$D" && chmod 700 "$D" && cd "$D"
openssl rand -hex 24 > password && chmod 600 password
cat > cert.cnf <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=Kanade Local Signing
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
P=$(cat password)
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 3650 -config cert.cnf 2>/dev/null
openssl pkcs12 -export -inkey key.pem -in cert.pem -out id.p12 -passout "pass:$P" -name "Kanade Local Signing" 2>/dev/null
security create-keychain -p "$P" "$D/kanade.keychain-db"
security set-keychain-settings "$D/kanade.keychain-db"
security unlock-keychain -p "$P" "$D/kanade.keychain-db"
security import id.p12 -k "$D/kanade.keychain-db" -P "$P" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$P" "$D/kanade.keychain-db" >/dev/null
rm -f key.pem id.p12
security find-identity -p codesigning "$D/kanade.keychain-db"
