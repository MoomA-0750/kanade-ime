# 引き継ぎ（今の状態、2026-11-21 朝）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今の仕事は、音声の「聞き続けるモード」を PC の版（Windows・Linux・macOS）に入れて、全部の版で機能を揃えること（本人の要望）。

## できたこと（2026-11-21 夜、commit は未 push）
- 設計: `docs/CONTINUOUS-PC.md`（design-planner の案。推す案で仮決定）。要点と捨てた案は `docs/decisions.md` 2026-11-21「聞き続けるモードを PC の版に入れる」
- core: `ContinuousSegmenter`（区切り・時間切れ。音の長さで時刻を数える）、`VoiceCommand.isCandidate`。試験 `ContinuousSegmenterTest`
- エンジン（desktop）: `VoiceSession`（押し方の判定 0.3 秒・長押し・聞き続けるモード・順序・遅れた結果を捨てる・打っている途中は待たせる・声のコマンド）、`DesktopVoice` に `onAudio`・`halt`・`trim`・`recognize`、試験用 `-Dkanade.voiceStreamWav`。プロトコル `voice down/up/toggle/blur`・`voicestate`・`voiceaction`（`docs/WINDOWS-PROTOCOL.md`）。試験 `VoiceSessionTest`（17件）。`kanade-engine voice --listen <wav>` で声を出さずに通しで流せる
- フロント: fcitx5（kanade.cpp）、macOS（Voice.swift・InputController.swift・EngineLink.swift・main.swift）、Windows（TSF の voiceaction）。Windows の F12 短押し・Esc・入力欄の移動はエンジンだけで済む
- 設定の画面: 「声のコマンド」「話さなければ止める」を Linux GTK・Qt、Windows、Mac に。`DesktopStore` は `voice_commands`・`continuous_idle_seconds` を読み書き
- SPEC 8-3・8-11・8-13・8-15・10-3・11-3・未決事項を直した
- 組めることの確かめ: Kotlin の試験は全部通る。fcitx5 のアドオンと Qt の設定は `podman run fedora:44` の中で組めた。Windows の DLL・設定は to-fu で組めた（`scripts/win.sh tsf`・`settings`）。Swift は `ssh mac` の `swiftc -typecheck`（ime と settings）
- 本物の録音の流れ: to-fu の parakeet で `kanade-engine voice --listen <wav>` を流し、区切りごとに文字になって話した順に入る・「取り消して」「次へ」「全部消して」「止めて」が働くのを見た（見本は eval/voice-sample-ja.wav と、macOS の say -v Kyoko で作った .cache/voice-commands*-ja.wav。合成音声の「送って」「送信して」は聞き違えられた）。区切りごとだと前の文脈が無く、頭の聞き取りが少し落ちる（「あした」→「明した」）・読点が付かない
- change-reviewer の指摘7件を直した（b8fcbc0。順序・マイクが使えないとき・つなぎが切れたとき・終えた直後の移動・打っている途中の Esc・Windows の Esc の離し・長押しのロック）。判断が要る4つは確認一覧
- Mac の pkg 2026.11.21.0101（公証済み、聞き続けるモード入り）を cramshell の ~/Downloads に置いた

## 残り（次の仕事）
- 実機での確かめ（本人）: Mac は cramshell の pkg 2026.11.21.0101。Linux は配布物を組み直す（cramgent。2026-11-21 夜は ssh がつながらなかった。確認一覧に既存の項目あり）。Windows は to-fu の開発用の登録（新しい DLL とエンジン）で試験の窓までは確かめた。本人がふだんのアプリ（ブラウザ・ターミナル）で F12 短押しを試すのはまだ
- 「送って」を本物の声で確かめる（合成音声では聞き違えられた。単体試験では確かめてある）
- Android の `continuousTick` を `ContinuousSegmenter` に替える（任意。今は Android は触っていない）
- 押している間の録音での声のコマンドを PC に（設計の手順 10。Android の `recognizeWithCommands` を core へ）
- 公開: 未 push の commit（06ed2b5〜）の push、Release、Homebrew tap。**本人の直接の「出して」が要る**

## 次のセッションが最初にやること
1. このファイルと `docs/CONTINUOUS-PC.md`、SPEC 8-3・8-11 を読む
2. 本人に仮決定5つ（decisions 2026-11-21 の「仮決定」）を確かめる
3. 本人に cramshell の pkg 2026.11.21.0101 で F12 の短押し・長押し・「送って」などを試してもらい、結果を聞く

## 本人の判断待ち
- 仮決定5つ（トグルのキーも聞き続ける／始めの音を 0.3 秒で決める／入力欄の移動でも終わりの音／「次へ」で終わる／確かめられないときの全部消す・取り消しは何もしない）
- cramshell で pkg 2026.11.21.0101 を試した結果（聞き続けるモード入り）
- レビューで出た判断4つ（終えた直後の移動で最後の区切りを捨てる・打っている途中の Esc・入力方式の切り替えで終わる・「送って」の前に確定）
- push・Release・tap を出してよいか

