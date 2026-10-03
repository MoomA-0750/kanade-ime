# 端末内の日本語音声認識の選択肢（2026-10-04 調べ）

本人の「whisper small の精度が悪い。ほかにもっと良い選択肢はないか、リアルタイムに出せるモデルは、Pixel や iPhone はどうしているか」に答えるための調査。Android 標準の音声認識（Google の SpeechRecognizer）は、本人が精度に不満なので外した。CER は文字誤り率（小さいほど良い）で、出典ごとに評価の条件が違うので単純には比べられない。

## 要点

- 確定用の第一候補は **NVIDIA parakeet-tdt_ctc-0.6b-ja**。JSUT の CER 6.4〜6.5%（whisper small 14.2%、large-v3 7.1%）。句読点が付く。sherpa-onnx に int8（約 656MB）があり、Kanade の AAR のまま動く → 確定の候補に入れた
- 今途中表示に使っている **ReazonSpeech k2-v2** も確定用として強い（JSUT 6.45%・CommonVoice 7.85%、large-v3 と同等以上）。句読点は付かない → 確定の候補に入れた（話して入れる句読点と組み合わせる）
- **SenseVoice-Small** は CommonVoice ja で CER 11.96%（whisper small 19.51%）。sherpa-onnx の 2025-09-09 版はエミュレータで日本語の見本が中国語に崩れたので、句読点も付く 2024-07-17 版にした
- 途中表示の本命は **Nemotron-3.5-ASR-Streaming-0.6B**（本物のストリーミング、日本語は最上位の対応区分、句読点あり、sherpa-onnx の OnlineRecognizer 用 int8 と Qualcomm NPU 向けがある）。FLEURS ja の CER 11.91%（560ms）。今の「オフラインモデルを 0.6 秒ごとに全部にかけ直す」方式を置き換えられる
- 安く効く改善: 今の方式に **LocalAgreement**（続けて2回一致した頭だけを確定扱い）を足すと、途中の文字の揺れと計算量が減る
- 注意: ReazonSpeech で学習したモデルは JSUT・CommonVoice ではよく見えるが、会話・ドラマ 20 本の第三者の比較では順位が逆転した（large-v3-turbo 18.4、nemo-v2 32.9、k2 44.5、kotoba v2.0 49.5）。最後は本人の声で測る
- Pixel と iPhone は、どちらも「ストリーミングで途中を出し、確定で書き換える」2段構え

## 確定用のモデル

| モデル | 大きさ | JSUT | CommonVoice | ほか | 句読点 | ライセンス | Android で |
|---|---|---|---|---|---|---|---|
| whisper small（入れてある） | 190MB | 14.2 | 15.1 | ReazonSpeech 41.5 | あり | MIT | whisper.cpp |
| kotoba-whisper v2.0（入れてある） | 538MB | 8.4 | 9.2 | ReazonSpeech 11.6 | 弱い（v2.1 以降は Python の後処理で付ける） | Apache-2.0 | whisper.cpp |
| whisper large-v3（参考） | – | 7.1 | 8.5 | ReazonSpeech 14.9 | あり | MIT | – |
| large-v3-turbo（入れてある） | 574MB | – | – | 会話 20 本で 18.4 | あり | MIT | whisper.cpp |
| **ReazonSpeech k2-v2**（入れてある） | 約 73MB（int8） | 6.45（int8 6.63） | 7.85（int8 8.19） | TEDxJP 9.09 | なし | Apache-2.0 | sherpa-onnx |
| ReazonSpeech nemo-v2 | 619M | 7.31 | 8.81 | TEDxJP 10.42 | – | Apache-2.0 | 既製の ONNX なし |
| **parakeet-tdt_ctc-0.6b-ja**（入れた） | 656MB（int8） | 6.4〜6.5 | 7.1 | TEDxJP 9.0 | あり | CC-BY-4.0 | sherpa-onnx（NeMo CTC） |
| SenseVoice-Small（入れた） | 239MB（int8） | – | 11.96 | – | 2024-07-17 版は逆テキスト正規化で付く | 要確認 | sherpa-onnx |
| Qwen3-ASR-0.6B | 0.6B＋180M | – | – | FLEURS 8.33 | 明記なし | Apache-2.0 | sherpa-onnx に int8（ホットワード対応）|
| Qwen3-ASR-1.7B | 1.7B＋300M | – | – | FLEURS 5.20、会話 20 本で 14.0（1位） | – | Apache-2.0 | sherpa-onnx は未対応（要望の段階） |
| Moonshine base-ja / tiny-ja | 61.5M / 27M | – | tiny 18.3 | tiny: ReazonSpeech 10.89 | – | 非商用（旧版） | sherpa-onnx |
| Dolphin small / base | 372M / 140M | – | 16.8 / 18.2 | – | – | 要確認 | sherpa-onnx |

スマホ（Snapdragon 8 Elite）での速さの出典のある実測は見つからなかった。parakeet-ja は k2 の約 4 倍重い見込み（推測）。

## リアルタイム（ストリーミング）に出せるもの

