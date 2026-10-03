# 取り込んでいる第三者のもの

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
| Material Symbols（Google、Rounded の 24px。`app/src/main/res/drawable/sym_*.xml` に変換して同梱） | リポジトリ・APK | Apache-2.0 | 画面キーボードと設定画面のアイコン |
| 見本の音声 `eval/voice-sample-ja.wav` | リポジトリ・APK | Windows の音声合成（Microsoft Haruka）で作成 | 公開前に扱いを確かめる |
