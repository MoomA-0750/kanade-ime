# Kanade の計画

2026-10-02 に CC との会話で方針を決めた。この文書がその会話の引き継ぎ資料で、会話履歴は残っていない前提で書いている。

## 目的

Samsung DeX で使う、物理キーボード最適化に全振りした Android 用日本語 IME を作る。

- 無駄なポップアップや画面キーボードなどの UI を出さない
- 変換は zenz（Zenzai）などの AI ベースで、ライブ変換を持つ
- 音声入力は Whisper などで行う
- 以上を可能な限りローカル（端末内）で動かす

## 本人が決めたこと（2026-10-02）

| 項目 | 決定 |
|---|---|
| 名前 | Kanade（リポジトリ `kanade-ime`） |
| キーボード配列 | **US 配列と JIS 配列の両方**に対応する。JIS は変換・無変換・かなキーをそのまま使う。US は日本語オン/オフを別のキー（候補: 右 Alt 単押し、Ctrl+Space、QMK で送る専用キーコード）に割り当て、設定で選べるようにする |
| 音声入力 | **押している間だけ録音（Whisper 系）と、話しながら文字が出る逐次認識の両方を切り替え**。まず押している間の録音から作り、逐次認識は後で足す |
| 進め方 | **Kite Browser と同様に完全自走**。判断は仮決定して進め、理由を記録する。GitHub への push だけは事前に確認する |
| 公開 | 当面ローカルのみ（GitHub へは上げない） |

## 実現性の見立て

| 要素 | 見込み | 方法 |
|---|---|---|
| 物理キーボード専用 IME | ◎ | `InputMethodService` で `onEvaluateInputViewShown()` を false にし、画面キーボードを出さない。キーは `onKeyDown`/`onKeyUp` で IME がすべて処理する |
| zenz による変換 | ◎ | azooKey フォークの llama.cpp（zenz のトークナイザ対応のため本家では読めない）を NDK でビルドし、JNI 経由で呼ぶ |
| ライブ変換 | ○ | 打鍵ごとに「かな→変換結果」を `setComposingText` で差し替える。zenz の小型モデルなら CPU で 1 打鍵数十〜100ms 台の見込み。前の推論の打ち切り、直前結果の再利用で遅延を抑える |
| 音声入力（押している間） | ○ | whisper.cpp。日本語は kotoba-whisper や large-v3-turbo の量子化版が候補 |
| 音声入力（逐次） | ○ | sherpa-onnx ＋ ReazonSpeech（ストリーミング zipformer）が候補 |
| 候補をカーソル近くに出す | △ | 最難関。標準の候補欄は画面下の帯。`CursorAnchorInfo` でカーソル位置は取れるが非対応アプリがある。オーバーレイ権限で浮かせる案もアプリ次第 |

## 既知の制約

- **DeX が先に取るキー**: Meta（Win）、Alt+Tab、Samsung の言語切替ショートカットなどは IME に届かない可能性がある。段階1で実機で洗い出し、表にして残す
- **未確定文字列の扱いがアプリで違う**: Termux などは composing text をうまく扱えない。アプリ（パッケージ名）別にライブ変換を切って直接入力にする設定を用意する
- **試験環境**: IME の基本動作はエミュレータや Pixel 3 で確かめる（本人の端末で試す前提にしない）。ただし DeX 固有のキー挙動と、zenz・Whisper の実速度は Galaxy 実機（Fold 7）でしか測れない。Fold 7 への導入は本人に頼むか、朝の確認一覧に積む
- **ビルド環境**: 作業機（Fedora）に Android SDK（`~/Android/Sdk`）と adb はある。java・gradle はまだ入っていない。重い NDK ビルドが足りなければ to-fu の WSL も使える（Kite で実績あり）

## 段階計画

### 段階0: 調査と方式決定

変換エンジンをどう載せるかを決める。候補は次の2つ。

- **A. azooKeyKanaKanjiConverter（Swift、MIT）を Swift の Android 向け SDK で動かす**。Zenzai・辞書・学習の仕組みをそのまま使える。ビルドの難度と apk サイズを確認する
- **B. 辞書とラティスは自前または Mozc（C++、BSD）、候補生成・評価に zenz**。Android でのビルドは楽

