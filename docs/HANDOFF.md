# 引き継ぎ（今の状態、2026-11-22 夜・フェーズ3 が済んだ）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今の仕事は「入力の記録」（本人用に、どういう入力・変換のやり直し・言語の切り替えがあったかを端末の中だけに軽く集める。パスワード欄は除く）。要望は `docs/INPUT-LOG-REQUEST.md`、設計は `docs/INPUT-LOG.md`（design-planner の案C で仮決定）。

## できたこと
- フェーズ1（設計）: `docs/INPUT-LOG.md`、`docs/decisions.md` 2026-11-22「入力の記録」（8fb2b4b）
- フェーズ2（エンジン）: core の log/（InputEvent・JsonLine・InputLogWriter・FieldKind/LogField）、InputEngine の `recorder`・`logField`・`logVoice`・`flushLog` など、EngineHost の `field` 行、DesktopStore の設定4つ、DesktopApp の書き手の付け外しと `clear-input-log`、VoiceSession、WINDOWS-PROTOCOL.md、SPEC.md 13 節（全部「仮」）。`./gradlew :core:test :desktop:test` は通る。実装で決めた細かい点は decisions.md 2026-11-22「フェーズ2」。commit は a99372e〜a2b3138

- フェーズ3（フロント）: Android（KanadeSettings の設定4つと SettingsBackup の除外、IME サービスの `logField`・`logSource`（hw・soft・flick・glide）・`logVoice`・`logVoiceCommand`・flush・書き手の付け外し。commit aa00e93・3bac61d）、fcitx5（`field` 送信。podman で組めた）、macOS（`IsSecureEventInputEnabled`・bundle id。ssh mac で型検査が通った）、Windows TSF（InputScope を編集セッションで読む。to-fu で DLL が組めた）。決めた細かい点は decisions.md 2026-11-22「フェーズ3」。Android は `:app:compileDebugKotlin` が通っただけで、実機・エミュレータでは動かしていない。Windows・fcitx5・macOS も組めるところまで（実際の入力欄での動きは未確認）

## 残り（フェーズ）
4. 見る・消す画面と設定（Android の設定の画面、各 PC の設定の画面）。`InputLogWriter.stats()`・`tail(n)`・`clearAll()` がある
5. 組んで確かめ（Kotlin の試験、podman で fcitx5、ssh mac で swiftc、to-fu で DLL）、引き継ぎ

## 次のセッションが最初にやること
1. このファイル・`docs/INPUT-LOG.md` の 3・5・SPEC の 13 節を読む
2. フェーズ4 を作る: Android の設定の画面（`settings/ToolsScreens.kt` のキー記録の隣。スイッチ・件数と大きさ・最近の行・全部消す。`InputLogWriter.stats()`・`tail(n)`・`clearAll()`。Android の書き手は Service の中なので、画面側は `InputLogWriter(dir, startThread=false)` で読む・消す）、各 PC の設定の画面（スイッチ・フォルダーを開く・全部消す。admin の `clear-input-log`）
3. 文字列は `app/src/main/res/values*/strings.xml`（既存のキー記録の文字列 `app_tools_key_log*` にならう）。画面は android-m3-expressive スキルに従う

## 本人の判断待ち
- 入力の記録の仮決定7つ（INPUT-LOG.md の 7。既定オフのスイッチ／ターミナルは確定と切り替えだけ／カーソルの前 32 文字／90 日・200MB／記録中の印なし／Android の書き出しなし／デスクトップの学習は変えない）
- 前からのもの: 聞き続けるモードの仮決定5つ・レビューの判断4つ・cramshell で pkg 2026.11.21.0101 を試した結果・push・Release・tap を出してよいか（前の引き継ぎの全文は git の 8fb2b4b の docs/HANDOFF.md。to-fu・cramshell での試し方もそこにある）
- フェーズ2 は個人の内容を扱う（打った文を残す）ので、push の前に change-reviewer で見ておくのがよい（まだ回していない）

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 *.swift`（settings は macos14.0）。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :core:test :desktop:test`
- fcitx5 のアドオンを組む: `podman run --rm -v $PWD/linux/fcitx5:/src:ro,Z registry.fedoraproject.org/fedora:44 bash -c 'dnf -q -y install cmake gcc-c++ fcitx5-devel extra-cmake-modules >/dev/null; cp -r /src /b && cd /b && cmake -S . -B build && cmake --build build'`
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`。**Mac の ~/kanade-build/dist は他のセッションと共有なので、組む前に他に組んでいるものが無いか（ps・dist の時刻）確かめる**
- Windows の DLL: `scripts/win.sh tsf` → `win.sh reg`。終わったら `win.sh start` で普段の起動に戻す
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 未 commit のまま: linux/settings/__pycache__ の .pyc と .claude/（このセッションの作業ではない）
- 未 push: 4442358 より後の commit 全部（フェーズ2 の commit を含む）

## 資料
- docs/INPUT-LOG.md（設計）、docs/INPUT-LOG-REQUEST.md（要望）、SPEC.md、docs/WINDOWS-PROTOCOL.md、docs/decisions.md、STATUS.md、Vault の Human/Projects/Kanade.md
