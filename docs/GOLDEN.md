# 共通試験（golden）の形

Rust 書き直しの計画（`docs/REWRITE-PLAN.md` 5.2・5.3）の「物差し」。Kotlin のエンジンに**実際に送った行**を流し、返ってきた行を記録する。Rust 版は同じ行を流して、同じ行が返るかを見る。
Kotlin 版は記録と今の出力を比べる試験（`GoldenTest`）を持ち、Rust 版は `kanade-tool golden check`（まだ無い）で同じファイルを読む。
フェーズ 0 の成果物。形は Fable の審査（2026-11-23）を受けて決めた（`docs/decisions.md` の同日の項）。

## 原則

- **規範は `send`（送る行）と `out`（返った行）と `req`（エンジンが出した言語モデルの依頼）。** `in`・`view` は人が読むための参考で、契約ではない。Rust は KeyScript も FakeDll も写さずに、`send` をそのまま流して `out`・`req` を比べるだけでよい
- 場面は独立。場面ごとに新しいエンジン・新しい相手・空の学習・空のキャッシュで始める。前置きの行（`hello`・`focus`・`field`・`ctx`）も隠さず最初のステップの `send` に書く
- 既定値に頼らない。記録するときは `settings`・`host` の全欄を書き出す（Kotlin の既定値が契約にならないように）
- 数は **UTF-16 の単位**（`compose` のカーソル・文節の長さ、`ctx` の切り詰め、`replace` など、プロトコルの数はすべて今の Kotlin と同じ）。記録の中に片割れの代用対が入ったら記録は失敗にする。絵文字を含む場面を必ず入れる

## 置き場所

- `testdata/golden/*.jsonl`。1行が1つの場面（JSON オブジェクト）。UTF-8、`\n` 区切り
- 比べるのは JSON を解いたあとの値（欄の順・非 ASCII のエスケープの仕方は比べない）。文字列は1文字ずつ同じ
- 許す違いは `testdata/golden/allowed.json`（場面の id・ステップ番号・期待する別の値を機械が読める形で。理由は同じ要素の `why` に書く）。作るのは違いが出たとき
- 流した場面の数と飛ばした場面の数を必ず出す。CI では飛ばしがあれば失敗にする（辞書が無いなどで eval を飛ばしたまま合格にしない）

## 場面

```json
{"v":1,"id":"romaji-basic","spec":"3-1","note":"ローマ字をかなにして Enter で確定",
 "layer":"host","dict":{"kind":"fake"},
 "settings":{"fullWidthSymbols":true,"liveConversion":true,"candidateCount":10,"punctuation":"、。","pairBrackets":true,
             "prediction":false,"predictionCount":5,"emojiShortcodes":true,"emojiPrefix":":","emojiPrefixTwice":false},
 "host":{"keyStyle":"PC","startMode":"LAST","toggles":["..."]},
 "snippets":[],"userDict":[],
 "steps":[
  {"send":["hello\t1\t0\tfake","focus\t1","field\tnormal\t\t"],"out":["c\t0\tmode\t0","done\t0\t0"],"req":[]},
  {"in":"{Ctrl+Space}ni","client":1,"send":["key\t1\td\t...","key\t2\tu\t..."],"out":["..."],"req":[],"view":{"text":"","comp":"に","cands":[]}}
 ]}
```

| 欄 | 中身 |
|---|---|
| `v` | 形の版（今は 1）。決まりは下の「版」 |
| `id` / `spec` / `note` | 場面の名前（英小文字・数字・`-`、ファイル内で一意）／確かめる SPEC の番号（なければ空）／ひとこと |
| `layer` | `host`（今のソケットのプロトコル。`EngineHost` ＋ キーの写し `WinKeyMapper` を通す）／`engine`（キーの写しの後、`KeyInput` から `ImeCommand` まで。フェーズ 2 の Rust のエンジン単体の物差し。形は下） |
| `dict` | `{"kind":"none"}`（辞書なし）／`{"kind":"fake"}`（下の偽の辞書）／`{"kind":"system","sha256":"…"}`（`dictool` で作った辞書。`system.kdic` と `connection.kcon` のハッシュ。評価セットの場面だけ） |
| `settings` | `EngineSettings` の全欄（`engine` の層も同じ） |
| `host` | `keyStyle`（`PC`・`MAC`。記録のときは明示する。`DEFAULT` は OS で変わるので使わない）、`startMode`、`toggles`（日本語のオン/オフに使うキーの集合）。`engine` の層では書かない |
| `snippets` / `userDict` | 入れておくスニペット・ユーザー辞書（なければ空）。絵文字の表は Kanade の同梱のもの（`EmojiShortcodes.default`）を使うと決め、表を変えたら録り直す |
| `steps` | 順に流す操作 |

### ステップ（`host` の層）

