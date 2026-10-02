# ビルドと確認の手順

## 作業機（Fedora）でのビルド

- JDK 21（Temurin）と Gradle を `~/.local/opt/` に置いてある（システムには入れていない）。Gradle は wrapper を使う
- Android SDK は `~/Android/Sdk`（`local.properties` に書く。リポジトリには入れない）

- NDK r29（29.0.14206865）と CMake 3.31.6 も `~/Android/Sdk` に入れてある（`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`）
- llama.cpp は submodule。clone したら `git submodule update --init`

```sh
export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1
./gradlew :core:test            # 変換ロジックの単体テスト（UI なし、JVM）
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

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
```

- 作業機は VM で CPU に AVX2 が見えないので、zenz の時間は参考にならない（正解率は正しい）。速度は Fold 7 で測る

## エミュレータでの確認（to-fu）

作業機は VM で KVM がないので、to-fu（Windows）の Android Studio のエミュレータ（Pixel_10_Pro_Fold、Android 16、x86_64）を ssh 越しに使う。
Kanade は今のところネイティブコードがないので x86_64 でそのまま動く（zenz を入れたら x86_64 向けの .so も要る）。

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
