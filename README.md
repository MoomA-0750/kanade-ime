# Kanade（kanade-ime）

Samsung DeX で物理キーボードを使うための Android 日本語 IME。Windows 版・Linux 版（fcitx5）もある。

- 物理キーボードでの入力を第一にする。物理キーボードがあるときは画面キーボードや余計なポップアップを出さず、無いときだけ画面キーボードを出す
- かな漢字変換は zenz（Zenzai）などのニューラル言語モデルで行い、ライブ変換を持つ
- 音声入力は、押している間だけ録音し、話しながら文字を出して、離したら句読点付きの文にする
- 変換・音声認識は端末内（ローカル）で動かす

仕様の正本は [SPEC.md](SPEC.md)。

## いまできること（2026-10-14）

- ローマ字かな入力、JIS（変換・無変換など）と US（右 Alt・Ctrl+Space・F13）の日本語オン/オフ、「あ」「A」の表示
- かな漢字変換: Mozc の辞書でラティスを作り、zenz（v3.2-xsmall）で並べ替える。日常文の評価で1位の正解が辞書だけの約 75% から約 94% に
- ライブ変換、文節の区切り・伸縮・移動、候補の窓（カーソルの近く、縦並び、指で選択、説明付き）、記号・絵文字・日付・時刻、全角/半角、カタカナ語の英語の表記（アップル→Apple）、括弧の対
- ユーザー辞書（変換で優先）、スニペット（打った瞬間に置き換え）、学習（前に選んだ候補を上に）
- F6〜F10・Ctrl+U/I/O/P/T、未確定文字列の中のカーソル移動
- 音声入力: 録音キー（既定 F12）を押している間だけ録音。話しながら ReazonSpeech で文字を出し、離したら確定用のモデル（既定は parakeet-ja。whisper なども選べる）で句読点付きの文にする。短く押すと聞き続ける
- Termux のように未確定文字列を出せないアプリでは、入力中の文字を候補の窓に出す
- 物理キーボードがないときだけ出る画面キーボード: 配列はグライド（なぞって入力）・フルキー（払って数字・記号、Ctrl/Alt/Fn/Esc/矢印も）・テンキー（12 キーのフリック）。好きな位置に浮かせるフロート、絵文字パネル（検索つき）、クリップボード（履歴・ピン留め・編集・切り抜き）
- 設定画面（Material 3 Expressive）、設定・ユーザー辞書・スニペットの書き出しと読み込み、英語と日本語の表示
- Windows 版: 同じ入力と音声入力（whisper small）を Windows の入力方式として使える。使い方は [docs/WINDOWS-USAGE.md](docs/WINDOWS-USAGE.md)
- Linux 版: 同じ入力を fcitx5 の入力方式として使える（aarch64・x86_64）。音声入力はどの入力方式でも使え、英字のときは英語で書く。設定の画面は GTK4・libadwaita の版と、Omarchy・Hyprland などのテーマに合わせる Qt Quick（QML）の版があり、デスクトップに合わせて開く。使い方は [docs/LINUX-USAGE.md](docs/LINUX-USAGE.md)

主な利用端末は Galaxy Z Fold 7（Snapdragon 8 Elite、One UI 8.5 / Android 16）を DeX で使う形。

## 文書

- [SPEC.md](SPEC.md) — 今どう動くべきか（仕様の正本）と未決事項
- [docs/decisions.md](docs/decisions.md) — なぜそうしたか、試してやめたこと
- [docs/PLAN.md](docs/PLAN.md) — 方針・段階計画と作業の記録（Windows 版は [docs/WINDOWS-PLAN.md](docs/WINDOWS-PLAN.md)）
- [STATUS.md](STATUS.md) — 現在の進み具合と次の一手
- [docs/USAGE.md](docs/USAGE.md) — 使い方（キー操作、ユーザー辞書、スニペット、音声入力）。アプリの設定画面の「使い方を見る」でも読める
- [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md) — 取り込んでいる第三者のもの（辞書・モデル・ライブラリ）とライセンス
- [docs/BUILD.md](docs/BUILD.md) — ビルドとエミュレータでの確認の手順

リポジトリは https://github.com/MoomA-0750/kanade-ime （公開）。push は本人に確かめてから行う。

## ライセンス

Kanade のコードは MIT（[LICENSE](LICENSE)）。取り込んでいる第三者のもの（辞書・モデル・ライブラリ・アイコン）は、それぞれのライセンスに従う（[docs/THIRD-PARTY.md](docs/THIRD-PARTY.md)）。
