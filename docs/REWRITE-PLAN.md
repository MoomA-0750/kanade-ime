# Kanade 書き直しの計画（Rust のコア）

2026-11-23、design-planner の案（実装なし）。仕様の正本は SPEC.md、判断の理由は docs/decisions.md。
この文書は「どう書き直すか」（4〜5 章）と「仕様をどう見直すか」（3 章、提案）をまとめる。本人に決めてほしいことは 6 章。
なお CLAUDE.md は「次の作業は、まず DeX のキー表を作る」と決めている。この計画はそれを覆さず、Phase 0 はキー表のあとに始める前提。

## 0. 要約

- 推す形: **部品ごとの差し替え（Strangler）に関門を3つ置く**。Rust は「実験」として始め、関門A（エンジンが Kotlin 版と一致し、速さも負けない）を越えたら本命にする。それまで Kotlin 版が本命で、保守も続ける
- 最初に差し替えるのは**デスクトップの常駐エンジン（JVM の `desktop/`）**。今のソケットのプロトコル（docs/WINDOWS-PROTOCOL.md）をそのまま話す Rust のサーバーにする。TSF の DLL・fcitx5 のアドオン・macOS の入力方式・設定の画面5つは変えない。JRE（jlink）の同梱と、mac の java 向けの entitlements が要らなくなる
- Android は関門B のあとに移す。Rust のエンジンを UniFFI で呼ぶ。画面キーボード・グライド・折りたたみ・クリップボードなど画面の部分は Kotlin のまま
- 一致の物差しは**共通試験**（キー列→命令の列の表）。Kotlin 版で記録し、Rust 版が同じ列を出すかを見る。zenz の答えは記録して再生し、エンジンの論理だけを比べる
- エンジンは「キーを受けて命令を返す、時計も入出力も持たない状態機械」。今の `InputEngine`（KeyInput → ImeCommand、zenz は依頼と返事）の形を引き継ぎ、状態を型で表す。日英混在の「区間」は最初から持たせる
- llama.cpp と whisper.cpp は今のとおり別々の共有ライブラリにし、C ABI で呼ぶ。ggml の名前がぶつかる問題と、Pixel 3 向けの `_v80` の版選びを今のまま保つため
- フェーズは 0〜6（7 は任意）。関門 A・B・C の3か所で「続けるか、やめるか」を決める
- 仕様の見直しは 3 章に「提案」として分けた。SPEC の「確定」の項目と本人の決定は覆さない

## 1. 要件

### なぜ・誰が
- 作る目的（PLAN.md）: 物理キーボードに最適化した日本語入力で、zenz によるライブ変換と音声入力を持ち、全部を端末の中で動かす。今は Android・Windows・Linux・macOS の4つで同じ入力を目指している（本人「全てのプラットフォームで機能をなるべく揃えたい」2026-11-21）
- 使う人は本人。使う機械は Fold 7（DeX と DeX の外）、Pixel 3（試験用）、cramgent（M1・Asahi・Hyprland・fcitx5）、cramshell・Reiyas-Air（M1 の mac）、to-fu（Windows）。リポジトリは MIT で公開していて、pkg と Homebrew の tap でも配っている

### 守る制約
| 制約 | 中身 | 書き直しへの効き |
|---|---|---|
| 端末の中で完結（1-2） | ネットにつなぐのはモデルを落とすときだけ | Rust でも同じ。落とす処理は設定の画面の側にあり、移さない |
| 速さ | 打鍵の間隔は 100〜150ms。Fold 7 で辞書だけ 3.6ms、確かめて直す 5.8ms（中央値）／13.3ms（95%） | この数を下回らないことを関門の条件にする |
| 熱 | M1 Air（ファンなし）で 0.15 秒に1キーを 30 秒打ったとき、エンジンの CPU 時間 4.8 秒 | デスクトップの関門の条件にする |
| Pixel 3 | dotprod が無いので `_v80` の版を読む（1-5） | zenz・whisper を別の共有ライブラリにしておく理由の一つ |
| DeX | DeX が先に取るキーがある（DEX-KEYS.md はまだ作っていない） | キーの割り当ては今のまま |
| 秘密 | パスワード欄では手を出さず、記録しない（1-4、13-4） | 共通試験に入れる |
| Windows | TSF の DLL は全部のアプリのプロセスに読み込まれる | DLL の中にエンジンを入れない（4.4） |
| macOS | 署名と公証が要る。許可（TCC）は署名の主で覚える。エンジンは Kanade.app の子として動く | エンジンを起こす道（`engine/bin/kanade-engine`）と VERSION の確かめ方を変えない |
| Linux | fcitx5 が切り替えキーを先に取る（11-2） | 変えない |
| ライセンス | MIT。CC BY-SA のデータは別のファイルにする | 5.8 |