| 欄 | 中身 |
|---|---|
| `send` | **規範**。エンジンへ送る行（`docs/WINDOWS-PROTOCOL.md` の DLL→エンジン）を順に。`key`・`commitall`・`focus`・`field`・`ctx`・`reset`・`mode`・`select`・`bye`・`hello` など、どの行でも書ける。行はタブ区切りのまま1行を1つの文字列にする。`key` などの id（第2欄）は、場面の頭から1ずつ数える（押下と離しはそれぞれ1つ。`focus`・`select`・`ctx`・`field` は数えない）。Rust は数え直さず、そのまま送る |
| `client` | 送る相手の番号（省略は 1）。2つ目の入力欄（別の DLL）を使う場面で 2 以上 |
| `out` | **規範**。このステップの `send` に対し、この相手へ届いた行（`c`・`done`）を届いた順に全部。`done` の id は `send` の id と同じ |
| `req` | **規範**。このステップのあいだに、エンジンが言語モデルへ出した依頼（並べ替え・予測の並べ替え）を順に。形は `{"kind":"rerank","id":3,"left":"…","reading":"…","candidates":[…],"right":"…"}`（`RequestRerank`）、`{"kind":"predRank",…}`（`RequestPredictionRank`）。フェーズ 0 の場面には依頼に答えない（zenz なし）ので、依頼を出す場面でも答えは返らない。答えを返す操作は下の「予約」 |
| `in` | 参考。`send` を作る元の書き方（`KeyScript`: 文字はそのまま、`{Space}`・`{Ctrl+Space}`・`{BS}`・`{Enter}`・`{RAlt}` など。US 配列のつもり）。Kotlin の `GoldenTest` だけが「`in` から `send` が再現できる」ことを確かめる |
| `view` | 参考。このステップの後のアプリの入力欄を `{"text":確定した文,"comp":入力中の文字,"cands":[]}`（出ていなければ空、出ていれば `[選んでいる位置, 候補…]`）。`out` と送ったキーから決まる値なので契約にしない |

### 偽の辞書（`dict: fake`）の決まり

Rust でも実装する契約。読みはひらがなの文字列で、次のとおり。
- `convert(reading)`: 1つ目は、読みを**2文字ずつ**に分けた文節（最後は1文字でもよい）。各文節の表記は `{` + 読みの2文字 + `}`。2つ目は、読み全体を1つの文節にして表記は `<` + 読み + `>`。（Kotlin の `uppercase()` には頼らない。ひらがなは大文字にならず、変換されたことが見えないため）
- `segmentCandidates(reading)`: `{読み}`、`<読み>`、読みそのもの、の順
- `pinned(reading)`: 学習で覚えた表記があれば1件（学習は場面の中だけ）
- `labelFor(reading, candidate)`: 候補が読みと同じなら「ひらがな」、それ以外は無し

### `engine` の層

エンジンの `KeyInput` の列と `ImeCommand` の列を、INPUT-LOG の keys の書き方（`{SP}{BS}{ENT}…`、足りない印は `docs/INPUT-LOG.md` に合わせて足す）の文字列で記録する。キーの道では出せない入力（`ReplaceLast`・`reconvert`・予測の選択・フリックやグライドの確定）も書ける。ステップは `{"keys":"…","cmds":[…],"req":[…]}`。フェーズ 0 では形だけ決め、記録は `host` の層を先に作る（`engine` の層はフェーズ 2 の前に足す）。

### 予約（今は書かない。足すとき形を変えない）

- `{"rerank":{"id":3,"ranked":["…"]}}`・`{"predRank":{…}}`: 言語モデルの答えを返すステップ。答えを返す時機を場面に書くので、スレッドの順は紛れ込まない（再生には同期で返す口をエンジンの側に足す）
- `t`: ステップの仮の時刻（ミリ秒）。音声（押し分け）・入力の記録（`fix` の秒数）を足すとき、時計を入力と一緒に渡す
- `log`: 出てきた入力の記録の出来事（時刻と通し番号を除く）
- `settings` の新しい欄（日英混在 `mixed_input` など）は、足すだけで形を変えない

## 一致の基準

- zenz なしの場面は、`send` を流して `out` と `req` が全件一致。違いを許すのは `allowed.json` に載せたものだけ
- `view` は比べない（参考）。ただし Kotlin の `GoldenTest` は `view` も確かめる
- 時刻・乱数・スレッドの順に左右される行は、場面に入れない。音声・タイマー・非同期の並べ替えは予約の欄ができるまで使わない
- 記録は必ず `FakeDll.inProcess`（同期。2 秒待ちで `consumed=false` になる経路を通さない）で行う
- `dict: system` の評価セットの場面は、5.3 の「N-best 上位10件」の物差しと役割が重なるので、少ない文数に絞る（同じコストの並びを炙り出す目的）

## 版

- `v` は各行にある。欄を**足すだけ**なら `v` は据え置く。既存の欄の意味を変えたら `v` を上げ、全件を録り直し、Kotlin と Rust の両方を同じ commit で直す
- 読み手は、知らない最上位の欄は無視してよい。知らない操作・`layer`・`dict.kind` の場面は「未対応」として数え、合格にはしない

## 場面の種（フェーズ 0）

1. `seed-*`: 今の単体テスト（`InputEngineTest`・`EngineHostTest` など）の動きを、読みやすい名前の場面に直したもの（SPEC の番号を付ける）。絵文字を含む場面を必ず入れる
2. `fuzz-*`: 固定した種の乱数で作ったでたらめなキー列。100 場面×30 キー（`FuzzTest` の 3000 回をそのまま記録しない）。乱数は記録を作る Kotlin の側だけで使い、記録には打ったキー（`send`）だけを書く
3. `eval-*`: 評価セット（`eval/daily*.tsv`）の読みをローマ字にして打ったもの（`dict: system`）。辞書が無いときは飛ばし、飛ばした数を出す
4. （後のフェーズ）`voice-*`・`lm` つき・`log` つき・`engine` の層

## 作り方と流し方

- 記録: `./gradlew :dictool:run --args="golden record testdata/golden"`（`desktop` の `GoldenRunner` が `FakeDll.inProcess` で流して書く。場面の定義は `GoldenSeeds.kt`）
- 確かめ: `./gradlew :desktop:test`（`GoldenTest` が `testdata/golden/*.jsonl` を読み、今のエンジンの出力と記録を比べる）。記録を作り直すのは、動きを変える commit のなかで、SPEC を直すのと同じ commit で行う
- 本人の実際の入力（入力の記録）から作った場面は、リポジトリの外に置く（`docs/MIXED-INPUT.md` 決定 13）
