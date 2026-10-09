# Kanade（kanade-ime）作業メモ

- 返答は日本語。最初に `docs/PLAN.md` と `STATUS.md` を読む
- 本人の決定で**完全自走**。判断は仮決定して進め、理由を `docs/PLAN.md` か作業記録に残す。GitHub への push だけは事前に確認する
- 共通ルールの正本は Obsidian Vault の `AGENTS.md`（`~/Documents/git-cloned/Obsidian-Vault/AGENTS.md`）。プロジェクトノートは Vault の `Human/Projects/Kanade.md`。区切りごとにそちらも最新化し、Vault は commit と push を一緒に行う
- 試験はエミュレータや Pixel 3 で。DeX 固有の確認と実速度の計測だけ Fold 7 実機が要る（導入は本人へ依頼）
- ファイルを消す・内容が失われる上書きをする前に `.trash/` へ退避する
- commit メッセージは `git commit -F <file>` で渡す

## 画面は Material 3 Expressive

- Android の画面（`app/` の Compose）は Material 3 Expressive で作る。無印の Material 3 の部品や書き方へ戻さない（`desktop/` の Swing と `third_party/` は対象外）
- 置き換え表は `android-m3-expressive` スキル。書いたら `scripts/check_m3e.sh app` が「問題なし」になること（CC では編集のたびにフックが自動で調べる）
- どうしても無印を使う行には、同じ行の末尾に `// m3e-allow: 理由` を書く
