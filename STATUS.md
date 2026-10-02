# STATUS

## 現在（2026-10-02）

- 段階0（調査と方式決定）を終えた。方式B（Kotlin の辞書・ラティス＋zenz を JNI）に仮決定。理由は `docs/PLAN.md` の「段階0の決定」、調査の詳細は `docs/RESEARCH-stage0.md`
- 次の一手: 段階1（最小の IME）。まずビルド環境（JDK・Gradle）を整える

## 完了

- 段階0: 既存の Android 向け zenz 実装（Sumire・Keynako）、azooKey の Swift 移植、Mozc の Android ビルドと辞書を調べ、方式を決めた

## 未完了・次の手順

- 段階1: 最小の IME（ローマ字→かな、物理キー処理、UI なし）
  - 作業機（Fedora）に java・gradle がない。JDK はホーム配下へ入れる。KVM がないので、エミュレータは to-fu のものを ssh 越しに使う（Kite と同じ）
- 段階2: Mozc 辞書の LOUDS 変換とビタビ、zenz の JNI