| 選択肢 | 日本語 | 大きさ | 精度 | 句読点 | Android で | メモ |
|---|---|---|---|---|---|---|
| **Nemotron-3.5-ASR-Streaming-0.6B** | 対応（最上位） | int8 の encoder 658MB | FLEURS 11.91（560ms 区切り） | あり | sherpa-onnx の OnlineRecognizer、QNN 版あり。`setOption("language","ja")` | 新しい音声だけを処理（計算量が一定）。OpenMDW-1.1 |
| Moonshine streaming small-ja / tiny-ja | 専用 | 113M / 27M | FLEURS 7.99 / 11.35、ReazonSpeech 26.31 / 28.08 | 記載なし | sherpa-onnx に実行系あり、ja の既製は未確認 | MIT |
| kodama-ja-streaming-small（個人作） | Moonshine を ReazonSpeech で再学習 | 324MB | – | – | ONNX Runtime | Apache-2.0 |
| fujie の CSJ+CEJC streaming zipformer | カナのみ | 70MB | 12.24 | なし | sherpa-onnx | 漢字が出ないので IME には不向き |
| sherpa-onnx 公式の streaming zipformer | 日本語なし | – | – | – | – | – |
| whisper_streaming（LocalAgreement） | whisper 系なら | – | 遅延 3.3 秒 | whisper 次第 | 方式なので自前で | 続く2回で一致した頭だけを確定 |

今の方式は、発話が長くなるほど1回の計算が増え（合計で2乗）、毎回全文を出し直すので前の文字が揺れやすい。

## Pixel と iPhone

| | Google（Pixel・Gboard） | Apple（iOS） |
|---|---|---|
| モデル | 2019 年に全部ニューラルの RNN-T（量子化後 80MB）を Gboard に。Pixel 4 は RNN-T（120M）＋LAS で再採点（57M）、計 177MB。その後カスケードエンコーダでストリーミングと非ストリーミングを一つに | 非公開。iOS 16 から音声入力は完全に端末内（Neural Engine）。iOS 26 の SpeechAnalyzer は新しい自社モデルで、システム側に置いてアプリで共有 |
| 2段構え | 1段目 RNN-T でストリーミング、2段目 LAS で候補を再採点（追加の時間は中央値 58ms） | volatile（速いが粗い）の結果を出し、final で置き換える。final は以後変わらない |
| 句読点 | Pixel 6 以降の高度な音声入力は日本語でも端末内で自動の句読点 | iOS 16 の自動の句読点は日本語対応 |
| 話し終わり | RNN-T が文末の記号を予測し、外の VAD と合わせて判断（別の検出器より遅延を半分以下に） | VAD 用の SpeechDetector がある |

## 次に試す組み合わせ（推薦）

| 案 | 途中表示 | 確定 | 手間 |
|---|---|---|---|
| A | 今の k2 の繰り返し＋LocalAgreement | parakeet-ja | 小（parakeet は入れた） |
| B（本命の作り替え） | Nemotron-3.5 streaming（可能なら NPU） | parakeet-ja か k2 | 中（OnlineRecognizer への移行、確定での書き換え） |
| C（実験） | A か B | Qwen3-ASR-0.6B（ユーザー辞書をホットワードに） | 小〜中 |

どの案でも、本人の声を 30〜50 本録って、CER と体感の遅延を実機で測る。

## 出典

- kotoba-whisper v2.0（whisper の各 CER）: https://huggingface.co/kotoba-tech/kotoba-whisper-v2.0
- 会話系の第三者比較: https://neosophie.com/en/blog/20260226-japanese-asr-benchmark
- kotoba-whisper v2.2: https://huggingface.co/kotoba-tech/kotoba-whisper-v2.2
- ReazonSpeech v2.1: https://research.reazon.jp/blog/2024-08-01-ReazonSpeech.html
- sherpa-onnx の ReazonSpeech: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html
- parakeet-tdt_ctc-0.6b-ja: https://huggingface.co/nvidia/parakeet-tdt_ctc-0.6b-ja 、sherpa-onnx: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/japanese.html
- SenseVoice: https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html 、https://arxiv.org/html/2407.04051v1
- Qwen3-ASR: https://arxiv.org/html/2601.21337v1 、https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html 、https://github.com/k2-fsa/sherpa-onnx/issues/3535
- Dolphin: https://arxiv.org/html/2503.20212v1
- Moonshine: https://arxiv.org/html/2509.02523v1 、https://huggingface.co/moonshine-ai/moonshine-streaming-small-ja
- Nemotron-3.5-ASR-Streaming: https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b 、https://github.com/k2-fsa/sherpa-onnx/pull/3671
- kodama-ja-streaming-small: https://huggingface.co/ayousanz/kodama-ja-streaming-small
- whisper_streaming: https://arxiv.org/abs/2307.14743 、Simul-Whisper: https://arxiv.org/pdf/2406.10052
- Google: https://ai.googleblog.com/2019/03/an-all-neural-on-device-speech.html 、https://arxiv.org/html/2003.12710 、https://arxiv.org/pdf/2010.14606 、https://support.google.com/gboard/answer/11197787
- Apple: https://developer.apple.com/videos/play/wwdc2025/277/ 、https://developer.apple.com/documentation/speech/speechtranscriber
