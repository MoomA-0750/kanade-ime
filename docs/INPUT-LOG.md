# 入力の記録（設計）

要件の正本は `docs/INPUT-LOG-REQUEST.md`。この文書は「どういう形で取るか」を決める。取ったデータの使い道（変換の精度・切り替えなしの日英混在）は次の段階で決める。設計は design-planner の案（2026-11-22）で、推す案Cで仮決定した。

## 1. 結論（案C: core で出来事を作り、core の部品で JSON Lines に書く。フロントは入力欄の種類とアプリだけを送る）

- 記録を作るのは core の `InputEngine`。確定・取り消し・切り替え・直接入力の語を、ここで1件の出来事にまとめる。キーを押すたびには書き出さず、確定1回ごとに1行
- 書き出すのは core の新しい部品 `InputLogWriter`。上限つきの待ち行列に入れ、別スレッドがまとめて追記する。あふれたら捨てて、捨てた件数を残す。Android とデスクトップで同じものを使う
- 音声入力の結果は、そこを持っている側が同じ部品へ渡す（デスクトップは `VoiceSession`、Android は `commitVoice`）
- フロント（Windows の TSF・fcitx5・macOS）に足すのは、入力欄の種類（ふつう／秘密／学習しない）とアプリの名前を送る1行だけ。エンジンはこの知らせが届くまで記録しない。古いフロントは送らないので、記録しない側に倒れる
- 有効にするのは設定のスイッチ `input_log`。全部の版で既定はオフ。本人の機械では本人がオンにする。配る物そのものには印を入れない（「3. やめたこと」の E2）
- 置き場所は各 OS の設定フォルダーの下の `input-log/`。日ごとに `YYYY-MM-DD.jsonl`。古い日数と合計の容量に上限を置き、超えた分は古い日から消す。権限はファイル 0600・フォルダー 0700。外へは送らない

## 2. 出来事の形（JSON Lines、1行1件、UTF-8）

全部の行に共通: `t`（epoch ミリ秒）、`ev`（種類）、`seq`（起きてからの通し番号）、`app`（Android はパッケージ名、macOS は bundle id、Windows は exe 名、fcitx5 は program。分からなければ空）、`src`（`hw`・`soft`・`flick`・`glide`・`voice`）、`fld`（任意。入力欄の細かい種類を `url`・`email`・`search` などに丸めたもの）

1. `start`: ファイルの頭とエンジンが起きたとき。読み解く前提（版・辞書・zenz の設定）
   `{"t":1795312340000,"ev":"start","v":1,"os":"linux","front":"fcitx5","build":"2026.11.22.0041","zenz":"xsmall","zenz_mode":"CORRECT","live":true}`
2. `commit`: 日本語を確定した
   `{"t":…,"ev":"commit","seq":42,"app":"com.google.android.gm","src":"hw","how":"enter","text":"変換を確かめる","reading":"へんかんをたしかめる","keys":"henkanwotasikameru{SP}{SP}{ENT}","ctx":"昨日の","conv":true,"segs":[{"r":"へんかんを","s":"変換を","i":0,"moved":false},{"r":"たしかめる","s":"確かめる","i":2,"moved":true}],"dict_top":"変化を確かめる","zenz_top":"変換を確かめる","zenz":"reranked","resize":0,"ms":2310}`
   - `how`: `enter`・`select`・`typing`・`other`・`focus`・`mode`・`prediction`・`emoji`・`snippet`
   - `keys`: 打ったキーの並び。特殊キーは `{SP}{BS}{ENT}{ESC}{L}{R}{SL}{SR}{F7}` など、文字の `{` は `{{`。256 文字まで
   - `segs[].i`: 選んだ候補の位置。`moved` は利用者が候補を動かしたか
   - `dict_top`: 辞書だけの1位。`zenz_top`: zenz が返した1位。`zenz`: `none`・`same`・`reranked`
   - `ctx`: カーソルの前の文（32 文字まで）
3. `cancel`: 確定せずに捨てた `{"ev":"cancel","seq":43,"reading":"ほげ","keys":"hoge{ESC}","how":"esc"}`
4. `direct`: 英字（直接入力）で打った語。空白・句読点・ほかのキー・切り替え・入力欄の移動で区切る `{"ev":"direct","seq":44,"text":"GitHub","end":"space"}`
5. `switch`: 日本語と英字を切り替えた `{"ev":"switch","seq":45,"to":"ja","by":"key","before":"push to GitHub "}`。`before` はこの入力欄で直前に入れた文（両モード合わせて 32 文字まで）。切り替えの後の語は続く `commit`・`direct` を順に読めば分かる
6. `fix`: 確定の直後（5 秒以内・ほかのキーの前）に Backspace で消した `{"ev":"fix","ref":42,"bs":3,"ms":1200}`
7. `voice`: 音声入力の結果 `{"ev":"voice","seq":46,"mode":"listen","model":"parakeet","text":"明日の会議は10時からです。","audio_ms":3200}`
8. `voice_cmd`: 声のコマンド `{"ev":"voice_cmd","cmd":"send"}`
9. `dropped`: 待ち行列があふれて捨てた件数 `{"ev":"dropped","n":120}`

