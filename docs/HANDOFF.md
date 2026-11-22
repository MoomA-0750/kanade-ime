# 引き継ぎ（今の状態、2026-11-22 夜・フェーズ4 が済んだ）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今の仕事は「入力の記録」（本人用に、どういう入力・変換のやり直し・言語の切り替えがあったかを端末の中だけに軽く集める。パスワード欄は除く）。要望は `docs/INPUT-LOG-REQUEST.md`、設計は `docs/INPUT-LOG.md`（design-planner の案C。Q1〜Q7 は 2026-11-22 に本人が決定）。

## できたこと
- フェーズ1（設計）: `docs/INPUT-LOG.md`、`docs/decisions.md` 2026-11-22「入力の記録」（8fb2b4b）
- フェーズ2（エンジン）: core の log/（InputEvent・JsonLine・InputLogWriter・FieldKind/LogField）、InputEngine の `recorder`・`logField`・`logVoice`・`flushLog` など、EngineHost の `field` 行、DesktopStore の設定4つ、DesktopApp の書き手の付け外しと `clear-input-log`、VoiceSession、WINDOWS-PROTOCOL.md、SPEC.md 13 節（全部「仮」）。`./gradlew :core:test :desktop:test` は通る。実装で決めた細かい点は decisions.md 2026-11-22「フェーズ2」。commit は a99372e〜a2b3138

- フェーズ3（フロント）: Android（KanadeSettings の設定4つと SettingsBackup の除外、IME サービスの `logField`・`logSource`（hw・soft・flick・glide）・`logVoice`・`logVoiceCommand`・flush・書き手の付け外し。commit aa00e93・3bac61d）、fcitx5（`field` 送信。podman で組めた）、macOS（`IsSecureEventInputEnabled`・bundle id。ssh mac で型検査が通った）、Windows TSF（InputScope を編集セッションで読む。to-fu で DLL が組めた）。決めた細かい点は decisions.md 2026-11-22「フェーズ3」。Android は `:app:compileDebugKotlin` が通っただけで、実機・エミュレータでは動かしていない。Windows・fcitx5・macOS も組めるところまで（実際の入力欄での動きは未確認）

- フェーズ4（画面）: Android の「入力の記録」の節（ToolsScreens.kt）、GTK・Qt（バックアップページ）、Windows・macOS（入力ページ）にスイッチ・フォルダーを開く・全部消す。SPEC 13-9、decisions.md 2026-11-22「フェーズ4」。Windows は to-fu で組め、macOS は型検査、Kotlin の試験と app のコンパイルは通る。Qt は組んでいない、GTK は構文だけ。どれも画面を出しての確認はまだ
- macOS の録音キー・Control+Space の素通し（リモートアプリが手前のとき）: a993083・25c6f32 で入れ、pkg 2026.11.22.0048 を cramshell の ~/Downloads に置いた（公証・staple 済み）。本人が確認済み（Moonlight が手前のとき録音キーも Control+Space も向こうへ届く）。0101 は Release・tap 済み。以降の Release は本人の「出して」が要る

## 残り（フェーズ）
5. 組んで確かめ（Kotlin の試験、podman で fcitx5、ssh mac で swiftc、to-fu で DLL）、引き継ぎ

## 次のセッションが最初にやること
1. このファイル・SPEC の 13 節を読む
2. フェーズ5: Qt の設定を podman で組む（`linux/settings/qt`）、エミュレータで Android の画面を出して撮る（android-m3-expressive の手順）、実際に打ってログが増えることと全部消すを確かめる。出た不具合を直す。change-reviewer で個人の内容を扱う部分（フェーズ2〜4）を見る

## 本人の判断待ち
- 前からのもの: 聞き続けるモードの仮決定5つ・レビューの判断4つ・cramshell で pkg 2026.11.21.0101 を試した結果・push・Release・tap を出してよいか（前の引き継ぎの全文は git の 8fb2b4b の docs/HANDOFF.md。to-fu・cramshell での試し方もそこにある）
- フェーズ2 は個人の内容を扱う（打った文を残す）ので、push の前に change-reviewer で見ておくのがよい（まだ回していない）

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 *.swift`（settings は macos14.0）。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :core:test :desktop:test`
- fcitx5 のアドオンを組む: `podman run --rm -v $PWD/linux/fcitx5:/src:ro,Z registry.fedoraproject.org/fedora:44 bash -c 'dnf -q -y install cmake gcc-c++ fcitx5-devel extra-cmake-modules >/dev/null; cp -r /src /b && cd /b && cmake -S . -B build && cmake --build build'`
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`。**Mac の ~/kanade-build/dist は他のセッションと共有なので、組む前に他に組んでいるものが無いか（ps・dist の時刻）確かめる**
- Windows の DLL: `scripts/win.sh tsf` → `win.sh reg`。終わったら `win.sh start` で普段の起動に戻す
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 未 commit のまま: linux/settings/__pycache__ の .pyc と .claude/（このセッションの作業ではない）
- push 済み（2026-11-22、本人の了承。bd2fd0f まで）

## 資料
- docs/INPUT-LOG.md（設計）、docs/INPUT-LOG-REQUEST.md（要望）、SPEC.md、docs/WINDOWS-PROTOCOL.md、docs/decisions.md、STATUS.md、Vault の Human/Projects/Kanade.md
