# STATUS

## 現在（2026-10-02）

- 段階0（方式決定）を終え、段階1（最小の IME）の実装をエミュレータで確かめたところ
- 段階1で残っているのは、Fold 7 実機の DeX で届くキーの表（[docs/DEX-KEYS.md](docs/DEX-KEYS.md)）だけ。本人に debug APK を入れてもらう必要がある
- 次の一手: 段階2（Mozc 辞書の LOUDS 変換とビタビを `core` に、zenz の JNI を別モジュールに）

## 完了

- 段階0: 既存の Android 向け zenz 実装（Sumire・Keynako）、azooKey の Swift 移植、Mozc の Android ビルドと辞書を調べ、方式B（Kotlin の辞書・ラティス＋zenz を JNI）に仮決定（`docs/PLAN.md`「段階0の決定」、`docs/RESEARCH-stage0.md`）
- 段階1（実機確認を除く）: `core`（ローマ字→かな・未確定・F6〜F10、単体テスト12件）と `app`（UI なしの IME、JIS/US の切り替えキー、設定とキー記録の画面）。to-fu のエミュレータで動作確認（`docs/BUILD.md`）

## 未完了・次の手順

- 段階1: Fold 7 の DeX でキー表を取る（本人に `app-debug.apk` の導入を依頼）
- 段階2:
  - Mozc の `dictionary_oss` を取得し、ビルド時に LOUDS 形式へ変換するツール（JVM）を作る。辞書はリポジトリに入れない
  - `core` にラティスとビタビ（N-best）。Sumire の `converter/` と `path_algorithm/` を参考にする
  - zenz: azooKey フォークの llama.cpp を submodule にして NDK でビルド（arm64-v8a と、エミュレータ用の x86_64）。作業機に NDK・CMake がまだない
  - Fold 7 で推論時間を測る
