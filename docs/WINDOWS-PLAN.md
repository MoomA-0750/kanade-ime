# Windows 版の計画

Kanade のライブ変換・zenz の並べ替え・音声入力を、Windows でも使えるようにする（2026-10-04 に本人が希望）。
このファイルは、Android 版を作ってきたセッションから、Windows 版を作る新しいセッションへの引き継ぎを兼ねる。

## いまの状態（Android 版の作り）

- `core/`: 入力の判断（ローマ字・ライブ変換・候補・学習・ユーザー辞書・スニペット・音声の区切り・話した句読点・グライド）。**Android に依存しない純 Kotlin（JVM 17）**で、JVM で単体テストしている
- `zenz/`・`whisper/`: zenz（llama.cpp）と whisper.cpp を JNI で呼ぶ薄い Kotlin。これも純 JVM
- `native/`: zenz・whisper の C++ 側（CMake）。`scripts/build-host-zenz.sh` で手元（Linux x86_64）向けにもビルドでき、`dictool` の評価で使っている
- `dictool/`: Mozc の辞書の変換、assets の取得・生成、評価（JVM のコマンド）
- `app/`: Android 固有（IME のサービス、画面キーボード、設定画面、sherpa-onnx の AAR での音声認識）
  - 注意: 入力の「段取り」（エンジンの命令をアプリに渡す、zenz の並べ替えを裏で回す、音声の聞き続けるモードの区切りと流し込み）は `KanadeInputMethodService.kt` に書いてあり、Android と混ざっている

つまり、コアの切り出しは済んでいる。Windows 版で新しく要るのは「Windows の入力の仕組み（TSF）とのつなぎ」と「段取りの共通化」。

## 方式

### 候補

| 方式 | 中身 | 良い点 | 悪い点 |
|---|---|---|---|
| A. **別プロセスのエンジン＋薄い TSF**（推奨） | TSF のテキストサービス（C++ の DLL）はキーを受けて名前付きパイプでエンジンに渡し、返ってきた入力中の文字・確定を書くだけ。エンジンは常駐する JVM のプロセス（`core`・`zenz`・音声認識をそのまま使う） | `core` をそのまま使える。DLL が小さいので、全アプリに読み込まれる DLL が落ちる危険が小さい。Mozc（Google 日本語入力）も同じ形（mozc_server） | 常駐プロセスが要る（JVM のメモリ 100〜300MB 程度）。起動・死活の管理が要る |
| B. すべて DLL の中 | C++/Rust で TSF と変換を全部書く | 常駐なし | `core` を書き直すことになる。重い（zenz・音声）処理が各アプリのプロセスに入る |
| C. Kotlin/Native で `core` を DLL に | `core` をマルチプラットフォームにする | 1つの DLL | `java.nio`（辞書の MappedByteBuffer）などを置き換える大きな手直し。zenz・音声の JNI も作り直し |

A にする。TSF の DLL はアプリごとのプロセスに読み込まれるので、そこに JVM や重い推論を入れない（B・C の弱点）のが大きい。

### 構成（案）

```
desktop/engine/      Kotlin/JVM。名前付きパイプのサーバー。core・zenz・whisper・sherpa-onnx（Java 版）を使う
                     候補の窓・入力モードの表示・設定の画面（Compose for Desktop を第一候補。Swing でもよい）
                     音声: マイクは javax.sound、認識は sherpa-onnx の Windows 用の Java API（ReazonSpeech など）か whisper
desktop/protocol/    パイプでやりとりする命令の形（キー・入力中の文字・確定・候補の位置）。JSON 行か小さなバイナリ
windows/tsf/         C++ の TSF テキストサービス（ITfTextInputProcessorEx・ITfKeyEventSink・ITfComposition・表示属性）。CMake + MSVC
windows/installer/   DLL の登録（regsvr32）、エンジンをログオン時に起動、JDK を jlink で同梱
```

