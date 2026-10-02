# Kanade（kanade-ime）

Samsung DeX で使うための、物理キーボード専用の Android 日本語 IME。

- 画面キーボードや余計なポップアップを出さない。物理キーボードでの入力に全振りする
- かな漢字変換は zenz（Zenzai）などのニューラル言語モデルで行い、ライブ変換を持つ
- 音声入力は Whisper 系（押している間だけ録音）と逐次認識系を切り替えて使える
- 変換・音声認識は可能な限り端末内（ローカル）で動かす

主な利用端末は Galaxy Z Fold 7（Snapdragon 8 Elite、One UI 8.5 / Android 16）を DeX で使う形。

## 文書

- [docs/PLAN.md](docs/PLAN.md) — 方針・決定事項・段階計画・未決事項（最初に読む）
- [STATUS.md](STATUS.md) — 現在の進み具合と次の一手

リポジトリはローカルのみ。GitHub へは本人の確認なしに push しない。
