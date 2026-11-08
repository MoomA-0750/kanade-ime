# ビルドと確認の手順

## 作業機（Fedora）でのビルド

- JDK 21（Temurin）と Gradle を `~/.local/opt/` に置いてある（システムには入れていない）。Gradle は wrapper を使う
- Android SDK は `~/Android/Sdk`（`local.properties` に書く。リポジトリには入れない）

- NDK r29（29.0.14206865）と CMake 3.31.6 も `~/Android/Sdk` に入れてある（`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`）
- llama.cpp は submodule。clone したら `git submodule update --init`

```sh
export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1
./gradlew :core:test            # 変換ロジックの単体テスト（UI なし、JVM）
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk（実機に入れるのはこれ。debug の鍵で署名してある）
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk（エミュレータでの試験用。run-as が使える）
```

- release は R8 でコードを縮める（`app/proguard-rules.pro`。sherpa-onnx の設定のクラスはネイティブが名前で読むので残す）。arm64 だけで約 53MB
- 実機には release を入れる。設定画面（Jetpack Compose）は debug だとほぼインタプリタで動くので、エミュレータで起動に 10 秒かかった（release は 2〜3 秒）。どちらも debug の鍵なので、入れ替えて入れられる
- 設定画面は material3 1.5.0-alpha18（Material 3 Expressive の部品が公開されている最後の、AGP 8.13・compileSdk 36 で使える版）。alpha19 以降と compose-bom 2026.08 以降は AGP 9.1・compileSdk 37 が要る

- 既定のネイティブは arm64-v8a（実機）だけ。to-fu のエミュレータ（x86_64）で試すときは `./gradlew :app:assembleDebug -Pkanade.abis=arm64-v8a,x86_64`
- 初回の `assembleDebug` は、Mozc の辞書（約 130MB）と zenz のモデル（21MB）を `.cache/` へ取得し、辞書を変換してから assets に入れる（`prepareKanadeAssets`）。2回目以降は取得も変換もしない
- ネイティブ（llama.cpp）のビルドは ABI ごとに数分かかる

## 変換の評価（手元）

```sh
./gradlew :dictool:installDist
D=dictool/build/install/dictool/bin/dictool
$D fetch .cache && $D build .cache/mozc-<コミット> .cache/out      # 辞書を作る（assets 用とは別に手元で試すとき）
$D convert .cache/out きょうはいいてんきですね                      # 辞書だけで変換
scripts/build-host-zenz.sh                                           # 手元用の libkanade_zenz.so
$D eval-zenz .cache/out eval/daily.tsv .cache/models/zenz-v3.2-xsmall-Q5_K_M.gguf .cache/host-zenz/libkanade_zenz.so 10
$D eval-zenzai .cache/out eval/daily.tsv .cache/models/zenz-v3.2-xsmall-Q5_K_M.gguf .cache/host-zenz/libkanade_zenz.so   # 並べ替えと Zenzai の方式（確かめて直す）を比べる
$D typo .cache/out しやしん ちよつと                                  # テンキーの打ち間違いを直した候補
$D reading .cache/out 漢字が書けない                                    # 逆引きの辞書（reverse.kdic）で文の読みを求める
```

- 作業機は VM で CPU に AVX2 が見えないので、zenz の時間は参考にならない（正解率は正しい）。速度は Fold 7 で測る（設定の「テストとログ」→「評価セットで測る」。結果は `adb logcat -s KanadeBench` にも出る）

## 音声認識（手元）

```sh
scripts/build-host-whisper.sh
$D whisper .cache/models/ggml-small-q5_1.bin .cache/host-whisper/libkanade_whisper.so eval/voice-sample-ja.wav
```