### 必ず保つもの: SPEC の「確定」
| 番号 | 中身 | 受け持つ層 | 移行での保ち方 |
|---|---|---|---|
| 3-7 | 確定の直後 300ms は選択範囲の通知を見ない | Android のサービス | Kotlin に残すので変わらない。手順で確かめる |
| 3-8 | zenz の結果を 64 件覚える | エンジン | 共通試験（ZenzaiTest から作る） |
| 7-12 | テンキーの ☺ | Android の画面 | 触らない |
| 7-14 | 折り目で分ける | Android の画面 | 触らない |
| 8-1 | F12 を押している間だけ録音する | 音声の段取り | 共通試験（VoiceSessionTest）と手順 |
| 8-2 | 話しながら文字を出し、離したら確定用のモデル | 音声の段取り | 手順＋見本の wav |
| 8-4 | 話して入れる記号 | 音声の後処理 | 共通試験（VoiceTest） |
| 8-8 | 確定用のモデルの既定は parakeet-ja | 設定の既定値 | 設定の一覧の試験 |
| 8-9 | 認識が付けた句読点を外す | 音声の後処理 | 共通試験（VoiceTest） |

### 保つもの: 本人の決定（SPEC では「仮」でも本人が決めたもの）
- US 配列と JIS 配列の両方に対応し、US の切り替えキー3つは既定で全部有効（2026-10-02）
- zenz は xsmall、small の選択肢は無し（10-03）。ネットはモデルの取得だけ（10-03）
- Windows の設定の画面は WinUI 3。macOS は pkg と Homebrew。mac のモードは1つで、切り替えは Kanade の中で行う
- 入力の記録 Q1〜Q7（11-22）、MIT のライセンス（11-08）
- キー記録は、DeX のキー表を作ってから既定でオフにする（CLAUDE.md。書き直しより先にやる）

## 2. Kotlin 版の資産と負債

### 資産（Rust 版へ持っていく、または物差しに使う）
| 資産 | 場所 | 使い道 |
|---|---|---|
| 単体テスト 240 件（core・desktop）、でたらめなキー列の試験（FuzzTest） | core/src/test、desktop/src/test | 共通試験の種 |
| 偽の DLL（FakeDll）・KeyScript | desktop/ | プロトコルの一致を見る試験 |
| 評価セット: daily 102 文・daily2 100 文・context 30 文・context-right 10 文、見本の音声 | eval/ | 関門の正解率 |
| 辞書の作り方（Mozc はコミットを固定、kdic・kcon・ksym・kpos・reverse.kdic、BUILD_REVISION 5） | dictool/ | 形式を変えずに Rust から読む |
| zenz のつなぎ（KV の使い回し、確かめて直す、生成、中断、スレッド数） | native/zenz_bridge.cpp | JNI の口の横に C の口を足して使い回す |
| whisper のつなぎ（窓を音声の長さまで縮める） | native/whisper/whisper_bridge.cpp | 同上 |
| 計測の手段（dictool の eval-zenz・eval-zenzai・eval-context、Fold 7 の評価のボタン） | dictool・app | 関門の測り方 |
| プロトコルと入力の記録の形の文書 | docs/WINDOWS-PROTOCOL.md、docs/INPUT-LOG.md | そのまま契約にする |
| フロントと設定の画面 | windows/・linux/・mac/・app の画面 | 書き直さない |

