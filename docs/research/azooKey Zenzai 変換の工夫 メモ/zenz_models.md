# zenz モデル（zenz-v1 〜 v3.2）の設計・学習・評価

調べた日：2026-11-08。主な一次資料は、NLP2025 の論文「ニューラルかな漢字変換システム Zenzai」（三輪敬太・高橋直希、2025年3月）、Hugging Face のモデルカード、AzooKeyKanaKanjiConverter の `Docs/zenzai.md`、作者の Zenn 記事（2024-05-16）、さくらインターネットの記事（2025-04-16）、IPA 未踏 2024 の成果報告。

## 1. 各版の構造・大きさ・トークナイザー・入力の形式

### Takeaway
どの版も GPT-2（Transformer の decoder だけ）を使い、ku-nlp/gpt2-small-japanese-char を土台に追加学習した条件付き言語モデル。small は約90M。トークナイザーは文字単位の BPE で、未知の文字はバイトに落とす（語彙は6000）。入力は私用領域の文字 U+EE00〜U+EE07 で区切る。版が上がるごとに区切りの文字が増え、v2 で左文脈、v3 でプロフィール（と試験的に話題・文体・設定）、v3.2 で右文脈が入った。

### Cited Findings
**構造と土台**
- zenz-v1 は約90M パラメータの GPT-2。京大の文字単位の日本語 GPT-2（ku-nlp/gpt2-small-japanese-char、CC-BY-SA 4.0）を追加学習した。トークナイザーにバイト単位の BPE を選んだのは、未知語に強く、文字単位でも扱いやすいため — [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
- v1 のモデルカードは「90M」とするが、Hub のメタデータは 95.1M と表示する。GGUF Q8_0 は 102MB — [HF zenz-v1](https://huggingface.co/Miwa-Keita/zenz-v1)
- zenz-v2 のカードは「GPT-2・90M パラメータ・文字単位＋バイト単位の BPE・ku-nlp/gpt2-small-japanese-char を土台」とする。v1 より基本の変換を良くし、機能を足した版。GGUF は Q5_K_M の1ファイルで 72.3MB — [HF zenz-v2-gguf](https://huggingface.co/Miwa-Keita/zenz-v2-gguf)
- zenz-v2.5 には medium 310M・small 91M・xsmall 26M の3種類がある。small は GPT-2 で 90.5M、BF16 の safetensors。カードは「かな漢字変換に特化した条件付き言語モデル。とくに文脈を考えた変換に強い」と書く — [HF zenz-v2.5-small](https://huggingface.co/Miwa-Keita/zenz-v2.5-small)
- 論文によると、3つの大きさとも日本語で事前学習済みのモデルを初期の重みにした。ただし xsmall は合う事前学習済みモデルが無かったので、重みを乱数で初期化した。トークナイザーは事前学習済みモデルのもの（BPE に基づき、バイトへの fallback が付いた文字レベル）で、語彙は6000 — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- Hub の一覧：zenz-v3-small-gguf（95.1M、2025-02-06）、zenz-v3.1-small（90.5M、2025-11-15）、zenz-v3.1-xsmall（22.5M、2025-11-15）、zenz-v3.1-small-gguf（95.1M、2025-08-30）、zenz-v3.1-xsmall-gguf（25.6M、2025-09-03）、zenz-v3.2-small-gguf（95.1M）、zenz-v3.2-xsmall-gguf（25.6M）。v3.2 の2つは調べた時点で「約22時間前」に更新されていた — [HF Miwa-Keita](https://huggingface.co/Miwa-Keita)
- zenz-v3.1-small のカードは README が空。分かるのは「gpt2、90.5M、BF16、cc-by-sa-4.0」だけ — [HF zenz-v3.1-small](https://huggingface.co/Miwa-Keita/zenz-v3.1-small)
- zenz-v3-small-gguf は Q5_K_M の1ファイルで約72MB。README は空 — [HF zenz-v3-small-gguf](https://huggingface.co/Miwa-Keita/zenz-v3-small-gguf/tree/main)

**入力の形式（区切りの文字）**
- v1 は「読みを U+EE00 で始め、U+EE01 から出力を始める」形。損失は U+EE01 より後ろだけで計算する — [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
- 公式ドキュメントにある各版の形式 — [AzooKeyKanaKanjiConverter Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)
  - v1：`<カタカナの読み><出力></s>`。文脈は無い
  - v2：`<カタカナの読み><文脈><出力></s>`。左文脈を読む
  - v3：文脈を先に置く形を勧める。`<文脈><カタカナの読み><出力></s>`。プロフィールは `` で、学習で身に付けてある（ドキュメントの言い方は「native」）。試験的なトークンに ``（話題）、``（文体）、``（設定）がある
  - v3.2：右文脈 `` を足した。置き場所は普通、左文脈の後・入力の前
- 論文での書き方は、`S = [<boi>, i0..in, <boo>, o0..om, <eoo>]`。左文脈を付けるときは、頭に `[<boc>, c0..cl]` を足す。この形は、ピンインから中国語の文を出す PinyinGPT-Concat と同じ — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- Swift の API：`versionDependentMode: .v3(.init(profile: "三輪/azooKeyの開発者", leftSideContext: "私の名前は"))`。`inferenceLimit` は「普通は 1 で足りる。遅くてもよいから高い精度がほしいなら 5 程度」。必要なメモリはおよそ 150MB。macOS は M1 以上の GPU が望ましく、Linux と Windows は CUDA で動く — [Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)

### Inferences
- 論文の <boi>/<boo>/<eoo>/<boc> は、実装の U+EE00/U+EE01/</s>/U+EE02 に当たると読める。
- 90M と 95.1M の差は、数え方の違いとみられる。HF の safetensors（BF16）は 90.5M、GGUF のメタデータは 95.1M と出る。v3.1-xsmall も 22.5M と 25.6M でずれている（GGUF 側で埋め込みを別に数えている可能性がある。確かめてはいない）。
- v2 では文脈を読みの後ろに置き、v3 では前に移した。左から右へ生成するモデルでは、文脈を先に読ませる方が自然な順になる。

### Gaps
- v3・v3.1・v3.2 の層の数・隠れ層の次元・コンテキスト長を書いた一次資料は見つからなかった（カードの README が空）。
- v2.5-medium の土台が ku-nlp/gpt2-medium-japanese-char だという記述は、ミラーの検索の抜粋で見ただけで、一次資料では確かめていない。
- 区切り文字ごとの長さの上限（プロフィール何文字、文脈何文字など）は確かめられなかった。

## 2. 学習データと、読みと表記の対の作り方

### Takeaway
公開コーパスの文に MeCab と ipadic-NEologd で読みを推定して付け、(左文脈・読み・出力) の対を自動で作った。v1 は約500万文で、Mac mini で3日かけて学習した。v2.5 は約1.9億対（Wikipedia と llm-jp-corpus-v3 の Common Crawl）で、H100 1枚と llm.c で学習した。「日本→ニホン/ニッポン」のように、読みの揺れをわざと入れて増やしている。

### Cited Findings
- v1 は約500万文。読みは MeCab と ipadic-NEologd で推定した。学習は Mac mini で3日。データ量を増やすほど、自作の150問での正解数が上がった（120万文で73、260万文で93、500万文で103）— [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
- 講演によると、学習データは手元で用意し、M2 Pro の Mac mini で数日の学習を数回試した — [Speaker Deck 2026-05-23](https://speakerdeck.com/miwa_keita/niyurarukanahan-zi-bian-huan-no-she-hui-shi-zhuang)
- 論文によると、llm-jp-corpus-v3 と日本語版 Wikipedia のアーカイブの一部に、MeCab と ipadic-NEologd で読みを推定してデータを作った。学習データは約190M 対。学習は llm.c の一部を直したフォークで、NVIDIA H100 80GB 1枚 — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- zenz-v2.5-dataset — [HF dataset](https://huggingface.co/datasets/Miwa-Keita/zenz-v2.5-dataset)
  - 欄は JSONL の `input`（カタカナ。記号・数字・空白が混じることがある）、`output`（漢字かな交じり）、`left_context`（無いこともある）
  - 元の資料は、`train_wikipedia.jsonl`（2024年2月に取った日本語 Wikipedia）と、`train_llm-jp-corpus-v3.jsonl`（`ja/ja_cc/level0/CC-MAIN-2023-40.jsonl.gz`）
  - 読みの揺れを入れている（例：日本 → ニホン／ニッポン）。読みの正確さは優先していないので、読み推定の学習には向かないと断っている
  - 使った道具は MeCab・ipadic-NEologd・jaconv。容量は約36.5GB、HF の表示は 129M（2025-01-17 更新）。ライセンスは CC BY-SA 4.0（llm-jp の部分は ODC-BY と Common Crawl の規約）
- さくらの「高火力 DOK」（H100）を無償で使い、1回の学習は数時間 — [さくらナレッジ 2025-04-16](https://knowledge.sakura.ad.jp/42901/)

### Inferences
- 人手の対訳は使っていない。「コーパスに形態素解析で読みを付ける」安い作り方で、量（1.9億対）で押している。左文脈は、コーパスで前にある文をそのまま使ったとみられる。

### Gaps
- v3 系でプロフィール・話題の学習データをどう作ったか（何を「プロフィール」として付けたか）は、一次資料に書かれていなかった。
- 掃除（重複を除く、品質で絞るなど）の細かい手順は、どの資料にも無かった。

## 3. 左文脈・プロフィール・話題の条件付け

### Takeaway
左文脈は v2 から（v2.5 で評価もした）。プロフィールは v3 で学習済みの機能（``）。話題・文体・設定（`/05/06`）は v3 では試験的。右文脈は v3.2 から。ほかに、再学習せずに個人に合わせる仕組みとして、5-gram の「ユーザ言語モデル」を推論のときに差分で効かせる（代理チューニング）。

### Cited Findings
- 論文は「入力だけを条件にした変換では許されても、左右の文脈・過去の入力履歴・ユーザの特性を考えると許されない候補を出す」ことを、かな漢字変換の典型的な失敗とする。対策として P(o | i, c) をモデル化する — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- 版ごとの条件付け（v2 は左文脈、v3 はプロフィール＋試験的に話題・文体・設定、v3.2 は右文脈）— [Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)
- IPA の成果報告：「確定済みの文脈や、ユーザが自由に指示を書ける『変換プロンプト』で、いろいろな条件を付けられる。プロフィールや話題トピックを与えると、文体や専門用語をより正しく変換できる」— [IPA 未踏2024 成果詳細](https://www.ipa.go.jp/jinzai/mitou/it/2024/nl10bi0000006qh1-att/seikashosai-ok-3.pdf)
- 同じ報告にある Tuner（「画面学習」）
  - macOS のアクセシビリティ API で、画面に出た文と取り込んだファイルを集める。近い重複は MinHash で除く
  - 集めた文から「ユーザ言語モデル」（5-gram）を作り、推論のときにモデルの間で差分を動的に当てる（代理チューニング）。こうしてニューラルのモデルを学習し直さずに個人に合わせる
  - — [IPA 未踏2024 成果詳細](https://www.ipa.go.jp/jinzai/mitou/it/2024/nl10bi0000006qh1-att/seikashosai-ok-3.pdf)
- HF には `Miwa-Keita/base_n5_lm`（2025-02-08）と `input_n5_lm_v1` がある — [HF Miwa-Keita](https://huggingface.co/Miwa-Keita)
- コマンドラインには、zenz-v3 以降で話題のプロンプトを有効にするオプションがある（AzooKeyKanaKanjiConverter のミラーのコミット。検索の抜粋で見ただけ）— [git.mii.dev のコミット](https://git.mii.dev/mii/AzooKeyKanaKanjiConverter/commit/9142f3e4930546f1d2ff0d06742704920b003f96)

### Inferences
- base_n5_lm と input_n5_lm_v1 は、Tuner の代理チューニングに使う5-gram（土台用とユーザ用）とみられる。名前から推した。

### Gaps
- プロフィールや話題を付けたときの効果を数字で示した評価は見つからなかった。
- 右文脈（v3.2）の効果を数字で示したものも、一次資料に無かった。

## 4. 評価（AJIMEE-Bench など）と、版どうし・他の IME との比べ

### Takeaway
v2.5 は AJIMEE-Bench（200問）で、Acc@1 が xsmall 66.5％・small 80.0％・medium 86.5％。Google 日本語入力（54.0）と GPT-4o（56.0）を大きく上回り、small は GPT-4.5（80.5）並み。v1 は自作の150問で103/150（GPT-4o は110）。

### Cited Findings
- AJIMEE-Bench
  - 日本語 Wikipedia 入力誤りデータセット（v2）のうち、かな漢字変換が元の誤り（`kanji-conversion_a`）から200問を取った。左文脈付きが100問、文脈なしが100問
  - 欄は `context_text`・`input`（カタカナ）・`expected_output`（正解は複数あり得る）・`original_text`
  - データは CC-BY-SA 3.0、評価のコードは CC0
  - — [GitHub AJIMEE-Bench](https://github.com/azooKey/AJIMEE-Bench)
- 論文の評価の作り方：読みは学習データと同じ方法で推定したあと、人手で読みの誤りを直し、許される変換をアノテーションした。指標は Acc@1（正解のどれか1つと一致すれば正解）と CER（正解が複数なら最小の値）— [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- 表1（200問全部。Acc@1 / CER）— [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)

  | 方式 | Acc@1 | CER |
  |---|---|---|
  | Google 日本語入力 | 54.0 | 6.7 |
  | ドラフトモデル（AzooKeyKanaKanjiConverter） | 44.5 | 7.6 |
  | gpt-4o-2024-08-06 | 56.0 | 7.7 |
  | gpt-4o-mini-2024-07-18 | 19.0 | 21.8 |
  | Zenzai xsmall | 66.5 | 4.6 |
  | Zenzai small | 80.0 | 2.6 |
  | Zenzai medium | 86.5 | 1.7 |

- 表3（文脈なしの100問だけ）：Google 62.0/4.4、ドラフト 46.0/6.6、GPT-4o 52.0/9.7、GPT-4o mini 18.0/22.7、xsmall 69.0/4.1、small 83.0/1.7、medium 89.0/1.8。Google とドラフトには文脈を渡す仕組みが無いので、文脈なしで評価した。Google は 2025-01-09 に CGI API で問い合わせ、句読点と括弧で区切って変換した — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- さくらの記事には、gpt-4.5-preview-2025-02-27 の 80.5 / 2.8 も載っている。「small は GPT-4.5 並み」としている — [さくらナレッジ](https://knowledge.sakura.ad.jp/42901/)
- 例：従来の方式は「領主の家に咆哮」としたが、Zenzai は「奉公」と正しく変換した。GPT-4o は「欲求の無闇な過熱」を「加熱」とするなど、多くの日本語話者なら解ける問題でも失敗が多かった — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- v1 の評価（2024-05）— [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
  - 自作の150問：従来の AzooKeyKanaKanjiConverter 56、Google CGI API 69、zenz-v1 103、GPT-4o（zero-shot）110
  - Anthy の corpus1.txt（1745問）：従来 1065、Google 1104、Zenzai 1179
- 第三者の計測（参考。条件がそろっていない）：azooKey + Zenzai + zenz-v3.2-small で AJIMEE-Bench 86.0％、Mozc の回帰テストで 78.1％ — [miyaoka/tugime Issue #23](https://github.com/miyaoka/tugime/issues/23)

### Inferences
- 公式の AJIMEE-Bench の数字は v2.5（2025年1月）までしか見つからない。v3 以降は「精度が上がった」とだけあって、数字は出ていない。第三者の 86.0％（v3.2-small）は、v2.5-small の 80.0 を上回り、v2.5-medium の 86.5 に近いが、測った条件（推論の回数の上限など）が違うので、そのまま比べられない。
- Mozc との直接の比べは公式には無い。Google 日本語入力（Mozc の親戚）の CGI API が代わりの比べ相手になっている。

### Gaps
- v3・v3.1・v3.2 の公式のベンチマークの数字は見つからなかった。
- AJIMEE-Bench の公開の場所は、ensan-hcl から azooKey の組織へ移っている。公開日は確かめられなかった。

## 5. 速さ・量子化と、既知の弱点と、その手当て

### Takeaway
速さは、統計式の変換器を下書き（ドラフト）にした投機的デコーディングと、推論の回数の上限 L で稼ぐ。small の Q5_K_M は 72.3MB で、1文字あたり約6ms。弱点は、辞書に無い読みへの対応、まれな語・新語、繰り返しの暴走、履歴学習・ユーザ辞書とのつなぎ。投機的デコーディングは「読みに忠実でない生成をはじく」役も持つ。

### Cited Findings
- 投機的デコーディングの仕組み — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
  - ドラフトが入力全体の変換を出す。ターゲット（zenz）は、各トークンが貪欲法で最大の確率かを並列に確かめる
  - 合わないところは、確率が最大のトークンを制約に入れてドラフトをやり直させる。繰り返しは L 回までに制限できる
  - ドラフトに辞書を持つ方式を使うので、出力が必ず辞書の項目と対応する。そのため、読みに忠実でない生成をはじける
- 表2（M2 Pro Mac mini、llama.cpp、GPU、KV キャッシュあり。Acc@1 / CER / 1文字あたり ms）— [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
  - xsmall：-SD 66.5/5.7/3.5、L=∞ 66.5/4.6/6.1、L=3 65.0/5.4/5.4、L=2 65.5/6.0/5.0、L=1 61.5/5.7/3.7
  - small：-SD 80.5/3.4/9.8、L=∞ 80.0/2.6/6.0、L=3 77.0/3.3/5.8、L=2 75.5/3.8/5.1、L=1 69.5/3.9/4.2
  - medium：-SD 86.5/2.8/26.4、L=∞ 86.5/1.7/6.9、L=3 83.0/2.1/5.8、L=2 79.0/2.9/6.2、L=1 70.5/3.6/4.8
  - xsmall は SD を入れるとかえって遅くなる（ドラフトだけでも約1.3ms/文字かかるため）
  - L=1 だと small と medium の差がほとんど無い。ドラフトの出力のうち、1回以下の直しで正解にできるのは最大70％程度とみられる。だから、ドラフトの性能を上げることがなお大事
- 量子化（llama.cpp の Q5_K_M）後の大きさ：medium 237.2MB、small 72.3MB、xsmall 19.9MB — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- v1 の頃（2024-05）— [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
  - Q8_0 は、試した範囲で劣化が無かった
  - 1回の操作は約40ms（目標は20〜30ms、ライブ変換で我慢できるのは約40ms）
  - 速さの比べ：従来 0.0262秒/問、上位10候補の付け直し（リランク）0.1206秒/問（88/150）、Zenzai 0.0598秒/問（100/150）
- 講演の目標は「約50ms 以内。これを超えると重いと感じる」。GPU のある環境だけに絞る、という妥協をした — [Speaker Deck 2026-05-23](https://speakerdeck.com/miwa_keita/niyurarukanahan-zi-bian-huan-no-she-hui-shi-zhuang)
- 弱点（v1）— [Zenn 2024-05-16](https://zenn.dev/azookey/articles/ea15bacf81521e)
  - ライブ変換で「精度」と「制度」を区別できない
  - 「ゴタントウシャ」は失敗し、「ゴタントウシャガ」なら成功する
  - まれな語・新語に弱い
  - 同音異義語の変換で、前の部分まで変わってしまう
  - 履歴学習・ユーザ辞書が効かない
  - 変換器に必要な読みが無いと失敗する（深掘る など）。このために、理論値より約4ポイント下がる
- 弱点（v2.5 の論文）— [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
  - SD ありだと、ドラフトの辞書に無い語をはじいてしまう。例：「夜駆け（ヨガケ）」で、small は -SD より 0.5 ポイント下がった
  - 逆に SD があると、読みに忠実になる。例：-SD の「戦後は蛇行一貫から」が、SD ありでは「戦後は蛇か一環から」になった
  - -SD だと生成が繰り返しに陥り、CER が極端に大きくなる問題がある
- 作者の固定ポスト（iOS 版 azooKey v3.0 で Zenzai を入れたとき）は、入力の誤りの訂正に未対応で、学習やユーザ辞書の効きが弱いとしている — [X @miwa_ensan](https://x.com/miwa_ensan)
- azooKey-Desktop のリリース — [Releases](https://github.com/azooKey/azooKey-Desktop/releases)
  - v0.1.0（8月25日）：Zenzai を最適化し、長文の入力が大幅に速くなった
  - v0.1.1（10月4日）：zenz の重みを v3.1 にした
  - v0.1.3（1月10日）：推論回数の上限の既定を 5 にした
  - v0.1.4（5月17日）：zenz-v3.2 を入れて精度が上がった。Zenzai による入力予測を入れた
  - 年はページに無い。HF の日付から推すと、2025年8月〜2026年5月
- 個人最適化（Tuner／5-gram の代理チューニング）と、ユーザ辞書・履歴学習とのつなぎが今後の課題。MLOps（ずっと保守し続けること）が難しい — [さくらナレッジ](https://knowledge.sakura.ad.jp/42901/), [IPA 成果詳細](https://www.ipa.go.jp/jinzai/mitou/it/2024/nl10bi0000006qh1-att/seikashosai-ok-3.pdf)
- 講演の教訓：従来の方式も実用上は約99％に届いている。0.9％の差を売るのは難しい。日本語入力は慣れで使うので、乗り換えさせる壁（UX）の方が精度より大きい — [Speaker Deck](https://speakerdeck.com/miwa_keita/niyurarukanahan-zi-bian-huan-no-she-hui-shi-zhuang)

### Inferences
- 推論回数の上限の既定は、ライブラリでは 1、azooKey-Desktop では v0.1.3 から 5。表2では、small で L=1 と L=∞ の間に約10ポイントの差がある。上限を上げたのは、速さに余裕ができたので精度を取りにいった判断とみられる。
- 固有名詞は、左文脈（論文の「ユリ」の例）とプロフィール・Tuner で手当てする方向にある。モデル自身の語彙を広げた（v3 系のデータを増やした等）という一次資料は見つからなかった。

### Gaps
- v3 以降で、繰り返しの暴走・固有名詞・新語の弱点が数字でどれだけ直ったかは、一次資料が無い。
- Android や iPhone での実際の遅延の公式の数字は見つからなかった。
- v3 以降の量子化ファイルの大きさは、v3-small Q5_K_M の約72MB だけ確かめた。v3.1 と v3.2 の GGUF の量子化の種類と大きさは確かめていない。
