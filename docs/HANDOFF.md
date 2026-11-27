# 引き継ぎ（今の状態、2026-11-27・Rust 計画フェーズ 1 済み／入力の記録フェーズ4 済み／日英混在の設計済み）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今の仕事は「入力の記録」（本人用に、どういう入力・変換のやり直し・言語の切り替えがあったかを端末の中だけに軽く集める。パスワード欄は除く）。要望は `docs/INPUT-LOG-REQUEST.md`、設計は `docs/INPUT-LOG.md`（design-planner の案C。Q1〜Q7 は 2026-11-22 に本人が決定）。

## 2026-11-23 に増えたこと（設計と計画のみ。コードは変えていない）
- 日英の切り替え不要（Meltype 型）の設計メモ: `docs/MIXED-INPUT.md`。推しは B1（判定してから変換・自前・既定オフ）。本人の14点（同メモ 10）は**まだ未回答**（確認一覧に残っている）。SPEC 未決事項に1行だけ足した
- Rust コアの書き直し計画: `docs/REWRITE-PLAN.md`。6 章の12点は**本人が全部推奨どおりで確定**（decisions.md 2026-11-23）。Rust は実験→関門A（共通試験の一致と速さ）のあと本命、デスクトップのエンジン→Android の順、ソケットは維持、Android は UniFFI、Meltype は**クリーンルームで MIT のまま（Meltype のソース・評価セットは読まない・使わない）**
- 決定 8〜10（SPEC の番号振り直しと分離、設定の一覧とマージ、確定の見直し）は、実施するときに同じ commit で SPEC を直す。SPEC 本文はまだ変えていない
- 日英混在の P0・P1 は Kotlin で先に（決定 5）。ただし P0 を始める前に14点のうち既定・キー・Space などを本人に決めてもらう

## できたこと（入力の記録など、前からのもの）
- フェーズ1（設計）: `docs/INPUT-LOG.md`、`docs/decisions.md` 2026-11-22「入力の記録」（8fb2b4b）
- フェーズ2（エンジン）: core の log/（InputEvent・JsonLine・InputLogWriter・FieldKind/LogField）、InputEngine の `recorder`・`logField`・`logVoice`・`flushLog` など、EngineHost の `field` 行、DesktopStore の設定4つ、DesktopApp の書き手の付け外しと `clear-input-log`、VoiceSession、WINDOWS-PROTOCOL.md、SPEC.md 13 節（全部「仮」）。`./gradlew :core:test :desktop:test` は通る。実装で決めた細かい点は decisions.md 2026-11-22「フェーズ2」。commit は a99372e〜a2b3138

- フェーズ3（フロント）: Android（KanadeSettings の設定4つと SettingsBackup の除外、IME サービスの `logField`・`logSource`（hw・soft・flick・glide）・`logVoice`・`logVoiceCommand`・flush・書き手の付け外し。commit aa00e93・3bac61d）、fcitx5（`field` 送信。podman で組めた）、macOS（`IsSecureEventInputEnabled`・bundle id。ssh mac で型検査が通った）、Windows TSF（InputScope を編集セッションで読む。to-fu で DLL が組めた）。決めた細かい点は decisions.md 2026-11-22「フェーズ3」。Android は `:app:compileDebugKotlin` が通っただけで、実機・エミュレータでは動かしていない。Windows・fcitx5・macOS も組めるところまで（実際の入力欄での動きは未確認）

- フェーズ4（画面）: Android の「入力の記録」の節（ToolsScreens.kt）、GTK・Qt（バックアップページ）、Windows・macOS（入力ページ）にスイッチ・フォルダーを開く・全部消す。SPEC 13-9、decisions.md 2026-11-22「フェーズ4」。Windows は to-fu で組め、macOS は型検査、Kotlin の試験と app のコンパイルは通る。Qt は組んでいない、GTK は構文だけ。どれも画面を出しての確認はまだ
- macOS の録音キー・Control+Space の素通し（リモートアプリが手前のとき）: a993083・25c6f32 で入れ、pkg 2026.11.22.0048 を cramshell の ~/Downloads に置いた（公証・staple 済み）。本人が確認済み（Moonlight が手前のとき録音キーも Control+Space も向こうへ届く）。0101 は Release・tap 済み。以降の Release は本人の「出して」が要る

## 残り（フェーズ）
5. 組んで確かめ（Kotlin の試験、podman で fcitx5、ssh mac で swiftc、to-fu で DLL）、引き継ぎ

