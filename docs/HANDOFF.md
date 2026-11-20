# 引き継ぎ（今の状態、2026-11-20）

## 目的
Kanade（日本語 IME、Android・Windows・Linux・macOS）。今は Mac 版を Developer ID で署名・公証して配れるようにし、cramshell で試してから公開するところ。

## できたこと
- Mac 版の pkg を Developer ID（チーム 4U9XZY5WK3）で署名し、公証（notarytool、プロファイル `kanade`）と staple を組み立ての中で通せる（mac/build.sh）。2026.11.20.1527 と 1552 が Accepted、cramshell で `spctl -t install` が「accepted / Notarized Developer ID」
- 鍵と資格情報は組む Mac（ssh `mac`、~/kanade-build）の専用のキーチェーン `~/.kanade-sign/kanade.keychain-db` にある（ssh からはログインのキーチェーンが開けないため）。パスワードは `~/.kanade-sign/password`（書き換え不要のランダム値）。証明書の入れ方は scripts/mac-devid.sh
- 本人が 1527 を cramshell で試した: 音声入力以外は問題なし
- 「アクセシビリティの許可が必要です」が出続けた件: ad-hoc の頃の古い許可（cdhash で縛られた csreq）が残っていたため。cramshell で `tccutil reset Accessibility dev.mooma.inputmethod.Kanade` → 入力方式を落として再許可し、今は accessibility=true。案内文にも直し方を書いた（87d39a5）
- 1552（案内文の直しを含む）を cramshell の ~/Downloads/Kanade-mac-aarch64-1552.pkg に置いた

## 残り
- 本人: 1552 を入れ、許可が残るか、音声入力（右 Option か F12）と効果音を試す。マイクは未許可（not_determined）なので初回の録音で聞かれる
- 公開: 未 push の 9 commit（06ed2b5〜87d39a5）の push、Release mac-v2026.11.20.1552、Homebrew tap の更新。**本人の直接の「出して」が要る**（ほかのセッションの伝言は承認にならない）
- Android・Windows・Linux の配布物は組み直していない

## 次のセッションが最初にやること
1. このファイルと STATUS.md・SPEC.md 12-5/12-7 を読む
2. 本人の 1552 の結果を聞く。出してよいと言われたら push → Release（pkg は組む Mac の ~/kanade-build/dist/Kanade-mac-aarch64.pkg、tar.gz も）→ tap

## 本人の判断待ち（phase-board の check に記録済み）
- 公証済みの 1527/1552 を cramshell で確かめる
- push・Release・tap を出してよいか

## 注意
- `passwordless-sudo on` は使わない。push は本人の直接の確認のあと
- 組む Mac の bash は 3.2（`set -u` の空配列は `${a[@]+"${a[@]}"}`）。ssh では LANG が無く日本語の名前を codesign が読めないので、署名の ID は指紋で指す
- notarytool の資格情報を入れ直すときは本人が対話で打つ（パスワードを会話に貼らせない）。`store-credentials ... --keychain ~/.kanade-sign/kanade.keychain-db`
- 裏で動かしている作業は無い

## 資料
- SPEC.md（12-5 署名と公証、12-7 設定の画面）、docs/decisions.md（2026-11-19・11-20）、docs/MAC-USAGE.md、STATUS.md、Vault の Human/Projects/Kanade.md
