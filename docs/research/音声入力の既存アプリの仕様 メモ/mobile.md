# モバイルのキーボードと OS の音声入力の仕様（Gboard／Pixel の高度な音声入力・Samsung キーボード・iOS の音声入力）

調べた日：2026-11-08。公式ヘルプは取得時点の版（Apple は iOS 27 の版の日本語ユーザガイド、Google は Gboard ヘルプの英語・日本語版）。Samsung は公式の詳しい資料が見つからず、多くが不明（各節の「Gaps」）。

## 開始と停止、自動停止の時間、聞いている間も打てるか

### Takeaway
iOS はマイクを押して始め、ボタンを押す・「音声入力を停止」と言う・30 秒黙る、のどれかで止まる。キーボードは開いたままで打ち続けられる（iOS 16 から）。Pixel の高度な音声入力はマイクを押して始め、もう一度押す・キーボードを閉じる・「止めて」と言う、で止まる。マイクを2回押すと続けて聞く。聞いている間もキーボードで打てる。Gboard の自動停止の秒数は公式に書かれていない。

### Cited Findings
- iOS：テキスト欄で挿入ポイントを置き、キーボードか欄のマイクボタンを押す。挿入ポイントの近くにマイクの印が出たら話し始める — [Apple：iPhoneでテキストを音声入力する](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS の止め方は3つ。マイクの印を押す、「音声入力を停止」と言う、「音声を出さずに30秒間待ちます。音声入力は自動的に停止します」 — [Apple（同上）](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS：「タイプ入力と音声入力を一緒に使用することもできます。つまり、音声入力中にもキーボードは開いたままになるので、タイプ入力と音声入力を簡単に切り替えて」入力できる — [Apple（同上）](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS 16 で、マイクが有効な間も切り替えずにキーボードと音声入力を併用できるようになった。誤変換はカーソルを動かしてキーボードで直せる。条件は A12 Bionic 以降（iPhone XS 以降） — [PHILE WEB（2022-10-19）](https://www.phileweb.com/review/column/202210/19/1867.html)
- iOS で音声入力をオンにする場所：設定 ＞ 一般 ＞ キーボード ＞「音声入力を有効にする」 — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- Gboard（ふつうの音声入力）：入力欄を押し、キーボード上部のマイクを押す。「お話しください」が出たら話す。止めるときはツールバーのマイクを押す — [Gboard ヘルプ（日本語）](https://support.google.com/gboard/answer/2781851?hl=ja)
- Pixel の高度な音声入力（アシスタントの音声入力）：開始はキーボードのマイクを押す。停止は「マイクボタンをタップするか、キーボードを閉じるか、『止めて』（英語では "Stop"）と言います」。マイクを**ダブルタップ**するとマイクが開いたままになり、メッセージを続けて何通も送れる。動いている間マイクの印が光り続ける — [Gboard ヘルプ 11197787（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)、[同（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- 高度な音声入力では、マイクがオンのままキーボードで打てる — [Gboard ヘルプ 11197787（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- Google の日本向け記事（2024-11-20 公開、Pixel 8・Android 14 で確認）では、停止の音声コマンドは「ていし」と書かれている（一部の Pixel でアシスタントの音声入力がオンのとき） — [android.com 日本語記事](https://www.android.com/intl/ja_jp/articles/37/)。ヘルプの今の版は「止めて」。版によって言い方が違う
- 高度な音声入力は 2021 年 10 月の Pixel 6 で始まった（Assistant voice typing として） — [9to5Google（2021-10-19）](https://9to5google.com/2021/10/19/pixel-6-gboard-assistant-voice-typing/)、[XDA](https://www.xda-developers.com/gboard-assistant-voice-typing-on-pixel-6/)
- Samsung キーボード：キーボード設定の音声入力で「Samsung voice input」と「Google 音声入力」のどちらかを選び、入力欄でマイクを押す — [SammyFans（One UI 6.1、2024-02-19）](https://www.sammyfans.com/2024/02/19/one-ui-6-1-how-to-activate-voice-input-feature-on-your-keyboard/)。日本の au の Galaxy S25／S26 の説明書では、設定 ＞ 一般管理 ＞ Samsungキーボード設定 ＞ 日本語入力オプションで「フリック入力、音声入力、マッシュルームなどの設定」をする — [au Galaxy S25 取扱説明書](https://www.au.com/online-manual/galaxy_s25/galaxy_s25_02/m_03_02_02.html)
- Samsung：One UI 7 以降の更新のあと、キーボードのマイクが消える報告がある。「ナビゲーションバーのキーボードボタン」をオンにすると戻るという対処が広まっている（フォーラムや第三者のブログで、公式ではない） — [Samsung Community（One UI 8.5）](https://us.community.samsung.com/t5/Questions/Voice-Typing-Engine-Missing-in-One-UI-8-5-Gboard-Still-Uses/m-p/3456938)、[yaps.ai](https://www.yaps.ai/ja/blog/samsung-voice-typing-not-working)

### Inferences
- iOS の 30 秒は「無音が続いたら止める」時間で、1回の発話の区切りではない。Pixel はマイクを押すかコマンドで止めるのが基本で、ダブルタップで続けて聞く形がある。どちらも「聞いている間も打てる」が今の標準
- 「止めて」のような止めるコマンドは、文中の「止めて」と区別がいる。Google は、発話の終わりに単独で言われたものをコマンドとみなしていると考えられる（公式の説明はない）

### Gaps
- Gboard（ふつうの音声入力と高度な音声入力）が無音で自動停止する秒数は公式に書かれていない
- Samsung voice input の止め方、自動停止、聞きながら打てるかは、信頼できる資料が見つからなかった

## 話している間の表示と、確定した文字に置き換わる方法

### Takeaway
iOS は欄の挿入ポイントの近くにマイクの印を出し、文字を順に書き込む。自信のない語には青い下線を付け、押すと別の候補が出る。Pixel は動いている間マイクの印が光り続け、認識した文字をそのまま欄に書く。押した語を直せる。途中の文字（未確定）の見せ方の細かい仕様（下線の色、置き換わる時の動き）は公式資料にない。

### Cited Findings
- iOS：挿入ポイントの近くにマイクの印が出たら話す。この印を押すと止まる — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS：自信のない語には青い下線が付き、押すと候補が出る。iOS 14 の一部の機種では1回押しても反応せず、2回押して置き換えのメニューを出すしかなかった報告がある（14.2 で直った） — [MacRumors フォーラム](https://forums.macrumors.com/threads/ios-dictation-cant-access-blue-underlined-suggestions.2100963)（利用者の報告）
- iOS：下線の付いた文字は直しにくく、押してカーソルを置くと文全体が選ばれることがある、という声 — [Apple Discussions](https://discussions.apple.com/thread/7341955)（利用者の報告）
- Pixel：動いている間マイクの印が光り続ける — [Gboard ヘルプ 11197787（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- Gboard（ふつうの音声入力）：「お話しください」と出る — [Gboard ヘルプ（日本語）](https://support.google.com/gboard/answer/2781851?hl=ja)

### Inferences
- どちらも、文字は欄の中に直接書かれ、別の窓は出さない。確定は「区切りが来たら」か「止めたとき」と考えられる。Kanade で作るなら、欄に途中の文字を下線付きで出し（変換中の文字と同じ setComposingText）、区切りで確定するのが既存の見え方に近い

### Gaps
- 途中の文字の色、下線、波形の有無、確定時の置き換え（一部だけ書き換えるのか全部なのか）は、Google・Apple とも公式の説明が見つからなかった
- Samsung の表示は資料なし

## 句読点の自動挿入と、記号・改行・絵文字などの音声コマンド（英語・日本語）

### Takeaway
iOS（iOS 16 から）も Pixel の高度な音声入力も、句読点を自動で入れ、設定で切れる。日本語の句読点（、。）にも効くが、「、」は意図どおりに入らないことがある。記号は名前を言って入れる。Gboard の日本語は「とうてん」「くてん／まる」「あたらしいぎょう」などの読みが公式に一覧されている。絵文字は「〇〇の絵文字」で入る。

### Cited Findings
- iOS：対応している言語では、話すと自動でカンマ、ピリオド、疑問符が入る。切るには 設定 ＞ 一般 ＞ キーボード ＞「自動句読点」 — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS 16 で自動句読点が入った。前は「まる」と言って「。」を入れる必要があった。ただし「、」が意図どおりに入らないことがある — [PHILE WEB（2022-10-19）](https://www.phileweb.com/review/column/202210/19/1867.html)
- iOS のコマンドの一覧（日本語のページに載っている呼び方）：句読点は「ピリオド／点／ドット／フルストップ」「カンマ」「感嘆符」「疑問符」「ドル記号」「開き丸かっこ」「閉じ丸かっこ」「引用符」「終わりの引用符」「コロン」「セミコロン」「ハッシュタグ／ポンド記号」。書式は「次の段落」「次の行」「大文字」「すべて大文字」「スペースなし」とそのオン〜オフの組。絵文字は「ハートの絵文字」「スマイリーの絵文字」など — [Apple：テキストの音声入力に使えるコマンド（iOS 27）](https://support.apple.com/ja-jp/guide/iphone/iph3bf19d7b9/27/ios/27)
- iOS の本文の例：改行は「改行」や「新しい段落」、句読点は「感嘆符」など記号の名前を言う、絵文字は「ハートの絵文字」「笑顔の絵文字」 — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- Gboard の日本語の記号の読み（公式の一覧）：改行「あたらしいぎょう」、段落（改行2回）「あたらしいだんらく」、、「とうてん」、。「くてん、まる」、「「かぎかっこ」、」「かぎかっことじ」、！「びっくりまーく」、？「はてなまーく」、（「まるかっこ」、）「まるかっことじ」、・「なかぐろ」、…「てんてんてん」、※「こめじるし」、＊「あすたりすく」、‐「はいふん」、＿「あんだーばー」 — [android.com 日本語記事（2024-11-20）](https://www.android.com/intl/ja_jp/articles/37/)
- Pixel の高度な音声入力：話すと自動で句読点が入る。設定 ＞ 音声入力 ＞「句読点を自動的に追加」で切れる — [Gboard ヘルプ 11197787（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)
- Gboard（ふつうの音声入力）：句読点は「さまざまなフレーズ」で入れられるが、言語によっては入れられない。絵文字は名前を言う。コマンドの一覧は、メニューか「EN」ボタンの「音声コマンドを表示」で見る — [Gboard ヘルプ（日本語）](https://support.google.com/gboard/answer/2781851?hl=ja)
- Pixel：絵文字は「笑顔の絵文字」のように名前を言う。話している間に情報（i）の印を押すとコマンドの一覧が出る — [Gboard ヘルプ 11197787](https://support.google.com/gboard/answer/11197787?hl=ja)、[検索結果の要約（英語版ヘルプ）](https://support.google.com/gboard/answer/11197787)
- Pixel の操作コマンド（日本語）：「最後の単語を削除して」「消去して」（最後の文）「すべて消去して」「送信して」「次へ」（次の入力欄）「止めて」。英語では "Delete last word" "Clear" "Clear all" "Send" "Next" "Stop" — [Gboard ヘルプ 11197787（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)、[同（英語）](https://support.google.com/gboard/answer/11197787?hl=en)

### Inferences
- 「くてん」と「まる」は Gboard では両方で「。」。iOS の日本語一覧は英語の訳で、日本語らしい「まる」「てん」の扱いは公式に書かれていない（以前は「まる」で「。」が入ったと PHILE WEB が書いている）
- コマンドと書き取りの区別について、公式の説明はない。Apple の一覧に「以下に示すコマンドは、テキストの音声入力中いつでも使えます」とあるので、決まった言い方を見つけたらコマンドとして扱っていると考えられる。だから「まる」「てん」を普通の語として書きたいときに誤ってコマンドになる問題は避けられない

### Gaps
- iOS の日本語での「まる」「てん」「かいぎょう」が今も使えるか、公式資料では確かめられなかった
- コマンドと書き取りを見分ける仕組み（間の取り方、文の最後かどうか）の公式説明はない
- Samsung voice input の句読点とコマンドは資料なし

## 間違いの直し方（候補、音声での取り消し、言い直し）

### Takeaway
iOS は青い下線の語を押して候補を選ぶ。「〜を〜に変更」「〜を削除」などの音声での編集は**米国英語のみ**（iPhone 12 以降、SE を除く）。日本語で使えるのは「取り消して」「やり直して」と、「選択」「削除」＋語句（本文の記述）。Pixel は語を押して、言い直す・打つ・綴りを言う・候補を押す。日本語では「最後の単語を削除して」「消去して」が使える。細かい編集のコマンド（"Change ___ to ___" など）と "Fix it" は Pixel 8 以降の米国英語のみ。

### Cited Findings
- iOS：綴りは「スティーブン・S-T-E-P-H-E-N に参加を依頼しよう」のように言う。綴りを直すときは「tea を T-E-E に変更」。編集は「選択」か「削除」のあとに語句を言う。「取り消して」「やり直して」でコマンドを取り消す・繰り返す — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- iOS：「〜を〜に変更」「〜の前に〜を挿入」「〜のあとに〜を挿入」「〜を選択」「〜を削除」「すべて削除」「取り消す」「やり直す」は「iPhone SE を除く iPhone 12 以降の米国英語で使用可能」 — [Apple：コマンド一覧](https://support.apple.com/ja-jp/guide/iphone/iph3bf19d7b9/27/ios/27)
- Pixel：誤認識された語を押して選び、話しかける・打つ・正しい綴りを言う・候補を押す、のどれかで直す — [Gboard ヘルプ 11197787（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)
- Gboard（ふつうの音声入力）：置き換えたい語を長押し → マイクを押す → 置き換える語を話す — [Gboard ヘルプ（日本語）](https://support.google.com/gboard/answer/2781851?hl=ja)
- Pixel の細かい編集：「___ の前に ___ を挿入」「___ を削除」「___ を ___ に変更」「___ を ___ と綴る」「___ を大文字に」、「元に戻す」、「すべて消去」。Pixel 8 以降・英語（米国）のみ — [Gboard ヘルプ 11197787（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- Pixel の "Fix it"（修正）：誤字、文法、句読点を直す。"More fixes" で別案、"Undo" で取り消し。Pixel 8 以降・英語・米国のみ。ネットが要る — [同](https://support.google.com/gboard/answer/11197787?hl=en)、[同（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)
- Pixel の文章ツールの音声コマンド（「校正して」「言い換えて」「丁寧に」「簡潔に」「絵文字を足して」など）。"Use this" で採用、"Next"／"Previous" で案を送る。Pixel 9 以降（9a を除く）、日本語を含む6言語（ドイツ語は「近日」） — [同（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- XDA（2021）："Undo" は直前の "Clear" や "Delete" を取り消す — [XDA](https://www.xda-developers.com/gboard-assistant-voice-typing-on-pixel-6/)

### Inferences
- 日本語の利用者にとって、両社とも「語を押して候補を選ぶ」「キーボードで直す」が主な直し方。音声だけで直す仕組みは英語が先で、日本語は「最後を消す」「取り消す」程度
- IME から見ると、押した語の候補を出すには、確定のあとも語の範囲と候補を覚えておく必要がある

### Gaps
- iOS の青い下線の今の動き（何個まで候補が出るか、日本語で出るか）の公式説明はない
- Samsung の直し方は資料なし

## 端末の中で処理するか、オフラインで日本語が使えるか、送られるデータ

### Takeaway
iOS は「多くの言語」を端末の中で処理し、ネットが要らない。端末の中かどうかは設定画面に出る。それ以外は Apple に送られる。Pixel の高度な音声入力（日本語を含む6言語）は文字が端末に残り、Google に送るのは「修正」と細かい編集を使ったときだけ。Samsung は日本語のオフラインの資料が見つからない。

### Cited Findings
- iOS：「多くの言語の音声入力は iPhone 上で処理されます。インターネット接続は必要ありません」。検索欄に音声入力した文字は、検索を処理するために検索エンジンに送られることがある — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- Apple：話した内容が端末の中で処理されるかは、Siri の設定かキーボードの設定に表示される。そうでなければ「the things you say are sent to and processed by Apple」 — [Apple：Siri と音声入力のプライバシー](https://www.apple.com/legal/privacy/data/en/ask-siri-dictation/)
- Apple：音声は「Siri と音声入力の改善」に同意しない限り保存されない。同意すると音声と文字を集めて人が聞くことがある。連絡先の名前・あだ名・関係、デバイス名、音楽の好み、写真の人物名、インストールしたアプリ名などが認識の改善に送られることがある。同意しなくても、要求の種類・成否・機種・性能の数値は保存される。データは端末が作る識別子に結び付き、識別子は「1時間に何度も」入れ替わる。履歴は「最長2年」保たれることがある — [Apple（同上）](https://www.apple.com/legal/privacy/data/en/ask-siri-dictation/)
- iOS 17 以降、iPhone XS 以降では音声入力が既定で端末の中で動く、という第三者の説明（Apple の資料ではない） — [fish.audio](https://fish.audio/blog/iphone-voice-to-text-tutorial-enable-disable-voice-input/)
- Pixel の高度な音声入力：対応は Pixel 6 以降（Fold、Tablet も）、言語は英語・フランス語・ドイツ語・イタリア語・**日本語**・スペイン語 — [Gboard ヘルプ 11197787（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)
- Pixel：基本の音声入力では文字は端末で処理され、「[修正] 機能または詳細な編集機能を使用した場合を除き、Google のサーバーに送信されることはありません」。細かい編集では、コマンドの文字と入力欄の全文が送られる（音声は送らず、サーバーに保存しない）。文章ツールの校正と言い換えは端末で動く — [同（日本語）](https://support.google.com/gboard/answer/11197787?hl=ja)、[同（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- Pixel の設定：設定 ＞ 音声入力で、高度な機能のオン・オフ、「言語の自動切り替え」（既定でオン）、自動句読点。設定 ＞ プライバシーの「Personalize for you」は文字起こしを端末に保存し、「学習した単語とデータを削除」で消せる — [同（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- Gboard の音声入力は既定で音声を Google に送る、という第三者の記述もある（高度な音声入力ではない場合の話と思われる）。上の公式の説明とは対象が違う — [yaps.ai](https://www.yaps.ai/ja/blog/samsung-voice-typing-not-working)
- Samsung：「Samsung On-Device Resources Japanese (Japan)」3.0.06.3 が 2025 年 10 月に出ており、Samsung のアプリのためのオフライン音声認識と説明されている（APKMirror の掲載で、公式ではない。キーボードの音声入力で使うかは不明） — [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-on-device-resources-japanese-japan/)
- Samsung voice input の以前の掲載では、オフラインの音声入力は韓国語と英語（米国）だけだった（古い可能性あり） — [APKMirror：Samsung voice input](https://apkmirror.com/apk/samsung-electronics-co-ltd/samsung-voice-input/)

### Inferences
- 日本語の端末内の音声入力は、Pixel 6 以降の高度な音声入力と iOS（対応機種）ではできる。Samsung は 2025 年に日本語の端末内の資源が出たので、端末内に移りつつあると考えられるが、確かめられていない
- 両社とも「文字は端末に残す、校正・言い換えなど LLM の機能だけ外に出す」の分け方。Kanade が端末の中だけで動かす方針は、利用者から見て標準と同じ水準

### Gaps
- iOS の日本語が端末内かどうか、機種ごとの条件は公式資料で確かめられなかった（設定画面に出るとだけある）
- Pixel の高度な音声入力が機内モードで完全に動くかは、公式に書かれていない
- Samsung voice input の日本語オフライン、送るデータの資料はない

## 多言語、日本語と英語のまぜ書き、電池と遅れ

### Takeaway
iOS は使う言語のキーボードを足すと、その言語で音声入力できる（キーボードの言語で決まる）。Pixel は「言語の自動切り替え」が既定でオンで、言語の略語を押して変えられる。日本語と英語を1文の中でまぜたときの動き、電池、遅れの数値は、公式資料にも信頼できる記事にも見つからなかった。

### Cited Findings
- iOS：「別の言語のキーボードを使うと、その言語での音声入力も使うことができます」 — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)
- Gboard：言語を切り替えるには複数の言語を有効にし、ツールバー左下の言語の略語を押して選ぶ。一部の言語は音声入力に対応していない — [Gboard ヘルプ（日本語）](https://support.google.com/gboard/answer/2781851?hl=ja)
- Pixel の高度な音声入力：「言語の自動切り替え」が既定でオン — [Gboard ヘルプ 11197787（英語）](https://support.google.com/gboard/answer/11197787?hl=en)
- iOS：音声入力でモバイルデータ通信の料金がかかることがある、と注記がある — [Apple](https://support.apple.com/ja-jp/guide/iphone/iph2c0651d2/ios)

### Inferences
- 両社とも、1回の音声入力では1つの言語を選ぶ形が基本。Pixel の自動切り替えは、言語を発話ごとに見分ける形と考えられる（1文の中のまぜ書きは別の話）

### Gaps
- 1文の中の日本語と英語のまぜ書きの扱い（英語の語をカタカナにするか英字にするか）の公式資料はない
- 電池の消費、最初の文字が出るまでの遅れの数値は、どの製品も見つからなかった
- Samsung の多言語の動きは資料なし
- Google の端末内の音声認識の技術ブログ（RNN-T、2019 年）は今回は取得していない