- エミュレータで試すときは、モデルを to-fu で落として `adb push` で `/sdcard/Android/data/dev.mooma.kanade/files/models/` に置ける。エミュレータは `-no-audio` で起動しているのでマイクは無音
- 見本の音声は to-fu の Windows の音声合成で作れる（PowerShell の `System.Speech`、`Microsoft Haruka Desktop`、16kHz・モノラル・16bit）。間を入れるときは `PromptBuilder.AppendBreak` を使う（SSML の break は読み上げられてしまった）。スクリプトは UTF-8 の BOM 付きで送る（ないと PowerShell 5 が日本語を読み違える）。デバッグ版なら `run-as dev.mooma.kanade cp … files/voice-inject.wav` でマイクの代わりに流せる

## Linux 版（cramgent などの別の機械）

- `scripts/linux.sh build <ssh の相手>`: エンジンの jar（`-Pkanade.target=linux-aarch64` など、相手の CPU に合わせて sherpa-onnx の native を選ぶ）・辞書・ネイティブのソースを束ねて送り、相手で `linux/build.sh` を動かす。JDK（Temurin 21）は手元で gh から落として送る（相手の curl では GitHub が 504 を返した）。相手に要るのは cmake・C/C++・fcitx5 のヘッダー
- `scripts/linux.sh install <ssh の相手> [--voice] [--add-im]`: 相手で配布物の `install.sh` を動かす（アドオンだけ sudo）
- 画面での確かめ方（Hyprland）: wtype の打鍵は仮想キーボードなので入力方式を通らない。uinput の仮のキーボード（sudo の python で /dev/uinput に書く）で打ち、grim で撮る。ssh から fcitx5 を起こし直すと Wayland の環境がないまま動くので、`systemctl --user restart <fcitx5 のサービス>` を使う
- ssh の中の LANG が空だと、エンジンの引数の日本語が化ける（`LANG=en_US.UTF-8` を付ける）。`pkill -f` の模様が ssh の命令の行に入っていると、ssh ごと切れる

## エミュレータでの確認（to-fu）

作業機は VM で KVM がないので、to-fu（Windows）の Android Studio のエミュレータ（Pixel_10_Pro_Fold、Android 16、x86_64）を ssh 越しに使う。
ネイティブ（zenz・whisper）は x86_64 向けも要るので、`-Pkanade.abis=arm64-v8a,x86_64` を付けてビルドする。

- to-fu の ssh の既定シェルは PowerShell。ssh を閉じると子プロセスが止まるので、エミュレータと `adb nodaemon server` は ssh をつないだまま（バックグラウンドで）動かす

```sh
ssh tofu '& "C:\Users\MoomA\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd Pixel_10_Pro_Fold -no-window -no-audio -no-boot-anim -no-snapshot-load'   # つないだまま
ssh tofu '& "C:\Users\MoomA\AppData\Local\Android\Sdk\platform-tools\adb.exe" nodaemon server'                                                              # つないだまま
scripts/emu.sh install
scripts/emu.sh adb shell ime enable dev.mooma.kanade/.KanadeInputMethodService
scripts/emu.sh adb shell ime set dev.mooma.kanade/.KanadeInputMethodService
```

- キーは `adb shell input keyevent KEYCODE_HENKAN` / `input text nihongo` / `input keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE` で送れる。IME に届いたキーは `adb shell run-as dev.mooma.kanade cat files/keylog.txt`
- APK を入れ直すと既定の入力方法が Gboard に戻ることがある。`ime set` し直す。`am start -S` で Kanade のパッケージを止めると IME も止まり、やはり Gboard に戻る
- 手元の ssh を切っても to-fu 側のエミュレータは残ることがある（もう動いていれば、もう1つ起動しようとして「同じ AVD は複数動かせない」で終わる）。`Get-Process emulator*` で確かめる
- 画面は `scripts/emu.sh shot <file.png>` で撮れる（半分の大きさの `-s.png` も作る）。画面要素を文字で探して押すのは `scripts/ui.sh tap <文字列>`
- 起動が長く続いたエミュレータは `input_method` などのサービスが消えた状態になることがある（`cmd: Can't find service`）。`adb emu kill` して起動し直す。起動直後は SystemUI の ANR が出ることがあり、`am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS` で閉じる
