# AzooKeyKanaKanjiConverter の非ニューラル変換の工夫（zenz 以外）

調べた時点: 2026-11-08。コードは `azooKey/AzooKeyKanaKanjiConverter` main の commit 5a9c4f6（2026-11-07）、iOS アプリ `azooKey/azooKey` main（2026-11-08）、辞書 `ensan-hcl/azooKey_dictionary_storage` main（2026-05-10、"beta v3.1.0-beta.x"）を浅く clone して読んだ（実行はしていない）。以下、リンク先の `blob/main/...` は上記の commit 時点の内容。

略記: AKKC = AzooKeyKanaKanjiConverter（変換エンジン）。コストの値 `value` は「大きいほど良い、負の対数尤度風の Float16」（後述）。

---

## 1. 辞書は何で、どう保存・検索され、どれくらいの大きさで、どう更新されるか（LOUDS）

### Takeaway
NEologd と SudachiDict を元に、独自の語の追加とコストの調整をした辞書（Apache-2.0）を使う。読みのトライを LOUDS に事前コンパイルし、読みの先頭1文字ごとに分けたファイルを必要時に読み込む。私が数えたところでは約 56.6 万語・約 35MB で、外部の集計とも一致した。辞書はアプリに同梱されていて、辞書を作るツールは完全には公開されていない。

