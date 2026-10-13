# Windows 版: DLL とエンジンのやりとり

TSF の DLL（`windows/tsf`、アプリのプロセスごとに1つ）と、常駐するエンジン（`desktop`、JVM のプロセス1つ）のあいだの形。
Kotlin 側は `desktop/src/main/kotlin/dev/mooma/kanade/desktop/`（`Protocol.kt`・`EngineHost.kt`）、試験用の偽の DLL は `FakeDll.kt`。

## つなぎ方

- AF_UNIX のソケット。場所は `%USERPROFILE%\.kanade\engine.sock`（AppData に置くと、パッケージ化されたアプリから WSAEINVAL でつながらない）（Linux では `$XDG_RUNTIME_DIR/kanade-engine.sock`）
  - 名前付きパイプにしなかった理由: Java だけではパイプのサーバーを作れない（JNA が要る）。同期の読み書きは1本のパイプで直列になり、エンジンから自分から送る（並べ替えの結果）のに重なった読み書きが要る。ソケットなら Java も Winsock もそのまま両方向に使え、Linux でも同じ形で試せる
  - 気をつけること: AppContainer のアプリ（スタートメニューの検索など）からはソケットのファイルに触れない見込み。主に使うアプリ（ブラウザ・ターミナル・エディタ）は AppContainer ではない。要るときは名前付きパイプ（ACL に ALL APPLICATION PACKAGES）を足す
- 1行1メッセージ。UTF-8、`\n` で区切る。欄はタブ区切り。欄の中の `\`・タブ・改行・CR は `\\`・`\t`・`\n`・`\r`
- エンジンが動いていない・返事が遅い（DLL 側で 200ms 程度）ときは、DLL はキーを素通しにする

## DLL → エンジン

| 行 | 意味 |
|---|---|
| `hello	<版>	<pid>	<exe>` | つないだ直後。エンジンは `mode` を返す（id 0） |
| `test	<id>	<d\|u>	<vk>	<char>	<mods>	<repeat>` | （前の版の DLL だけが使う）OnTestKeyDown/Up。返事 `done	<id>	<1\|0>`（1 なら OnKeyDown/Up に回してほしい）。今の DLL は OnTestKeyDown の時点で `key` を送る（下） |
| `key	<id>	<d\|u>	<vk>	<char>	<mods>	<repeat>` | OnKeyDown/Up。返事は命令の行と `done	<id>	<consumed>`。consumed が 0 なら DLL はキーを食べずにアプリへ渡す（OnKeyDown で `*pfEaten = FALSE`） |
| `commitall	<id>` | 入力中の文字を確定してほしい（マウスで入力欄を触ったなど） |
| `ctx	<左の文>	<右の文>` | 入力を始める前の、カーソルの前後の文（zenz の文脈）。入力中でなければ受け取る |
| `reset` | アプリが入力中の文字を終わらせた（OnCompositionTerminated）。エンジンは入力を捨てる |
| `focus	<1\|0>` | フォーカスを得た/失った。得たら、エンジンはこの DLL を「いまの相手」にして `mode` を返す |
| `caret	<left>	<top>	<right>	<bottom>` | 入力中の文字の画面上の位置（GetTextExt）。候補の窓をここに出す |
| `mode	<1\|0>` | 言語バーなど、キー以外で日本語のオン/オフが変わった |
| `menu	<項目>` | タスクバーの入力の表示の右クリックのメニューで選んだ（`settings`・`user-dictionary`・`snippets`・`import-android`・`log`）。エンジンが画面を開く |
| `bye` | 切る |

- `vk` は仮想キー。右 Alt は DLL が VK_RMENU（0xA5）に直す。`char` は ToUnicodeEx の結果（なければ 0）。`mods` は Shift=1・Ctrl=2・Alt=4・Win=8
- キーの割り当て（どのキーで何をするか）は全部エンジン（`WinKeyMapper.kt`）が決める。DLL は決めない
- DLL は OnTestKeyDown の時点で `key` を送ってエンジンに処理させ、返事を取っておく。使わず書くものもないキーには「使わない」と答え（アプリへ届く）、それ以外は OnKeyDown で取っておいた命令を書く。IMM のアプリ（EDIT など）では、OnTestKeyDown で「使う」と答えたキーを OnKeyDown で「使わない」と返すと、キーが消えるため。OnTestKeyDown を呼ばないアプリ（RichEdit など）では OnKeyDown で送る

## エンジン → DLL

命令は `c	<id>	<命令>...` の行で並べ、最後に `done	<id>	<consumed>` を送る。id が 0 のものはエンジンから自分から送るもの（並べ替えの結果、フォーカス時のモード）で、DLL は UI のスレッドに渡して非同期の編集セッションで書く。

| 命令 | 意味 |
|---|---|
| `compose	<文字>	<カーソル>	<選んでいる文節>	<文節の長さ,区切り>` | 入力中の文字を置き換える（空なら消す）。カーソルは先頭からの文字数、-1 は末尾。文節がなければ空 |
| `commit	<文字>` | 確定する（入力中の文字は消える） |
| `mode	<1\|0>` | 日本語のオン/オフ（DLL は言語バーのアイコンなどを合わせる） |
| `replace	<前の文字>	<打った文字>	<置き換え>` | スニペット（直接入力中）。カーソルの前が「前の文字」なら消して「置き換え」を入れる。違えば「打った文字」を入れる |

候補の窓・入力モードの表示はエンジンが自分の窓で出すので、DLL には送らない。