まず読むもの:

- [ニューラル言語モデル zenz を Android で使用する方法（Qiita）](https://qiita.com/Kazu_Project/items/536d89d1c499e1896b30)
- [StupidGame/Keynako](https://github.com/StupidGame/Keynako) — azooKey の Flutter 移植。Android の Zenzai を llama.cpp ＋ JNI で実装
- [azooKey の開発近況（2025年6月）](https://zenn.dev/azookey/articles/7a2c2d20a3cc4a)
- [nekomimist/skk-zenz](https://github.com/nekomimist/skk-zenz)、[unok/myime](https://github.com/unok/myime) — 他環境での zenz 組み込み例
- 既存の Android IME で zenz を組んだもの（KazumaProject 系など）があれば構成を確認する

決めたら、理由とともにこの文書へ追記する。

#### 段階0の決定（2026-10-02、CC が仮決定）

調査の詳細と出典は [RESEARCH-stage0.md](RESEARCH-stage0.md)。

**方式Bを採る。辞書とラティスは Kotlin で自前に持ち、zenz は llama.cpp（azooKey フォーク）を NDK でビルドして JNI で呼ぶ。**

| 部品 | 決定 |
|---|---|
| 辞書 | Mozc OSS 辞書（`src/data/dictionary_oss`）。ビルド時に自前の LOUDS 形式へ変換し、APK に同梱する。アプリ内のライセンス表示に IPAdic・ICOT の文面を入れる |
| ラティス・探索 | Kotlin で LOUDS トライ＋ビタビ（N-best）。UI なしで JVM の単体テストを回せる純 Kotlin モジュールにする |
| zenz | azooKey フォークの llama.cpp を submodule にし、CMake で arm64-v8a 向けに静的リンク。CPU 推論、4スレッド |
| モデル | zenz-v3.2-xsmall（Q5_K_M、21MB、Apache-2.0）から始め、精度が足りなければ small（74MB）へ。リポジトリには入れず取得手順を書く |
| zenz の使い方 | まず「辞書の N-best 上位を zenz で採点し、元の順位と混ぜる」と「貪欲生成の1候補を足す」。左文脈は `getTextBeforeCursor` で40文字。要求に番号を振り、古い推論は取り消す。発展形として Zenzai の制約付き再探索を Kotlin へ移植する |
| 参考実装 | Sumire（KazumaProject/JapaneseKeyboard、MIT）の LOUDS・ビタビ・`zenz_bridge.cpp`。依存はせず、必要な部分を読んで書くか、出典と MIT 表示を残して取り込む |

理由:

- **Kotlin で完結する**。方式Aは Swift ランタイムと Foundation（ICU 込み）だけで arm64 の .so が約86MB 増え、ツールチェーン（Swift 6.4 SDK、Fedora 向けは fedora41/ubi9 のみ）も1系統増える。エンジンは Sendable でなく、Kotlin の別スレッドから呼ぶと落ちる既知の問題もある
- **同じ構成が Sumire で実証済み**（MIT）。LOUDS・ビタビ・zenz の JNI・物理キーボードの設定まで揃っていて、読める参考がある
- **設計上の約束に合う**。変換ロジックを UI なしでテストでき、エンジン（ネイティブ）は zenz の推論だけに絞れる
- **Mozc 本体（`libmozc.so`）は採らない**。ビルドは可能だが JNI のクラス名が Google のパッケージに固定、protobuf の SessionHandler 丸ごとで、zenz をラティスに差し込むには C++ 改造が要る
- 辞書を Mozc にしたのは、IME 用のコストと接続行列が揃い、形式が素直なテキストだから。azooKey の辞書（Apache-2.0、語彙が新しい）は形式の一部が未文書なので、語彙を足したくなったら後で検討する

見直す条件: 変換品質が辞書＋zenz の並べ替えでは明らかに足りず、Zenzai の制約付き再探索の移植も重いと分かったとき。そのときは方式A（先例: ku6si0/azookey-android）を再検討する。

未確定のまま進めること:

- zenz-v3.2 の Apache-2.0 は HuggingFace のタグ表記でしか確認できていない。公開しないうちは問題にならないが、公開前に作者の表明を確かめる
- Android 実機での zenz の速度は未知。段階2で Fold 7 で測る

### 段階1: 最小の IME（Kotlin）

- ローマ字→かな、物理キー処理、画面 UI なし
- 日本語オン/オフ（JIS: 変換/無変換/かな、US: 設定で選んだキー）
- **DeX で届くキー・届かないキーの一覧**を実機で作る（エミュレータで先に骨組みを確認）

#### 段階1の実装と仮決定（2026-10-02）

- 構成: `core`（純 Kotlin。ローマ字→かな、未確定文字列、キー入力→IME の操作。JVM で単体テスト）と `app`（`InputMethodService`、物理キーの写像、設定とキー記録の画面）。`app` は `core` の `InputEngine` に入力を渡し、返ってきた操作（未確定の更新・確定・モード変更）を `InputConnection` に流すだけ
- 画面キーボードは出さない（`onEvaluateInputViewShown` が false）。日本語オンの表示はステータスアイコンだけ
- 日本語オン/オフ: JIS は変換・かな＝オン、無変換・英数＝オフ、半角/全角＝切り替え（常に有効）。US 用の右 Alt 単押し・Ctrl+Space・F13 は設定で個別に選べ、**既定は3つとも有効**（DeX で届かないものがあっても他で切り替えられるように）。右 Alt を切り替えに使う間は、右 Alt の押下自体をアプリへ渡さない
- 未確定がないときの Space・Enter・Backspace・矢印などはアプリへそのまま渡す。**Space は半角**のまま（全角スペースにしない）
- 未確定があるとき: Enter で確定、Esc で取り消し、Backspace で1文字（綴りの途中なら1字）消す、F6〜F10（または Ctrl+U/I/O/P/T。MS-IME と同じ割り当てで、本人の要望で 2026-10-02 に追加）でひらがな・カタカナ・半角カナ・全角英数・半角英数。未確定がないときの Ctrl+U などはアプリのショートカットとして通す。Space は段階3まで何もしない（変換キーの予約）。矢印・Ctrl 併用などは確定してからアプリへ渡す
- 日本語入力中の数字・記号・大文字は**既定で全角**（MS-IME・Google 日本語入力と同じ）。設定で半角にできる。`- , . [ ] / ~` は `ー 、 。 「 」 ・ 〜`
- キー記録: IME に届いたキーをアプリ内のファイル（最大 256KB）に書き、設定画面で読める。DeX のキー表（[DEX-KEYS.md](DEX-KEYS.md)）を adb なしで作るため、段階1では既定で記録する
- 本人が Fold 7 の DeX で使ってみて、ナビゲーションバーなども出ずに使え、不具合はなかった（2026-10-02）
- 確認: to-fu のエミュレータで、変換キー・F7・無変換・右 Alt・Ctrl+Space・F13・Backspace・Enter の動きと、画面キーボードが出ないこと（`mIsInputViewShown=false`）を確かめた。手順は [BUILD.md](BUILD.md)

### 段階2: 変換エンジン

- 段階0で決めた方式で、かな→漢字変換を動かす
- 端末上での 1 回あたりの推論時間を測り、記録する

### 段階3: ライブ変換

- 打鍵ごとの composing text 差し替え、Space で候補、Enter で確定、文節の伸縮と移動
- 候補表示は最小限（まず画面下の帯、次にカーソル近くを試す）
- アプリ別の直接入力切り替え

### 段階4: 音声入力

- whisper.cpp で押している間だけ録音（割り当てキーは設定）
- 後で sherpa-onnx の逐次認識を足し、設定で切り替え

### 段階5（任意）

- 重いモデルを使いたいときだけホームラボの GPU へ投げる経路。オフラインでもローカルで動き続けること

## 設計上の約束

- IME の層（キー処理・表示）と、変換・音声認識のエンジン（ネイティブ）を分ける。エンジンは Kotlin のインターフェース越しに呼び、UI 側の都合をエンジンへ持ち込まない
- 変換ロジック（ローマ字→かな、文節操作、候補の選び方）は UI なしでテストできる形にする
- モデルファイルはリポジトリに入れない。取得手順を `docs/` に書く
