# 段階0の調査記録（2026-10-02）

変換エンジンの方式を決めるための調査。決定そのものは `PLAN.md` の「段階0の決定」に書いた。
【確認】はソース・公式ページ・実物で確かめたこと、【推測】は見立て。調査時の clone は `/tmp/claude-1000/`（一時置き場、消えてよい）。

## zenz（Zenzai）の要点

- **モデル**【確認】すべて GPT-2 構成、語彙 6000（byte-level BPE、ほぼ文字単位）

  | モデル | Q5_K_M | ライセンス |
  |---|---|---|
  | zenz-v3.2-xsmall-gguf（6層・512次元） | 20,970,304 B | Apache-2.0 |
  | zenz-v3.2-small-gguf（12層・768次元） | 73,871,936 B | Apache-2.0 |
  | v3 / v3.1 / v2.5 / v2 / v1 | — | CC-BY-SA-4.0 |

  v3.2 の Apache-2.0 は HuggingFace のタグとカード表記でのみ確認（カードに説明文なし）。v3.2 は右文脈に対応
- **プロンプト**（v3 以降）【確認】`[プロフィール][トピック][スタイル][好み] <左文脈> <右文脈> <カタカナの読み> <出力></s>`。タグは U+EE0x の私用領域文字。読みはカタカナ、半角スペースは全角スペースへ、改行は削る（トークナイザがどちらも `[UNK]` にする）。BOS は付けない。azooKey の既定は左右文脈各40文字まで
- **llama.cpp はフォークが要る**【確認】GGUF が `tokenizer.ggml.pre = gpt2-small-japanese-char` を宣言しており本家は読めない。特殊トークンID（GGUF上 bos=1/eos=2）も誤りで、実際は unk=0/pad=1/bos=2/eos=3。azooKey/llama.cpp（ブランチ `azookey/b9637-compat`、コミット 66afb88）、fkunn1326/llama.cpp b4846 が修正版。【推測】GGUF のメタデータ書き換えか同じパッチで本家でも読める
- **推論の3方式**
  - (a) 貪欲生成: `</s>` まで argmax
  - (b) 候補評価: プロンプト＋候補を1回の decode に流して対数確率を足す。プロンプトの KV キャッシュを共有できる
  - (c) Zenzai の制約付き生成と評価（投機的デコーディング）: ラティスの最良候補をドラフトにし、zenz の argmax と食い違った位置までを前方一致制約にしてラティスを再探索する。出力は必ず辞書の経路に戻る。打鍵ごとに前回の制約を差分で引き継ぐ（`ZenzaiCache`）
- **速度の実測**【確認】Android 実機の数値は見つからず
  - skk-zenz（x86_64・4スレッド・v3.2-small Q5_K_M・CPU）: 短い読み約20ms、22文字の貪欲生成60ms、5候補の採点24ms、20候補約40ms、RSS約95MB。4スレッド超はほぼ効かない
  - Zenzai 論文（M2 Pro GPU、1文字あたり）: xsmall 3.5ms、small 9.8ms（投機的デコーディングで4.2ms）
  - 【推測】Snapdragon 8 Elite なら xsmall の評価は1回十数ms、small は20〜60ms級。debounce と古い要求の取り消しが前提
- skk-zenz の知見: 文脈なしで zenz だけに並べ替えさせると直る件数と壊れる件数がほぼ同じ。辞書側の順位を残して混ぜる（zenz の点 − β·log(1+元の順位)、β=1 が良好）

## 方式A: azooKeyKanaKanjiConverter を Swift SDK for Android で

- 【確認】Swift 6.3（2026-03）で公式 Swift SDK for Android。現行 6.4.0、NDK r30、API 23 以上。Fedora 向けツールチェーンは fedora41/ubi9 のみ
- 【確認】エンジンは Foundation をそのまま使い（DateFormatter、全角半角変換）、ICU まで要る。arm64 の必要 .so 合計約86MB（うち `lib_FoundationICU.so` 45.8MB、ICU データ34.4MB）
- 【確認】本家 CI に Android ビルドジョブあり（community SDK 6.1、Zenzai なし）。Linux 系では llama.cpp は systemLibrary で外から渡す形で、Android 用は自前ビルド。Zenzai は b4846 系の古い API に固定
- 【確認】先例: fkunn1326/azookey-android-demo（llama はモック）、ku6si0/azookey-android（2026-07、Zenzai を arm64 で動かす非公式 IME）。`-disable-dynamic-actor-isolation` がないと Kotlin の別スレッドから呼んで SIGTRAP。`KanaKanjiConverter` は Sendable でなく、専用スレッドに寄せる必要がある
- 【確認】エンジンは 1.0 前で、マイナー版でも互換性が壊れると明言
- 辞書 azooKey_dictionary_storage: Apache-2.0、35MB（tar.gz 10.3MB）、NEologd と SudachiDict が元。フォーマットの一部は仕様書が TBW
- 【推測】工数: ビルドと JNI まで2〜4日、Zenzai 有効化2〜5日、サイズ削減1〜3日