### 負債（書き直しで返すもの）
| 負債 | 中身 | Rust 版での返し方 |
|---|---|---|
| InputEngine が大きい | 1654 行。可変の欄が多く、状態がどこにも型として書かれていない | 状態を列挙型にし、モジュールに分ける（4.2） |
| Android のサービスが大きい | KanadeInputMethodService.kt は 2197 行。zenz のスレッド、聞き続けるモード・声のコマンドの段取りをサービスの中に持つ | 段取りを `kanade-host` に寄せ、デスクトップと同じものを使う |
| 音声の段取りが2つある | Android はサービスの中、PC は `VoiceSession`。版ごとの差（SPEC の未決事項） | 1つにする（提案 P-8） |
| JVM の常駐 | -Xmx512m。jlink の JRE を3つの OS に同梱。mac は java のために JIT などの entitlements が要る。録音と効果音は javax.sound。起動のあいだのキーは素通しになる | ネイティブの1つの実行ファイルにする |
| インストーラーが java のプロセスを探している | install.ps1 が java のコマンドラインでエンジンを探す（11-18 に壊れた） | プロセスの名前で探す |
| プロトコルに古い行が残っている | `test`・`caret`・`voice start/stop/cue`。版の取り決めが弱い | P-10 |
| 設定の読み書きが5つの画面でばらばら | Windows の Store.cs がファイルを丸ごと書き直して、知らない項目を消した（2回）。`LOCAL_KEYS`、`source: desktop`、key_style を書き出さない、といった個別の扱い | P-6・P-7 |
| 辞書の形式が Java の char に依存 | kdic は UTF-16 の大きいエンディアンで、String の辞書順に並べている | Rust では UTF-16 の比べ方を正確に写す（不確かな点） |
| ggml の名前がぶつかる | llama.cpp と whisper.cpp を別の .so にしている | 別々のまま C ABI で読む |
| CI が無い | 手元と ssh 先でしか試していない | 5.7 |

## 3. 仕様の見直し（提案。本人が決めるまで SPEC は変えない）

| # | 今 | 提案 | 根拠 | 覆すか |
|---|---|---|---|---|
| P-1 | 番号がずれている（2-4 が2つ、3-8 が 3-2 の前、4-10〜12 が 4-2 の前、7-13 が 7-12 の前） | 番号を振り直す。古い番号との対応表を decisions に残す | 共通試験が番号を参照するので、ずれていると対応がずれる | 覆さない |
| P-2 | SPEC にエンジンの動きと版ごとの動きが混ざっている。12-5 は組み方や署名の手順まで含み、4-1 は測った値を含む | 「A. エンジン共通（どの版でも同じ。共通試験で確かめる）」と「B. 版ごと（フロント・画面・配布）」に分ける。組み方は docs/BUILD-*.md へ、測った値は decisions へ移す | 書き直しで保つ範囲が A そのものになる。SPEC が外から見た動きだけになる | 覆さない（置き場所だけ変える） |
| P-3 | 1-1「DeX で物理キーボードを使うための Android 用 IME」 | 今の目的（4つの OS、「AI時代の文字入力」）と、1 章の制約を書く | 実態と合っていない | 覆さない |
| P-4 | 「確定」は9件だけ。ほかは長く使っていても「仮」 | 共通試験ができた「仮」の項目を、本人が一覧でまとめて見直し、確定にする | 書き直しで保つ範囲がはっきりする。共通試験が「自動テスト」の条件を満たす | 決まりは変えない（運用だけ変える） |
| P-5 | 版ごとの食い違い（Linux は Kanade の中に英字のモードが無い、PC には押している間の声のコマンドが無い、など） | 各項目に「版の差」の欄を設け、差を「意図した差」と「まだ揃っていない差」に分ける | 揃えるべき差が見えるようになる | 覆さない |
| P-6 | 設定は Android で約 58、デスクトップで約 43 の名前があり、5つの画面がそれぞれ読み書きしている | core に設定の一覧（名前・型・既定値・範囲・OS ごとの既定値・書き出すか）を1つ置く。各画面の試験は「知らない項目を消さない」と「既定値が一覧と同じ」を見る | Store.cs の消えた件と、key_style・LOCAL_KEYS の個別の扱いを1つの決まりにまとめられる | 覆さない |
| P-7 | 読み込むと今の内容が置き換わる（9-3）。PC の版で書き出したものだけはマージする | 読み込みは既定でマージにし、置き換えは明示して選ぶ。書き出しに `schema` の版を付ける。「この機械だけ」の項目（key_style・input_log* など）は一覧の印で決める | `source: desktop` という印の決まりが要らなくなる | 9-3 の「置き換わる」を変える提案。覆さない代案: 今の決まりのまま、印の決め方だけを一覧に移す |
| P-8 | 音声の段取りが版で違う（8-3・8-11・8-12・8-10 の多くは Android だけ） | 段取り（押し方・区切り・声のコマンド・直後の Backspace・言いよどみ）を全部エンジンの VoiceSession に寄せ、フロントは録音と書き込みだけにする | 未決事項の「Android だけ」が自然に消える | 覆さない（揃える方向） |
| P-9 | 日英混在は docs/MIXED-INPUT.md の案のとおり「日本語モードの中の機能、既定はオフ」 | そのまま採る。新しいエンジンは KanaUnit に言語の印（ja・en・auto）を最初から持たせる | 後から足すと変える場所が 15 前後になる見込み（MIXED-INPUT 11） | 覆さない。MIXED-INPUT の 14 点は本人が決める |
| P-10 | プロトコルの `hello <版>` で版を名乗るだけ。古い行も受け付けている | `hello` に版を名乗らせ、エンジンが受け付ける版を返す。古い行（test・caret・voice start/stop/cue）は「次の版で外す」と書く。共通試験の出力をこの行の形で書き、プロトコルの一致の試験と兼ねる | Rust のサーバーが受け付ける範囲をはっきりさせる | 覆さない |
| P-11 | 入力の記録は v1 | そのまま保つ。日英混在が入ったら v2（MIXED-INPUT の P4） | Q1〜Q7 は本人の決定 | 覆さない |
| P-12 | Windows の音声の既定は whisper small（配布物に入れているため。見直す条件つき、10-14） | JRE が抜けて配布物が軽くなったら、ほかの版と同じ parakeet-ja（初めて使うときに落とす）にするかを本人が決める | 版の差が一つ減る | 本人が決める。代案は今のまま |