## 次のセッションが最初にやること
0. DeX のキー表（docs/DEX-KEYS.md）は本人の環境待ちで保留（今夜取れる見込み。**ファイルには触れない**。届いたら取り込み、キー記録を既定でオフにする）
1. Rust 計画のフェーズ 0（docs/REWRITE-PLAN.md 5.4）は**ほぼ済み**: 共通試験の形（docs/GOLDEN.md、Fable 審査済み）、記録の道具（desktop/Golden.kt・GoldenSeeds.kt、`dictool golden record <絶対パス>`）、場面 seeds 25＋fuzz 100（testdata/golden/）、GoldenTest、Kotlin 版の基準値（decisions.md 2026-11-23）。残り: 評価セット（eval-*、`dict: system`）の場面、`engine` の層、単体テスト 240 件の場面化の追加、Fold 7・M1 の基準値（実機）、基準値のうちエンジンの既定方式の確認
2. Rust 計画のフェーズ 1（辞書・ローマ字・ラティス）は**済み**（2026-11-27、`docs/RUST-PHASE1.md`）。`rust/` の workspace（kanade-text・kanade-dict・kanade-convert・kanade-tool）で、辞書だけの N-best が Kotlin 版と 699/699 読み一致。流し方は RUST-PHASE1.md。次はフェーズ 2（エンジンと zenz、関門A）で、作り始める前に Fable 審査点「エンジンの API と状態の型」。Opus 推奨。始めるかは本人に確認
3. 入力の記録のフェーズ5（Qt を podman で組む、エミュレータで Android の画面、実際に打って確かめる、change-reviewer）は前からの残り。順番は本人に確認中
4. 日英混在の P0・P1（Kotlin）は、MIXED-INPUT の14点の返事を待つ

## 本人の判断待ち
- （決定済み 2026-11-23）Rust コアの書き直しの 6 章の12点は全部推奨どおりで確定（decisions.md 2026-11-23）。Rust は実験→関門A のあと本命、Meltype はクリーンルーム・MIT のまま（ソースは読まない）。実装はまだしていない
- 日英の切り替え不要（Meltype 型）の設計（docs/MIXED-INPUT.md、2026-11-23）の 14 点。実装はまだしていない
- 前からのもの: 聞き続けるモードの仮決定5つ・レビューの判断4つ・cramshell で pkg 2026.11.21.0101 を試した結果・push・Release・tap を出してよいか（前の引き継ぎの全文は git の 8fb2b4b の docs/HANDOFF.md。to-fu・cramshell での試し方もそこにある）
- フェーズ2 は個人の内容を扱う（打った文を残す）ので、push の前に change-reviewer で見ておくのがよい（まだ回していない）

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 *.swift`（settings は macos14.0）。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :core:test :desktop:test`
- fcitx5 のアドオンを組む: `podman run --rm -v $PWD/linux/fcitx5:/src:ro,Z registry.fedoraproject.org/fedora:44 bash -c 'dnf -q -y install cmake gcc-c++ fcitx5-devel extra-cmake-modules >/dev/null; cp -r /src /b && cd /b && cmake -S . -B build && cmake --build build'`
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`。**Mac の ~/kanade-build/dist は他のセッションと共有なので、組む前に他に組んでいるものが無いか（ps・dist の時刻）確かめる**
- Windows の DLL: `scripts/win.sh tsf` → `win.sh reg`。終わったら `win.sh start` で普段の起動に戻す
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 未 commit のまま: linux/settings/__pycache__ の .pyc と .claude/（このセッションの作業ではない）
- push 済み（2026-11-22、本人の了承。bd2fd0f まで）。それ以降の commit（MIXED-INPUT・REWRITE-PLAN・決定の記録）は未 push。push は本人の確認のあと
- Rust の試験は辞書が要る: `scripts/rust-dict-prepare.sh "$(mktemp -d)"` で展開し、`KANADE_DICT_DIR` に指定して `cd rust && cargo test`（無いと件数つきで失敗する）
- 裏で動かしている作業は無い

## 資料
- docs/RUST-PHASE1.md（Rust フェーズ 1 の今の状態・流し方・不確かな点）
- docs/REWRITE-PLAN.md（Rust 書き直しの計画案。要件・資産と負債・仕様の見直し提案・クレート構成・移行の関門・フェーズ）
- docs/MIXED-INPUT.md（日英混在の設計メモ。実装なし。Meltype の調査・方式比較・フェーズ・評価・決めてほしい14点）
- docs/INPUT-LOG.md（設計）、docs/INPUT-LOG-REQUEST.md（要望）、SPEC.md、docs/WINDOWS-PROTOCOL.md、docs/decisions.md、STATUS.md、Vault の Human/Projects/Kanade.md
