#!/usr/bin/env bash
# Rust 書き直しのフェーズ 1 の物差しを Kotlin で記録する（docs/RUST-PHASE1.md）。
#   KANADE_DICT_DIR=<辞書を展開したフォルダ> scripts/rust-phase1-dump.sh
# 辞書のフォルダは scripts/rust-dict-prepare.sh で作る。記録は testdata/nbest・testdata/romaji に書く。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dict="${KANADE_DICT_DIR:?KANADE_DICT_DIR に辞書を展開したフォルダを指定する}"
dict="$(cd "$dict" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}"
nb="$root/testdata/nbest"
ro="$root/testdata/romaji"

# Gradle を1回だけ起こすため、installDist した dictool を直接呼ぶ
(cd "$root" && ./gradlew -q :dictool:installDist)
tool="$root/dictool/build/install/dictool/bin/dictool"

"$tool" nbest-dump "$dict" "$nb/daily.tsv" "$root/eval/daily.tsv:0" eval/daily.tsv:0
"$tool" nbest-dump "$dict" "$nb/daily2.tsv" "$root/eval/daily2.tsv:0" eval/daily2.tsv:0
"$tool" nbest-dump "$dict" "$nb/context.tsv" "$root/eval/context.tsv:1" eval/context.tsv:1
"$tool" nbest-dump "$dict" "$nb/context-right.tsv" "$root/eval/context-right.tsv:1" eval/context-right.tsv:1
"$tool" nbest-dump "$dict" "$nb/tests.tsv" "lines:$nb/readings-tests.txt" lines:testdata/nbest/readings-tests.txt
"$tool" nbest-dump "$dict" "$nb/random.tsv" "random:20261127:400"
"$tool" reverse-dump "$dict" "$nb/reverse-daily.tsv" "$root/eval/daily.tsv:1" eval/daily.tsv:1
"$tool" reverse-dump "$dict" "$nb/reverse-daily2.tsv" "$root/eval/daily2.tsv:1" eval/daily2.tsv:1
"$tool" ksym-dump "$dict" "$nb/tables.tsv"
"$tool" romaji-dump "$ro/tests.tsv" "lines:$ro/inputs-tests.txt" lines:testdata/romaji/inputs-tests.txt
"$tool" romaji-dump "$ro/random.tsv" "random:20261127:2000"
"$tool" romaji-table-dump "$ro/table.tsv"
"$tool" width-dump "$ro/width.tsv" 20261127 1000