## 4. 書き直しの設計

### 4.1 クレートの分け方（リポジトリ直下の `rust/` に Cargo の workspace を置く。Gradle とは分ける）
| クレート | 中身（今の Kotlin の対応） | 入出力 |
|---|---|---|
| kanade-text | ローマ字・かな・全角半角・括弧（RomajiConverter・RomajiTable・CharWidth・Brackets） | 無し |
| kanade-dict | kdic・kcon・ksym・kpos・reverse の読み書き（core/dict）。mmap で読む | ファイル |
| kanade-convert | ラティス・ビタビ・N-best の A*・接頭辞で探し直す処理・予測・打ち間違い・候補の出どころ（core/convert） | 無し |
| kanade-lm | 言語モデルの trait、zenz の C ABI の読み込み（libloading）、記録の再生と偽物、Reranker の論理 | 共有ライブラリ |
| kanade-engine | 状態機械（InputEngine）、絵文字・スニペット・ユーザー辞書・学習・再変換、記録の出来事を作る処理 | 無し |
| kanade-voice | 区切りの判定（Endpointer・ContinuousSegmenter）、VoiceSession、話して入れる記号・声のコマンド・言いよどみ、効果音の合成、認識の trait（sherpa・whisper の実装は feature で切り替え） | 無し（認識だけ共有ライブラリ） |
| kanade-log | InputLogWriter（JSON Lines、待ち行列、消す処理） | ファイル・スレッド |
| kanade-settings | 設定の一覧、settings.properties、書き出しの JSON | ファイル |
| kanade-host | 段取り（zenz の作業のスレッドを「新しい依頼が古い依頼を止める」形で回す、音声の作業、タイマー） | スレッド |
| kanade-server（実行ファイル） | ソケットのサーバー、EngineHost、WinKeyMapper、admin の頼みごと、録音（cpal）、効果音を鳴らす | ソケット・音 |
| kanade-android（cdylib） | UniFFI の口 | JNI |
| kanade-tool（実行ファイル） | dictool の代わり（辞書を作る・評価・共通試験を流す） | ファイル |
| （任意）kanade-capi | 同じプロセスの中で呼ぶための C ABI（cbindgen の kanade.h） | — |

### 4.2 エンジン
- `Engine::handle(&mut self, KeyInput) -> Vec<Command>`、`on_reranked(id, Vec<String>)`、`on_predictions_ranked(id, …)`。今の Kotlin と同じ形にする（共通試験でそのまま比べられる）
- 状態を列挙型で表す: `Idle`・`Composing { units, caret, pending_romaji }`・`Converting { segments, focus }`・`EmojiQuery`・`Reconverting`。予測の窓は別の欄に持つ
- 時計を持たない。「直後 5 秒」などの時刻は入力と一緒に渡す
- 記録は `Command::Log(InputEvent)` として返し、書くのは host が行う
- `Unit { raw, text, lang }` の lang は、今は ja だけを使い、日英混在のときに en・auto を使う

