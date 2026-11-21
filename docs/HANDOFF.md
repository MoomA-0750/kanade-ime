# 引き継ぎ（今の状態、2026-11-22 夜）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今の仕事は「入力の記録」（本人用に、どういう入力・変換のやり直し・言語の切り替えがあったかを端末の中だけに軽く集める。パスワード欄は除く）。要望は `docs/INPUT-LOG-REQUEST.md`、設計は `docs/INPUT-LOG.md`（design-planner の案C で仮決定）。

## できたこと
- フェーズ1（設計）: `docs/INPUT-LOG.md`、`docs/decisions.md` 2026-11-22「入力の記録」（8fb2b4b）

## 残り（フェーズ）
2. エンジン（core・desktop）: core/…/core/log/（InputEvent・JsonLine・InputLogWriter・FieldKind）、InputEngine の recorder と logField、EngineHost の `field` 行と `clear-input-log`、DesktopStore の設定4つ、DesktopApp で書き手を付け外し、VoiceSession の voice・voice_cmd、WINDOWS-PROTOCOL.md、SPEC.md に「入力の記録」の節（全部「仮」）。試験は INPUT-LOG.md の 6 のとおり
3. フロント: Android（FieldKind.fromAndroid・logField・音声・flush）、fcitx5・macOS・Windows TSF が `field` を送る
4. 見る・消す画面と設定（Android の設定の画面、各 PC の設定の画面）
5. 組んで確かめ（Kotlin の試験、podman で fcitx5、ssh mac で swiftc、to-fu で DLL）、引き継ぎ

## 次のセッションが最初にやること
1. このファイル・`docs/INPUT-LOG.md`・SPEC の節の一覧を読む
2. フェーズ2 を作る。1件ずつ戻せる単位で commit する

## 本人の判断待ち
- 入力の記録の仮決定7つ（INPUT-LOG.md の 7。既定オフのスイッチ／ターミナルは確定と切り替えだけ／カーソルの前 32 文字／90 日・200MB／記録中の印なし／Android の書き出しなし／デスクトップの学習は変えない）
- 前からのもの: 聞き続けるモードの仮決定5つ・レビューの判断4つ・cramshell で pkg 2026.11.21.0101 を試した結果・push・Release・tap を出してよいか（前の引き継ぎの全文は git の 8fb2b4b の docs/HANDOFF.md。to-fu・cramshell での試し方もそこにある）

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 *.swift`（settings は macos14.0）。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :core:test :desktop:test`
- fcitx5 のアドオンを組む: `podman run --rm -v $PWD/linux/fcitx5:/src:ro,Z registry.fedoraproject.org/fedora:44 bash -c 'dnf -q -y install cmake gcc-c++ fcitx5-devel extra-cmake-modules >/dev/null; cp -r /src /b && cd /b && cmake -S . -B build && cmake --build build'`
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`。**Mac の ~/kanade-build/dist は他のセッションと共有なので、組む前に他に組んでいるものが無いか（ps・dist の時刻）確かめる**
- Windows の DLL: `scripts/win.sh tsf` → `win.sh reg`。終わったら `win.sh start` で普段の起動に戻す
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 未 commit のまま: linux/settings/__pycache__ の .pyc と .claude/（このセッションの作業ではない）
- 未 push: 4442358 より後の commit 全部

## 資料
- docs/INPUT-LOG.md（設計）、docs/INPUT-LOG-REQUEST.md（要望）、SPEC.md、docs/WINDOWS-PROTOCOL.md、docs/decisions.md、STATUS.md、Vault の Human/Projects/Kanade.md