- 段取り（`KanadeInputMethodService.kt` のうち Android に依存しない部分）は、`core` か新しい共通のモジュールに移して、Android 版と Windows 版の両方から使う。移すときは Android 版が壊れないよう、エミュレータで確かめる
- 候補の窓は、TSF の UI 要素（ITfCandidateListUIElement）に乗せるのが本筋だが、まずはエンジンが自前の窓をカーソルの近くに出す（Android 版の重ねる窓と同じ考え方）。カーソルの位置は TSF の ITfContextView::GetTextExt で DLL が取ってエンジンに送る
- 物理キーボードは Android 版と同じ: US 配列でも JIS 配列でも、右 Alt の単押し・Ctrl+Space・変換/無変換などで日本語のオン/オフ

## 段取り（フェーズ）

1. **ビルド環境**: to-fu（Windows 11 Pro）には MSVC（Build Tools 2022、14.44）・CMake（Build Tools 同梱）・Windows SDK 10.0.26100 がある。JDK は無いので、管理者権限なしでユーザーのフォルダに置く（Android 版の手元と同じ Temurin 21）。ビルドは手元（Linux）から ssh で to-fu に投げる（`scripts/emu.sh` と同じやり方）
2. **エンジンのサーバー**: パイプのサーバーと、キー列を流して入力中の文字・確定を見る試験の道具。Linux でも JVM で動かして試せるようにする（パイプの部分だけを差し替え）
3. **TSF の最小**: 日本語オフは素通し、オンでローマ字→かなの入力中の文字を出し、Enter で確定。メモ帳・ブラウザ・ターミナルで確かめる
4. **変換と候補の窓**: Space で変換・候補の窓・数字で選ぶ・F6〜F10。ライブ変換
5. **zenz の並べ替え**: Android 版と同じ（zenz の GGUF は `dictool` が取得するもの）。llama.cpp を MSVC でビルド
6. **音声入力**: 録音キーを押している間だけ録音、ReazonSpeech（話しながら）と、確定の文を作るモデル（ReazonSpeech・SenseVoice・parakeet・whisper）。モデルは Android 版と同じく、設定の画面からダウンロード
7. **設定・インストール**: 設定の画面（ユーザー辞書・スニペット・音声のモデル）、Android 版の設定の書き出しファイル（`SettingsBackup.kt` の JSON）を読めるようにする、インストーラー

## 本人の答え（2026-10-04）

- DLL の登録: **to-fu で登録して試してよい**（普段のユーザーで。落ちたら登録の解除で戻す）
- 主に使うアプリ: **ブラウザ・ターミナル・エディタ**（Office は後回し）。この3つを優先して確かめる
- キーボード配列: **US と JIS の両方**（Android 版と同じく、右 Alt の単押し・Ctrl+Space・変換/無変換・半角/全角）
- MS-IME: **最終的には Kanade だけにしたい**。試験中は逃げ道として MS-IME を残し、外すのは安定してから本人が行う

## 進み具合と仮決定（2026-10-04〜）

- フェーズ1 済: to-fu の JDK は **Android Studio 同梱の JBR 21** を使う（入れる手間がなく、Temurin 21 と同じ版）。配るときは jlink で同梱する（フェーズ7）。エンジン（Kotlin）は手元でビルドして送り、DLL は to-fu の MSVC でビルドする。道具は `scripts/win.sh`（engine・data・tsf・eng・ps）
- フェーズ2 済: `desktop/`（Gradle の `:desktop`、1つのモジュール）にエンジンを置いた。計画の `desktop/engine`・`desktop/protocol` に分けなかったのは、命令の形を使う Kotlin がエンジンだけで、C++ 側とは文書（`docs/WINDOWS-PROTOCOL.md`）で合わせれば足りるため
  - つなぎ方は名前付きパイプではなく **AF_UNIX のソケット**にした（理由は `docs/WINDOWS-PROTOCOL.md`）。Linux でも同じ形で動き、to-fu でも動いた
  - エンジンは1つの `InputEngine` を全アプリで共有（日本語のオン/オフも共通。Android 版と同じ）。入力中の文字はフォーカスのある DLL だけが持つ
  - キーの割り当ては全部エンジン側（`WinKeyMapper.kt`）。DLL は仮想キー・ToUnicodeEx の文字・修飾キーを送るだけ
  - 試験: `kanade-engine type '<キー列>'`（同じプロセス）と `send`（ソケット越し）、単体テスト（偽の DLL `FakeDll.kt`）。to-fu で辞書の読み込み約0.1〜0.3秒、1文の打鍵で約70ms