### 4.3 推論・認識のつなぎ
- zenz: `native/zenz_bridge.cpp` の JNI の関数の横に `extern "C"` の関数（init・free・cancel・score・verify・greedy・set_threads）を足し、中身は共有する。Kotlin 版と Rust 版が同じ .so を使えるので、並行して運用できる
- 確かめて直す方式は、作業のスレッドから接頭辞で探し直す処理を呼び返す（今は Android のサービスがやっている）。host の中で同じ形にする
- whisper も同じ形で C の口を足す
- sherpa-onnx は C API の共有ライブラリを使う（デスクトップは今の jar の代わり。Android は関門C までは Kotlin の AAR のままにし、認識の trait を Kotlin の側で実装して渡してもよい）

### 4.4 フロントとのつなぎ
| 案 | Windows | macOS | fcitx5 | 良い点 | 悪い点 |
|---|---|---|---|---|---|
| **A. 今のソケットを保ち、エンジンだけを Rust に（推す）** | 変えない | 変えない | 変えない | フロントの変更がゼロ。FakeDll の試験がそのまま使える。戻すのは配布物を戻すだけ | 常駐のプロセスとソケットは残る |
| B. mac と fcitx5 は同じプロセスの中（C ABI）、Windows はソケット | 変えない | EngineLink を書き直す | アドオンを書き直す | mac と fcitx5 でソケットと起こす処理が要らなくなる | 版が2つの形に分かれる。mac の音声とマイクの許可の流れを作り直すことになる |
| C. 全部同じプロセスの中 | 不可（DLL が全アプリに入るので、zenz と音声のモデルがアプリごとに読まれる） | — | — | — | 採らない |

A を推す。B は関門B のあとの任意のフェーズ7として残す。

### 4.5 Android
| 案 | 良い点 | 悪い点 |
|---|---|---|
| **UniFFI（推す）** | KeyInput・ImeCommand を record と enum の型で写せる。呼び返し（認識の trait）も書ける | 生成の道具が1つ増える。ライセンスが MPL-2.0（記憶で書いたので要確認） |
| 手書きの JNI（jni クレート） | 依存が少ない | 型の変換を手で書くので、間違えやすい |

- 共有ライブラリは `libkanade_core.so`（cargo-ndk、arm64-v8a。エミュレータ用に x86_64）。zenz と whisper は今の .so（`_v80` を含む）を Rust から dlopen する
- 3-7 の 300ms、onStartInput のまわり、画面キーボード・グライド・TapCorrection は Kotlin に残す（グライドを移すかは関門C のあとに決める）

## 5. 移行の方針

### 5.1 方針の比べ方
| | S0 書き直さず小さく直す | S1 実験として並行するが、ゴールを決めない | S2 すぐ Rust を本命にする | **S3 部品ごとに差し替え＋関門（推す）** |
|---|---|---|---|---|
| 中身 | InputEngine を分ける・音声の段取りを core へ・設定の一覧を作る・GraalVM の native-image で JRE を外せるか試す | Rust を作り続けるが、本命にする時を決めない | Kotlin は直すだけにして、Rust だけに機能を足す | デスクトップのエンジン → Android の順に差し替える。関門で止められる |
| 変える場所 | Kotlin のファイル 10 前後 | 多い | 多い | フェーズごとに分かれる（5.4） |
| 戻せるか | 戻せる | 戻せる | 難しい | 関門ごとに戻せる（配布物を戻すだけ） |
| 壊れやすい所 | native-image は JNI・sherpa の jar・javax.sound の設定が要り、動くかどうか分からない | 機能の二重の実装がいつまでも続く | Rust 版が Kotlin 版に追いつくまで本人の入力が劣る | 共通試験の作りに負う所が大きい |
| 後の土台 | JVM が残る | — | — | 日英混在をそこに作れる |
| 確かめ方 | 今の試験 | 共通試験 | 共通試験 | 共通試験と関門 |

S3 を推す理由: 本人が「本命か実験か」をまだ決めていないので、決める時点（関門A）を計画の中に置ける。フロントを変えずに差し替えられるので、戻すのが配布物の入れ替えだけで済む。S0 の一部（設定の一覧、P-6）は S3 の中でもやる。

