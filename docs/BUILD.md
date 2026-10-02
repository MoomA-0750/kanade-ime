# ビルドと確認の手順

## 作業機（Fedora）でのビルド

- JDK 21（Temurin）と Gradle を `~/.local/opt/` に置いてある（システムには入れていない）。Gradle は wrapper を使う
- Android SDK は `~/Android/Sdk`（`local.properties` に書く。リポジトリには入れない）

```sh
export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1
./gradlew :core:test            # 変換ロジックの単体テスト（UI なし、JVM）
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

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
- 起動が長く続いたエミュレータは `input_method` などのサービスが消えた状態になることがある（`cmd: Can't find service`）。`adb emu kill` して起動し直す。起動直後は SystemUI の ANR が出ることがあり、`am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS` で閉じる
