# azooKey（iOS・macOS）と hazkey の使い勝手、変換品質の受け止められ方

調査日: 2026-11-08。GitHub のリリース・issue は `gh` で直接読んだ（日付は UTC）。Web 記事は WebFetch の要約を経ているので、引用の細部は原文で確かめること。

## 1. azooKey iOS: カスタマイズ・配列・候補・ライブ変換・Zenzai・学習・絵文字・フルアクセス

### Takeaway
iOS 版は「自由に作り替えられるキーボード」が売り。カスタムタブ（Custard）、ライブ変換、オフラインの Zenzai を備え、無料でフルアクセスも要らない。2025〜2026 年（v3.0〜v3.1.1）は、フリックの配列を Custard 基盤にまとめ、カスタムタブで QWERTY 型のキーを使えるようにする作業が中心だった。iOS の Zenzai 設定の細部（推論上限・端末要件）は一次情報を見つけられなかった。

### Cited Findings
- 公式サイトの機能一覧:
  - ライブ変換: 「打った側から自動で変換してくれる」。
  - 片手モード: 縦持ち・横持ち、ローマ字入力・フリック入力のそれぞれで設定できる。
  - 候補: タップすると拡大表示され、長い候補を選びやすい。
  - 絵文字と顔文字の変換は、それぞれ ON/OFF できる。
  - 価格: 「現在課金が必要な機能は一切提供していません」。
  — [azooKey 公式](https://azookey.com/)
- Zenzai は設定で有効にする。「完全オフラインで動作し、入力内容は外部に送信されません」。サイトに書かれた要件は「iOS 17.6以上」（iOS 26 にも対応）だけで、ハードウェアの要件は書かれていない — [azooKey 公式](https://azookey.com/)
- フルアクセス:
  - オンで使える追加機能は、キーの振動、クリップボード履歴、ペーストボタン。
  - 「フルアクセスがオフでも全く問題なくご利用いただけます」。
  - Q&A でも「必要ありません」とし、積極的に有効にする必要はないとしている。
  — [azooKey 公式](https://azookey.com/)、[Q&A](https://azookey.com/QA)
- カスタムタブ: 日本語入力と並べて使える独自の配列を作れる。ユーザーが作ったタブは問い合わせれば「原則掲載いたします」 — [Q&A](https://azookey.com/QA)、[CustomTabs](https://azookey.com/CustomTabs)
- README の紹介文: 「live conversion, flexible key layouts, and a clean SwiftUI interface」。カスタムキー・カスタムタブ、Zenzai 内蔵、App Store と TestFlight で配布、MIT ライセンス — [azooKey GitHub](https://github.com/azooKey/azooKey)
- v3.0（2025-09-08）の主な変更:
  - 「学習が意図せずにリセットされる問題を修正」。
  - 片手モードに「キーボードの高さ」設定を統合。
  - タイル型のクリップボード履歴 UI。
  - iOS 26 の角丸に対応。
  - 中華フォント問題の修正。
  — [azooKey v3.0 release](https://github.com/azooKey/azooKey/releases/tag/v3.0)
- v3.1（2026-08-25）の主な変更（最新は v3.1.1、2026-09-20）:
  - 「カスタムタブエディタでQWERTYタイプのキーをサポート」。
  - 「FlickLayoutProviderを廃止し、Custardベースの実装に統合」。
  - 上フリックでペーストボタンを出す機能が QWERTY でも有効になった。
  - キーの4方向ラベル。
  - direct input actions。
  — [azooKey v3.1 release](https://github.com/azooKey/azooKey/releases/tag/v3.1)
- Android 用 Sumire の開発者 KazumaProject が、iOS 版 azooKey に複数の PR を出している（v3.0） — [azooKey v3.0 release](https://github.com/azooKey/azooKey/releases/tag/v3.0)

### Inferences
- 標準の配列（フリック）を、ユーザーが作るカスタムタブと同じ記述形式（Custard）で表す設計に寄せている。こうすると標準とカスタムの機能差がなくなる。Kanade の配列定義にも同じ考え方が使える。
- フルアクセスを要求しない設計は、プライバシーへの不安を減らす売りとして明示されている。

### Gaps
- iOS 版の Zenzai 設定の中身（推論上限・変換プロフィール・文脈の利用）、対応端末や RAM、電池への影響は一次情報で確かめられなかった。
- 学習リセットの UI が iOS 版にあるかは、公式ページで確かめられなかった（v3.0 で意図しないリセットの不具合を直した記録だけがある）。
- App Store のレビュー本文は取得していない。

## 2. azooKey on macOS: GPU 推論・ライブ変換・文脈・いい感じ変換・候補ウィンドウ

### Takeaway
macOS 版は Zenzai を「常に有効」の前提で作っている（v0.1.2 で Zenzai の ON/OFF を廃止）。カーソル直前の文脈と変換プロフィールを使い、Mac の GPU を使ってローカルで推論する。LLM 機能の「いい感じ変換」（OpenAI API、macOS 26 以降は Apple Intelligence が既定）や翻訳、予測入力、誤入力の訂正を足していく方向にある。状態は 2026-11 時点でもアルファ（安定版は v0.1.4、2026-05-17）。体感で目立つ不満は、重さ・固まり（レインボーカーソル、メモリの積み上がり）と、学習が Zenzai に負けて効かないこと。

### Cited Findings
- 初期の設計（2024-05-16）:
  - モデルは zenz-v1（GPT-2 系 90M、Q8_0 量子化）を llama.cpp で Swift から直接呼ぶ。従来エンジンの候補を下書きにして zenz が検証する、推測デコード型の方式。
  - 推論上限は「1」。
  - 自作の150問で 56→100 問正解、時間は 0.0262→0.0598 秒/クエリ。Anthy コーパス 1,745 問では 1065→1179。
  - 1操作あたり約 40 ms。作者は、これがライブ変換の上限で、20〜30 ms なら滑らかと述べている。
  - 体感は M2 Pro の Mac mini で「ちょっと重いかな？ってときのMacくらい」。M3 MacBook Air でも同程度という報告がある。
  - 失敗例: 「精度」と「制度」を区別できない。「ゴタントウシャ」は変換できず「ゴタントウシャガ」なら変換できる。辞書にない語（深掘る）は候補を作れない。ライブ変換中に、末尾ではなく前のほうの文字列が変わる。
  — [Zenn: Zenzai を azooKey on macOS に搭載します](https://zenn.dev/azookey/articles/ea15bacf81521e)
- ITmedia の試用記事（2025-07-17、v0.1.0-alpha.19）:
  - 「MacのGPUを使って高度な推測を行う」。ローカルで動き、オフラインでも使える。
  - カーソル直前の文章を読んで変換を予測する。
  - 設定項目: 「変換プロフィール」（職業・趣味を入れる、実験的）、「推論上限」（上げると遅くなる）、ライブ変換、ユーザー辞書（登録語は2番目の候補に出る）。
  — [ITmedia p1](https://www.itmedia.co.jp/news/article/2507/17/1250717051/)
- いい感じ変換（同記事 p2）:
  - 操作: 文字を選んで Ctrl+S を押すと専用の窓が開き、どう直したいかを書く。和英・英和の翻訳、口調や体裁の変更、「つづき」と打つと続きの候補を複数出す。
  - 欠点: 文体がである調でもですます調に寄りやすい。入力を受け付けない欄では使えない。Ctrl キーが要る（Karabiner での割り当てを勧めている）。
  - 間違えた変換を一度直すと次から正しくなる。
  - 文中の英単語には弱い（カタカナで打てば Apple・Google は出る）。ATOK の、Shift で英字モードに入る仕組みのほうが便利と比べている。
  — [ITmedia p2](https://www.itmedia.co.jp/news/articles/2507/17/news051_2.html)
- 利用者の感想（Zenn、2025-07-08、約1か月使用）:
  - 「安定して精度高く漢字変換できるライブ変換体験はかなり楽しい」。標準の macOS のライブ変換は「変換精度に不満」があり、azooKey のほうが上。
  - 短い断片を直すときは思い通りに動かない。アプリを切り替えるとレインボーカーソルが出る。
  — [Zenn hibara428](https://zenn.dev/hibara428/articles/de6515763138a5)
- README の機能一覧: Zenzai、ライブ変換、LLM による「いい感じ変換」、プロフィールプロンプト、履歴学習、ユーザー辞書、Tuner 連携、AZIK。インストールは pkg か `brew install azooKey`。状態はアルファ版 — [azooKey-Desktop](https://github.com/azooKey/azooKey-Desktop)
- リリースの経過:
  - v0.1.1（2025-10-04）: zenz v3.1 に更新。「学習を利用」でクラッシュする不具合を修正。
  - v0.1.2（2025-11-10）: 「Zenzaiのトグルを削除」。カスタム入力テーブル、日付・時刻の変換。
  - v0.1.3（2026-01-10）:
    - macOS 26 以降は Apple Intelligence の基盤モデルによるいい感じ変換が既定。
    - 文字を選んで「英数」を2回押すと英訳、「かな」を2回押すと和訳。
    - Colemak/Dvorak、Unicode 入力（Ctrl+Shift+U）、履歴学習のリセット。
  - v0.1.4（2026-05-17）:
    - zenz-v3.2 で精度が上がった。
    - PoC の予測入力・誤入力訂正（Backspace で typo 訂正を出す。誤入力訂正の候補は予測候補と分けて優先表示）。
    - Zenzai を使った入力予測。上矢印で追加候補。
    - 候補ウィンドウのデザイン調整。
    - いい感じ変換を Esc でキャンセルできる。ピン留めプロンプトのショートカット。
  - v0.1.5-beta.1（2026-06-13）: 変換を別プロセス（ConverterServer）に分けた。大きく壊れている可能性があるとの警告付き。
  — [releases](https://github.com/azooKey/azooKey-Desktop/releases)
- issue #347（2026-10 に close）: プロセス分離の後、セッションごとに GGUF モデルを読み込んだまま解放せず、メモリが積み上がって入力が数秒固まる（M3 Pro、3日以上連続で動かした例） — [#347](https://github.com/azooKey/azooKey-Desktop/issues/347)
- issue #356（open）: 「きどうてすと」を何度「起動テスト」に直して確定しても、左文脈がない場所では Zenzai の並べ替えで「軌道テスト」に戻る。「何度直しても学習されない」ように見えて強いストレスになる、という報告。提案は、読みが完全一致した直近の確定を最優先する方式（Google 日本語入力の学習に近い） — [#356](https://github.com/azooKey/azooKey-Desktop/issues/356)

### Inferences
- 文脈を使う言語モデルの並べ替えと、利用者の学習（確定履歴）がぶつかるのは構造的な問題。Kanade で zenz を使うなら「直近に確定した同じ読みを優先する」規則を最初から入れておくべき。
- 40 ms/操作がライブ変換の体感の境目という作者の目安は、Android の実機でそのまま使える基準になる。

### Gaps
- 候補ウィンドウの細かい挙動（縦か横か、ページ送り、数字キーでの選択）は、記事にも README にも詳しく書かれていなかった。
- Metal を使っている明示は ITmedia の「GPUを使って」だけで、実装（llama.cpp の Metal）の公式の記述は見ていない。
- 文脈をどの API で取っているか（Accessibility か IMK の文脈か）は確かめていない。

## 3. hazkey（Linux / fcitx5）: 作り・バックエンド・設定・導入・不具合

### Takeaway
hazkey は2つの部品でできている。fcitx5 のアドオン（C++）と、Swift で書いた変換サーバー hazkey-server で、両者は Protobuf で通信する。設定は Qt の hazkey-settings で行う。Zenzai は llama.cpp で動き、CPU と Vulkan を選べる。0.2.1（2026-02-08）からモデルは設定画面でダウンロードでき、既定は CPU。macOS 版と違い Zenzai は任意で、いい感じ変換のような LLM 機能はない。不具合は、サーバーとの接続や GPU ドライバ（マルチ GPU の Vulkan、aarch64）まわりに集中している。

### Cited Findings
- 作り: リポジトリは `fcitx5-hazkey` / `hazkey-server` / `hazkey-settings` / `protocol` に分かれる。依存は Swift ≥ 6.1、fcitx5 ≥ 5.0.4、Qt ≥ 6.7、Protobuf。配布は AUR と .deb。ビルドフラグに `-DGGML_VULKAN` がある。2026-11 時点で約 250 stars — [hazkey GitHub](https://github.com/7ka-Hiira/fcitx5-hazkey)
- hazkey-server には辞書、絵文字辞書、llama.cpp がサブモジュールとして入っている — [hazkey-server ディレクトリ](https://github.com/7ka-Hiira/fcitx5-hazkey/tree/main/hazkey-server)
- 設定のスキーマ（`protocol/config.proto`）から読める設定項目:
  - 自動変換: 無効 / 常に / 複数文字のとき。
  - 補助テキストの表示モード。
  - 予測候補の一覧: 無効 / 通常の結果 / 予測の結果。候補の数、1ページあたりの候補数、リッチ表示。
  - 学習: 入力履歴の利用、新しい履歴を保存しない、プロファイルとは独立した履歴。
  - 特殊変換: 桁区切りの数字、メールのドメイン、日付、時刻、ローマ数字、Unicode のコードポイント、半角カタカナ、拡張絵文字。
  - キーマップと入力テーブル。
  - Zenzai: `zenzai_enable`、`zenzai_infer_limit`、`zenzai_contextual_mode`、独自の重みファイルのパス、`zenzai_backend_device_name`、`zenzai_profile` / `topic` / `style` / `preference`。
  - コマンドとして、全履歴の消去（ClearAllHistory）とモデルの再読み込み。
  — [config.proto](https://github.com/7ka-Hiira/fcitx5-hazkey/blob/main/protocol/config.proto)
- 0.2.0（2025-11-09）:
  - 学習機能が入り、既定でオン。hazkey-settings、カスタムキーマップと入力テーブル、自動変換と予測候補一覧のオフ設定。
  - エンジンを更新して「パフォーマンスが大幅に改善」。
  - Zenzai を別パッケージ（hazkey-zenzai）に分け、ランタイム（Vulkan、CPU 等）を選べるようにした。「CPUでも実用的な速度が出ます」。
  — [0.2.0 release](https://github.com/7ka-Hiira/fcitx5-hazkey/releases/tag/0.2.0)
- 0.2.1（2026-02-08、2026-11 時点の最新）:
  - Zenzai の追加モジュールが不要になった。バックエンドのデバイスは設定画面で選び、「初期設定はCPU」。モデルも設定画面からダウンロードできる。
  - 「学習データが増えた場合に確定が遅くなる」問題を修正し、保存を終了時と自動保存の間隔ごとにした。
  - 学習データの場所を XDG_STATE_HOME に変更。
  - Esc がアプリに届かない問題を修正。
  — [0.2.1 release](https://github.com/7ka-Hiira/fcitx5-hazkey/releases/tag/0.2.1)
- 0.0.9（2025-03-18）でモデルを zenz v3 に更新した — [releases](https://github.com/7ka-Hiira/fcitx5-hazkey/releases)
- 公式ドキュメント:
  - モデルは zenz-v3.1（GPT-2 系 90M）。
  - 文脈変換の例: 前の語が「絵」なら「描き」、「小説」なら「書き」。文脈を取れるアプリでだけ働く。
  - プロフィール変換の例: 「りょうし」が、魚屋・猟師・物理学者・雑誌編集者のプロフィールでそれぞれ違う結果になる（学習オフのとき）。
  — [hazkey docs: Zenzai](https://hazkey.hiira.dev/docs/zenzai)
- 導入: ドキュメントに Ubuntu と Arch の手順、ソースからのビルド手順がある — [hazkey docs: install](https://hazkey.hiira.dev/docs/install)
- AUR の fcitx5-hazkey-bin 0.2.1-1 では、パスの想定違いで Zenzai が動かなかった（ライブラリは `/usr/lib/hazkey/libllama/backends/`）。0.2.1-2 で修正された — [AUR](https://aur.archlinux.org/packages/fcitx5-hazkey-bin)
- issue に出ている使い勝手の問題:
  - #9（直った）: 履歴を保存するときに確定が遅い。
  - #17（直った）: hazkey-server が GNOME の終了を遅くする。
  - #26: 入力中に時々固まる。サーバー側に約5秒のタイムアウトが要るという意見。
  - #29: NVIDIA と Mesa の Vulkan ICD が同居すると SIGILL で落ち、fcitx5 も巻き込まれる（RTX 4070 と Ryzen の iGPU）。報告者は後に「Rust で同じ構成の IME を自作したので最近使っていない」と書いている。
  - #35: 学習した履歴が、サーバーを再起動するまで順位に反映されない。
  - #41（2026-11-07）: aarch64 で Zenzai を有効にすると SIGSEGV（CPU バックエンドが x86 用しか作られない）。
  - #24: 「そくてんぶこう」で「則天武后醤油か砂糖か」という異様な候補が出る。
  - #11（直った）: 麻雀用語が変換できない。
  - #16: ライブ変換中に Home/End が効いてほしい。
  - 未実装の要望: #6 文節の編集、#2 ユーザー辞書、#39 複数クライアントから1つのサーバーを使う。
  — [hazkey issues](https://github.com/7ka-Hiira/fcitx5-hazkey/issues?q=is%3Aissue)

### Inferences
- 変換を別プロセスのサーバーにする構成は、macOS 版も v0.1.5 で追いかけた。ただ、どちらも「サーバーとの接続が切れる・固まる・資源を解放しない」問題を抱えた。プロセスを分けるなら、タイムアウト、IME 側へのフォールバック、セッションの解放を最初から設計に入れる必要がある。
- GPU（Vulkan）は環境の差で落ちやすい。hazkey が既定を CPU にしたのは、安定性を優先した判断と読める。Android では端末ごとの Vulkan 実装の差がもっと大きいので、同じ判断が当てはまりそう。
- macOS 版との違い:
  - Zenzai: macOS は常にオン、hazkey は任意で既定 CPU。
  - LLM 機能: macOS はいい感じ変換・翻訳・予測・typo 訂正を持つが、hazkey は持たない。
  - 設定: hazkey はプロフィール単位にまとめ、topic/style/preference を分けた項目にしている。

### Gaps
- Linux 上の実測（CPU と Vulkan の遅延の数値）は、公開された計測を見つけられなかった。
- hazkey の個人ブログのレビューは、検索では見つからなかった。

## 4. Android 移植・AzooKeyKanaKanjiConverter や zenz を使う他の IME

### Takeaway
公式の Android 版はない。2026-11 時点で見つかったのは、非公式の実験と個人のプロジェクトだけ。Swift を Android 向けにビルドして JNI で呼ぶもの、Windows で Mozc の候補を zenz で並べ替えるもの、zenz 系の独自モデルなど。

### Cited Findings
- fkunn1326/azookey-android-demo（2025-02）: swift-android-sdk で AzooKey エンジンを Android から呼ぶ JNI のデモ — [repo](https://github.com/fkunn1326/azookey-android-demo)
- ku6si0/azookey-android（2026-07、非公式）:
  - QWERTY ローマ字入力、かな漢字変換、部分変換、学習。
  - Zenzai（zenz-v3、llama.cpp、arm64 のみ、ON/OFF できる）。
  - 全 Unicode の絵文字パレット、スペースの長押しでカーソル移動など。
  — [repo](https://github.com/ku6si0/azookey-android)
- g0083/ZeroConvert-IME（v0.4.1、2026-11）:
  - Windows 版 azooKey を基盤に、zenz 系の独自モデル「ZeroCon Model」でライブ変換、タイポ補正、先読みを行う。Android の APK もある。
  - 翻訳は端末内の PLaMo 3 Translate 0.8B（Android では約 547MiB を初回にダウンロード）。
  — [repo](https://github.com/g0083/ZeroConvert-IME)
- kohei-kawaguchi/llm-japanese-input（2026-09）: Mozc ベースの Windows IME。文節ごとに Mozc の上位5候補を、読みと周りの文字列を入れた zenz-v3.2-small で CPU 上で採点して並べ替える。読みをそのまま写した候補には罰則を加え、Mozc の元の順位も事前の重みとして使う — [repo](https://github.com/kohei-kawaguchi/llm-japanese-input)
- その他:
  - fkunn1326/azooKey-Windows（2026-11 も更新中）。
  - unok/myime（Mozc と AzooKey 変換器と Zenzai の Windows IME）。
  - keufcp/zenzai-kkc-server（Linux の CPU で動く HTTP API。AVX2 が必須）。
  — [GitHub 検索 azooKey-Windows](https://github.com/fkunn1326/azooKey-Windows)、[zenzai-kkc-server](https://github.com/keufcp/zenzai-kkc-server)
- Android の Sumire（KazumaProject）は azooKey iOS に PR を出しているが、Sumire 自体が AzooKey エンジンを使っているという情報は見つからなかった — [azooKey v3.0 release](https://github.com/azooKey/azooKey/releases/tag/v3.0)

### Inferences
- Android で Swift のエンジンを動かせることは、デモで確かめられている。ただ、llama.cpp を arm64 向けに作る必要がある（hazkey #41 の aarch64 の問題と同じ種類の落とし穴）。
- 「既存エンジンの上位 N 候補を zenz で並べ替えるだけ」（llm-japanese-input）という軽い組み込み方は、Kanade の最初の一歩として現実的。

### Gaps
- これらの非公式移植について、利用者の評価や実機での速度は見つからなかった。

## 5. 利用者の評価: 精度（Google・ATOK・Mozc との比較）、速度、電池、失敗例

### Takeaway
macOS でも文脈を使ったライブ変換は「楽しい」「標準より精度が高い」と好意的に受け止められている。不満は次の3つに集まる。英単語や専門語・珍しい語（辞書にない語）に弱いこと、学習が効きにくいこと（Zenzai の並べ替えに負ける、再起動まで反映されない）、重さや固まり（レインボーカーソル、メモリ、サーバーのフリーズ）。Google・ATOK と体系立てて比べたものや、電池への影響の報告は見つからなかった。

### Cited Findings
- 標準の macOS IME より精度が高く、ライブ変換を日常で使えるようになった — [Zenn hibara428](https://zenn.dev/hibara428/articles/de6515763138a5)
- 英単語の扱いは ATOK に劣る。一度直した変換は覚える — [ITmedia p2](https://www.itmedia.co.jp/news/articles/2507/17/news051_2.html)
- 定量的な比較: 作者の150問で 56→100、Anthy コーパスで 1065→1179（従来エンジンとの比較で、Google・ATOK との比較ではない） — [Zenn azooKey](https://zenn.dev/azookey/articles/ea15bacf81521e)
- 失敗例:
  - 学習が戻される（軌道テスト・起動テスト） — [#356](https://github.com/azooKey/azooKey-Desktop/issues/356)
  - 「便宜上」が出ないという報告を切り分けた — [#391](https://github.com/azooKey/azooKey-Desktop/issues/391)
  - 「則天武后醤油か砂糖か」 — [hazkey #24](https://github.com/7ka-Hiira/fcitx5-hazkey/issues/24)
  - 麻雀用語が出ない — [hazkey #11](https://github.com/7ka-Hiira/fcitx5-hazkey/issues/11)
- 速度と固まり:
  - 約 40 ms/操作で「ちょっと重い Mac くらい」 — [Zenn azooKey](https://zenn.dev/azookey/articles/ea15bacf81521e)
  - 入力が数秒固まる — [#347](https://github.com/azooKey/azooKey-Desktop/issues/347)
  - 時々固まる — [hazkey #26](https://github.com/7ka-Hiira/fcitx5-hazkey/issues/26)

### Inferences
- 精度への評価は高いが、比べている相手は主に「標準の macOS IME」と「従来の azooKey」。Google 日本語入力や ATOK との直接の比較は、公開資料の中では弱い。
- 日常で使う感触を決めているのは、精度より学習の即効性と「固まらないこと」。

### Gaps
- App Store のレビュー、X の投稿、Reddit の意見は取得できなかった（検索で拾えなかった）。
- 電池（iOS で Zenzai を使ったときの消費）の報告は見つからなかった。