### 5.2 共通試験の作り方
- 置き場所: `testdata/golden/*.jsonl`。1行が1つの場面で、`id`・`spec`（SPEC の番号）・`settings`・`ctx`（左右の文脈）・`field`・`lm`（`none` か、記録した答えのファイル）・`steps` を持つ
- `steps` は入力（キーの書き方は INPUT-LOG の keys と同じ。`{SP}{BS}{ENT}…`）と、期待する出力の行（**WINDOWS-PROTOCOL の命令の形**: `compose`・`commit`・`cands`・`mode`・`hidecands`、それに `lmreq`・`log` を足す）の組
- 作り方: Kotlin に `dictool golden record` を足し、`InputEngine` を流して記録する。場面の種は (1) 今の単体テスト 240 件を場面に直したもの、(2) FuzzTest の種を固定したでたらめな列、(3) 評価セットの読みをローマ字にして打ったもの、(4) EngineHostTest・VoiceSessionTest・DesktopStoreTest の流れ（プロトコルの一致を見る用）
- zenz の再生は2段: ① エンジンの段は「依頼 → 並べた結果」を記録して再生する（エンジンの論理だけを比べる）。② Reranker の段は「score・verify・greedy の生の値」を記録して再生する（並べ替えと直す処理の論理を比べる）。本物の zenz を使うのは評価セットだけ
- Rust の側は `kanade-tool golden check` で流す
- 本人の実際の入力（入力の記録）から作った場面は、リポジトリの外に置く（MIXED-INPUT の決定 13 に合わせる）

### 5.3 一致と合格の基準
| 物差し | 基準 |
|---|---|
| 共通試験（zenz なし・再生あり） | 全件一致。違いを許すのは、一覧（`testdata/golden/ALLOWED.md`）に理由を付けて載せたものだけ |
| 辞書だけの N-best（評価セット 202 文と ConverterTest） | 上位 10 件の表記・順番・コストが一致 |
| 本物の zenz（daily＋daily2） | 1位の正解数が Kotlin 以上（手元 189/202、Fold 7 187/202）。文ごとの違いは2文まで、それぞれ理由を書く |
| 文脈（context・context-right） | 正解数が Kotlin 以上（手元の数は Phase 0 で測り直す） |
| 速さ・Fold 7 | 辞書だけの中央値 3.6ms 以下、確かめて直すの中央値 5.8ms 以下・95% 13.3ms 以下 |
| 速さ・デスクトップ | M1 で 0.15 秒に1キーを 30 秒打ったときの CPU 時間が 4.8 秒以下。常駐のメモリと、起動から最初のキーを受け付けるまでの時間が、Phase 0 で測った Kotlin の値以下 |
| 音声 | 見本の wav で区切りの出来事の列が一致。同じモデルで文字が一致 |
| 入力の記録・設定 | 出力が一致（時刻と通し番号を除く）。設定のファイルは項目が一致 |
| 日英混在（入れるとき） | MIXED-INPUT の決定 12 の推し: daily 系の正解数が下がらない、日本語を英語と誤るのが 1% 以下、英語を日本語と誤るのが 5% 以下、判定は1キー 1ms 以下（本人が決める） |