## 注意
- 型チェック: Swift はこの PC に無い。`ssh mac` へ Sources を送って `swiftc -typecheck -swift-version 5 -target arm64-apple-macos13.0 *.swift`（settings は macos14.0）。Kotlin は `JAVA_HOME=$HOME/.local/opt/jdk-21.0.12.1+1 ./gradlew :core:test :desktop:test`
- fcitx5 のアドオンと Qt の設定を組む: `podman run --rm -v $PWD/linux/fcitx5:/src:ro,Z registry.fedoraproject.org/fedora:44 bash -c 'dnf -q -y install cmake gcc-c++ fcitx5-devel extra-cmake-modules >/dev/null; cp -r /src /b && cd /b && cmake -S . -B build && cmake --build build'`（Qt は qt6-qtbase-devel qt6-qtdeclarative-devel qt6-qtbase-private-devel）
- pkg を組む: `KANADE_NOTARY_PROFILE=kanade scripts/mac.sh build mac`（公証まで。数分）。cramshell へ: `ssh mac 'cat ~/kanade-build/dist/Kanade-mac-aarch64.pkg' > k.pkg && scp k.pkg cram:Downloads/Kanade-mac-aarch64-<版>.pkg`（版は同じ場所の .version）。入れたあとはログアウトが要る
- Windows の `focus` が入力欄を移っていないのに来るアプリがあると、聞き続けるモードがすぐ切れる（未確かめ。切れすぎるなら DLL が文書の替わりだけで `voice blur` を送る形に）
- 効果音を測る: `./gradlew :core:test --rerun-tasks --tests '*VoiceEarconTest' -Pkanade.earconDir=<フォルダ>` → `~/.claude/skills/ui-sound/scripts/measure.sh`
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 組む Mac の bash は 3.2。ssh では ログインのキーチェーンが開けないので、証明書と資格情報は専用のキーチェーン（~/.kanade-sign）
- 裏で動かしている作業: なし（to-fu のエンジンは `win.sh start` の普段の起動に戻してある）。未 commit のまま: linux/settings/__pycache__ の .pyc と .claude/（このセッションの作業ではない）。cramshell の ~/Downloads に Kanade-mac-aarch64-2026.11.21.0101.pkg（最新。聞き続けるモード入り）
- Windows の DLL を to-fu で試す: `scripts/win.sh tsf`（Release を付けない。reg は tsf-build を登録する）→ `win.sh reg` → エンジンを `-Dkanade.voiceStreamWav=<wav>` 付きで起こす（`win.sh start` の命令に足して ps で）→ `win.sh type --rich "{f12}{wait}…"`。DLL の記録は %LOCALAPPDATA%\Kanade\tsf-log を作ると出る（試したあと消した）。終わったら `win.sh start` で普段の起動に戻す（戻してある）
- Windows の通しの確かめ（2026-11-21 夜）: 試験の窓（RichEdit）で F12 短押し→区切りごとに入る・取り消して・次へ・全部消して・止めてを見た
- to-fu の ssh 越しの System.Speech の音声合成は無音の wav を作る（使えない）。日本語の見本は `ssh mac say -v Kyoko --data-format=LEI16@16000 -o x.wav "..."` で作り、頭の付いた wav から data だけを 44 バイトの頭で書き直す
- to-fu で本物のモデルを使う: `scripts/win.sh engine` のあと `scripts/win.sh ps "\$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; \$env:JAVA_OPTS='-Dkanade.voiceFinal=parakeet'; & C:\Users\MoomA\kanade\engine\bin\kanade-engine.bat voice --listen '<wav>'"`

## 資料
- docs/CONTINUOUS-PC.md（設計）、SPEC.md（8-3・8-11・8-13・8-15・11-3・12-2）、docs/decisions.md（2026-11-20・21）、docs/WINDOWS-PROTOCOL.md、STATUS.md、Vault の Human/Projects/Kanade.md

## 追記（2026-11-21、公開の状況）
- 本人の了承を受け、4442358（メニューバーの項目の幅固定まで）を origin/main へ push した。それより後の commit（聞き続けるモード・a618716 まで）は未 push
- Release と tap は止めている。本人が確かめた pkg 0006 は cramshell から消え、Mac の dist にあるのは別セッションの 0101（聞き続けるモード入り、本人は未確認）だけ。どれを出すかを確認一覧に積んだ（A: 0101 を出して残りも push、B: 4442358 で組み直し、C: 試してから）。本人が選んだら `scripts/mac-release.sh`（dist/ に pkg と .version を置いて使う。tap は ../homebrew-tap）
