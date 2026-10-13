# Kanade（kanade-ime）作業メモ

- 返答は日本語。最初に `docs/PLAN.md` と `STATUS.md` を読む
- **次に作業するときは、まず DeX のキー表（`docs/DEX-KEYS.md`）を作る。** 作り終えたらキー記録を既定でオフにする（本人の指示。`STATUS.md` の次の手順の先頭）
- 本人の決定で**完全自走**。判断は仮決定して進め、理由を `docs/PLAN.md` か作業記録に残す。GitHub への push だけは事前に確認する
- 共通ルールの正本は Obsidian Vault の `AGENTS.md`（`~/Documents/git-cloned/Obsidian-Vault/AGENTS.md`）。プロジェクトノートは Vault の `Human/Projects/Kanade.md`。区切りごとにそちらも最新化し、Vault は commit と push を一緒に行う
- 試験はエミュレータや Pixel 3 で。DeX 固有の確認と実速度の計測だけ Fold 7 実機が要る（導入は本人へ依頼）
- ファイルを消す・内容が失われる上書きをする前に `.trash/` へ退避する
- commit メッセージは `git commit -F <file>` で渡す

## 仕様の管理

- このリポジトリは試作しながら仕様を詰める。仕様の正本は `SPEC.md`
- 動きを変えたら、同じ commit で `SPEC.md` を直す。履歴は書かず、今の状態に書き換える
- 判断の理由、試してやめたことは `docs/decisions.md` に日付付きで追記する
- `SPEC.md`の各項目には「確定」か「仮」を付ける。「確定」の項目には、外から見た動きを確かめる自動テストを付ける。自動テストが難しいものは、確かめる手順を書けば確定にしてよい
- 迷っていることは `SPEC.md` 末尾の「未決事項」に書き、本人に確認する
