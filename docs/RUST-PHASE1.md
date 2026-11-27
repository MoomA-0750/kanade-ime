# Rust 書き直し フェーズ 1（辞書・ローマ字・ラティス）の今の状態

計画は `docs/REWRITE-PLAN.md` 5.4 のフェーズ 1。判断の理由は `docs/decisions.md` 2026-11-27。この文書は上書きで今の状態だけを書く。

## 今の状態（2026-11-27）: 終わりの判定を満たした

- **辞書だけの N-best が Kotlin 版と全件一致**: 699/699 読み（評価セット daily 102・daily2 100・context 30・context-right 10、試験の読み 57、でたらめな読み 400）。上位 10 件の表記・順番・コストに加え、語の区切り（lid・rid・コスト・表記）と文節も一致
- 同じ記録で、接頭辞で探し直す処理（P）2333/2333、同じ読みの辞書の語（E）699/699、予測の語（W）1945/1945 も一致
- 逆引き（reverse.kdic）234/234 文、記号・絵文字・英語の表と品詞の表 4/4、ローマ字 2075/2075 入力（試験 75＋でたらめ 2000）、ローマ字の表・接頭辞・逆引き 484/484、全角半角・括弧 1042/1042
- 許した違いは無い（`testdata/nbest/ALLOWED.md` は作っていない）
- `cargo test`・`cargo clippy --all-targets -- -D warnings`・`cargo fmt --check` が通る。Kotlin の `:core:test :desktop:test` も通る（Kotlin のソースは dictool のコマンド追加だけ）

## 速さ（この PC: x86 Linux・6 コア。辞書だけの変換 10 件、温めてから各読み 5 回の中央値／95%）

| 読み | Kotlin（JVM） | Rust（release） |
|---|---|---|
| daily 102 | 0.413ms / 2.125ms | 0.130ms / 0.246ms |
| daily2 100 | 0.236ms / 0.525ms | 0.092ms / 0.206ms |
| context 30 | 0.318ms / 1.008ms | 0.045ms / 0.129ms |
| でたらめ 400 | 0.144ms / 0.528ms | 0.051ms / 0.146ms |

Kotlin の値は `scripts/rust-phase1-dump.sh` を流したときの出力（記録と同じ JVM で、ファイルごとに 3 回温めただけなので、JIT が温まりきっていない分を含む）。Rust は `kanade-tool bench`。Fold 7・M1 では測っていない（ssh mac に cargo が無い）。

## 作ったもの

| 場所 | 中身 | 行数の目安 |
|---|---|---|
| `rust/crates/kanade-text` | ローマ字（表・変換）、CharWidth、Brackets、UTF-16 の文字列の小道具（`esc`） | 約 760 |
| `rust/crates/kanade-dict` | kdic（mmap・UTF-16 の辞書順の二分探索・書き出し）、kcon、kpos、ksym、reverse.kdic の逆引き | 約 890 |
| `rust/crates/kanade-convert` | ラティス・前向きのビタビ・後ろ向きの A*（N-best）、接頭辞で探し直す処理、exactWords、predictWords、文節分け、`java.util.PriorityQueue` の写し | 約 890 |
| `rust/crates/kanade-tool` | `check`・`nbest-check`・`romaji-check`・`convert`・`bench`、結合試験 `tests/kotlin_match.rs` | 約 770 |
| `dictool/.../RustCompare.kt` | Kotlin の記録を作るコマンド（nbest-dump・reverse-dump・ksym-dump・romaji-dump・romaji-table-dump・width-dump） | 約 270 |
| `scripts/rust-dict-prepare.sh`・`scripts/rust-phase1-dump.sh` | 辞書の展開、記録の作り直し | — |
| `testdata/nbest`・`testdata/romaji` | Kotlin の記録（辞書の sha256 を頭に書く。辞書そのものは入れない） | — |

## 流し方