### Cited Findings
- 出典: 「辞書のライセンスはApache License Version 2.0です。NEologdとSudachiDictをベースとし、独自にエントリを追加し、コストを調整して用いています」。独自エントリは「新語、人名、絵文字、顔文字、単漢字、一般的だが漏れている語など」— [Zenn: azooKeyをOSSにした (2023-02-05)](https://zenn.dev/azookey/articles/abeeb27c248e22)
- 生成: 「事前にトライをLOUDSとしてコンパイルした辞書ファイルを実行時に動的に読み込む」。「辞書生成モジュールは完全にはOSS化しておらず、現状はコンパイル済みの辞書ファイルのみ配布」。頭文字ごとに分けた辞書を必要に応じて読む（メモリ対策）— [同記事](https://zenn.dev/azookey/articles/abeeb27c248e22)
- 2026-10〜11 に、有料アプリへ同梱する人たちが Issue で元データの版や表示義務（NEologd・SudachiDict・Wikipedia 由来の word2vec など）を尋ねた。メンテナは LICENSE と README へのリンクを示し、「配布者ご自身でご確認ください」と答えて Issue を閉じた。元データの内訳は答えていない — [Issue #364](https://github.com/azooKey/AzooKeyKanaKanjiConverter/issues/364)
- 絵文字辞書は別リポジトリで、Mozc（BSD）・Unicode/CLDR・独自分（MIT）を含むと質問者が述べている — [Issue #364](https://github.com/azooKey/AzooKeyKanaKanjiConverter/issues/364)
- 1語の型 `DicdataElement` = word（表記）, ruby（カタカナの読み）, lcid / rcid（左右の連接ID。連語では左右で違う）, mid（意味ID）, baseValue（Float16）, adjust（誤り訂正などのための動的な調整）— [Docs/dicdata_format.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/dicdata_format.md)
- ファイルの構成: `.louds`（LOUDS のビット列）、`.loudschars2`（各ノードのラベル。Unicode ではなく1バイトの CharID）、`.charID`（文字→ID の表）、`.loudstxt3`（各ノードの語のデータ）— [dicdata_format.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/dicdata_format.md)
- 引く手順: 起動時に charID を1回読む → クエリを ID 列にする → クエリの先頭文字に対応する louds と loudschars2 を読み、`LOUDS` 構造体にしてキャッシュ → ノード番号を列挙 → 先頭文字に対応する loudstxt3 から該当ノードを読む（loudstxt3 はキャッシュせず、毎回 IO）— [dicdata_format.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/dicdata_format.md)
- loudstxt3 の中身: 先頭に UInt16 のエントリ数と UInt32 のオフセット表。各エントリは「UInt16 の件数、1件あたり10バイト（lcid・rcid・mid が UInt16、score が Float32）、そのあとタブ区切りの文字列」— [extension LOUDS.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DataStructure/extension%20LOUDS.swift), [LearningMemory.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/LearningMemory.swift)
- 連接コスト: 品詞バイグラム（CID、ipadic の品詞＋独自に足した 1316 EOS・1317「？」・1318「！」で計1319）は疎な行列なので専用の形式（`cb/` 以下）。意味ID の連接（MID、502種）は密な行列なので `mm.binary` にそのまま入れている — [dicdata_format.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/dicdata_format.md), [failures.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/failures.md), [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift)
- サイズ（自分で測った値）: 辞書リポジトリの `Dictionary/` は計 35MB。内訳は louds/ 23MB（louds 160・loudschars2 160・loudstxt3 422 ファイル、ファイル名は先頭文字のコード）、cb/ 5.4MB（1320ファイル）、p/ 5.2MB（1320ファイル）、mm.binary 988KB。loudstxt3 を数えると、トライのノード 864,256、語を持つ読み 297,755、語 566,214 — [azooKey_dictionary_storage](https://github.com/ensan-hcl/azooKey_dictionary_storage)
- 外部の集計でも「azooKey 側 566,214 語」（Mozc の語 707,915 を足して計 1,274,129 語にした派生辞書）— [hajime-dictionary](https://github.com/KikuchiTomo/hajime-dictionary)
- 将来の構想: システム辞書を任意のディレクトリから読めるようにしたので、語彙の多い辞書の選択、アプリと別の定期更新（Background Assets）、外部の辞書が可能になる。ただ約100MB の配信費用と、更新を続けられる体制が問題で、今は見送っている — [Docs/Visions/dictionary.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/Visions/dictionary.md)
- 「辞書はアプリとバンドルしているが、個別に更新できるよう切り離した方が開発が楽」— [azooKey docs/advice_for_azooKey_based_development.md](https://github.com/azooKey/azooKey/blob/develop/docs/advice_for_azooKey_based_development.md)
- 本人たちが認める失敗:
  - コストが Float16（対数尤度をそのまま使い続けた名残）。足し算が遅く、macOS では扱いにくい。作り直すなら UInt16 か UInt8 にする。
  - CharID の割り当てが雑。
  - 品詞体系が ipadic。作り直すなら UniDic を使う。
  - いずれも直すと、ユーザ辞書・学習データの作り直しが要る。
  — [Docs/failures.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/failures.md)
- 定数: 辞書の語の最大長は 20。スコアが `threshold = -17` 以下の語は積極的に捨てる。長さに応じたペナルティは `-2.0 / 表記の文字数` — [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift)

### Inferences
- 先頭1文字で分けるのは、メモリの少ない iOS キーボード拡張で、使う分だけ mmap・読み込みするための設計とみられる。loudstxt3 を毎回 IO で読む代わりに、常に置いておくメモリを小さくしている。
- 辞書のコストは「頻度（ユニグラム）＋品詞の連接（バイグラム）＋意味ID の連接（内容語のバイグラム）」の3層。Mozc 型の統計的変換に、意味の共起を足した形。

### Gaps
- NEologd と SudachiDict の版、コストの推定に使ったコーパスは公開資料に見つからなかった（Issue #364 でも答えていない）。
- 辞書の更新は、辞書リポジトリへのコミットとアプリの版上げでしか確認できず、更新の頻度や方針の文書はない。
- `.loudschars2` `.charID` と CID 重みのバイナリ形式は、Docs が「TBW」（未記述）。

---

## 2. 変換の中心（ラティス・連接コスト・差分計算）

### Takeaway
Viterbi で N-best を作る。品詞バイグラムに加え、文節に分けたあと「内容語バイグラム」（意味ID の連接）を足して、共起する語を優遇する。入力の「1文字追加・1文字削除・末尾の置換・変化なし・文節確定後」で別の差分アルゴリズムを使い、前回のラティスを使い回して速くしている。

### Cited Findings
- 「文節単位に分割したあと、『内容語バイグラム』とでもいうべき追加のコストを計算」し、「共起しやすい語」が並べば評価が上がり、共起しにくい語なら下がる — [Docs/conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 文節の切れ目は、品詞を前置機能語・内容語・後置機能語に分けて判定する。切れ目にならないのは「前置機能語→内容語」と「内容語→後置機能語」の2つだけ（`isClause`）。意味ID が 500 のときは連接の値を 0 にする — [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift)
- `convertToLattice` の場合分け:
  - 前回の入力がないとき: `kana2lattice_all`
  - 同じ入力のとき: `kana2lattice_no_change`（キャッシュから戻すだけ）
  - 文節確定のあと: `kana2lattice_afterComplete`
  - それ以外: 差分 `differenceSuffix` を `kana2lattice_changed` へ（末尾の変わった文字につながるノードだけ消して足し直す）

  — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift), [SuffixReplacementProcessing.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/SuffixReplacementProcessing.swift), [PrefixCompletionProcessing.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/PrefixCompletionProcessing.swift)
- 二重インデックスラティス（v0.9〜）: ローマ字入力 `ittai` の部分列からは「イッ」ちょうどの読みを作れない（`it`=イt、`itt`=イッt）。それでは「行っ」のような語を引けない。そこで表層文字列（イッタイ）のインデックスと、入力の履歴（ittai）のインデックスを混ぜた。通常の変換は表層側、誤り訂正は入力側を使う — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 先頭の表記を固定する変換（`FullInputProcessingWithPrefixConstraint`）: 固定した表記は、学習データやユーザ辞書から来たノードにも制約としてかける — [FullInputProcessingWithPrefixConstraint.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/FullInputProcessingWithPrefixConstraint.swift)

### Inferences
- 1打ごとの再計算を差分で済ませるのが、ライブ変換と予測を毎打鍵で回せる前提になっている。

### Gaps
- MID（意味ID、502種）をどう作ったか（word2vec のクラスタリングか等）は、資料では確認できなかった。Issue #364 で word2vec の利用を推測する質問はあったが、答えはない。

---

## 3. 学習（保存の形・重み・忘れ方）

### Takeaway
学習した語は、ユーザ辞書と同じ LOUDS 形式の「別の辞書項目」として持つ。使った回数と読みの長さから固定のスコアを計算する。回数は32日ごとに半分になり、0 で消える。上限件数を超えると古いものから消す。スコアはおよそ -1〜-8 で、普通の辞書語（-2〜-17）より上に来やすい。作者自身も「長い候補では強すぎる」と書いている。

### Cited Findings
- 2層の記憶: 一時記憶（キーボードを開いてから閉じるまで、メモリ上）と長期記憶（ファイル）。検索は両方を引き、普段は一時記憶だけを更新する。閉じるときに長期記憶へマージする（重い処理なので閉じるときだけ）— [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 学習の単位は3つ: ①単語、②文節、③候補全体。例「この本を本屋で買った」→ ①本・本屋・買っ、②この本を・本屋で・買った、③全文 — [同上](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 「元の単語とは別の表現として新たな辞書項目を追加し、計算時にそちらが優先的に選ばれるようコストを調整」— [同上](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- スコアの式（`valueForData`）: `value = -1 - 4/読みの文字数 - 3*(1 - count/255)^3`。count は UInt8（上限255）。1回使った3文字の語なら約 -5.3、多く使うほど -1 に近づく。一時記憶では `adjust` を使い、このスコアになるよう語を上書きする — [LearningMemory.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/LearningMemory.swift)
- 比べると、辞書の語は例えば「この」-3.35、「また」-3.87（`p/pc_0.csv`）で、-17 以下は捨てられる — [azooKey_dictionary_storage](https://github.com/ensan-hcl/azooKey_dictionary_storage), [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift)
- 忘れ方: メタデータ（最後に使った日・最後に更新した日・回数）を持ち、マージのたびに「32日ごとに回数を半分」にする。0 になったら消すので、1回使えば32日、2回で64日、4回で128日残る。上限（`maxMemoryCount`、例 65536）を超えると古いものから消す。最後に使ってから128日を超えるなど、日付がおかしいものも捨てる — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md), [LearningMemory.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/LearningMemory.swift), [Docs/learning_data.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/learning_data.md)
- ファイル: `memory.louds` / `memory.loudschars2` / `memory.loudstxt3` / `memory.memorymetadata`。壊れにくく更新する手順:
  1. `.2` 付きのファイルに書く
  2. `.pause` を置く
  3. `.2` を本来のファイルへコピーする
  4. `.pause` を消す

  `.pause` が残っていれば学習を止め、次に開いたときに3以降をやり直して直す — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 学習のリセット: 候補を長押しすると「この候補の学習をリセット」。`forgetMemory`、全消去は `resetMemory` — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md), [learning_data.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/learning_data.md)
- モード: `inputAndOutput`（使う・更新する）／`onlyOutput`（使うだけ）／`nothing`。言語ごとに学習のディレクトリを分けられる — [LearningType.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/LearningType.swift), [learning_data.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/learning_data.md)
- 作者の反省・構想: 「現在の学習は、特に長い候補において強すぎるため、弱くした方が良いかもしれない」。ほかに、プライベート／ゲストモード、バックアップ、誤変換の「修正」の自動検出 — [Docs/Visions/learning.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/Visions/learning.md)
- 利用者の声（2022）: ライブ変換中の誤変換まで学習してしまい、学習のリセットが要る — [Jazzと読書の日々 (2022-11-08)](https://wineroses.hatenablog.com/entry/2022/11/08/165930)

### Inferences
- スコアの式は、文脈（連接コスト）を見ずに「使った」という事実を強く効かせる。だから一度確定した長い文節や全文が、別の文脈でも勝ちやすい。Kanade で真似るなら、候補全体の学習を弱めるか、全文が一致したときだけ使うのがよさそう。

### Gaps
- 「上位何件まで学習を優先するか」のような、順位での上限はない（コストの比較だけ）。忘れ方を変えた理由などの記録は見つからなかった。

---

## 4. 誤り訂正（typo correction）とコストの抑え方

### Takeaway
入力（`ComposingText.input`）への置換ルールを、ソースコードに直接書いている。フリックの濁点忘れ（た→だ）や、ローマ字の隣のキーの打ち間違い（ts→ta）など。ペナルティを足していき、上限を超えたら打ち切る。さらに辞書のトライと並べて探索し、辞書にない接頭辞になった時点で枝を刈る。助詞・助動詞はペナルティを2.5倍にする。既定で有効なのは iOS だけ。

### Cited Findings
- 仕組み: 「ts」を一定のペナルティで「ta」と読み替える、「た」を「だ」と読み替える、など。ナイーブに組み合わせると爆発する（「た」が10個で1024通り）ので、ペナルティの合計に上限を設ける。v0.9 以降は辞書の検索と並行して行い、トライにノードがない接頭辞の訂正は早めに刈る — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 具体的な値: `maxPenalty = 3.5 * 3`（標準の重み 3.5 のルールなら3回まで）。対象は1文字か2文字の並び（`lengths = [0, 1]`）。
  - 直接入力（フリック・かな）の表: カ→ガ（7.0）、キ→ギ、タ→ダ（6.0）、ツ→ッ（6.0）・ヅ（4.5）、ト→ド（4.5）、ハ→バ（4.5）・パ（6.0）、ヒ→ビ・ピ（4.5）、バ→パ、ヤ→ャ、ユ→ュ、ヨ→ョ など。重みを書いていないものは 3.5。
  - ローマ字の表（いずれも 3.5）: bs→ba、no→bo、li→ki、lo→ko、lu→ku、my→mu、tp→to、ts→ta、wi→wo、pu→ou。

  — [TypoCorrection.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/TypoCorrection.swift)
- ペナルティのかけ方: `adjust = (-2/文字数)/2 × penalty × ratio`。ratio は助詞（lcid 147〜368）・助動詞（369〜554）で 2.5、それ以外は 1。足したあと閾値（-17 と長さの補正）を下回れば候補から外す — [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift)
- 例: 基礎コスト -10 の「大学生」を「たいがくせい」から訂正で得たら、adjust -3 で合計 -13 として扱う — [dicdata_format.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/dicdata_format.md)
- 既定: `TypoCorrectionMode.automatic` は iOS でだけ有効。macOS など他では無効 — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift), [ConvertRequestOptions.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/ConvertRequestOptions.swift)
- ルールを当てるのは、範囲が直接入力だけ、またはローマ字だけのとき。カスタムの入力表（`.mapped`）や混ざった入力では当てない — [TypoCorrection.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/TypoCorrection.swift)
- 候補の並べ方での保険: 上位3件のうち最低1つは「誤り訂正ではなく、入力に完全一致する候補」にする — [KanaKanjiConverter.swift processResult](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 別系統として、言語モデルを使う実験的な訂正の API（`experimentalRequestTypoCorrection`、`ZenzaiTypoCandidateGenerator.swift` 約1550行）もある。これは zenz の担当の範囲 — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)

### Inferences
- 表が小さいので、フリックでよくある「濁点・半濁点・小書きの付け忘れ」と、QWERTY の隣のキーの数組に絞っている。物理キーボードでは誤りの種類が違うので、既定でオフにしているとみられる。Kanade のテンキー（フリック）には、直接入力の表がそのまま参考になる。

### Gaps
- 重み（3.5、4.5、6.0、7.0）をどう決めたかの資料はない。

---

## 5. 予測変換と、確定後の次の語の予測

### Takeaway
入力中は、最後の1〜2文節（と入力全体）を読みで前方一致検索して伸ばす。クエリが1文字のときは、辞書を作る時点で並べておいた一覧を使う。確定後は ①末尾の品詞の後に来やすい語（ゼロヒント）、②複合語の残りの部分（内閣→総理大臣）、③確定した語に合う絵文字、を混ぜる。前の打鍵で出した予測を、まだ合う限り残す「安定化キャッシュ」もある。

### Cited Findings
- 入力中: 「最後の数文字をクエリとして辞書で前方一致検索」し、コスト的に問題なければ足す。1文字だけのクエリは重いので、辞書の生成時に「あ」から始まる予測候補を列挙しておく（`p/p_あ.csv` の類）。ローマ字の途中（「r」だけ）でも、ら・り・る・れ・ろ のそれぞれで予測する — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 実装（`getPredictionCandidate`）: 一番良い候補の最後の文節で予測し、次に最後の2文節をまとめたもので予測する（各 N_best=5）。さらに入力全体を1つの文節として予測する。上位3件まで — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 安定化: 前回の予測候補をキャッシュし、今の入力と両立する（伸ばした入力が候補の読みの接頭辞のままの）候補を優先して残す。両立しなくなったら捨てる（`stablePredictionCandidates` / `mergeStableCandidates`）— [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 確定後の予測:
  1. 末尾の品詞に基づく予測: `pi_1285.csv` のような「品詞1285 の後に来やすい語」の表を使う（名詞の後なら「が」「は」）。
  2. 末尾の単語からの予測: 辞書には「内閣」と「内閣総理大臣」の両方がある。「ナイカク」で前方一致検索し、表記が「内閣」で始まる語の残りを出すので「総理大臣」が予測される。
  3. 絵文字: 確定した「悲しい」で絵文字を引いて😭を出す。

  — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 実装の細かい点（`requestPostCompositionPredictionCandidates`）:
  - ゼロヒント・予測とも N_best=15 で作る。
  - ゼロヒントのうち助詞は最大3件まで。
  - 絵文字は最大3件（value -3）。
  - 合計がおよそ10件になるよう、残りを予測とゼロヒントで分ける。
  - 句点を確定したら、確定後の予測を終える。

  — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift), [PostCompositionPredictionCandidate.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/PostCompositionPredictionCandidate.swift)
- 予測を選んだときの扱い: `acceptPredictionCandidate` で候補の残りの読みを入力に足し、そのあとの変換を、選んだ表記で始まるものに制約する（この制約は Zenzai のときだけ）。予測を選んだときは、学習も `updateLearningData(_:with:)` で別に扱う — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 英語の予測: OS のスペルチェッカー（UITextChecker）の補完を使う（penalty -5）。補完は高くつくので、変換器の間で NSCache を共有する。ローマ字入力で入力がすべて ASCII なら、英語の候補を上位に足せる（`englishCandidateInRoman2KanaInput`、penalty -10）。英字の読みを持つ辞書の語は、小文字の入力でも大文字に一致させる — [SpellChecker.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpellChecker.swift), [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 2024年3月の開発記事: 予測変換を強化し、確定後にも予測を出すようにした — [Zenn: azooKeyの開発近況（2024年3月末）](https://zenn.dev/azookey/articles/e9c4fcd09535e9)

### Inferences
- 確定後の予測は言語モデルを使わず「品詞の後に来る語の表＋複合語の分解＋絵文字」でできており、端末上で軽い。Kanade の「予測の候補」にそのまま移しやすい。

### Gaps
- `pi_*` / `pc_*` の表（p/ の 1320 ファイル）をどう作ったか（コーパス・件数）は資料がない。

---

## 6. ライブ変換と、その安定化

### Takeaway
エンジン側は単純で、「予測を除いた完全一致の候補のうち一番上」を表示するだけ。ちらつきを抑える工夫はアプリ側（`LiveConversionManager`）にある。①カーソル位置が1文字以下の間や、入力全体を覆う候補がない間は、ひらがなのまま出す。②最後の語の読みが1文字なら、その語はひらがなに戻す。③先頭の文節の変換結果の履歴を取り、N回続けて同じなら自動で確定する（自動確定。既定は「弱い」で N=16）。

### Cited Findings
- 「ライブ変換のない場合と同様に変換候補をリクエストし、『（予測変換ではなく）完全一致変換の中で最も順位が高いもの』をディスプレイ」— [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)。2023 の記事でも同じ説明で、モバイルへの移植としてはおそらく唯一の実装、とある — [Zenn (2023-02-05)](https://zenn.dev/azookey/articles/abeeb27c248e22)
- ライブ変換中はカーソル移動をあきらめ、編集状態を解く — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- `updateWithNewResults`: `convertTargetCursorPosition > 1` で、かつ読みの長さが入力全体と一致する候補があればそれを使う。なければ入力をそのまま（value 0 の名詞として）表示する。続く `adjustCandidate` で、最後の語の読みが2文字未満ならその語をひらがなに戻す。コードには「TODO: 最後の1単語のライブ変換を抑制したい」「TODO: ローマ字入力中に最後の単語が優先される問題」が残っている — [azooKey Keyboard/Display/LiveConversionManager.swift](https://github.com/azooKey/azooKey/blob/develop/Keyboard/Display/LiveConversionManager.swift)
- 自動確定（`candidateForCompleteFirstClause`）: 先頭の文節ごとに候補の履歴（`headClauseCandidateHistories`）を持つ。打鍵が足されたら追記し、削除なら最後を1つ落とし、置換なら落としてから追記する。直近 threshold 回の先頭の文節の表記がすべて同じなら、その文節を確定する。threshold は、無効=∞、弱い=16、普通=13、強い=10、非常に強い=6。既定は「弱い」。設定画面の説明は「自動確定を使うと長い文章を打っているときに候補の選択がしやすくなります」— [LiveConversionManager.swift](https://github.com/azooKey/azooKey/blob/develop/Keyboard/Display/LiveConversionManager.swift), [AutoCompletionStrengthSetting.swift](https://github.com/azooKey/azooKey/blob/develop/AzooKeyCore/Sources/AzooKeyUtils/KeyboardSetting/AutoCompletionStrengthSetting.swift)
- 自動確定のあとは、確定した文節を BOS として扱い、ラティスの残りを使い回す（`kana2lattice_afterComplete`）— [PrefixCompletionProcessing.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/PrefixCompletionProcessing.swift)
- `requestRichCandidates`（Zenzai の候補を増やす）は時間がかかるので、ライブ変換には勧めないと注記がある — [ConvertRequestOptions.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/ConvertRequestOptions.swift)

### Inferences
- 「先頭の文節が16打鍵続けて変わらなければ確定する」仕組みは、長文で先頭がちらつくのを根本から止める。誤った確定の害（学習も含む）を抑えるため、既定を弱くしているとみられる。前の節の「ライブ変換中の誤変換を学習してしまう」という声と合わせると、自動確定した文節を学習の対象にするかは要検討。

### Gaps
- macOS 版（azooKey-Desktop）にある遅延などの、別の安定化の工夫は今回読んでいない。

---

## 7. 候補の並び順・文節の候補・ひらがなとカタカナの保証

### Takeaway
候補は次の順に並ぶ。
1. 上位5件: 全文の変換・予測・英語・ユーザショートカット。うち上位3件に最低1つは入力に完全一致するもの。
2. 次の5件: 先頭の文節だけの候補（読みが長い順）。
3. そのあと: 先頭位置の辞書の語（長い順、評価順）。カタカナ・ひらがな・大文字・全角・半角は固定コストで混ぜ、5番目あたりに特殊な変換を差し込む。

### Cited Findings
- 文書での説明:「最初の5件: 完全一致または予測変換またはローマ字英語変換（ただし上位3件までに最低1つは完全一致が含まれる）／そこから5件: 文節単位変換／そこから: 全部ひらがな、全部カタカナ、全部大文字などの変換と前方一致で長い順・高評価順に辞書データを表示（5番目あたりでUnicode変換、西暦和暦変換、メアド変換、装飾文字などの特殊変換を挿入する）」。実装は「とても複雑な実装になっているため、改善したい」— [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- 実装（`processResult`）:
  - 文節の候補（`firstClauseResults`）は「読みの長い順、同じ長さなら評価順」の上位5件。文節を伸ばしたり縮めたりする UI に使える別の配列として返す。
  - 重複は表記で取り除く。
  - 確定した `completedData` を受けると、残りを変換し直す（文節ごとの確定）。

  — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 全体を1つにした候補とその固定コスト（`getAdditionalCandidate`）:
  - カタカナ: `-14 × カタカナらしさ`。プヴペィフ を含めば×0.5、ュピポ は×0.6、パォグーム は×0.7（文字の統計から、外来語らしさを見る）。
  - ひらがな: -14.5
  - 大文字: -14.6
  - 全角英数: -14.7
  - 半角カナ: -15
  - 全角英数と半角カナは、オプションでオンにする。

  — [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- ラティスの部品（`getWiseDicdata`）:
  - 漢数字の読み→数字（`getJapaneseNumberDicdata`、「シ」だけで四とする字句解析）。
  - 前後が数字でなければ、数字を -14 の候補に、漢数字（1E12 以下）を -16 の候補にする。
  - 英字の入力は英単語として -14。
  - 1文字の入力は、ひらがな -13・カタカナ -14。
  - 記号の全角と半角の切り替え（-14 から5ずつ下げる）、似た記号のグループ（☆★♡、通貨記号、→⇒ など）、異体字（高/髙、崎/﨑、吉/𠮷）。

  — [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift), [JapaneseNumber.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/JapaneseNumber.swift)

### Inferences
- 「ひらがな・カタカナを必ず出す」は、ランキングの外で順位を保証しているのではない。固定コスト（-14 前後）を持つ候補として混ぜ、さらに後ろの「全部ひらがな…」の区画に置いている。だから必ず一覧の中にはある。

### Gaps
- 文節の区切りを伸ばしたり縮めたりする UI はアプリ側にあるはずだが、今回は追っていない（エンジンは `firstClauseResults` と、確定した文節のあとの再計算までを用意している）。
- 「再変換」（確定済みの文字を変換し直す）を、エンジンの公開 API としては確認できなかった。

---

## 8. 特殊な変換（日付・時刻・数・ローマ数字・絵文字・顔文字・全角半角・英語のスペル）

### Takeaway
`SpecialCandidateProvider` という差し替えられる部品が7種ある（カレンダー、メール、装飾文字、Unicode、バージョン、時刻、3桁区切りの数）。ほかに辞書のテンプレート（`<date>` `<random>`）、絵文字の TextReplacer、OS のスペルチェッカーがある。

### Cited Findings
- 7種の部品（既定は `KanaKanjiConverter.defaultSpecialCandidateProviders`）— [SpecialCandidateProvider.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/SpecialCandidateProvider.swift)
  - 和暦と西暦の相互変換（令和元年の扱いを含む）— [CalendarCandidate.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/CalendarCandidate.swift)
  - 時刻: 3桁「930」→「9:30」、4桁「1230」→「12:30」（時は24まで、分は59まで、value -10）— [TimeExpression.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/TimeExpression.swift)
  - 3桁区切り: 整数部が4桁以上の数（小数1つまで）にカンマを入れる（value -10）— [CommaSeparatedNumber.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/CommaSeparatedNumber.swift)
  - メール: 入力が「@」で終わると、メールアドレスらしい候補を足す — [EmailAddress.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/EmailAddress.swift)
  - 装飾文字（英字を数学用の太字などの Unicode に）。今は英字だけ — [RomanTypographys.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/RomanTypographys.swift)
  - Unicode: 「uxxxx / U+xxxx」をその文字に — [Unicode.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/Unicode.swift)
  - バージョン: 「ばーじょん」でアプリのバージョン。Mozc の真似とコメントにある — [VersionCandidate.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/SpecialConversion/VersionCandidate.swift)
- テンプレート: 候補の表記に `<date format="yyyy年MM月dd日" type="western|japanese" delta=... deltaunit=...>` や `<random type="int|double|string" value=...>` を埋め、表示するときに展開する。既定の例は「今日」「明日」「和暦」「サイコロ」「乱数」「おみくじ」。確定済みのテンプレート部分は展開し直さない — [TemplateData.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/TemplateData.swift), [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)
- 絵文字と顔文字: 別の絵文字辞書（`azooKey_emoji_dictionary_storage`、サブモジュール）と `TextReplacer`。今の機能は「絵文字のバリエーションを表示すること」に限る。顔文字は本辞書の独自エントリにある — [TextReplacer.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/Replacer/TextReplacer.swift), [.gitmodules](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/.gitmodules), [Zenn (2023)](https://zenn.dev/azookey/articles/abeeb27c248e22)
- 全角と半角: 全体を全角英数・半角カナにした候補を、オプションで出す（前の節）— [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift)

### Inferences
- ローマ数字（Ⅳ など）の専用の変換は、エンジンのコードに見当たらなかった。辞書の語や記号のグループで扱っている可能性がある。

### Gaps
- ローマ数字や単位（㎏・㌔ など）の専用の変換は確認できなかった（辞書の中身までは調べていない）。

---

## 9. ユーザ辞書・共有の辞書・日本語と英語の混在・ローマ字の表

### Takeaway
ユーザ辞書は、キーボードの外（アプリ）でコンパイルした LOUDS（"user"）を共有のディレクトリから読む。ほかに、全文が一致したときだけ出る「ショートカット」と、実行時に渡す「動的ユーザ辞書」がある。入力は1打ずつ「どの方式で打ったか」を記録するので、日本語と英字の混在やカーソルの途中への挿入を正しく扱える。ローマ字の表は、標準・AZIK・JIS かな・US かなを内蔵し、TSV で独自の表を足せる。

### Cited Findings
- ユーザ辞書は `sharedContainerURL`（App Group）から "user" と "user_shortcuts" の LOUDS として読む。実行中に更新されうるので、"user" と "memory" はキャッシュから外す。`importDynamicUserDictionary(dicdata, shortcuts:)` は動的な辞書で、検索が遅いので件数を抑える。ショートカットは「読みに完全一致する場合のみ」使う — [DicdataStore.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift), [KanaKanjiConverter.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift), [learning_data.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/learning_data.md)
- ユーザ辞書の構想: 品詞を設定できるようにする、インポートとエクスポート、カッコでカーソルを中に入れるようなカスタム動作、ユーザが作った辞書を配る「Dictionary Market」— [Docs/Visions/dictionary.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/Visions/dictionary.md)
- 混在の入力: `ComposingText` は `input: [InputElement(文字, inputStyle: direct / roman2kana / mapped)]` を持つ。だから「k を日本語で打つ → a」は「か」、「k を英語で打つ → a を日本語で」は「kあ」と区別できる。「じ|ゃ」の間に u を打つと「じうゃ」になる（入力を `じ(direct) u(roman) ゃ(direct)` に組み替える）。iOS 標準の方式は「じゃう」になる — [conversion_algorithms.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/conversion_algorithms.md)
- ローマ字の表: `defaultRoman2Kana` / `defaultAzik` / `defaultKanaJIS` / `defaultKanaUS` を内蔵する。v0.10.0 からカスタム入力表（TSV）に対応し、`{any character}`（例 `n{any character}` → `ん{any character}`）や `{composition-separator}`（入力の末尾。例 `s{composition-separator}` → す）を書ける。誤り訂正は、末尾の不安定な部分の長さ（`maxUnstableSuffixLength`）を考えて辞書の枝刈りと組み合わせる — [Docs/custom_input_table.md](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/custom_input_table.md), [InputTables/](https://github.com/azooKey/AzooKeyKanaKanjiConverter/tree/main/Sources/KanaKanjiConverterModule/InputManagement/InputTables), [TypoCorrection.swift](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Sources/KanaKanjiConverterModule/DictionaryManagement/TypoCorrection.swift)
- azooKey はトグル入力（携帯打ち）と、なぞり入力に対応しない（エンジンにはひらがなを渡せるので問題はない）— [advice_for_azooKey_based_development.md](https://github.com/azooKey/azooKey/blob/develop/docs/advice_for_azooKey_based_development.md)

### Inferences
- 「入力の履歴」と「表層の文字列」を分けて持つのは、日本語と英字の混在・誤り訂正・二重インデックスラティスのどれにも効く土台になっている。Kanade の入力の状態を設計するときの参考になる。

### Gaps
- ユーザ辞書の語に既定で付くコストや品詞は、アプリ側で決めていて今回は確認していない。
