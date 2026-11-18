# Kanade（macOS 版）の使い方

Android 版・Windows 版・Linux 版と同じ入力（ライブ変換・zenz の並べ替え・音声入力）を、macOS の入力ソースとして使う。中身は Windows 版・Linux 版と同じエンジンで、入力ソースのアプリ（InputMethodKit）が Windows の TSF の DLL の代わりをする。macOS 14 以上、Apple シリコン（pkg は Apple シリコン向けだけ組んでいる。Intel は同じ手順で Intel の Mac の上で組む）。

## 入れる・外す

- 入れる: `Kanade-mac-<arch>.pkg` を開き、インストーラーの案内どおりに進める（Mac のパスワードを1回求める）。全員分に入り、入れた人の入力ソースへの登録まで済む
  - Homebrew なら `brew install --cask MoomA-0750/tap/kanade`（同じ pkg を GitHub の Release から入れる）
  - Developer ID の署名が無い版は、ダウンロードした pkg を開くときに macOS に止められる。システム設定 → プライバシーとセキュリティの「このまま開く」で開く
  - 入れ直す（新しい版にする）ときも、新しい pkg を入れるだけ（`brew upgrade --cask kanade`）。落とした音声のモデル・設定・辞書・学習は残る。前に本人のフォルダー（`~/Library/Input Methods`）へ入れた版はゴミ箱へ移る
  - 入力ソースは `/Library/Input Methods/Kanade.app`、設定の画面は `/Applications/Kanade Settings.app`
  - エンジン・辞書・音声のモデルは `~/Library/Application Support/Kanade/engine`、設定・ユーザー辞書・学習は `~/Library/Application Support/Kanade`、記録は `~/Library/Logs/Kanade/engine.log`・`ime.log`（入れたときの記録は `/tmp/kanade-postinstall.log`）
  - Mac のほかのユーザーは、システム設定 → キーボード → 入力ソースの「編集…」で「日本語」の Kanade を足す
  - 音声入力のモデルは、入力メニューの「設定…」→「音声入力」から落とす
- 外す: 設定の画面の「設定の保存と読み込み」→「Kanade を取り除く」。Mac のパスワードを求め、入力方式・設定の画面・エンジンをゴミ箱へ移す。設定・辞書・音声のモデルも取り除くかを選べる。Homebrew で入れたなら `brew uninstall --cask kanade`（設定と記録も消すなら `--zap`）
- 組み立て（開発用）: リポジトリの手元で `scripts/mac.sh build <ssh の相手>`。要るのは組む Mac の Xcode（swiftc・codesign・pkgbuild）だけで、JDK・cmake は手元から送る。ネイティブ・入力ソース・設定の画面は相手の Mac の上でビルドし、`dist/` に pkg・版・tar.gz を作る（`scripts/mac.sh fetch <ssh の相手>` で持ってくる）。相手の Mac に Developer ID Application の証明書があれば .app を、Developer ID Installer があれば pkg を署名する。公証も出すときは、相手の Mac で `xcrun notarytool store-credentials <名前>` をしておき、`KANADE_NOTARY_PROFILE=<名前>` を付けて組む
  - Release と Homebrew の tap を新しい版にするのは `scripts/mac-release.sh`
  - `scripts/mac.sh install <ssh の相手> --voice` は、相手の Mac で tar.gz の `install.sh` を動かし、本人のフォルダー（`~/Library/Input Methods`）へ入れる（sudo は要らない。`--voice` で音声のモデル（日本語の parakeet-ja 656MB・ReazonSpeech 73MB と、英語の parakeet-tdt v3 670MB）も落とす）。外すのは tar.gz の `uninstall.sh`（設定・辞書・音声のモデルも消すときは `--all`。消したものはゴミ箱へ）

## 使い始める

1. メニューバーの入力メニューで「Kanade」を選ぶ（出ていなければ、システム設定 → キーボード → 入力ソース → 編集 で「日本語」の中の Kanade を足す）
2. 初めて選んだとき、アクセシビリティの許可を求められる（音声入力の録音キーに使う）。システム設定 → プライバシーとセキュリティ → アクセシビリティ で Kanade を入にする
3. 初めて録音したとき、マイクの許可を求められる

## 打つ

- 日本語と英字の切り替えは macOS の入力ソースが受け持つ（英字は ABC など。Control+Space・地球儀キー）。Kanade に入ると、いつも日本語から始まる。JIS 配列のかなキーは日本語、英数キーは Kanade の中の直接入力
- ローマ字で打つと、打つたびに変換した結果が出る（ライブ変換）。Return で確定
- Space で変換し、候補の窓から Space・↑↓・数字・クリックで選ぶ。9件ずつの頁で、頁が2つ以上なら窓の右下に「1/3」。←→ で文節を移り、Shift+←→ で区切りを変える
- `:` に続けて絵文字の英語の名前を打つと、合う絵文字が候補に出る（`:rocket` → 🚀。Return・数字・閉じの `:` で入る。Esc でふつうの文字に戻る。詳しくは `docs/USAGE.md` の「絵文字を名前で入れる」）
- F6〜F10（または Control+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数（F6〜F10 は、システム設定で「F1、F2 などのキーを標準のファンクションキーとして使用」を入にするか、fn と一緒に押す）

## 音声入力（どの入力ソースでも）

- 右 Option か F12 を押している間だけ録音し、離すと句読点付きの文が入る。Control+Option+Space は押すたびに録音を始める・終える。録音中の Esc で取り消す。すぐ離した（0.3 秒より短い）ときは何も入れない
- Kanade のときは日本語（既定 parakeet-ja）、ほかの入力ソースのときは英語（既定 parakeet-tdt v3）で書く。ほかの入力方式（ことえりなど）のときは、クリップボードを借りて貼る（すぐ元に戻す）
- 右 Option を録音キーにしているあいだは、右 Option で特殊な文字（Option+e など）を打てない。左 Option は使える。録音キーは設定の画面の「音声入力」で変える

## 設定

入力メニューの「設定…」「音声入力…」「ユーザー辞書…」「スニペット…」か、`~/Applications/Kanade Settings.app` で開く。中身は Linux 版・Windows 版と同じで、入力・音声入力（録音キーを押して決める、モデルを落とす・消す）・ユーザー辞書とスニペット（一覧で足す・直す・消す、テキストでまとめて編集）・設定の保存と読み込み・使い方。変えると、エンジンが 2 秒ほどで読み直す。ファイル（`~/Library/Application Support/Kanade/settings.properties` など）を直接書き換えてもよい。項目は Linux 版と同じ（`docs/LINUX-USAGE.md`）。

## ほかの Kanade との行き来

設定の画面の「設定の保存と読み込み」で、設定・ユーザー辞書・スニペットを1つのファイル（kanade-settings-….json）に書き出し、ほかの Kanade で読み込む。Android・Windows・Linux・Mac のどれで書き出したものも、どれでも読める。録音キー・英語のモデル・表示の言語は Windows・Linux・Mac のあいだで引き継ぎ、Android 版は読み飛ばす（Android 版は、PC の版から読んだときは、ファイルに無い Android 版だけの設定を今のまま残す）。

## まだできないこと

- 聞き続けるモード、声のコマンド・取り消しなど Android 版だけの音声の機能（Windows 版・Linux 版と同じ）
- メニューバーの印は「日本語」のときも直接入力のときも同じ
