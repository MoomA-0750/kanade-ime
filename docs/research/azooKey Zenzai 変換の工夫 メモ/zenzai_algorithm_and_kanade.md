# Zenzai（AzooKeyKanaKanjiConverter）の変換アルゴリズムと Kanade の zenz の使い方の比較

調べた日: 2026-11-08。コードは AzooKeyKanaKanjiConverter の main（commit 5a9c4f6、2026-11-07）と azooKey-Desktop の main の浅い clone を読んだ。以下、AKKC = `https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/` とし、`AKKC/Sources/.../zenzai.swift` のように書く。Zenzai のディレクトリは `Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/`（以下 `Zenzai/`）。Kanade は `/home/mooma-0750/Documents/git-cloned/kanade-ime`（commit 4044866）を読んだだけで、何も動かしていない。

## 1. Zenzai は最終の候補をどう決めるか。inference limit とは何で、上限に達すると何が起きるか

### Takeaway
依頼文の見立てはおおむね正しい。もう少し正確に言うと、Zenzai は「統計的変換器（ラティス）をドラフトモデルにした貪欲な投機的デコーディング」である。変換器が出した1位の文を zenz が1回の decode で全トークン同時に検証し、各位置で argmax と違うトークンが出たら「そこまでの表記＋argmax のトークン」を接頭辞の制約にして、変換器に制約付きでラティスを探し直させる。`inferenceLimit` は1回の変換で zenz を呼ぶ回数の上限で、尽きたら最後の（未検証の）ドラフトをそのまま返す。そのときの制約は次の打鍵へ持ち越されるので、上限 1 でも何打鍵かかけて正しい文に近づく。

