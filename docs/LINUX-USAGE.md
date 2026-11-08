# Kanade（Linux 版）の使い方

Android 版・Windows 版と同じ入力（ライブ変換・zenz の並べ替え・音声入力）を、Linux の fcitx5 の入力方式として使う。中身は Windows 版と同じエンジンで、fcitx5 のアドオンが Windows の TSF の DLL の代わりをする。aarch64（Apple M1 の Asahi Linux など）と x86_64 で動く。

## 入れる・外す

要るもの: fcitx5（開発用のヘッダーを含む。Arch は `fcitx5` に入っている。Debian・Ubuntu は `fcitx5-modules-dev`）、cmake、C/C++ のコンパイラ、curl、python3。設定の画面には python-gobject と libadwaita（Debian・Ubuntu は `python3-gi gir1.2-adw-1`）。Java は要らない（配布物に入っている）。sudo も要らない。

- 組み立て: リポジトリの手元で `scripts/linux.sh build <ssh の相手>` を実行する。ネイティブ（zenz・whisper・fcitx5 のアドオン）は相手の機械の上でビルドするので、その機械の CPU に合ったものになる（別の CPU の機械へ配布物を写すと、遅いか動かないことがある）
- 入れる: `scripts/linux.sh install <ssh の相手> --voice --add-im`（相手の機械で配布物の `install.sh` を動かす）
  - エンジン・辞書・音声のモデル・アドオンは `~/.local/share/kanade`、設定・ユーザー辞書・学習は `~/.config/kanade`、記録は `~/.local/state/kanade/engine.log`
  - fcitx5 のアドオンの設定は `~/.local/share/fcitx5/{addon,inputmethod}/kanade.conf`（Library に .so のフルパスを書く）。入れたあと fcitx5 を起こし直す（systemd のサービスで動いていれば自動）
  - 設定の画面をアプリの一覧に「Kanade の設定」として足す
  - `--voice`: 音声入力のモデル（日本語の parakeet-ja 656MB・ReazonSpeech 73MB と、英語の parakeet-tdt v3 670MB）を落とす。設定の画面からも落とせる
  - `--add-im`: fcitx5 の入力方式の一覧の最後に Kanade を足す（既定の入力方式は変えない。前の設定は `~/.config/fcitx5/profile.before-kanade`）。付けなければ fcitx5-configtool で足す
- エンジンは fcitx5 の起動と一緒にアドオンが起こす（起動に数秒かかり、そのあいだのキーは素通しになる）
- 外す: `~/.local/share/kanade/uninstall.sh`（設定・辞書・音声のモデルも消すときは `--all`。消したものはゴミ箱へ）

## 打つ

- 日本語と英字の切り替えは fcitx5 が受け持つ（英字は keyboard-us）。fcitx5 の設定のキー（既定は Ctrl+Space。cramgent は左 Shift の単押し・変換・無変換・半角/全角）で Kanade に入ると、いつも日本語から始まる
  - Kanade の中では Ctrl+Space・右 Alt での切り替えは使わない（fcitx5 やデスクトップがそのキーを先に使うことがある。cramgent では右 Alt は Hyprland の音声入力の「押して話す」）
- ローマ字で打つと、打つたびに変換した結果が出る（ライブ変換）。Enter で確定
- Space で変換し、候補の窓（fcitx5 が描く）から Space・↑↓・数字・クリックで選ぶ。9件ずつの頁で、頁が2つ以上なら窓の上に「1/3」のように出る。←→ で文節を移り、Shift+←→ で区切りを変える
- F6〜F10（または Ctrl+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数

## 音声入力（どの入力方式でも）

- F12 か右 Alt を押している間だけ録音し、離すと句読点付きの文が入る。Ctrl+Alt+Space は押すたびに録音を始める・終える。録音中の Esc で取り消す。すぐ離した（0.3 秒より短い）ときは何も入れない
- Kanade のときは日本語（既定 parakeet-ja）、ほかの入力方式（keyboard-us など）のときは英語（既定 parakeet-tdt v3）で書く。話している途中の文字（ReazonSpeech）と「認識中」は、カーソルの近くの窓に出る
- キーとモデルは設定の画面の「音声入力」で変える。入力欄のないところ（デスクトップそのものなど）では効かない
- Superwhisper など、同じキーを使うほかの音声入力と一緒には動かさない

## 設定

設定の画面（GTK4・libadwaita）は、アプリの一覧の「Kanade の設定」か、fcitx5 のトレイのメニュー（Kanade を使っているとき）の「Kanade: 設定」「音声入力」「ユーザー辞書」「スニペット」で開く（`~/.local/share/kanade/bin/kanade-settings [input|voice|dictionary|snippets|backup|usage]`）。中身は入力・音声入力（録音キー・モデルを落とす）・ユーザー辞書・スニペット・Android 版の設定の読み込みと書き出し・使い方。変えると、エンジンが 2 秒ほどで読み直す。

ファイルを直接書き換えてもよい:

- `~/.config/kanade/settings.properties`: 項目は Windows 版と同じ（`live_conversion`・`full_width_symbols`・`punctuation`・`pair_brackets`・`toggle_triggers`・`spoken_punctuation`・`voice_final`・`learning`・`ui_language`）と、Linux 版だけの `voice_final_en`（parakeet-en・whisper-small・whisper-turbo）・`voice_hold_keys`（fcitx5 のキーの名前をカンマ区切り。例 `F12,Alt_R`）・`voice_toggle_key`（例 `Control+Alt+space`。空なら使わない）
  - `voice_final` は parakeet・reazon・sensevoice・whisper-small・whisper-kotoba・whisper-turbo（落としたものだけ使える。parakeet と reazon 以外は自分で `~/.local/share/kanade/data` に置く）
  - `ui_language`（auto・ja・en）: auto は LANG に合わせる（日本語でなければ英語）
- `~/.config/kanade/user_dictionary.txt`・`snippets.txt`: 書式は Android 版と同じ
- Android 版の設定（「設定の書き出し」の JSON）を読み込む: `~/.local/share/kanade/bin/kanade-engine import-android <json>`

## まだできないこと

- 聞き続けるモード、声のコマンド・取り消しなど Android 版だけの音声の機能（Windows 版と同じ）
- IBus（Fedora・Ubuntu の GNOME の既定）。fcitx5 だけ