秘密・学習しない・まだ種類が分からない入力欄では何も書かない（アプリの名前も残さない）。

後で使い道を足すときに欠けると困るもの: 変換の精度には `reading`・文節ごとの選び直し・`dict_top` と `zenz_top`・`ctx`・`start` の版。日英混在には `direct` の語・`switch` の時刻と `before`・出来事の順番・`app`。打ち間違いには `keys` の Backspace と `fix`。

## 3. プロトコルに足すもの（`docs/WINDOWS-PROTOCOL.md`）

| 行 | 意味 |
|---|---|
| `field<TAB><kind><TAB><app><TAB><fld>` | 今の入力欄の種類。`kind` は `normal`・`secret`・`nolearn`。`focus 1` の直後に送り、入力中に変わったと分かったときにも送る |
| `admin<TAB><id><TAB>clear-input-log<TAB>` | 記録を全部消す（設定の画面から） |

- エンジンは相手ごとに入力欄の種類を持つ。`focus 1` を受けたら「分からない」に戻し、`field` が届くまで記録しない
- `kind` が `normal` でなければ、出来事を作らない（キーの並びもためない）
- 各フロントで見るもの:
  - Windows（TSF）: `GUID_PROP_INPUTSCOPE` の InputScope が `IS_PASSWORD`・`IS_NUMERIC_PASSWORD`・`IS_NUMERIC_PIN`・`IS_ALPHANUMERIC_PIN`・`IS_ALPHANUMERIC_PIN_SET` なら `secret`。読めなければ `secret`
  - fcitx5: `capabilityFlags()` の `Password`・`Sensitive` なら `secret`。アプリは `program()`
  - macOS: `IsSecureEventInputEnabled()` が真なら `secret`（システム全体の印なので、ターミナルのセキュア入力中も取らない）。アプリは `client().bundleIdentifier()`
  - Android（プロトコルは通らない）: `onStartInput` で決める。text の PASSWORD・VISIBLE_PASSWORD・WEB_PASSWORD、number の PASSWORD は `secret`。`IME_FLAG_NO_PERSONALIZED_LEARNING` は `nolearn`。number・phone・datetime のクラスは全部 `secret`（認証コード・PIN）。hint に「パスワード」「password」「暗証」「PIN」「コード」「code」「OTP」があれば `secret`。判定は core の純粋な関数 `FieldKind.fromAndroid(inputType, imeOptions, hint)`
- 二重の守り（core）: `direct` の語が秘密の鍵に見えるときは捨てる（8 文字以上・空白なしで、小文字・大文字・数字・記号のうち3種以上。または `sk-`・`ghp_`・`xox`・`AKIA` などで始まる）
- ターミナルとパスワード管理アプリ（設定 `input_log_limited_apps`）: `commit` と `switch` だけ残し、`keys`・`cancel`・`direct` は取らない（sudo のパスワードを入力欄の種類で見分けられないため）

## 4. 重くしない作り

- 打鍵の経路でするのは、記録がオンで入力欄が `normal` のときに、キーの並びの StringBuilder に印を1つ足すことだけ。出来事を作るのは確定や切り替えのとき、JSON の文字列にするのは書き出すスレッド
- `InputLogWriter`: 待ち行列（4096 件）に待たずに入れ、入らなければ捨てた数を増やす。デーモンのスレッドが 2 秒ごとか 256 件ごとにまとめて追記。日が変わったら次のファイル。起きたときと日が変わったときに `input_log_days`（既定 90）より古いファイルと、合計 `input_log_max_mb`（既定 200）を超えた分を古い順に消す。1日のファイルが 20MB を超えたらその日はもう書かない。`flush()` は Android の `onFinishInput` とデスクトップの終わるとき
- JSON は core の中の小さな書き手で作る（core はほかのライブラリに頼っていない）

## 5. 変える場所と順番