### 5.4 フェーズ
| P | 目的 | 変える場所 | 手順の数 | 不確かな点 | 終わりの判定 | モデル | Fable 審査点 |
|---|---|---|---|---|---|---|---|
| 0 | 物差しを作る（Kotlin だけ） | dictool に1コマンド、testdata/golden/、docs/GOLDEN.md、（提案を採るなら）SPEC の番号 | 6（形を決める、記録を作る、単体テストを場面に直す、でたらめな列、評価セット、基準値を測る） | 非同期の id 0 の行の並び方 | 共通試験ができて Kotlin で全部通る。基準値が decisions に残っている | Sonnet | **形と一致の基準**（以後の契約になるため） |
| 1 | 辞書・ローマ字・ラティス | クレート 4（text・dict・convert・tool） | 5 | UTF-16 の並び、同じコストのときの並び、mmap | 辞書だけの N-best が全件一致 | Opus | — |
| 2 | エンジンと zenz（**関門A**） | クレート 3（lm・engine・log）、zenz_bridge.cpp に C の口 | 7 | 確かめて直すの呼び返し、再生の粒度 | 5.3 の上から4行と速さ（手元）を満たす | Opus | **エンジンの API と状態の型（作り始める前）**、**関門A の判断** |
| 3 | デスクトップのエンジンを置き換える（**関門B**） | クレート 4（settings・voice・host・server）、whisper の C の口 | 3a 入力 6、3b 音声 6 | cpal の録音（mac のマイクの許可は子のプロセスに付くか）、sherpa の C API の版 | FakeDll の流れが全部一致。cramgent で本人が使って困らない | Sonnet（VoiceSession とスレッドは Opus） | 関門B の前に差分の見直し（change-reviewer） |
| 4 | 配布を切り替える（Linux → macOS → Windows） | linux/build.sh・install.sh、mac/build.sh・pkg の scripts、windows/installer、scripts/*.sh | OS ごとに 3〜4 | mac の Hardened Runtime と公証、Windows の MSVC と cmake | 3つの OS で入れ直し・取り除くができる。入力の速さの基準を満たす | Sonnet | 公証の設定を変える前 |
| 5 | Android をつなぐ（**関門C**） | kanade-android、app の build.gradle.kts、KanadeInputMethodService.kt（段取りを外す） | 7 | UniFFI と主スレッド、`_v80`、APK の大きさ | 共通試験を手元の JVM から JNI で流して一致。Fold 7 の評価のボタンで速さの基準。Pixel 3 で落ちない | Opus | **UniFFI の口の形（作り始める前）** |
| 6 | Kotlin のエンジンを退役させる | core・desktop・zenz・whisper の各モジュール、dictool | 4 | 画面に残る Kotlin（グライドなど）が core のどこを使っているか | Gradle から core が外れる（または画面用だけ残る） | Sonnet | — |
| 7（任意） | mac・fcitx5 を同じプロセスの中で呼ぶ | kanade-capi、mac/ime、linux/fcitx5 | 5 | mac の音声の流れ | — | Opus | 始めるかどうかの判断 |

- 待ち時間で時間を言えるもの: llama.cpp のネイティブの組み立ては ABI ごとに数分（BUILD.md）。Fold 7・公証は Phase 0 で測って書く
- 日英混在の MIXED-INPUT の P2 以降は、関門A のあとに本命の側で作る（6 章 12 の 5）

### 5.5 並行している間の約束
1. 動きを変えるときは、SPEC と共通試験の場面を先に書く。次に本命の側に入れる。もう一方へ移すのを待つものは「移植待ち」（この文書の付録）に積む
2. 不具合を直したら、その不具合が起きる場面を共通試験に足す
3. 関門A までは Kotlin の記録が正しい答えとする。関門A のあとは Rust を正しい答えにし、Kotlin の記録は固定する
4. 形を変えるときは、両方の側を同じ commit で直し、版を上げる。対象: kdic などの辞書、settings.properties、書き出しの JSON、入力の記録の JSONL、プロトコル
5. 大きなエンジンの機能（日英混在の P2 以降）は、二重に作らない
6. 移植待ちが 10 件を超えたら、Kotlin に新しい機能を入れるのを止めるか、Rust をやめるかを本人に聞く

### 5.6 リスクとやめる判断
| リスク | 見張り方 | やめる・戻す判断 |
|---|---|---|
| N-best の並びが合わない | Phase 1 の全件一致 | 2回直しても理由が分からなければ、Phase 1 で止める |
| エンジンが一致しない・遅い | 関門A | 5.3 を満たさず、2回直しても届かなければ Rust を止め、実験として残す（Kotlin を本命のまま） |
| デスクトップで使い心地が悪くなる | 関門B（本人が cramgent で使う） | Kotlin のエンジンの配布物に戻す（フロントは同じなので、入れ替えるだけ） |
| Android が遅い・落ちる | 関門C | Android は Kotlin のエンジンのまま、デスクトップだけ Rust にする |
| 二重の保守が膨らむ | 移植待ちの件数 | 5.5 の 6 |
| ライセンスが混ざる | cargo-deny（許すライセンスの一覧） | 一覧にない依存は入れない |

### 5.7 ビルド・配布
- デスクトップ: jlink の手順を消し、`kanade-engine` を1つの実行ファイルにする。起こす道（`engine/bin/kanade-engine`）・VERSION・データの置き場所（mac の engine/data にある音声のモデルを含む）は変えないので、Kanade.app・pkg の識別子・cask は今のまま。mac は java 向けの entitlements を外し、Hardened Runtime をかけ直す。Windows の install.ps1・uninstall.ps1 は、java のコマンドラインではなく `kanade-engine.exe` のプロセスの名前で探す
- 組む所は今と同じ（Linux は使う機械の上、mac は ssh mac、Windows は to-fu）。llama.cpp と whisper.cpp は cmake クレートで組む
- Android: cargo-ndk を Gradle の作業にする。NDK r29 はもう入っている
- CI（無い所に足す）: GitHub Actions の Linux で cargo test・共通試験・Kotlin の試験・辞書だけの評価。zenz の評価を入れるかは別に決める
- 配布物の大きさは Phase 4 で測って decisions に書く（JRE の分が減る見込みだが、まだ測っていない）

### 5.8 ライセンス
- Kanade のコードは MIT のまま。Rust の依存は cargo-deny で MIT・Apache-2.0・BSD・ISC・Unicode・MPL-2.0（UniFFI）だけを許す
- 日英の判定は `MixedDetector` という trait の裏の独立したクレート（`kanade-mixed`）に置く。既定はクリーンルーム（Meltype のソースは読まない）
- GPL の道を選ぶ場合: (a) Kanade 全体を GPL-3.0-or-later に変える、または (b) GPL のクレートを feature で足した版だけを GPL で配る、のどちらか。どちらを選ぶにしても、**誰かが Meltype のソースを読む前に決める**（読んだ人が書いたものは、もうクリーンルームと言えないため）

## 6. 本人に決めてほしいこと（★が推し）

1. **Rust の位置づけ**: ★実験として始め、関門A を越えたら本命／最初から本命／実験のまま本命にしない／書き直さず小さく直す（S0）
2. **移す順番**: ★デスクトップのエンジン → Android／Android → デスクトップ／両方を同時に
3. **デスクトップのつなぎ方**: ★今のソケットのまま（mac と fcitx5 を同じプロセスの中にするのは任意のフェーズ7）／mac と fcitx5 は今から同じプロセスの中に（Windows は DLL が全アプリに入るので、どちらの案でもソケットのまま）
4. **Android の結び方**: ★UniFFI／手書きの JNI
5. **日英混在をどちらで作るか**: ★P0・P1 は Kotlin で（小さく、測るだけ）、P2 以降は関門A のあとに本命の側で／全部 Kotlin で今すぐ／全部 Rust で（関門A まで待つ）
6. **並行中の新機能**: ★SPEC と共通試験を先に書き、Kotlin に入れて「移植待ち」に積む。大きなエンジンの機能は止める／Kotlin の新機能を全部止める／制限しない
7. **Meltype のライセンス**: ★クリーンルームで MIT のまま／Kanade 全体を GPL-3.0-or-later に変えてソースを読む／GPL の部分を別のクレートに分け、その版だけ GPL で配る。誰かがソースを読む前に決める必要がある（戻せない）
8. **SPEC の作り直し（P-1・P-2）**: ★番号を振り直し、エンジン共通と版ごとに分け、組み方や測った値は外へ出す／番号だけ直す／今のまま
9. **設定（P-6・P-7）**: ★設定の一覧を1つにし、読み込みは既定でマージ／一覧だけ作り、置き換えのまま／今のまま
10. **確定の運用（P-4）**: ★共通試験のある「仮」を一覧で見直す／今のまま
11. **CI**: ★GitHub Actions（Linux）／手元だけ
12. **Windows の音声の既定（P-12）**: ★今のまま whisper small／ほかの版と同じ parakeet-ja

## 7. 不確かな点

- 確かめていない前提
  - kdic は Java の String の辞書順（UTF-16）で並んでいる。Rust で同じ並びと二分探索を写せるかは Phase 1 で確かめる
  - 同じコストの経路の並びが、Kotlin の実装の順番に頼っているかもしれない
  - sherpa-onnx 1.13.8 の C API の共有ライブラリが、各 OS と Android 向けに配られているか確かめていない
  - mac で cpal で録音したとき、マイクの許可が Kanade.app の子のプロセスに今と同じく付くか確かめていない
  - UniFFI のライセンス（MPL-2.0）は記憶で書いた
  - JRE を外したときの配布物の大きさとメモリは測っていない
  - 「入力の速さの基準」に使う打鍵の試験の道具（typesim.py）はリポジトリに無く、置き場所が不明
- 読んでいない範囲: app の画面のコード、windows・linux・mac のフロントの中身、docs/USAGE*.md・RESEARCH-*.md・DEX-KEYS.md、INPUT-LOG.md の 41 行以降、InputEngine.kt と KanadeInputMethodService.kt の本体の大部分、dictool の Main.kt の本体

## 付録: 移植待ち

（Phase 0 から使う。今は空）