### Cited Findings
- **ループの骨格**: 論文の Algorithm 1 は `A ← DraftModel(Q, C); C ← TargetModel(Q, A); if C = Null return A` の繰り返し。受理するかどうかは「ドラフトの全トークンが、それより前の文脈に対してターゲットモデルの最も確率の高いトークンか」で決め、満たさないトークンがあればそこの最大確率のトークンを修正案として制約 C を更新する。ループは推論回数制約 L 以下に制限できる（精度は落ちるが端末に合わせて負荷を調整できる） — [三輪・高橋「ニューラルかな漢字変換システム Zenzai」NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- 実装は `all_zenzai`。`while true` の外側ループでドラフトを作る。制約が空なら `kana2lattice_all(N_best: 2)`、制約があれば `kana2lattice_all_with_prefix_constraint(N_best: requestRichCandidates ? 3 : 1)`。N-best の中で value が最大のものを候補にし、内側の `reviewLoop` で `zenz.candidateEvaluate` を呼ぶ — [AKKC/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/zenzai.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/zenzai.swift)（L287–539）
- 評価の結果は `.pass(score, alternativeConstraints)`、`.fixRequired(prefixConstraint: [UInt8])`、`.wholeResult(String)`、`.error` の4つ — [Zenzai/Zenz/ZenzCandidateEvaluator.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzCandidateEvaluator.swift)（L10–20）
- **検証の中身**: プロンプト＋候補のトークン列を、最後のトークンを除いて1回の `llama_decode` に入れ、候補の各位置の logits を得る（「token i の判定に要るのは token i−1 の logits までなので、最後の候補トークン自体は decode しない」）。位置ごとに argmax を取り、候補のトークンと違えば次のように返す。argmax が EOS なら、そこまでの文字列を `.wholeResult` に入れる（この文で終わるべきという制約で、`hasEOS = true`）。それ以外なら、そこまでの文字列＋argmax のピースを `.fixRequired` に入れる。最後まで一致すれば `.pass` — 同ファイル L276–447
- **スコアは並べ替えに使わない**: 「この値は順位付けには使われない。正規化項の全語彙走査を避けるため、等価な順位を持つ未正規化 logit を蓄積する」。比較は logit のままで、softmax は計算しない — 同ファイル L413–415, L382
- **制約の扱い**: `review()` は `.fixRequired` / `.wholeResult` から新しい `PrefixConstraint` を作る。前回と同じ制約がまた出た場合はこう扱う。候補に学習またはユーザー辞書の語が入っていれば `ignoreMemoryAndUserDictionary = true` にして探し直す。入っていなければ諦め、空の制約で、未検証のまま返す。新しい制約が前の制約の延長（漸進的な更新）なら、手元の N-best の中に新しい制約を満たす別の候補がないかを先に探し（`.retry`）、ラティスの再探索を省く — zenzai.swift L556–654
- 制約付き探索は、ラティスのノードの表記のバイト列が制約のバイト列の接頭辞と一致する経路だけを残す（`computeMatchedAndTotalLength`。`hasEOS` なら長さの完全一致を求める） — [AKKC/.../ConversionAlgorithms/Core/FullInputProcessingWithPrefixConstraint.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/FullInputProcessingWithPrefixConstraint.swift)
- **inference limit に達したとき**: `if inferenceLimit == 0 { debug("inference limit! \(candidate.text) is used for excuse"); return (eosNode, lattice, makeCache(constraint: constraint, satisfyingCandidate: candidate)) }`。いまのドラフトを未検証のまま結果にし、制約とドラフトを `ZenzaiCache` に入れて次の打鍵へ引き継ぐ — zenzai.swift L403–407
- **次の打鍵への引き継ぎ**: `ZenzaiCache.getReplaceableConstraint` は、前回の候補（`satisfyingCandidate`）の語のうち、読みが新しい入力の先頭にそのまま残っているものの表記をつないで、初期制約にする。前回の候補がなく、入力が前回の入力の延長なら、前回の制約をそのまま使う — zenzai.swift L84–134
- Zenn の解説（zenz-v1 の頃）: 「推論回数の上限」は macOS 版 azooKey で 1。前の打鍵の制約を引き継ぐので、例文「精彩を欠いた」は通して3回の推論が要るが、1打鍵1回でも正しい結果に着く。初期の制約は前回の1位の候補から作るので推論が減る — [Zenzai の解説（Zenn, 2024-05-16）](https://zenn.dev/azookey/articles/ea15bacf81521e)
- 例: 「おんしゃをだいいちにしぼうしてます」のドラフト「恩赦を第一に死亡してます」が、制約「御」、次に「志」を経て「御社を第一に志望してます」になる。出力はいつも変換器の経路なので、読みとの対応が保証される — [Zenn](https://zenn.dev/azookey/articles/ea15bacf81521e)
- **既定値は文書によって違う**: ライブラリの `ZenzaiMode.on(... inferenceLimit: Int = 10 ...)`。説明は「Smaller limit makes conversion faster but quality will be worse. (Default: 10)」 — [AKKC/.../ConverterAPI/ConvertRequestOptions.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/ConvertRequestOptions.swift)。docs の例は `inferenceLimit: 1` で、「通常1で十分。低速でも高精度が欲しければ5程度」 — [AKKC/Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)。いまの azooKey-Desktop の既定値は 5（設定で 1〜50） — [azooKey-Desktop/Core/Sources/Core/Configs/IntConfigItem.swift](https://github.com/azooKey/azooKey-Desktop/blob/main/Core/Sources/Core/Configs/IntConfigItem.swift)、ConfigWindow.swift L642
- **L ごとの精度と速度**（M2 Pro Mac mini、GPU、KV キャッシュあり。Time は入力1文字あたりの ms）

  | モデル | 方式 | Acc@1 | Time |
  |---|---|---|---|
  | xsmall | 生成のみ (−SD) | 66.5 | 3.5 |
  | xsmall | +SD, L=∞ | 66.5 | 6.1 |
  | xsmall | +SD, L=1 | 61.5 | 3.7 |
  | small | −SD | 80.5 | 9.8 |
  | small | +SD, L=∞ | 80.0 | 6.0 |
  | small | +SD, L=1 | 69.5 | 4.2 |
  | medium | −SD | 86.5 | 26.4 |
  | medium | +SD, L=∞ | 86.5 | 6.9 |
  | medium | +SD, L=1 | 70.5 | 4.8 |

  xsmall では SD を入れるとかえって遅くなる。ドラフトモデルだけでも1文字約 1.3ms かかり、xsmall が十分軽いため、と著者は見ている。L=1 では small と medium の差がとても小さい。ドラフトのうち1回以下の修正で正解にできるのは最大 70% 程度らしく、ドラフトの性能が大事だとしている — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf) 表2
- SD の副作用: 変換器の辞書にない語は出せない（small の −SD は「夜駆け」を正しく出したが、+SD は辞書に「駆け=ガケ」がなく弾いた）。一方で、生成だけのときの繰り返しの暴走は起きない — 同論文 5.2

### Inferences
- 結果を返す分岐は4通りある。(a) 全トークン一致（pass）。(b) 上限に達したので未検証のドラフト。(c) 同じ制約がまた出たので空の制約で返す。(d) エラー。ライブ変換で上限 1〜5 なら (b) がよく起きるはずで、「打つたびに少しずつ直っていく」動きになる。
- 「並べ替え（N 件をそれぞれ採点）」と違い、Zenzai は1候補だけを1回の decode で検証する。正しいドラフトなら推論は1回で済み、違っても最初にずれた位置で直す方向（argmax）が分かる。この「最初にずれた位置の情報」を変換器へ返すのが、並べ替えとの本質的な違い。

### Gaps
- 一般のキー入力1回あたりの推論回数の分布（何打鍵で収束するか）は、公開資料に見当たらなかった。

## 2. Zenzai を有効にしたとき、文節の候補・予測の候補・2位以下の候補をどう作るか

### Takeaway
ふだん（`requestRichCandidates = false`、ライブ変換）は、zenz が検証するのは1位の文だけ。2位以下は、ループの途中で試したドラフト（`insertedCandidates`、新しいものほど前）とラティスから取る。候補の窓を開いたとき（Desktop では変換キーで候補の選択に入ったとき）だけ `requestRichCandidates = true` になる。このときは各位置の上位3トークンを集め、1位との確率比が 0.25 を超える「別の接頭辞」を最大5つ作り、手元のドラフトから当てはまる候補を拾う。比が 0.5 を超え、手元に当てはまる候補がなければ、変換器を追加で呼んで候補を作る。文節の候補と予測の候補は、ほぼラティスと辞書から作り、zenz は直接使わない（実験的な「次に打つかなの予測」は別にある）。

### Cited Findings
- 結果のリストは `insertedCandidates` で、ドラフトを評価する前に毎回 `insert(..., at: 0)` で先頭へ入れる（「ここでN-Bestも並び変えていることになる」）。最終の EOS ノードの `prevs` が、この順の候補になる — zenzai.swift L263–281, L399–402
- rich のとき: `alternativeConstraints` のうち `probabilityRatio > 0.25` のものについて、試した候補（`constructedCandidates`）の中でその制約を満たす最良のものを2位に入れる。見つからず `probabilityRatio > 0.5` なら、`kana2lattice_all_with_prefix_constraint(N_best: 3)` を追加で呼ぶ — zenzai.swift L447–487
- 別案のトークンは、各位置の logits の上位3件（`FixedSizeHeap(size: requestRichCandidates ? 3 : 0)`）から集め、全体で上位5件（`altTokens` size 5）を残す。比は `expf(item.logit - maxItem.logit)` — ZenzCandidateEvaluator.swift L275–411
- 候補の順番の作り方: Zenzai が有効なら、全文の上位5件の value を、Zenzai の出した順に合うよう並べ直して書き換える（「candidateのvalueをZenzaiの出力順に書き換えることで、このあとのrerank処理で騙されてくれるようになっている」と FIXME 付きのハック）。完全一致の要求では、Zenzai が有効なら value で並べ直さず、出た順のまま返す — [AKKC/.../ConverterAPI/KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)（L1017–1041）
- 文節の候補（`firstClauseResults`）は、ラティスの結果（`clauseResult`）の各候補の最初の文節から作る（上位5件など） — KanaKanjiConverter.swift L1111–1137 付近
- 予測の候補は `bestCandidateDataForPrediction`（全文の候補の value が最大のもの）を元に、辞書で引く `getPredictionCandidate` から最大3件 — KanaKanjiConverter.swift L983–990, L1045–1050
- 辞書の予測が空で、`experimentalZenzaiPredictiveInput` がオンのときだけ、zenz-v3 で「次に打つかな」を最大10文字、貪欲に生成する（`predictNextInputText(count: 10, maxEntropy: 3.0)`。エントロピーが 3.0 を超えたら止める。「、。！？」でも止まる）。生成したかなを入力に足して別のセッションで変換し、予測の候補にする — KanaKanjiConverter.swift L765–800、[Zenzai/Zenz/ZenzInputTextGenerator.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzInputTextGenerator.swift)
- 予測のプロンプトは `条件 + 左文脈 + 右文脈 + 入力カタカナ`（出力タグなし）。かな入力の続きを予測する — [Zenzai/Zenz/ZenzPromptBuilder.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzPromptBuilder.swift) `inputPredictionPrompt`
- azooKey-Desktop は、候補の選択に入るとき（`.enterCandidateSelectionMode`）に `update(requestRichCandidates: true)` を呼ぶ。文節の一部を確定したとき（`prefixCandidateCommited`）は、左文脈に確定した表記を足し、rich で残りを変換し直す — [azooKey-Desktop/azooKeyMac/InputController/azooKeyMacInputController.swift](https://github.com/azooKey/azooKey-Desktop/blob/main/azooKeyMac/InputController/azooKeyMacInputController.swift) L402、[Core/Sources/Core/InputUtils/SegmentsManager.swift](https://github.com/azooKey/azooKey-Desktop/blob/main/Core/Sources/Core/InputUtils/SegmentsManager.swift) L607–618
- **先頭を確定した候補の固定**（新しめの機能）: 利用者が先頭の文節を受け入れると `settingPrefixCandidate` で `prefixCandidate` を持ち、読みが入力の先頭に残っている間は `FixedPrefix` として固定する。評価では、固定した表記を左文脈へ移し、固定した読みを入力から外して、残りだけを zenz で評価する（「モデルは固定された表記が読みを消費したことを知らないので、残したまま評価すると続きの判断がずれる」） — zenzai.swift L46–114、ZenzCandidateEvaluator.swift L94–132
- 予測の誤字訂正の候補（`ZenzaiTypoCandidateGenerator`、1554 行）は実験的な API で、zenz か n-gram で確率を評価する — [Zenzai/Zenz/ZenzaiTypoCandidateGenerator.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzaiTypoCandidateGenerator.swift)

### Inferences
- Zenzai で zenz が直接よくするのは「全文の1位」と、rich のときの数件の別案だけ。候補の窓の3位以降は、ほぼ辞書・ラティスの順のまま。Kanade の「上位10件をまとめて採点して並べ替える」は、候補の窓の並びではむしろ Zenzai より zenz を多く使っている。

### Gaps
- `processResult` の最終の並び（`mainResults` の組み立て）は非常に複雑で（コードのコメントにも「非常に複雑な方法」とある）、全部はたどっていない。

## 3. 左文脈・プロフィール・個人化（personalized model・学習）をモデルへどう渡すか。学習辞書とどう関わるか

### Takeaway
文脈と条件はすべてプロンプトの接頭辞で渡す。v3 では `プロフィール 話題 文体 設定 左文脈 右文脈 入力 ` の順。個人化（Tuner）は再学習ではなく「代理チューニング」で行う。トークンごとに `logit + α·(log P_personal − log P_base)` を足す方式で、P は zenz と同じトークナイザで作った 5-gram の LM。学習辞書・ユーザー辞書の語は、ラティスでは強いノードとして入る。zenz の検証では、その語の先頭のトークンだけで採るかどうかを決め、続きのトークンは argmax と違っても許す。さらに、読みの長さに応じた加点（log）で採りやすくしてある。弾かれ続けたら `ignoreMemoryAndUserDictionary` で学習の語を外して探し直す。

### Cited Findings
- プロンプトの組み立て（v3）: `conditions.joined() + 左文脈 + 右文脈 + 入力 + `。左文脈は後ろから、右文脈は前から、それぞれ既定で 40 文字（`maxLeftSideContextLength` / `maxRightSideContextLength` で変えられる）。プロフィールなどの各条件は後ろから 25 文字 — ZenzPromptBuilder.swift `candidateEvaluationPrompt` / `v3Conditions` / `trimmedContext`
- タグの意味: v3 は `` のプロフィールをネイティブに学習済みで、実験的に `` 話題、`` 文体、`` 設定にも対応する。v3.2 は `` の右文脈に対応し、ふつうは左文脈の後、入力の前に置く — [AKKC/Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)
- **カーソルの位置の印**（v3、`enableAlignmentSeparator`）: 入力の途中にカーソルがあると、入力の中に `` を入れ、候補の末尾にも `` を付けて評価する（カーソルより前だけを変換する場面）。azooKey-Desktop は `enableAlignmentSeparator: true` で、`profile: Config.ZenzaiProfile()`、左右の文脈を渡す — ZenzPromptBuilder.swift、ZenzCandidateEvaluator.swift `candidateTextForEvaluation`、SegmentsManager.swift L163–186
- v2 ではユーザー辞書の語を `辞書:語(よみ)` としてプロンプトに入れられたが、v3 には対応するタグがないので入れない — ZenzPromptBuilder.swift（「ユーザ辞書の条件はv2のみ」）
- **個人化**: `PersonalizationMode(baseNgramLanguageModel, personalNgramLanguageModel, n: 5, d: 0.75, alpha: 0.5)`。評価中の各位置で、候補のトークン列の接頭辞を base と personal の n-gram に `bulkPredict` させ、全語彙について `logits + alpha*(log(pp+1e-7) − log(pb+1e-7))` を足してから argmax を取る — ConvertRequestOptions.swift L218–231、ZenzCandidateEvaluator.swift L288–334
- IPA の成果: Tuner は画面の文字（macOS のアクセシビリティ API）や取り込んだファイルを集め、MinHash で近い重複を除き、「ユーザ言語モデル」（5-gram）を作る。これを「言語モデル間で実行時に動的に差分を適用する代理チューニング」で zenz に当て、ニューラルモデルを再学習せずに適応させる — [IPA 2024年度未踏 成果詳細（三輪・高橋）](https://www.ipa.go.jp/jinzai/mitou/it/2024/nl10bi0000006qh1-att/seikashosai-ok-3.pdf)
- azooKey-Desktop の個人化の強さ: off=0、soft=0.5、normal=1.0（既定）、hard=1.5。個人の LM は `Library/Application Support/p13n_v1/lm*` の marisa ファイルで、ないときは個人化しない — [azooKey-Desktop/Core/Sources/Core/Configs/CustomCodableConfigItem.swift](https://github.com/azooKey/azooKey-Desktop/blob/main/Core/Sources/Core/Configs/CustomCodableConfigItem.swift)、SegmentsManager.swift L106–125
- n-gram の実装は同じリポジトリの `EfficientNGram`（Trainer / Inference / Tokenizer。Kneser-Ney、marisa） — [AKKC/Sources/EfficientNGram](https://github.com/azooKey/AzooKeyKanaKanjiConverter/tree/main/Sources/EfficientNGram)
- **学習辞書・ユーザー辞書との関わり**:
  - 語の表記に当たるトークンのうち、2番目以降（`userWordContinuations`）は argmax と違っても棄却しない（「学習・ユーザ辞書の単語は先頭のtokenで採否を判断し、以降のtokenは表記のまま残す」）
  - 先頭のトークンにも `log(learningPriority)` を足す。読み 1〜4 文字なら `len+2`、5〜15 文字なら `len*2`、それより長ければ 30。`logit(候補) + 加点 > logit(argmax)` なら採る（「積極的な複合語化の効果を期待」）
  - 同じ制約で弾かれ続け、候補に学習やユーザー辞書の語があれば、`ignoreMemoryAndUserDictionary = true` にして探し直す

  — ZenzCandidateEvaluator.swift L222–253, L379–394, L544–554、zenzai.swift L578–590
- 学習やユーザー辞書の語を含む結果は、確定した変換のメモ化キャッシュへ入れない。個人化があると、メモ化をすべて切る — zenzai.swift L210–225, L489–496、ZenzCandidateEvaluator.swift L170–189
- Zenn の時点（zenz-v1、2024-05）では「履歴学習は現在機能していない」「ユーザ辞書・変換範囲の調整は未実装」 — [Zenn](https://zenn.dev/azookey/articles/ea15bacf81521e)。いまのコードでは上のとおり実装済み（コードで確認）

### Inferences
- 学習辞書と LM の兼ね合いは、Kanade が 2026-10-03 に当たった問題（並べ替えの結果を学習すると固まっていく）と同じ種類のもの。Zenzai は「学習の語は先頭のトークンだけで判断し、加点もする。弾かれ続けたら外す」で、学習が LM に潰されるのも、LM が学習に潰されるのも防いでいる。

### Gaps
- 加点の式（`len+2` など）や α の既定値の根拠を書いた文書は見つからなかった。

## 4. 速くする工夫（llama.cpp の使い方、打鍵をまたぐ KV キャッシュ、量子化、GPU/Metal、差分の評価、早い打ち切り）と公開された実測値

### Takeaway
推論そのものの速さより、「推論しない工夫」が多い。(1) ドラフトの1候補を1回の decode で検証する。(2) 打鍵をまたいで制約とドラフトを引き継ぐ。(3) ローマ字の綴りが途中なら評価を飛ばす。(4) ラティスを差分で作る。(5) 評価・ドラフト・確定した変換の3段のメモ化キャッシュ。(6) プロンプトのトークン列のキャッシュ。(7) 共通の接頭辞の KV の使い回し（評価用と予測用の2系列の間でも写す）。(8) 最後のトークンは decode しない。(9) logits の softmax を取らない。llama.cpp の設定は n_ctx 512、flash_attn、n_ubatch 64、スレッドは性能コアの数（Apple 以外は最大8で1〜2本残す）、mmap。量子化は Q5_K_M（xsmall 19.9MB）。

### Cited Findings
- llama.cpp の設定: `n_ctx = 512`、`flash_attn = true`、`n_batch = 512`、`n_ubatch = 64`、`no_perf = true`、`use_mmap = true`。CPU 版（`ZenzaiCPU`）は `n_gpu_layers = 0`、`offload_kqv = false`、CPU だけのデバイスリスト（「n_gpu_layers = 0 だけでは llama.cpp が Metal デバイスも列挙し、context 生成時に初期化してしまう」） — [Zenzai/Zenz/ZenzContext.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzContext.swift) L262–300, L362–447
- スレッド数: Apple Silicon では最高性能のクラスタの物理コア数（「性能の異なるクラスタを跨ぐと短い decode の barrier 待ちが増える」）。それ以外では、論理 CPU 数から（8以上なら2、4以上なら1）引き、上限8 — ZenzContext.swift L380–420
- **KV の使い回し**: `prevInputBySeq[seqId]` と新しいトークン列の共通の接頭辞を数え、`min(共通長, logits_start_index)` から先の KV を `llama_kv_cache_seq_rm` で消し、違う部分だけを decode する。評価（seq 0）と入力の予測（seq 1）のうち、もう一方の系列の方が長く一致していれば `llama_kv_cache_seq_cp` で写してから使う — ZenzContext.swift L463–516
- モデルと context の共有: 同じ GGUF は `SharedZenzModel` で重みと語彙を共有する。context は `SharedZenzCache`（`countLimit = 1`）でモデルごとに1つで、推論は `inferenceLock` で順番にする（「複数Converterが同時に18 MiBのKV cacheを保持することを避ける」） — Zenz.swift L1–40、ZenzContext.swift
- **評価の差分**: 同じ検索の中で前回とプロンプトが同じなら（`reusesAddressedPrefix`）、制約で決まった接頭辞の分だけ評価の開始位置（`startOffset`）を後ろへずらし、判定済みの位置を見直さない — ZenzCandidateEvaluator.swift L163–233
- **ローマ字が途中なら評価しない**: rich でなく個人化もなく、ローマ字の表で未確定の後ろの文字がある（例: 「か k」）なら、ドラフトを返すだけで zenz を呼ばない（「次のキーでかなへ置換される。モデルが未確定ASCIIを評価しても結果を再利用できないため」）。直前に LM が認めた候補だけを安定した接頭辞として引き継ぐ — zenzai.swift L202–207, L408–426
- **ラティスの差分の構築**: `ZenzaiCache.getPreprocessedLattice` は、入力が同じならラティスをそのまま使い（`resetNodeStates` だけ）、入力が前回の延長なら `buildLatticeWithIncrementalCache` で差分を作る。同じ呼び出しの中で探し直すときは、最初のラティスを使い回す — zenzai.swift L136–154, L309–323
- **メモ化キャッシュ**（`ZenzaiMemoizationCache`、Converter ごと、入力をまたいで残る）: 評価 256 件、確定した変換 64 件、初回のドラフト 128 件、プロンプトのトークン列 128 件の LRU。キーは入力・カーソル・制約・version 設定など。Backspace で戻ったり、同じ入力をもう一度見たりしたときに、推論を丸ごと飛ばせる — ZenzContext.swift L197–260、zenzai.swift L210–258, L289–375
- 量子化: 論文の評価は Q5_K_M で、medium 237.2MB、small 72.3MB、xsmall 19.9MB。パラメタ数は medium 310M、small 91M、xsmall 26M — [NLP2025 P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf) 付録A・4章。azooKey-Desktop が読むファイル名も `ggml-model-Q5_K_M.gguf` — SegmentsManager.swift L171。zenz-v1 の頃は Q8_0 で、試した範囲では精度は落ちなかった — [Zenn](https://zenn.dev/azookey/articles/ea15bacf81521e)
- GPU: 論文の計測は「端末内蔵の GPU」（M2 Pro、Metal）。docs は「M1以上のmacOS環境が望ましい。GPUを利用」「メモリ 150MB 程度」「Linux・Windows でも CUDA で動作」 — [P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)、[Docs/zenzai.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md)。IPA の報告では「M1 チップ搭載端末でも十分な速度」 — [IPA 成果詳細](https://www.ipa.go.jp/jinzai/mitou/it/2024/nl10bi0000006qh1-att/seikashosai-ok-3.pdf)
- 公開された実測（zenz-v1、Zenn、150 問、M2 Pro）: 変換器だけ 56/150・0.0262 s/問、並べ替え（上位10件を zenz で並べ替え）88/150・0.1206 s/問、Zenzai 100/150・0.0598 s/問。macOS の azooKey で「一操作あたり40ミリ秒程度」。20〜30ms ならなめらか、約40ms がライブ変換の遅れの上限という感覚 — [Zenn](https://zenn.dev/azookey/articles/ea15bacf81521e)
- 公開された実測（NLP2025、1文字あたりの ms）は1章の表のとおり（xsmall: 生成のみ 3.5、+SD L=1 3.7、ドラフトだけで約 1.3） — [P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf)
- 早い打ち切り: 検証は、最初に argmax とずれた位置で返す（それより後の logits は計算済みだが見ない）。予測の生成は、エントロピーが 3.0 を超えるか句読点で止まる — ZenzCandidateEvaluator.swift、ZenzInputTextGenerator.swift
- 参考: AKKC の最新の commit は「fix(ci): Android を Swift 6.4.2 に更新」で、CI に Android の構築が入っている — `git log`（5a9c4f6）

### Inferences
- 評価の decode は1回で、(プロンプトの違う部分＋候補のトークン)を1つの batch に入れる。xsmall の CPU で1回あたり、Kanade の「並べ替え 10 件（1 decode にまとめて 27.6ms）」より小さいはず。候補が1件で、トークンは文の長さ（十数〜数十）しかないため。

### Gaps
- iPhone や Android の CPU での Zenzai の実測は見つからなかった（論文・Zenn・IPA はどれも Mac の GPU）。

## 5. Kanade はいま zenz をどう使っているか（Zenzai との比較）

### Takeaway
Kanade は Zenzai の「並べ替え法」に当たる。Kotlin の Mozc 辞書でラティスを作り、前向きビタビ＋後ろ向き A* で N-best（候補の数の設定、既定はおそらく10）を取る。上位10件を zenz-v3.2-xsmall（Q5_K_M、CPU 4スレッド）で採点し、`対数確率 − 1·ln(1+元の順位)` で並べ替える。採点は、プロンプトの KV を系列 0 に置き、候補ごとの系列（最大15）へ写して1回の decode でまとめて行う（Fold 7 で中央値 27.6ms）。入力中は辞書の1位をすぐ出し、並べ替えが終わったら差し替える。文節を選び直すときも、前後の文節をつないだ文で採点する。生成は入力中は使わない。予測の候補には zenz を使わない。

### Cited Findings
- 方針: 「方式B（Kotlin の辞書・ラティス＋zenz を JNI）」。見直す条件は「辞書＋zenz の並べ替えで品質が明らかに足りないとき」。PLAN には「発展形として Zenzai の制約付き再探索を Kotlin へ移植する」とある — `/home/mooma-0750/Documents/git-cloned/kanade-ime/docs/decisions.md`（2026-10-02）、`docs/PLAN.md` L73, L84
- 変換器: `KanaKanjiConverter.convert(input, n=10)`。辞書の前方一致でノードを置き、ユーザー辞書（コスト 1000）、学習した語（2500）、未知語の1文字（12000）を足し、前向きで各ノードの最小コストを求め、後ろ向き A* で N-best（表記で重複を除く。展開は最大 20000） — `core/src/main/kotlin/dev/mooma/kanade/core/convert/KanaKanjiConverter.kt`
- 並べ替え: `Reranker(topK = 10, beta = 1.0)`。`(候補の上位10 + 生成).distinct()` を `model.score` で採点し、`scores[i] − β·ln(1+rank)` で並べる（辞書にない生成は rank = topK）。残りは元の順で後ろに付ける — `core/src/main/kotlin/dev/mooma/kanade/core/convert/Reranker.kt`
- ネイティブの採点: プロンプトを系列 0 に置き、前回と共通のトークンの KV を使い回す（`decode` で共通の接頭辞を数え、`llama_memory_seq_rm`）。候補ごとに系列 1..15 へプロンプトの KV を `llama_memory_seq_cp` で写し、候補のトークン（＋EOS）を1つの batch で decode する。各トークンで全語彙の log-softmax を取って足す（EOS まで）。`kv_unified = true`、`n_seq_max = 16`、`n_ctx = n_batch = n_ubatch = 512`、`n_gpu_layers = 0`、中断は abort callback — `native/zenz_bridge.cpp`
- プロンプト: `左文脈(40) 右文脈(40) 読み(カタカナ) `（azooKey の ZenzPromptBuilder と同じ並び）。プロフィールは渡していない。半角スペースは全角へ、改行は消す — `zenz/src/main/kotlin/dev/mooma/kanade/zenz/ZenzModel.kt`
- ライブ変換: 「まず辞書の1位をすぐ出し、言語モデル（zenz）の並べ替えが終わったら差し替える」（SPEC 3-1 仮）。読みが変わるたびに `source.convert(r, settings.candidateCount)` と `RequestRerank` を出す — `SPEC.md` 3-1・4-1、`core/.../InputEngine.kt` `refreshCandidates`（L812–825）
- 文節の候補: その読みを1文節として変換した N-best に、ひらがな・カタカナなどを足す。並べ替えは「前の文節＋候補＋後の文節」の文で採点する（`requestSegmentRerank`） — `InputEngine.kt` L785–800、`docs/PLAN.md` L206
- 予測の候補: 辞書の接頭辞の範囲を、コスト＋伸びた文字数×400 で並べる。zenz では並べない（打つたびに推論が増えるため）。「しゅうせ」の1位が「修せよ」になるなど、並びがおかしい例がある — `docs/decisions.md` L312、`SPEC.md` 未決事項 L117
- 学習: 利用者が候補を動かして選んだときだけ学習する（並べ替えで1位が替わっただけの候補を覚えると、結果が固まっていくため）。ユーザー辞書の語は候補の先頭に固定し、並べ替えでも下げない — `docs/decisions.md`（2026-10-03）、`SPEC.md` 6-1
- 実測（Fold 7、xsmall、102 文）: 辞書だけ（ラティス＋N-best 10件） 3.6ms／6.7ms（中央値／95%）。zenz の生成 12.0ms／18.8ms。並べ替え 10件 27.6ms／39.7ms。正解は辞書だけ 78/102、並べ替え 96/102。生成を足しても1位は変わらなかった。small は 92%→94% で 3〜4 倍遅い。左文脈（30 文）で、辞書 14、文脈なし 18、文脈あり 24 — `docs/PLAN.md` L129–161

### Inferences
- **Zenzai がして、Kanade がしていないこと**:
  1. ずれた位置で直す方向を変換器へ返すこと（制約付きの再探索）。Kanade は N-best の上位10件の外にある正解を出せない。Zenzai は「御→志」のように、10 件に入らない経路も制約で引き出せる
  2. 打鍵をまたいで制約を引き継ぐこと（1回に少しずつ直す）
  3. ローマ字の綴りが途中のときに推論を飛ばすこと
  4. 評価・ドラフト・確定した変換のメモ化キャッシュ（Backspace で戻ったとき推論しない）
  5. ラティスの差分の構築
  6. プロフィールなどの条件タグ
  7. 個人化 LM による logit の補正
  8. 学習の語を「先頭のトークンだけで判断＋加点」で LM と両立させること（Kanade は学習を「動かして選んだときだけ」に絞る形で避けた）
  9. カーソルの位置の印 ``
  10. 次に打つかなの予測
- **Kanade がしていて、Zenzai の既定にないこと**: 候補の窓の上位10件を全部 zenz で採点していること（Zenzai は1位と数件の別案だけ）。文節を選び直すとき、文全体で採点していること。並べ替えの式に辞書の順位の事前知識を混ぜていること。
- **採点の違い**: Kanade は EOS まで含めた文の対数尤度の和（正規化あり）。Zenzai は「各位置の argmax に一致するか」の二値の判定で、長さの偏りが入らない。代わりに、2位以下の順番を決める力はない。

### Gaps
- Kanade の既定の `settings.candidateCount` の値は今回確かめていない（Reranker の topK は 10）。
- Kanade の入力中、ローマ字の綴りの途中（例: 「k」）を読みに含めて並べ替えを出しているかは、コードを読み切れていない。

## 6. Kanade が取り入れられる技術（Android の CPU、llama.cpp）と見込みのコスト

### Takeaway
費用対効果の順に並べる。(A) メモ化キャッシュ、(B) ローマ字の途中で推論しない、は数十行で入り、すぐ効く。(C) 「1位の検証＋制約付き再探索」は Kanade の前向き・後ろ向きの構造に素直に載せられる。1回の推論は1候補分で済み、並べ替え 10 件より軽い見込み。だから「1位は Zenzai 方式、候補の窓は今の並べ替え」の組み合わせが現実的。プロフィールのタグはほぼ無料。個人化 LM と次のかなの予測は、コストと得が見合うか測ってから。

### Cited Findings（根拠として使う既存の事実）
- Kanade の辞書だけの変換は Fold 7 で中央値 3.6ms、並べ替え 10 件は 27.6ms、生成は 12.0ms — `docs/PLAN.md` L135–140
- 論文の xsmall では SD がかえって遅かった（GPU で生成だけが十分速いため）。L=1 で精度が 5 ポイント落ちた（66.5→61.5） — [P1-19](https://www.anlp.jp/proceedings/annual_meeting/2025/pdf_dir/P1-19.pdf) 表2
- Zenzai の検証は1候補を1回の decode で行い、softmax は取らず argmax だけを見る — ZenzCandidateEvaluator.swift

### Inferences（提案と見積もり。どれも未計測の推測）
1. **メモ化キャッシュ**（入力・左右の文脈・候補列 → 並べ替えの結果の LRU、数十件）。Backspace で戻ったとき、同じ入力の文節を選び直したときに推論が 0 になる。実装は Kotlin で数十行。危険は小さい（学習とユーザー辞書が変わったら捨てる）
2. **ローマ字の綴りの途中では並べ替えを出さない**（Zenzai の `defersEvaluationForPendingInput`）。打鍵ごとの推論がおよそ半分になる見込み（子音の打鍵の分）。表示は辞書の1位のまま
3. **1位の検証＋制約付き再探索**（Zenzai 方式）。Kanade の `KanaKanjiConverter` に「表記のバイト列の接頭辞の制約」を入れる。前向きのとき、各ノードで経路の表記が制約の接頭辞と食い違う経路を落とす。ただし Kanade の前向きはノードごとに最小コストを1つ持つだけで、経路の表記を持たない。だから接頭辞の一致の状態（何バイトまで一致したか）を、ノードの状態に足す必要がある（AKKC は `constraintMatchedUTF8Count` を持つ）。工数は中くらい（数百行）。ネイティブには「候補1件を decode し、各位置の argmax と一致するかを返し、ずれたら（位置、argmax のピース）を返す」関数を1つ足すだけ。推論のコストは、トークン 15〜30 の decode 1回で、並べ替え 10 件（同じプロンプト＋10 系列）より小さい。おそらく生成 1 件（12ms）と同じくらいか、それ以下（推測）。上限は Desktop と同じく 1〜5 で、打鍵をまたいで制約を持ち越す。得られるもの: N-best の上位10件の外の正解（Kanade の評価で残った 6/102 の誤りのいくつか）、長い文での改善。危険: 辞書にない語は出せない（いまの並べ替えと同じ制約）。xsmall では SD が速さの面で得にならないかもしれない（論文の GPU の結果）。ただし Android の CPU では生成がずっと遅いので、論文と違う結果になりうる（未確認）。**Fold 7 で「並べ替え 10 件」と「検証＋再探索 L=1/3」の正解率と時間を比べるのが次の一歩**
4. **組み合わせ**: 入力中の1位は 3 の方式（速い・正確）。Space で候補の窓を開いたら、今の並べ替え（10件、27.6ms）。Desktop の rich のときと同じ考え方
5. **プロフィールのタグ**（`` + 25文字）を設定に足す。コストはプロンプトの数トークンだけ（KV は使い回せる）。効果は未知で、評価セットが要る
6. **最後のトークンを decode しない・softmax を取らない**: いまの `log_prob` は、各トークンで全語彙（xsmall の語彙は数千程度、推測）を2回走査する。並べ替えには正規化が要るので、softmax は今のまま残す。3 の検証側では argmax だけにする
7. **学習の語の扱い**（先頭のトークンだけで判断＋加点）は、3 を入れるときに必ず一緒に入れる。入れないと学習した語が LM に棄却され続ける。Kanade の「動かして選んだときだけ学習」はそのまま使える
8. **個人化 LM**（5-gram、代理チューニング）: 毎トークン、全語彙について n-gram の確率を引くので、CPU では重い（AKKC も個人化があると全メモ化を切る）。Android ではまず保留が妥当
9. **次に打つかなの予測**（最大10文字の生成、エントロピーで停止）: 生成は 1トークン=1 decode なので、Fold 7 で 1 文字あたり数 ms とすると、10文字で数十 ms（生成 1 件 12ms からの推測）。予測の候補の並びの問題（「しゅうせ」→「修せよ」）には、むしろ予測の候補（辞書の上位数件）を zenz の並べ替えにかける方が安い。いまの採点の関数で、候補 5 件ならおよそ 15〜20ms（推測）
10. **スレッド数**: AKKC は Apple 以外では「論理 CPU − 1〜2、上限8」。Fold 7（8コア、大中小）で Kanade は固定の 4 スレッド。性能コアの数に合わせる（Apple の「クラスタを跨がない」の考え方）と、短い decode の待ちが減るかもしれない。設定を変えて測るだけで済む

### Gaps
- Android の CPU で「検証1回」と「並べ替え10件」の時間の比は、測っていないので分からない。上の数字はすべて推測。
- Kanade の評価セット（102 文）で、N-best の上位10件の外に正解があった件数は確かめていない（あれば、3 の効果の上限が分かる）。
