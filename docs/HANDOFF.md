# 引き継ぎ（今の状態、2026-11-21）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。Mac 版は署名・公証して配れる。今は Mac 版の操作の作り直し（入力ソースの切り替えの回避、音声入力の押し方と効果音）を cramshell で試している。次の大きな仕事は、音声の「聞き続けるモード」を PC の版（Windows・Linux・macOS）にも入れて、全部の版で機能を揃えること。

## できたこと（2026-11-20〜21、commit は未 push）
- af2e0d0: 日本語⇔英字の切り替えを macOS の入力ソースの切り替えから外した（入力モードは1つ、Control+Space・🌐 は CGEventTap で先に取る、「あ」「A」は Kanade が出す）。本人が cramshell で「ラグなくスムーズ」と確認済み（SPEC 12-2）
- bb6fb13: 録音キーの一回押し（もう一度押すまで録音）・長押し（押している間）の両対応（Mac）と、マリンバの効果音4種（始め2・終わり2。終わりは認識の結果で選ぶ）。Android にも同じ音を入れた。pkg 2026.11.20.2339 を cramshell へ置いた。本人の確認はまだ（SPEC 8-15、decisions 2026-11-20）
- ee36eb2: メニューバーに Kanade 自身の項目（「あ」「A」とメニュー）を出す（StatusItem.swift）。pkg は組んでいる最中（`.../scratchpad/build3.log` の末尾が done なら終わり）。cramshell への送り方は下の「注意」

- 92d10f8・次の commit: 設定 `start_mode`（入力欄に入ったときの始めのモード。全部の版）と `mac_switch_keys`（Mac。Control+Space・🌐 を Kanade が取るか macOS に任せるか）。SPEC 2-4・12-2、decisions 2026-11-21。Mac の pkg を組んで cramshell へ置く（版は ~/Downloads の新しいもの）。Linux Qt・Windows C# は未ビルド

## 残り（次の仕事）
- **聞き続けるモードを PC の版に入れる**（本人の要望「全てのプラットフォームで機能をなるべく揃えたい」）。Android の実装は `app/.../KanadeInputMethodService.kt` の `continuous*`・`continuousTick`・`startContinuous`・`stopContinuous`・`recognizeUtterance(continuousMode = true)`・`runVoiceCommand`（約 1480〜1830 行）。部品は core にある（`VoiceEndpointer`・`PauseSplitter`・`VoiceCommand`）。PC には無い: 録音の途中の音を取り出す・無音の判定をする・区切りごとに認識して入れる・もう一度押す／無音 N 秒／別の入力欄で終える・声のコマンド（送って・次へ・全部消して・止めて・取り消して）・設定 `continuous_idle_seconds`。PC 側の入口は `desktop/.../EngineHost.kt` の startVoice/stopVoice/cancelVoice と Voice*.kt（録音は `VoiceInput` 相当）。Mac の録音キー処理は `mac/ime/Sources/Voice.swift`（今は短押し→「押すたびに始める・終える」。ここを聞き続けるモードへつなぐ）。Windows の DLL・Linux の fcitx5 アドオンは録音キーを持つので、その押し方（短押し・長押し）も揃える
- 設計は先に `design-planner` に回すのがよい（録音の API、区切りの認識を非同期に入れる所、各 OS の入口の違い）
- 公開: 未 push の commit（06ed2b5〜ee36eb2）の push、Release、Homebrew tap。**本人の直接の「出して」が要る**
- 設定でメニューバーの項目を消せるようにする（要望が出たら）

## 次のセッションが最初にやること
1. このファイルと SPEC.md 8-3・8-15・12-2 を読む
2. 本人に、2339（または新しい pkg）の結果を聞く（切り替え・一回押し/長押し・効果音・メニューバーの項目）
3. 聞き続けるモードの設計を design-planner に頼み、本人に方針を見せてから作る

## 本人の判断待ち
- cramshell で pkg を試した結果（音の大きさ・始めの音のタイミング・メニューバーの項目）
- push・Release・tap を出してよいか

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 Sources/*.swift`。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :desktop:compileKotlin :app:compileDebugKotlin`
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`（公証まで。数分）。cramshell へ: `ssh mac 'cat ~/kanade-build/dist/Kanade-mac-aarch64.pkg' > k.pkg && scp k.pkg cram:Downloads/Kanade-mac-aarch64-<版>.pkg`（版は同じ場所の .version）。入れたあとはログアウトが要る
- 効果音を測る: `./gradlew :core:test --rerun-tasks --tests '*VoiceEarconTest' -Pkanade.earconDir=<フォルダ>` → `~/.claude/skills/ui-sound/scripts/measure.sh`
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 組む Mac の bash は 3.2。ssh では ログインのキーチェーンが開けないので、証明書と資格情報は専用のキーチェーン（~/.kanade-sign）
- 裏で動かしている作業: なし。cramshell の ~/Downloads に Kanade-mac-aarch64-2026.11.20.2359.pkg（最新。メニューバーの項目・一回押し/長押し・効果音・start_mode・mac_switch_keys を含む）

## 資料
- SPEC.md（8-3・8-15・12-2・12-5）、docs/decisions.md（2026-11-20・21）、STATUS.md、Vault の Human/Projects/Kanade.md
