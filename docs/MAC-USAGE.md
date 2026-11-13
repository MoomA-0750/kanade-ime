# Kanade（macOS 版）の使い方

Android 版・Windows 版・Linux 版と同じ入力（ライブ変換・zenz の並べ替え・音声入力）を、macOS の入力ソースとして使う。中身は Windows 版・Linux 版と同じエンジンで、入力ソースのアプリ（InputMethodKit）が Windows の TSF の DLL の代わりをする。macOS 13 以上（設定の画面は 14 以上）、Apple シリコンと Intel。

## 入れる・外す

要るもの: Xcode（組む Mac に。swiftc・codesign）。JDK・cmake は組み立てのときに手元から送る。Java は要らない（配布物に入っている）。sudo も要らない。

- 組み立て: リポジトリの手元で `scripts/mac.sh build <ssh の相手>`。ネイティブ・入力ソース・設定の画面は相手の Mac の上でビルドする
- 入れる: `scripts/mac.sh install <ssh の相手> --voice`（相手の Mac で配布物の `install.sh` を動かす）
  - エンジン・辞書・音声のモデルは `~/Library/Application Support/Kanade/engine`、設定・ユーザー辞書・学習は `~/Library/Application Support/Kanade`、記録は `~/Library/Logs/Kanade/engine.log`・`ime.log`
  - 入力ソースは `~/Library/Input Methods/Kanade.app`（入れると登録して有効にする）、設定の画面は `~/Applications/Kanade Settings.app`
  - `--voice`: 音声入力のモデル（日本語の parakeet-ja 656MB・ReazonSpeech 73MB と、英語の parakeet-tdt v3 670MB）を落とす。入れ直しても残る
- 外す: `~/kanade-build/dist/Kanade-mac-*/uninstall.sh`（設定・辞書・音声のモデルも消すときは `--all`。消したものはゴミ箱へ）

## 使い始める

1. メニューバーの入力メニューで「Kanade」を選ぶ（出ていなければ、システム設定 → キーボード → 入力ソース → 編集 で「日本語」の中の Kanade を足す）
2. 初めて選んだとき、アクセシビリティの許可を求められる（音声入力の録音キーに使う）。システム設定 → プライバシーとセキュリティ → アクセシビリティ で Kanade を入にする
3. 初めて録音したとき、マイクの許可を求められる

## 打つ

- 日本語と英字の切り替えは macOS の入力ソースが受け持つ（英字は ABC など。Control+Space・地球儀キー）。Kanade に入ると、いつも日本語から始まる。JIS 配列のかなキーは日本語、英数キーは Kanade の中の直接入力
- ローマ字で打つと、打つたびに変換した結果が出る（ライブ変換）。Return で確定
- Space で変換し、候補の窓から Space・↑↓・数字・クリックで選ぶ。9件ずつの頁で、頁が2つ以上なら窓の右下に「1/3」。←→ で文節を移り、Shift+←→ で区切りを変える
- F6〜F10（または Control+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数（F6〜F10 は、システム設定で「F1、F2 などのキーを標準のファンクションキーとして使用」を入にするか、fn と一緒に押す）

## 音声入力（どの入力ソースでも）

- 右 Option か F12 を押している間だけ録音し、離すと句読点付きの文が入る。Control+Option+Space は押すたびに録音を始める・終える。録音中の Esc で取り消す。すぐ離した（0.3 秒より短い）ときは何も入れない
- Kanade のときは日本語（既定 parakeet-ja）、ほかの入力ソースのときは英語（既定 parakeet-tdt v3）で書く。ほかの入力方式（ことえりなど）のときは、クリップボードを借りて貼る（すぐ元に戻す）
- 右 Option を録音キーにしているあいだは、右 Option で特殊な文字（Option+e など）を打てない。左 Option は使える。録音キーは設定の画面の「音声入力」で変える

## 設定

入力メニューの「設定…」「音声入力…」「ユーザー辞書…」「スニペット…」か、`~/Applications/Kanade Settings.app` で開く。中身は入力・音声入力・ユーザー辞書・スニペット・Android 版の設定の読み込みと書き出し。変えると、エンジンが 2 秒ほどで読み直す。ファイル（`~/Library/Application Support/Kanade/settings.properties` など）を直接書き換えてもよい。項目は Linux 版と同じ（`docs/LINUX-USAGE.md`）。

## まだできないこと

- 聞き続けるモード、声のコマンド・取り消しなど Android 版だけの音声の機能（Windows 版・Linux 版と同じ）
- 設定の画面からの音声のモデルの取得（`install.sh --voice` で落とす）
- メニューバーの印は「日本語」のときも直接入力のときも同じ