- フェーズ3 済（試験の窓まで）: `windows/tsf`（C++、CMake + MSVC）。キーは全部エンジンへ送り、返ってきた命令で入力中の文字・確定・モードを書くだけ。エンジンにつながらない・300ms 返事がないときは素通し
  - 試験は `scripts/win.sh type '<キー列>'`（`kanade_typetest.exe`。このプロセスだけ Kanade に切り替え、自分の窓が前面のときだけ SendInput で打つ。本人の作業中の画面で、ほかの窓へ打たないため）。Win32 の EDIT（IMM 経由）と RichEdit（TSF に直接対応）の両方で、ローマ字・ライブ変換・変換・F7・Backspace・右 Alt・Ctrl+Space・変換/無変換・ショートカットでの確定を確かめた
  - 分かったこと: RichEdit など TSF に直接対応したアプリでは OnTestKeyDown が呼ばれず、Alt を押したことも届かない（離したことだけ届く）。右 Alt の単押しは「離したとき、その間に Alt と一緒にほかのキーを押していなければ」で判定する
  - 分かったこと: IMM のアプリでは、入力中にモードを閉じるとシステムが入力中の文字を確定させる。モードの切り替えは文字を書いたあとにする
  - 開発中の DLL は使用中だと上書きできないので、毎回別の名前に写して登録し直す（`scripts/win.sh reg`）。調べる記録は `%LOCALAPPDATA%\Kanade\tsf-log` フォルダがあるときだけ書く
  - 本人が試したら、どのアプリでも日本語に切り替えられなかった（2026-10-04）。原因はエンジンが止まっていたこと（隠したコンソールが Ctrl+C を受けて終わった）。エンジンを javaw（コンソールなし）で動かし、記録は自分で `%LOCALAPPDATA%\Kanade\engine.log` に書く。DLL はつながらないとき、`HKCU\Software\Kanade\EngineCommand` の命令でエンジンを起動する（Mozc と同じ。普通の権限のプロセスからだけ、15秒に1回まで、アプリのジョブから外して）
  - 本人の2回目: Zen とメモ帳はライブ変換つきで打てた。Windows Terminal は英字だけ（10月1日から動くプロセスが最初の版の DLL を持ったまま。開き直しが要る）。スタートメニューの検索はライブ変換なし（隔離されたアプリが DLL を読めず、代わりの入力になっていた見込み）→ DLL のフォルダとソケットに ALL APPLICATION PACKAGES の権限と低い整合性を付けた。ソケットの場所は KF_FLAG_NO_APPCONTAINER_REDIRECTION で取る
  - DLL の記録は WriteFile で共有して書く（同じプロセスに別の版の DLL があると、前の書き方では空のファイルになった見込み）
  - 本人の3回目（サインインし直して）: Zen・メモ帳は動く、Windows Terminal とスタートメニューは英字だけ。DLL の記録では Windows Terminal・1Password（どちらもパッケージ化されたアプリ）で接続が WSAEINVAL（10022）
    - 再現: 試験の窓を Windows Terminal のパッケージの中（Invoke-CommandInDesktopPackage、ログオン中のセッション）で動かすと同じ 10022。パッケージ化されたアプリでは AppData が仮想化され、そこに置いた AF_UNIX のソケットにつながらない
    - 直した: ソケットを `%USERPROFILE%\.kanade\engine.sock` に移した。パッケージの中でもつながった
    - スタートメニュー（AppContainer）: `kanade_appcontainer.exe`（命令を AppContainer の中で動かす道具）で試すと、AppContainer の中ではプロファイルのフォルダの場所が取れていなかった。USERPROFILE で取り直し、つながった
    - 移行のあいだだけ、エンジンは前の場所（%LOCALAPPDATA%\Kanade\engine.sock）でも待つ（前の版の DLL を読み込んだままのアプリのため）。フェーズ7で外す
  - 残り: ブラウザ・ターミナル・エディタでの確認（本人の作業中の画面で全体にキーを送るのは避け、本人に試してもらう）。言語バー/タスクバーの「あ」「A」の表示（GUID_LBI_INPUTMODE のボタン）、アイコン

