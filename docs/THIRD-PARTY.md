# 取り込んでいる第三者のもの

公開するときは、アプリ内のライセンス表示にこれらを載せる（Mozc の辞書の README は assets に同梱済み）。

| もの | 入り方 | ライセンス | 備考 |
|---|---|---|---|
| Mozc OSS 辞書（google/mozc `src/data/dictionary_oss`、コミット a069a88） | ビルド時に取得・変換して assets へ | IPAdic 由来部分は IPAdic の許諾（著作権表示と免責文の同梱が条件）＋ICOT 由来条項、独自部分は BSD-3-Clause、沖縄辞書は Public Domain | `mozc-dictionary-license.txt` として同梱 |
| Mozc の記号・絵文字の表（`src/data/symbol/symbol.tsv`、`src/data/emoji/emoji_data.tsv`、同じコミット） | ビルド時に取得・変換して assets へ（`symbol.ksym`） | BSD-3-Clause（Mozc）。絵文字の読みは Unicode CLDR 由来（Unicode License v3） | |
| zenz-v3.2-xsmall-gguf（Miwa-Keita、リビジョン 4f5423f） | ビルド時に取得して assets へ | Apache-2.0（HuggingFace の表記。公開前に作者の表明を確かめる） | |
| llama.cpp（azooKey フォーク、コミット 66afb88） | submodule、静的リンク | MIT | |
