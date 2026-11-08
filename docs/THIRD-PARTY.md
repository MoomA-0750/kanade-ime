# 取り込んでいる第三者のもの

Kanade 自身のコードは MIT（リポジトリ直下の `LICENSE`）。下のものは MIT の対象ではなく、それぞれのライセンスに従う。

公開するときは、アプリ内のライセンス表示にこれらを載せる（Mozc の辞書の README は assets に同梱済み）。

| もの | 入り方 | ライセンス | 備考 |
|---|---|---|---|
| Mozc OSS 辞書（google/mozc `src/data/dictionary_oss`、コミット a069a88） | ビルド時に取得・変換して assets へ | IPAdic 由来部分は IPAdic の許諾（著作権表示と免責文の同梱が条件）＋ICOT 由来条項、独自部分は BSD-3-Clause、沖縄辞書は Public Domain | `mozc-dictionary-license.txt` として同梱 |
| Mozc の記号・絵文字の表（`src/data/symbol/symbol.tsv`、`src/data/emoji/emoji_data.tsv`、同じコミット） | ビルド時に取得・変換して assets へ（`symbol.ksym`） | BSD-3-Clause（Mozc）。絵文字の読みは Unicode CLDR 由来（Unicode License v3） | |
| zenz-v3.2-xsmall-gguf（Miwa-Keita、リビジョン 4f5423f） | ビルド時に取得して assets へ | Apache-2.0（HuggingFace の表記。公開前に作者の表明を確かめる） | |
| llama.cpp（azooKey フォーク、コミット 66afb88） | submodule、静的リンク | MIT | |
| whisper.cpp（v1.9.4） | submodule、静的リンク | MIT | |
| whisper のモデル（OpenAI Whisper small・large-v3-turbo の ggml 版） | 設定画面の「ダウンロード」で HuggingFace から落とす（APK に入れない） | MIT | |
| kotoba-whisper v2.0（kotoba-tech） | 同上 | Apache-2.0 | |
| sherpa-onnx（v1.13.8、onnxruntime を静的に含む AAR） | ビルド時に取得して依存に | Apache-2.0（onnxruntime は MIT） | |
| ReazonSpeech の k2 モデル（`csukuangfj/reazonspeech-k2-v2-ja-en`） | 設定画面の「ReazonSpeech をダウンロード」で HuggingFace から落とす | Apache-2.0（ReazonSpeech の表記。公開前に確かめる） | |
| SenseVoice-Small（FunAudioLLM、sherpa-onnx の int8 版 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`） | 設定画面の「SenseVoice をダウンロード」で HuggingFace から落とす | モデルのライセンス（FunASR Model License）を公開前に確かめる | |
| parakeet-tdt_ctc-0.6b-ja（NVIDIA、sherpa-onnx の int8 版 `sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8`） | 設定画面の「parakeet-ja をダウンロード」で HuggingFace から落とす | CC-BY-4.0（公開するなら表示が要る） | |
| Nemotron-3.5-ASR-Streaming-0.6B（NVIDIA、sherpa-onnx の int8 版 `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`） | 設定画面の「モデル」の一覧から HuggingFace で落とす | OpenMDW-1.1（公開前に条件を確かめる） | |
| Material Symbols（Google、Rounded の 24px。`app/src/main/res/drawable/sym_*.xml` に変換して同梱） | リポジトリ・APK | Apache-2.0 | 画面キーボードと設定画面のアイコン |
| Material Icons（Google。形の数値を `linux/settings/qt/qml/Icon.qml` に写した） | リポジトリ・Linux 版の設定の画面の QML 版 | Apache-2.0 | QML 版のアイコン |
| Qt 6（Qt Base・Qt Declarative） | Linux 版の設定の画面の QML 版が、使う機械に入っているものを動的にリンクする（配布物には入れない） | LGPL-3.0 | |
| FrequencyWords の英語の上位5万語（hermitdave/FrequencyWords、`content/2018/en/en_50k.txt`、コミット 525f9b5。OpenSubtitles 2018 由来） | ビルド時に取得して、グライド入力の英単語（`glide-en.txt`）として assets へ | CC BY-SA 4.0（コードは MIT） | 公開するときは出典の表示と、同じ許諾での単語リストの公開が要る |
| SCOWL 2020.12.07（Kevin Atkinson ほか。english-words・american-words の大きさ 70 まで） | ビルド時に取得して、グライドの英単語のふるいに使う（辞書にある語だけを残す）。著作権表示を `scowl-license.txt` として assets へ | MIT に近い許諾（著作権表示と許諾文を添える） | |
| JMdict（英語版）・JMnedict（EDRDG。jmdict-simplified 3.6.2+20260928191014 の JSON） | ビルド時に取得して、カタカナ→英語の表記の表（`english.ksym`）にして assets へ | CC BY-SA 4.0（EDRDG のライセンス。「This package uses the JMdict/EDICT and JMnedict files. These files are the property of the Electronic Dictionary Research and Development Group, and are used in conformance with the Group's licence.」の表示が要る） | 公開するときは、アプリの中にもこの表示を出す |
| Unicode の emoji-test.txt（Emoji 16.0） | ビルド時に取得して、絵文字パネルの並び（`emoji-panel.tsv`）にして assets へ | Unicode License v3 | |
| 見本の音声 `eval/voice-sample-ja.wav` | リポジトリ・APK | Windows の音声合成（Microsoft Haruka）で作成 | 公開前に扱いを確かめる |