- フェーズ4 済（2026-10-05 夜）: 候補の窓と「あ」「A」の表示をエンジンが Swing で出す（`SwingCandidateView.kt`。Compose for Desktop にしなかったのは、部品を足さずに済み、窓が小さいため）
  - フォーカスを奪わない窓（focusableWindowState=false）。入力中の文字（変換中は選んでいる文節）のすぐ下に出し、画面の下にはみ出すなら上に。9件ずつのページ、説明（ひらがな・カタカナ・絵文字など）、ページの位置。色は Windows のライト/ダークに合わせる
  - 座標: JVM は -Dsun.java2d.uiScale=1 で物理ピクセルのまま使う。DLL は GetTextExt の結果を LogicalToPhysicalPointForPerMonitorDPI で物理ピクセルに直して送る（拡大率に対応していないアプリでは引き伸ばす前の座標が返り、ずれていた）
  - モードを切り替えたら DLL がカーソルの位置を送り、そこに「あ」「A」を 0.9 秒出す
  - クリックで候補を選ぶのは外した: Java だけでは WS_EX_NOACTIVATE の窓にできず、クリックで入力欄のフォーカスが外れて入力中の文字が消えた。フェーズ5でネイティブの部品を作るときに入れる

## 本人に確かめること（始める前に。答えは上）

- to-fu で、作った IME の DLL を登録してよいか（`regsvr32` は管理者権限が要る。登録すると全アプリに読み込まれる。登録の解除で戻せる）
- 試すのは to-fu でよいか（普段使いの PC なら、落ちたときに他のアプリを巻き込む。DLL を小さく保つ・試すときは Windows Sandbox や別ユーザーも候補）
- 主に使う Windows のアプリ（ターミナル・ブラウザ・Office など。TSF への対応がアプリでまちまちなので、優先して確かめる）
- Windows のキーボード配列（US か JIS か）と、MS-IME と並べて使うか

## 気をつけること

- TSF の DLL はほぼ全アプリのプロセスに読み込まれる。DLL の中で例外を投げない・重い処理をしない・エンジンが落ちていても素通しで動くようにする
- 署名のない DLL・常駐プロセスは、Defender や SmartScreen に止められることがある
- 公開する場合、Android 版と同じく第三者の許諾（`docs/THIRD-PARTY.md`）を確かめる。FrequencyWords（CC BY-SA 4.0）など

## 読むファイル

- `README.md`・`STATUS.md`・`docs/PLAN.md`（Android 版の全体）、`docs/USAGE.md`（使い方。Windows 版でも同じ操作にそろえる）
- `core/src/main/kotlin/dev/mooma/kanade/core/InputEngine.kt`（エンジン。`KeyInput` を渡して `ImeCommand` が返る）
- `app/src/main/kotlin/dev/mooma/kanade/KanadeInputMethodService.kt`（Android での段取り。共通化する部分を探す）
- `app/src/main/kotlin/dev/mooma/kanade/KeyMapper.kt`（物理キー → `KeyInput`。Windows の仮想キーにも同じものが要る）
- `zenz/`・`native/`・`scripts/build-host-zenz.sh`（ネイティブの作り方）
- `app/src/main/kotlin/dev/mooma/kanade/SherpaRecognizer.kt`・`VoiceCatalog.kt`・`VoiceInput.kt`（音声のモデルと認識）