| 順 | 場所 | 足すもの |
|---|---|---|
| 1 | core/…/core/log/: `InputEvent.kt`・`JsonLine.kt`・`InputLogWriter.kt`・`FieldKind.kt` | 出来事の型、JSON 1行、待ち行列と書き出し・日ごとのファイル・整理・権限、入力欄の種類と Android の判定、鍵に見える語 |
| 2 | core/…/InputEngine.kt | 記録の受け口 `recorder` と入力欄の情報 `logField`。入力1回ぶんのまとめ役。確定・取り消し・切り替え・直接入力・zenz の印・`fix` |
| 3 | desktop/…/EngineHost.kt | `field` 行、相手ごとの入力欄の情報、`focus 1` で「分からない」に戻す、admin の `clear-input-log` |
| 4 | desktop/…/DesktopStore.kt・DesktopApp.kt | 設定 `input_log`・`input_log_days`・`input_log_max_mb`・`input_log_limited_apps`（書き出しに入れない）。書き手を作る・止める・設定の変化で付け外す |
| 5 | desktop/…/VoiceSession.kt | `voice`・`voice_cmd` |
| 6 | docs/WINDOWS-PROTOCOL.md | `field`・`clear-input-log` |
| 7 | app/…/KanadeInputMethodService.kt・KanadeSettings.kt | `FieldKind.fromAndroid`、`engine.logField`、音声の記録、`src`、`flush`、設定 |
| 8 | app の設定の画面（キー記録の隣） | スイッチ、件数と大きさ、最近の行を見る、全部消す |
| 9〜11 | linux/fcitx5、mac/ime、windows/tsf | `field` を送る |
| 12 | 各 PC の設定の画面 | スイッチ、フォルダーを開く、全部消す |
| 13 | SPEC.md（「入力の記録」の節、全部（仮））・docs/decisions.md | — |

## 6. 試験

- `InputLogWriterTest`: 1行ずつの JSON になる・日が変わると別のファイル・日数と容量で古いものが消える・書くスレッドが止まっていても `record` が待たない（あふれたら `dropped`）・0600/0700
- `InputLogEngineTest`: 変換して確定で `commit` 1件（`reading`・`keys`・`segs[].moved`）・zenz の並べ替えで `zenz=reranked`・切り替えで `switch`・英字で `direct`・Esc で `cancel`・確定直後の BS で `fix`・`secret`／`nolearn`／分からない／オフでは何も出ない・制限するアプリでは `keys`・`cancel`・`direct` が出ない
- `FieldKindTest`: inputType と hint ごとの判定、鍵に見える語
- `EngineHostTest`（FakeDll）: `focus 1` だけでは記録しない・`field normal` のあとで記録・`field secret` では出ない・`clear-input-log` で消える
- フロント（自動の試験が難しいもの）: 各 OS のパスワード欄で打って、その日の行が増えないことを見る手順を SPEC に書く

## 7. 仮決定（本人に確かめる。戻せる）

- Q1 有効にする方法: 設定のスイッチ（既定オフ）。配る物に「本人用」の印を入れて既定でオンにする形はとらない
- Q2 ターミナル・パスワード管理アプリ: 確定した日本語と切り替えだけ残し、英字の語・キー・取り消しは残さない
- Q3 カーソルの前の文（`ctx`、32 文字まで）を残す（他人の文が入りうる）
- Q4 既定 90 日・200MB
- Q5 「記録中」の印は今は出さない（設定の画面にだけ出る）
- Q6 Android の「書き出す」は置かない（見る・消すだけ）
- Q7 デスクトップでも学習しない入力欄で学習を止めるかは、今回は変えない

## 8. やめた案

- 案A 今の KeyLog（Android のキー記録）を広げる: Android だけ。打鍵のたびに同期で書く。キーしか持たず変換の情報が無い。DeX のキー表を作り終えたら既定でオフにする予定のもの
- 案B 各フロントで記録: フロントは読み・文節・zenz を知らない。4つの言語で同じものを書くことになる
- 案D 全部のキーを生で残して後で再現: 量が多い。辞書・学習・zenz が変わると再現がずれる。判定を誤ったときに失うものが一番大きい
- SQLite: デスクトップに JDBC が要る。日ごとに消す・grep で見るのは JSON Lines が簡単。後で移すのはいつでもできる
- E2 配る物に印を入れて既定でオン: 今の配り方に flavor や印が無く、本人に試してもらった pkg をそのまま Release に出した例がある。印つきの物が公開に出る道が実際にある
- `focus` の行に種類を足す: 同じフォーカスのまま入力欄の種類が変わることがある。いつでも送り直せるよう `field` に分けた

## 9. 確かめていない前提

- Windows の InputScope の取り方と、Chrome・Edge のパスワード欄で `OnSetFocus` が来るか
- fcitx5 の Wayland で `program()` が空になるか
- macOS で `bundleIdentifier()` がどのアプリでも取れるか
- 英字（直接入力）のキーが全部の版でエンジンの `handleDirect` まで届くか（Android の物理キーは未確かめ）
- 容量の見込み（1件 300〜500 バイト、1日 2000 回の確定で 1MB 前後）は見積もり
