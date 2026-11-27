#!/usr/bin/env bash
# app の組み立てで作った辞書（app/build/generated/kanadeAssets の .kgz）を、Rust の試験と比較の記録で読むフォルダへ展開する。
#   scripts/rust-dict-prepare.sh <出力フォルダ>   → export KANADE_DICT_DIR=<出力フォルダ>
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/app/build/generated/kanadeAssets"
out="${1:?出力フォルダを指定する}"
mkdir -p "$out"
for f in system.kdic connection.kcon reverse.kdic; do gunzip -c "$src/$f.kgz" > "$out/$f"; done
cp "$src/pos.kpos" "$src/symbol.ksym" "$src/emoji.ksym" "$src/english.ksym" "$out/"
echo "展開した: $out"