## 方式B: 辞書とラティス＋zenz

- **Sumire**（KazumaProject/JapaneseKeyboard、MIT、2026-10-01 更新）【確認】
  - 変換本体は Kotlin。LOUDS トライ（`converter/louds/`）、`graph/GraphBuilder.kt`、`path_algorithm/FindPath.kt`（ビタビ）、`ConnectionMatrix.kt`
  - 辞書は Mozc の辞書を自前 LOUDS 形式へ変換（system 約11.5MB＋接続ID 1.9MB、zip）。Mozc のコードは使っていない
  - zenz は別モジュール `:zenz`（azooKey フォークの llama.cpp を submodule、CMake・NDK r29・arm64 静的リンク、`zenz_bridge.cpp` 約1700行、`ZenzEngine.kt`）。既定モデルは v3.2-xsmall Q5_K_M をビルド時に取得して APK 同梱
  - 推論は別プロセスのサービスで Binder 経由。新しい要求で古い推論を abort。使い方は「貪欲生成1候補をライブ枠へ（debounce 300ms、n_ctx 512、4スレッド）」と「辞書上位4候補を zenz で採点して混ぜる（0.7:0.3）」
  - 物理キーボードの設定画面もある
  - Kanade の構成にほぼ一致する先行実装
- **Keynako**（StupidGame、Apache-2.0）【確認】Sumire の zenz JNI をほぼそのまま取り込み、azooKey の LOUDS 辞書を Kotlin で読んで N-best ビーム探索（`AzooKeyDictionary.kt` 約450行）
- **Mozc の Android ビルド**【確認】`libmozc.so` は Bazel で今もビルドでき CI も通る（Java の IME クライアントは廃止）。JNI は protobuf の SessionHandler を丸ごと出す形で、クラス名が Google のパッケージに固定。strip 後約5.4MB。Mozc を JNI で使う現役の第三者 Android IME は見つからず。zenz をラティスに差し込むには C++ 改造が要る
- **Mozc の辞書**（`src/data/dictionary_oss`）【確認】約129万行（`読み 左ID 右ID コスト 表記`）、品詞2672、接続コスト全行列 2672²（36MB テキスト）。IPAdic＋ICOT の表示義務つきで自由に使え、独自部分は BSD-3-Clause。郵便番号辞書は OSS 版になし
- その他の辞書: SKK-JISYO.L は GPL で品詞・コストなし、UniDic は大きすぎ、NEologd は2020年で停止、mozc-ut は CC BY-SA 混在、SudachiDict（Apache）は解析用コスト
- Mozc＋zenz の先例（Android 以外）: koyasi777/mozkey、aruiki/Kotori

## 出典

- https://qiita.com/Kazu_Project/items/536d89d1c499e1896b30
- https://github.com/KazumaProject/JapaneseKeyboard 、https://github.com/KazumaProject/Zenz-Android-Demo
- https://github.com/StupidGame/Keynako
- https://github.com/nekomimist/skk-zenz 、https://github.com/unok/myime
- https://github.com/azooKey/AzooKeyKanaKanjiConverter （`Docs/zenzai.md`、`.github/workflows/swift.yml`）
- https://github.com/azooKey/llama.cpp 、https://github.com/azooKey/azooKey_dictionary_storage
- https://zenn.dev/azookey/articles/7a2c2d20a3cc4a 、https://zenn.dev/azookey/articles/ea15bacf81521e
- https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf
- https://huggingface.co/Miwa-Keita
- https://www.swift.org/blog/swift-6.3-released/ 、https://www.swift.org/documentation/articles/swift-sdk-for-android-getting-started.html
- https://github.com/fkunn1326/azookey-android-demo 、https://github.com/ku6si0/azookey-android
- https://github.com/7ka-Hiira/fcitx5-hazkey 、https://github.com/fkunn1326/azooKey-Windows
- https://github.com/google/mozc （`docs/build_mozc_for_android.md`、`src/android/jni/mozcjni.cc`、`src/data/dictionary_oss/README.txt`）
- https://github.com/koyasi777/mozkey 、https://github.com/aruiki/Kotori
