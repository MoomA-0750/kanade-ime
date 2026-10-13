# Kanade（Windows 版）の使い方

Android 版と同じ入力（ライブ変換・zenz の並べ替え・音声入力）を Windows で使う。操作は Android 版（`docs/USAGE.md`）にそろえてある。

## 入れる・外す

- 配布物（`Kanade-windows-x64.zip`）を展開し、管理者の PowerShell で `powershell -ExecutionPolicy Bypass -File install.ps1`
  - 本体は `C:\Program Files\Kanade`、辞書・モデルは `%LOCALAPPDATA%\Kanade\data`、設定・ユーザー辞書・学習は `%APPDATA%\Kanade`
  - 日本語のキーボードの一覧に「Kanade」が足される（MS-IME は残る。外すのは設定アプリの「言語と地域」→ 日本語 → キーボード）
  - エンジンはログオン時に起動する。止まっていても、最初のキーで起動する（1〜2秒は英字のまま出る）
- 外す: `powershell -ExecutionPolicy Bypass -File "C:\Program Files\Kanade\uninstall.ps1"`（設定・辞書も消すときは `-Purge`）

## 打つ

- Win+Space で「Kanade」を選ぶ
- 日本語のオン/オフ: 右 Alt の単押し、Ctrl+Space、変換（オン）/ 無変換（オフ）、半角/全角。切り替えるとカーソルの近くに「あ」「A」が出る
- タスクバーの入力の表示（MS-IME と同じ場所）に「あ」「A」が出る。左クリックで切り替え、右クリックで設定などのメニュー
- ローマ字で打つと、打つたびに変換した結果が出る（ライブ変換）。Enter で確定
- Space で変換し、候補の窓から Space・↑↓・数字・クリックで選ぶ。←→ で文節を移り、Shift+←→ で区切りを変える
- F6〜F10（または Ctrl+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数
- F12 を押している間だけ録音し、離すと句読点付きの文が入る（モデルは設定の画面で選ぶ。既定は whisper small）。話している途中の文字はカーソルの近くに出る

## 設定

タスクバーの入力の表示の右クリック、またはスタートメニューの「Kanade の設定」から開く（Windows 11 の設定アプリと同じ見た目）:

- 入力: ライブ変換・全角・句読点・括弧の対・切り替えに使うキー・学習（覚えたものを消す）
- 音声入力: 話した句読点、確定の文を作るモデル（parakeet・ReazonSpeech・SenseVoice・whisper small/kotoba/turbo。落とす・消す・使う）、マイクの設定
- ユーザー辞書・スニペット: 一覧で足す・直す・消す、テキストでまとめて編集
- 読み込み・書き出し: Android 版の設定（「設定の書き出し」の JSON）を読み込む、Android 版で読める形で書き出す、設定のフォルダ・記録を開く
- 変えたものはすぐ反映される

## まだできないこと

- 聞き続けるモード
- 古い仕組み（IMM）のアプリでは、候補をクリックすると選んでいた候補のまま確定する
- 古い仕組み（IMM）のアプリでは、日本語オフのスニペットが効かない