```sh
scripts/rust-dict-prepare.sh "$(mktemp -d)"   # app/build/generated/kanadeAssets の .kgz を展開（先に app を組んでおく）
export KANADE_DICT_DIR=<そのフォルダ>
cd rust && cargo test                       # 辞書が無いと kotlin_match が件数を出して失敗する（飛ばさない）
cargo run --release -p kanade-tool -- check  # 全部比べて、違いの例を出す
cargo run --release -p kanade-tool -- bench
scripts/rust-phase1-dump.sh                  # Kotlin の記録を作り直す（辞書や Kotlin の変換を変えたとき）
```

記録の形（1行ずつ、文字列は `esc`: `\` タブ 改行 `|` `=` と制御文字・片割れの代用対を `\` で書く）:
- `testdata/nbest/*.tsv`: `R 読み 件数`、`C コスト 表記 語(lid,rid,cost,表記|…) 文節(表記|…)`、`P 接頭辞 コスト 表記 語`（無ければ `-`）、`E 同じ読みの語(|…)`、`W 接頭辞 予測(読み=表記|…)`
- `testdata/nbest/reverse-*.tsv`: `V 文 区切り(表記=読み|…)`。`tables.tsv`: 表の大きさと全部の引いた結果のハッシュ、品詞の表
- `testdata/romaji/*.tsv`: `I 入力 確定した単位(かな=ローマ字|…) 1字ごとの途中 flush の単位`、`table.tsv`（E・X・V）、`width.tsv`（W）

## 不確かな点・分かったこと

- **同じコストの経路の並びは、Kotlin の実装の順に依存する（確かめた）**。でたらめな読み 400 のうち 6 件は、A* の待ち行列を Rust の `BinaryHeap`（同点は入れた順）にすると上位 10 件の順が変わる。`java.util.PriorityQueue` の `siftUp`・`siftDown` を写した `JavaPriorityQueue` で全件一致した（jshell で同点の取り出し順を確かめた試験つき）。評価セットの 242 読みは、どちらでも一致した
- UTF-16 の並び: kdic の読みは Java の String の辞書順（UTF-16 の単位の大小）。Rust の `Vec<u16>` の比べ方は同じ。絵文字（代用対）と U+FF61 の並びが、コードポイント順と違うことを単体試験で確かめた。ラティスは代用対の片割れを1単位の未知語として置く（Kotlin と同じ）
- mmap: memmap2 で読む。辞書を書き換えると読み違えるので、動いている間は書き換えない前提（Kotlin の MappedByteBuffer と同じ）
- `RomajiConverter` の小文字化: Java の `Character.toLowerCase(char)` を、Rust の `to_lowercase` の最初の1文字で写した。ASCII と普通の文字は同じ。特別な対応のある文字（İ など）は記録に無く、確かめていない（キーの文字は ASCII なので実害は無い見込み）

## 写していないもの（フェーズ 2 で要るもの）

- `typoCorrections`（フリックの打ち間違い）: Kotlin は `HashMap` の反復の順に `sortedBy` をかけるので、同点の順が Java の HashMap の作りに依存する。写すなら Java の `String.hashCode` と HashMap の表の大きさを再現する必要がある
- `DictionaryCandidateSource`（文節の候補の並べ方・ラベル・予測の組み立て・英語の表記）、`UserDictionary` の書式の読み、`LearningHistory`、`DateCandidates`、絵文字のショートコード・スニペット。ラティスにユーザー辞書・学習の語を入れる口（`ExtraWords`）だけは作り、単体試験がある

## 次にやること

1. フェーズ 2（エンジンと zenz、関門A）の前に、Fable 審査点「エンジンの API と状態の型」を通す（REWRITE-PLAN 5.4）
2. フェーズ 2 で、上の「写していないもの」を写す。比べ方は同じ（dictool に記録のコマンドを足し、kanade-tool で比べる）
3. 速さを Fold 7・M1 で測る（M1 は cargo を入れれば `kanade-tool bench` で測れる）
4. CI（GitHub Actions）に `cargo test` と `kanade-tool check` を入れるとき、辞書の作り方（dictool で Mozc から作るか、配布物を落とすか）を決める
