# 日本語の IME・キーボードの音声入力と、日本語の音声入力の決まりごと（Kanade との比較）

調べた日: 2026-11-08。Web の一次資料（各社のヘルプ）を優先し、取れなかったものは「空白」に書いた。Kanade 側は SPEC.md の「8. 音声入力」、docs/RESEARCH-voice.md、docs/USAGE.md、docs/decisions.md、コード（`core/.../voice/SpokenPunctuation.kt`、`whisper/.../WhisperModel.kt`、`app/.../KanadeInputMethodService.kt`）を読んだ。

## 1. 音声入力がある日本語のキーボードと、オンラインかどうか・画面・コマンド

### Takeaway
日本語の IME で「自前の認識」を持つのは、Simeji（クラウドの AI、整形とフィラー除去つき）と、Gboard（Pixel 6 以降の「高度な音声入力」は端末内）くらい。ATOK はどの版も OS や Google の認識を呼ぶだけで、Microsoft IME の音声は Windows の「音声入力（Win+H）」で、クラウド（Azure）が要る。azooKey は音声入力の情報が見つからなかった。完全に端末内で、モデルを選べて、押している間だけ録る IME は、調べた範囲では Kanade のほかにない。

### Cited Findings
**Gboard（Google）**
- Pixel 6 以降の「高度な音声入力」は日本語に対応。自動の句読点（設定で切れる）、絵文字の名前を言う（「笑顔の絵文字」）、編集のコマンド「最後の単語を削除して」「消去して」（最後の文）「すべて消去して」「次へ」（次の入力欄）「止めて」、「送信して」で送信 — [Gboard ヘルプ](https://support.google.com/gboard/answer/11197787?hl=ja)
- 画面: 入力中はカラフルなマイクのボタン。タップ・キーボードを閉じる・「止めて」で止まる。ダブルタップでマイクを点けたまま続けて何通も送れる。情報のアイコンでコマンドの一覧 — [Gboard ヘルプ](https://support.google.com/gboard/answer/11197787?hl=ja)
- 処理は端末内。修正などの機能を使うときを除き Google のサーバーへ送らない — [Gboard ヘルプ](https://support.google.com/gboard/answer/11197787?hl=ja)
- Pixel 以外の Android の Google 音声入力では、「とうてん」で「、」、「まる」か「くてん」で「。」、「かいぎょう」か「あたらしいぎょう」で改行（ページは文字化けしていて、読みは再構成） — [@IT Tech TIPS](https://atmarkit.itmedia.co.jp/ait/articles/2107/19/news027.html)
- 同じ記事: Simeji では Simeji の音声入力が使えない場面があり、Gboard などに切り替えて Google 音声入力を使う、という扱い — [@IT](https://atmarkit.itmedia.co.jp/ait/articles/2107/19/news027.html)
- Pixel の音声入力は RNN-T でストリーミングし、別のモデルで書き換える2段構え（Kanade の調べ） — `docs/RESEARCH-voice.md`、[Google AI Blog](https://ai.googleblog.com/2019/03/an-all-neural-on-device-speech.html)

**Simeji（Baidu 系）**
- 2017 年に Android で「AI 音声入力」を始めた。Baidu Brain の深層学習の認識。句読点は話して入れる（「。」と言う）。感情の言葉のあとに顔文字を候補に出す。オンラインかの明記はないが Baidu Brain 頼みなのでクラウドとみられる — [ITmedia Mobile 2017](https://www.itmedia.co.jp/mobile/articles/1706/14/news133.html)
- 今の Simeji（PC 版、Mac・Windows）は AI 音声入力で「えーっと」「あの」などの言い淀みを除き、言い直しを直し、チャットはくだけた調子・メールは丁寧に整形、まとまりのない話を整理、方言を標準語に。小声入力、リアルタイム翻訳（100 以上の言語）、よく使う語・専門用語を覚える辞書 — [Simeji 公式](https://simeji.me/)
- PC 版の AI 音声入力の β は 2026-06-29。押して始め、もう一度押して終わる（長押し不要）。レビューでは「IME というより AI 音声入力ソフト」 — 検索結果の要約から（[Simeji PC のダウンロード](https://simeji.me/download-pc)、[note のレビュー](https://note.com/jaga_farm/n/ne4ebbd324844?hl=en)）。原文は読めていない
- 利用者の不満: 頼んでいない箇条書きに変えられる、誤反応が多い、AI の機能を個別に切れない — [Yahoo!知恵袋](https://detail.chiebukuro.yahoo.co.jp/qa/question_detail/q11330660191)

**ATOK（ジャストシステム）**
- ATOK for Android [Professional] は、キーボードから Google 音声入力を呼べるようになった（自前の認識ではない）。時期は ATOK Passport の履歴で 2025 年 12 月とする要約と、2026 年 2 月とするブログで食い違う。買い切りの旧版にはボタンが出ない。[Professional] は ATOK Passport プレミアム（月 660 円・年 7,920 円）が要る — [shimajiro@mobiler](https://shimajiro-mobiler.net/2026/08/27/post88089/)、[ATOK 更新履歴](https://atok.com/history/)、[利用者の X 投稿 2025-12-16](https://x.com/10cube/status/2000833300278206720)
- 一太郎 2024 は Windows 11 の音声認識で音声入力を足し、ATOK の変換精度を使う。「それを変換」と言うと直前の入力を ATOK で再変換する。削除・選択のコマンドもある（完全な一覧は記事にない） — [PC Watch](https://pc.watch.impress.co.jp/docs/news/1551111.html)、[ITmedia](https://www.itmedia.co.jp/pcuser/articles/2312/01/news106.html)
- 一太郎 2025 で音声入力に句読点の自動挿入が加わった — [ITmedia PC USER](https://www.itmedia.co.jp/pcuser/articles/2412/02/news113.html)（検索結果の要約から）
- 古い FAQ（2009）では、ATOK 自体に音声入力はなく ViaVoice などの外部ソフトを使う — [ジャストシステム FAQ 045895](https://support.justsystems.com/faq/1032/app/servlet/qadoc?QID=045895)

**Microsoft（Windows の音声入力、Microsoft IME と組で使う）**
- Win+H かタッチキーボードのマイクで始める。Azure Speech のオンライン認識なのでネットが要る。設定は自動の句読点・不適切な言葉のフィルター・マイク・待つ時間・ランチャー。エコー除去あり — [Microsoft サポート](https://support.microsoft.com/ja-jp/windows/%E9%9F%B3%E5%A3%B0%E5%85%A5%E5%8A%9B%E3%82%92%E4%BD%BF%E7%94%A8%E3%81%97%E3%81%A6-pc-%E3%81%A7%E9%9F%B3%E5%A3%B0%E3%82%92%E3%83%86%E3%82%AD%E3%82%B9%E3%83%88%E3%81%AB%E5%A4%89%E6%8F%9B%E3%81%99%E3%82%8B-fec94565-c4bd-329d-e59a-af033fa5689f)
- 日本語の編集コマンド: 「それを削除／それを消去／それを取り消す」（直前の語）、「それを選択」、「Enter キーを押す」「BackSpace」「タブ」「Space を挿入」、止める「音声入力を停止」「聞き取りを停止」など。選んでいるときに削除と言うと選択範囲を消す — 同上

**Apple（iPhone）**
- 記号は文と一緒に言う。「かぎかっこ／かぎかっことじ」「まる」「てん」「かいぎょう」「びっくりマーク」「はてなマーク／クエスチョンマーク」「なかぐろ」「スラッシュ」「アットマーク」「ドット」「円記号」、顔文字「ニコニコマーク」「ウィンク」。カーソルを動かしてからマイクを押すとその位置に入る — [iPhone Mania](https://iphone-mania.jp/manual/preparation-148605/)（古い版の記事。ネットが要り、内容が Apple に送られる、と書いている）
- iOS 16 で自動の句読点（日本語も）、絵文字の音声入力（A12 以降）、音声入力中もキーボードが出たまま — [PhileWeb](https://www.phileweb.com/review/column/202210/19/1867.html)（検索結果の要約から）。iOS 16 以降は端末内（Kanade の調べ、`docs/RESEARCH-voice.md`）

**azooKey**
- iOS 版・macOS 版の紹介（ライブ変換、Zenzai、カスタムキー、OpenAI API の「いい感じ変換」「つづき」）に音声入力の記述はない — [App Store](https://apps.apple.com/app/id1542709230)、[ITmedia 2025-07](https://www.itmedia.co.jp/news/article/2507/17/1250717051/2)

**音声専用のキーボード（参考）**
- Typeless（Android にもある）: 普通のキーはなく大きなマイク。タップで話し、もう一度タップ。止めて 1〜2 秒で入る。フィラー（「あー」「えーと」）除去、言い直しは最後の意図だけ残す、宛先のアプリで調子を変える（WhatsApp はくだけて、Gmail・Slack は丁寧に）、入れたあとに声で部分を直す編集ボタン、入力欄がなくても保存してコピーできる — [ライフハッカー・ジャパン](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/)
- 比較記事では Typeless・Aqua Voice・Wispr Flow・Hanaseru がフィラー除去や言い直しの処理を持ち、Gboard や Apple の標準の音声入力にはそれがほぼない、とする。比較記事の多くは売り手が書いている — [start-link](https://start-link.jp/hubspot-ai/ai/ai-tools/voice-input-tools-comparison)、[catorce](https://catorce.jp/st/knowledge-voice-input-ai.html)（検索結果の要約から）

### Inferences
- 日本語の「キーボードの中の音声」は (a) OS や Google の認識を呼ぶだけ（ATOK・Microsoft IME・旧 Simeji）、(b) 端末内のストリーミング（Gboard の Pixel 版・iOS）、(c) クラウドの LLM で整形（Simeji の新しい AI 音声入力・Typeless など）の3つに分かれる。Kanade は (b) をユーザーが選べるモデルで行う珍しい立ち位置
- 押して話す・もう一度押す（トグル）が主流で、「押している間だけ」（Kanade の 8-1）は少数派。ただし Kanade は短く押すと聞き続ける（8-3）のでトグルも持つ

### Gaps
- Microsoft IME そのものに独自の音声機能があるかは見つからなかった（Windows の音声入力に任せている、と見られる）
- Gboard の Pixel 以外の端末での日本語の音声がオフラインで動くか（オフラインの音声パックの日本語）、2026 年の最新は確かめられなかった
- Apple の公式の日本語コマンド一覧（support.apple.com の iph3bf19d7b9）は本文が取れなかった。iOS 26 で変わったかも未確認
- Shellboard などほかの日本語キーボードの音声入力は情報が見つからなかった
- Simeji のスマホ版の今の音声入力が、PC 版と同じ整形・フィラー除去を持つかは公式ページで確かめられなかった（ページは PC 版の説明が中心）

## 2. 日本語の音声のコマンドで標準になっている言葉

### Takeaway
句読点は「てん／とうてん／読点」「まる／くてん／句点」、改行は「かいぎょう／改行」（Google は「あたらしいぎょう」、Windows は「次の行」「新しい段落」も）、かぎかっこは「かぎかっこ」「かぎかっことじ」が各社共通。Windows は「かぎかっこ開く」「二重かぎかっこ」まで持ち、記号の語彙が一番多い。編集のコマンドは Google が「〜して」の命令形、Windows が「それを〜」の形。

### Cited Findings
- Windows の日本語の記号の言い方（抜粋）: 読点、句点・くてん、びっくりマーク・感嘆符・エクスクラメーションマーク、疑問符・はてなマーク・クエスチョンマーク、三点リーダー、セミコロン、ハイフン・マイナス記号、番号記号、ドル記号、パーセント記号、アンパサンド、かっこ・かっこ開く・左かっこ／かっことじ・かっこ閉じる・右かっこ、アスタリスク、アットマーク、角かっこ・大かっこ、かぎかっこ開く・左かぎかっこ／かぎかっこ閉じる・右かぎかっこ、二重かぎかっこ開く／閉じる、改行は「次の行・新しい段落・改行・かいぎょう」、円記号、ポンド記号、ユーロ記号、プラス記号、小なり記号、等号、大なり記号、プラスマイナス、度記号 — [Microsoft サポート](https://support.microsoft.com/ja-jp/windows/%E9%9F%B3%E5%A3%B0%E5%85%A5%E5%8A%9B%E3%82%92%E4%BD%BF%E7%94%A8%E3%81%97%E3%81%A6-pc-%E3%81%A7%E9%9F%B3%E5%A3%B0%E3%82%92%E3%83%86%E3%82%AD%E3%82%B9%E3%83%88%E3%81%AB%E5%A4%89%E6%8F%9B%E3%81%99%E3%82%8B-fec94565-c4bd-329d-e59a-af033fa5689f)
- iPhone: まる・てん・かいぎょう・かぎかっこ・かぎかっことじ・びっくりマーク・はてなマーク・なかぐろ・スラッシュ・アットマーク・ドット・円記号・ニコニコマーク・ウィンク — [iPhone Mania](https://iphone-mania.jp/manual/preparation-148605/)
- Google: とうてん、まる／くてん、かいぎょう／あたらしいぎょう — [@IT](https://atmarkit.itmedia.co.jp/ait/articles/2107/19/news027.html)
- 編集: Google「最後の単語を削除して」「消去して」「すべて消去して」「次へ」「止めて」「送信して」 — [Gboard ヘルプ](https://support.google.com/gboard/answer/11197787?hl=ja)。Windows「それを削除」「それを選択」「Enter キーを押す」など — [Microsoft サポート](上と同じ)。一太郎「それを変換」で再変換 — [PC Watch](https://pc.watch.impress.co.jp/docs/news/1551111.html)
- Kanade の今の語彙（`SpokenPunctuation.kt`）: かぎかっこ／かぎかっことじ（閉じかぎかっこ等）、かっこ／かっことじ、くてん・句点、とうてん・読点、かいだんらく（空行）、かいぎょう・改行、ぎもんふ・はてなマーク・クエスチョンマーク、かんたんふ・びっくりマーク・エクスクラメーションマーク、なかぐろ。ひらがな・カタカナ・漢字の書き方をどれも持つ。「かっこいい」「かっこよく」は記号にしない。認識が付けた句読点と重なったら1つにまとめる

### Inferences
- Kanade に無い、他社で標準の言い方: 「まる」「てん」（iOS・Google の一番短い言い方）、「あたらしいぎょう」「次の行」「新しい段落」、「かぎかっこ開く」「かっこ開く」「かっこ閉じる」（Windows の言い方。今の Kanade では「かっこ開く」は「（開く」になると見られる）、「二重かぎかっこ」、「三点リーダー」、「スラッシュ」「アットマーク」「ドット」など ASCII の記号、絵文字・顔文字の名前
- 「まる」「てん」は普通の語（「丸い」「点数」）とぶつかるので、Kanade が足すなら「かっこ」と同じように前後で見分ける工夫が要る。iOS が「文と一緒に言う」と案内しているのは、文脈で見分けているためと見られる（推測）

### Gaps
- iOS・Gboard の日本語の記号コマンドの公式の完全な一覧は取れなかった

## 3. フィラー・言い直し・数字・英語・表記の扱い

### Takeaway
OS 標準（Google・Apple・Windows）の日本語の音声入力にはフィラーを除く機能の記述が見つからず、フィラー除去と言い直しの処理は Simeji・Typeless など LLM で整形する新しい製品の売りになっている。数字・英語・漢字かなの選び方について、各社の日本語の公式説明は見つからなかった。

### Cited Findings
- Simeji: 「えーっと」「あの」を除き、言い直しを直し、話し言葉を整った文に — [Simeji 公式](https://simeji.me/)
- Typeless: 「あー」「えーと」を除き、言い直しは最後の意図だけ残す — [ライフハッカー](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/)
- 標準の Gboard・Apple・Google の音声入力には、フィラーを整える機能はほぼない（比較記事の主張） — [検索結果の比較記事の要約](https://start-link.jp/hubspot-ai/ai/ai-tools/voice-input-tools-comparison)
- Windows は「不適切な言葉のフィルター」を持つ（フィラーとは別） — [Microsoft サポート](上と同じ)
- 整形しすぎの反発もある（Simeji で頼んでいない箇条書き） — [Yahoo!知恵袋](https://detail.chiebukuro.yahoo.co.jp/qa/question_detail/q11330660191)
- Kanade: フィラーを除く処理はコードに見当たらない。除くのは無音・ほぼ無音と、whisper の決まり文句の幻聴（「ご視聴ありがとうございました」「チャンネル登録よろしくお願いします」「おやすみなさい」「字幕は視聴者によって作成されました」「Thank you for watching」など。末尾の句点を外して丸ごと一致で比べる） — `whisper/src/main/kotlin/dev/mooma/kanade/whisper/WhisperModel.kt`
- Kanade のモデルでの句読点: parakeet-ja・Nemotron・SenseVoice・whisper は付く、ReazonSpeech は付かず、kotoba-whisper は少ない — `docs/USAGE.md`

### Inferences
- フィラーの出かたはモデル次第。parakeet・ReazonSpeech は話し言葉のコーパス（ReazonSpeech はテレビ）で学習しているので「えー」「あのー」をそのまま書く傾向があると見られる（推測。Kanade の見本で測るとよい）。Kanade は LLM を持たないが、「えー」「えーと」「あのー」「まあ」「うーん」などを文頭・読点の前後でだけ消す軽い規則なら端末内で足せる
- 言い直し（「明日、いや明後日」）の処理は規則では難しく、LLM 頼み。Kanade には zenz（小さな GPT-2 系の変換モデル）があるが、文の書き換えには向かない（推測）
- 数字（「にじゅうさん」→「23」）や英語（「グーグル」→「Google」）は各モデルの学習次第で、Kanade には後処理がない。逆テキスト正規化の規則か、ユーザー辞書の表記で寄せる方法が考えられる

### Gaps
- Google・Apple が日本語で数字をアラビア数字にするか漢数字にするか、英語の語を英字にするかの公式の決まりは見つからなかった
- 繰り返し（「あの、あの」）の扱いの出典は見つからなかった

## 4. 音声の文字とかな漢字変換のつながり（再変換・すぐ直す）

### Takeaway
音声で入れた文を IME の変換で直せるのは、調べた範囲では一太郎 2024 の「それを変換」（声で再変換）くらい。Gboard・Windows は声で消す・選ぶ、Typeless は声で部分を直す。Kanade は音声の文を確定の文字として直接入れるので、未確定のまま候補を選び直すことはできない。

### Cited Findings
- 一太郎 2024: 「それを変換」で直前の入力を ATOK で再変換 — [PC Watch](https://pc.watch.impress.co.jp/docs/news/1551111.html)
- Windows: 「それを選択」で選び、選んでいれば削除のコマンドで消す — [Microsoft サポート](上と同じ)
- Gboard: 修正などの機能を使うときはサーバーへ送る（修正の機能がある） — [Gboard ヘルプ](https://support.google.com/gboard/answer/11197787?hl=ja)
- Typeless: 入れたあとの編集ボタンで声で部分を直す — [ライフハッカー](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/)
- Kanade: 確定の文は `commitText` で直接入れ、`engine.leftContext` に足す（次の変換の文脈に使う）。認識しているあいだに打ち始めていたら、その文字を先に確定する。聞き続けるモードでは入力中の文字が確定するまで待つ。途中の文字は未確定の文字（Termux などでは帯）に出し、キーを押すと消える — `app/src/main/kotlin/dev/mooma/kanade/KanadeInputMethodService.kt`、`docs/USAGE.md`
- Kanade の再変換（4-9）は、Kanade が確定した文字の「表記→読み」の覚えと、かな・記号から読みを作る。読めない漢字があれば何もしない — `SPEC.md` 4-9、未決事項

### Inferences
- 音声の文は変換を通らずに入るので、読みの覚えに入らない。そのため音声で入った漢字の語を選んで Space を押しても、読めずに再変換できない見込みが高い（コードの読みからの推測。実機で確かめる）。音声の確定時に、認識の読み（ReazonSpeech はかなを出せない、Nemotron 等も表記のみ）は得られないので、辞書の逆引き（未決事項にある案）が要る
- 利点として、音声の文が `leftContext` に入るので、続けてキーで打つ変換は音声の文脈を使える

### Gaps
- Gboard・iOS で、音声の確定直後に IME の変換候補（同音の別の漢字）を出す機能があるかは見つからなかった

## 5. Kanade の足りないところと強み（ほかの日本語キーボード・音声専用アプリと比べて）

### Takeaway
Kanade は「完全に端末内」「モデルを選べる・取り込める」「途中の文字と確定の2段構え」「物理キーの押している間の録音」で他にない作り。一方で、声の編集コマンド、フィラー除去、取り消し、記号の語彙、絵文字、数字・英語の整え、ユーザー辞書の活用、音声の文の再変換が足りない。

### Cited Findings（Kanade の今の仕様。出典は SPEC.md・USAGE.md）
- 録音キー（既定 F12、画面ではマイクのキー）を押している間だけ録音（8-1 確定）。途中の文字は Nemotron-3.5 Streaming か ReazonSpeech、離すと適用したモデル（既定 parakeet-ja）で句読点付きの文（8-2・8-8 確定）。短く押すと聞き続け、0.7 秒黙るたびに入れる。もう一度押す・1分話さない・入力欄を移ると終わる（8-3 仮）。話して句読点（8-4 仮）。無音・幻聴を入れない（8-5 仮）。モデルの一覧・札・Wi-Fi の確認・続きから落とす・取り込み（8-6 仮）。5分でメモリから放す（8-7 仮）。音声は外へ送らない
- 録音中はカーソルの近くに「🎤」、認識中は「…」。「見本の音声で測る」で落としたモデルの速さと結果を比べる — `docs/USAGE.md`

### Kanade に足りないもの（他社が持つもの）
| 足りないもの | 持っているところ | 出典 |
|---|---|---|
| 声の編集コマンド（直前を消す・全部消す・選ぶ・止める） | Gboard「最後の単語を削除して」「消去して」「すべて消去して」「止めて」、Windows「それを削除」「それを選択」 | [Gboard](https://support.google.com/gboard/answer/11197787?hl=ja)、[Microsoft](https://support.microsoft.com/ja-jp/windows/%E9%9F%B3%E5%A3%B0%E5%85%A5%E5%8A%9B%E3%82%92%E4%BD%BF%E7%94%A8%E3%81%97%E3%81%A6-pc-%E3%81%A7%E9%9F%B3%E5%A3%B0%E3%82%92%E3%83%86%E3%82%AD%E3%82%B9%E3%83%88%E3%81%AB%E5%A4%89%E6%8F%9B%E3%81%99%E3%82%8B-fec94565-c4bd-329d-e59a-af033fa5689f) |
| 声で送信・次の入力欄・Enter・Tab | Gboard「送信して」「次へ」、Windows「Enter キーを押す」「タブ」 | 同上 |
| 声で再変換 | 一太郎「それを変換」 | [PC Watch](https://pc.watch.impress.co.jp/docs/news/1551111.html) |
| 音声の文のキーでの再変換（読みの逆引き） | （一太郎は ATOK で再変換） | 同上、SPEC 未決事項 |
| フィラー除去・言い直しの処理・整形 | Simeji、Typeless | [Simeji](https://simeji.me/)、[ライフハッカー](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/) |
| 「まる」「てん」「あたらしいぎょう」「次の行」「かっこ開く」「二重かぎかっこ」「三点リーダー」、ASCII の記号（スラッシュ・アットマーク・ドット・円記号など） | iOS、Google、Windows | 2. を参照 |
| 絵文字・顔文字を声で | Gboard「笑顔の絵文字」、iOS「ニコニコマーク」、旧 Simeji は顔文字を候補に | [Gboard](https://support.google.com/gboard/answer/11197787?hl=ja)、[iPhone Mania](https://iphone-mania.jp/manual/preparation-148605/)、[ITmedia 2017](https://www.itmedia.co.jp/mobile/articles/1706/14/news133.html) |
| 自動句読点を切る設定（句読点が付くモデルでも付けない） | Gboard・Windows は設定で切れる | 同上 |
| コマンドの一覧を画面で見る | Gboard の情報アイコン | [Gboard](https://support.google.com/gboard/answer/11197787?hl=ja) |
| 宛先に合わせた調子（です・ます／くだけた） | Simeji、Typeless | 同上 |
| ユーザー辞書・よく使う語を認識に効かせる | Simeji の辞書機能 | [Simeji](https://simeji.me/) |
| 小声の入力 | Simeji | [Simeji](https://simeji.me/) |
| 翻訳して入れる | Simeji、Typeless | 同上 |
| エコー除去・マイクを選ぶ | Windows | [Microsoft](上と同じ) |
| 入力欄がなくても話した内容を残す | Typeless | [ライフハッカー](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/) |
| 不適切な言葉のフィルター | Windows | [Microsoft](上と同じ) |

### Kanade の強み
- 完全に端末内（音声を外へ送らない）。Windows の音声入力（Azure）、Simeji・Typeless（クラウドの AI とみられる）、Gboard の修正機能（サーバーへ送る）と違う。Pixel 以外の Android でも端末内で動く
- モデルを選べて取り込める（parakeet-ja・ReazonSpeech・SenseVoice・whisper 系・Nemotron、ggml・sherpa-onnx の形式）。精度の札と「見本の音声で測る」。他社にこの種の選択はない
- 途中の文字（Nemotron の逐次）と確定（parakeet-ja）の2段構え。Pixel・iPhone と同じ考え方を、Pixel 以外・Windows でも
- 物理キーボードの1キーで押している間だけ録る（DeX・Windows で打鍵と行き来しやすい）。短く押せば聞き続ける。聞いている間もキーで打て、打っている文字を上書きしない
- 話して句読点で、ひらがな・カタカナ・漢字のどの書き方で認識されても記号になり、自動の句読点と重なっても1つにまとまる。「かっこいい」を誤って記号にしない
- whisper の決まり文句の幻聴を入れない
- 音声の文を次の変換の文脈（leftContext）に使う
- Android と Windows の両方で同じモデル（Windows の既定は whisper small）

### Inferences（足すときの見込み）
- 端末内のまま足しやすい順（推測）: (1) 記号の語彙を Windows・iOS 並みに増やす（「まる」「てん」は語の前後で見分ける）、(2) 声の編集コマンド（「さっきのを消して」「全部消して」「止めて」「送信して」）を確定の文の全体一致で見る、(3) 直前の音声の文を1回で取り消すキー（Backspace の長押しなど）、(4) 文頭・読点の前後のフィラーを消す規則（設定で切れる）、(5) 音声の確定時に表記→読みを辞書の逆引きか読みの出るモデルで作り、再変換の覚えに入れる、(6) ユーザー辞書を Qwen3-ASR などのホットワードに渡す（`docs/RESEARCH-voice.md` の案 C）
- 整形（言い直し・調子）は LLM が要り、端末内・低遅延の方針とぶつかる。Kanade の方針では「足さない」も筋が通る

### Gaps
- 他社の実際の日本語での認識精度・遅延を Kanade のモデルと同じ条件で比べた資料はない（`docs/RESEARCH-voice.md` も「本人の声で測る」としている）
- Simeji・Typeless が音声をどこで処理しているか（クラウドか）は一次資料で確かめられなかった
