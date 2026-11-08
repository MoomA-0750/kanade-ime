# デスクトップOSに組み込まれた音声入力（Windows 音声入力・音声アクセス／macOS 音声入力・Voice Control／日本語IME）

調べた日: 2026-11-08。公式ページは日付の表記がないものが多い（MS のページは「© Microsoft 2026」、Apple のページはバージョンの選択で macOS 27 まで出る）。

## 1. 始め方・止め方と無音で止まるまでの時間

### Takeaway
macOS の音声入力はトグル式で、**30秒声がないと自動で止まる**。長さの制限もタイムアウトもないと公式に書いてある。Windows の音声入力（Win+H）もトグル式だが、無音で止まるまでの時間は公式に書かれていない。非公式の情報では「5〜10秒ほど黙ると止まり、時間は変えられない」とされる。押している間だけ聞く方式は、どちらの公式の説明にも出てこない。

### Cited Findings
**Windows 11 音声入力（voice typing）**
- 始め方: ハードウェアのキーボードで Win+H、またはタッチキーボードのマイクのボタン。「聞き取り中...」が出るのを待ってから話す。カーソルがテキストボックスにあることが条件 — [MS サポート「音声入力を使用して、PC 上でタイプをする代わりに話す」](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- 止め方: 声で「音声入力を一時停止」「ディクテーションを一時停止」「音声入力を停止」「ディクテーションを停止」「聞き取りを停止」「音声モードを停止」「音声モードを一時停止」と言う。または浮いているメニューのマイクのボタンを押す — 同上
- 設定（浮いている窓の歯車）: 音声入力ランチャー（テキストボックスにいるとき、すぐ音声入力を始められる入口）、句読点の自動挿入、不適切な言葉のフィルター、既定のマイク、**「コマンドを実行するまでの待ち時間」**（話し方がゆっくりな人にも速い人にも合うよう、声のコマンドを実行するまで待つ時間を選べる）、エコーキャンセル（スピーカーの音を除く） — 同上
- 無音で止まる時間は、公式のページには書かれていない — 同上
- 非公式の情報: 「5〜10秒ほど黙ると聞き取りが止まる。仕様で、Windows では変えられない」。ただしこれは自社の音声入力製品を売っている会社のブログで、根拠に挙げているのはコミュニティの回答者の発言 — [yaps.ai](https://www.yaps.ai/blog/windows-voice-typing-keeps-stopping)
- Anki の利用者の報告: Anki では途中で止まるが、Chrome やメモ帳では聞き続ける（アプリによって動きが違う）。止まったら Win+H を押し直すしかない — [Anki Forums](https://forums.ankiweb.net/t/windows-voice-typing-functionality-is-limited-on-this-app/23255)
- Windows 10 のディクテーション（前の世代）: Win+H で始め、「ディクテーションを停止」と言うかマイクを押し直すと止まる — [MS サポート（同じページの Windows 10 の節）](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)

**Windows 11 音声アクセス（Voice Access）**
- オンにする方法: 設定 > アクセシビリティ > 音声 から、または Win+Ctrl+S、またはクイック設定のアクセシビリティから — [窓の杜 2025-10-24](https://forest.watch.impress.co.jp/docs/news/insiderpre/2057245.html)
- 起こす: 「音声アクセスのウェイク アップ」「ミュート解除」。スリープ: 「音声アクセス スリープ」「ミュート」。マイクを切る: 「マイクをオフにする」。文字を打つだけのモード: 「ディクテーション モード」 — [MS サポート「音声アクセス コマンドの一覧」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/voice-access-command-list)
- 音声アクセスは、音声入力（Win+H）のように使うたびに起動するものではない。常に聞いていて、声で起こしたり眠らせたりする作り（コマンドの一覧の作りから読み取れる） — 同上

**macOS 音声入力（Dictation）**
- 始め方: マイクのキー（ファンクションキーの列にある場合）を押して離す。押し続けると Siri が起動する。またはショートカット、またはメニューの「編集 > 音声入力を開始」。ショートカットは「キーボード設定 > 音声入力 > ショートカット」で選ぶ。「カスタマイズ」で自分で決めることもできる（例: Option+Z） — [Apple「Macで音声入力を使用する」(ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- 選べるショートカットに「Fn（ファンクション）キーを2回押す」がある — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)
- 既定のショートカット: Ventura の頃の解説では「Control キーを2回押す」で、MacMost の動画では止めるときは1回押せばよい。2026年の解説では既定は Fn／地球儀のキーを2回押すになっている。いつ変わったかは確かめられなかった — [MacMost](https://macmost.com/how-to-use-dictation-on-your-mac-2.html)、[yaps.ai (ja)](https://www.yaps.ai/ja/%E3%83%96%E3%83%AD%E3%82%B0/mac%E9%9F%B3%E5%A3%B0%E5%85%A5%E5%8A%9B%E3%81%AE%E4%BD%BF%E3%81%84%E6%96%B9)
- 止め方: Esc、マイクのキー、または音声入力のショートカット — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- 自動で止まる時間: 「30秒間音声が検出されないと音声入力は自動的に停止します。」／「音声入力できるテキストの長さに制限はなく、タイムアウトもありません。」 — 同上
- Voice Control（音声コントロール）がオンのときは、普通の音声入力は使えない。文字は Voice Control で入力する — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)

### Inferences
- どちらの OS もトグル式（1回押すと始まり、もう1回押すか声のコマンドで止まる）が基本。押している間だけ聞く方式は公式には用意されていない。
- 無音で止まるまでの時間は、Mac が30秒で、Windows は短め（非公式には5〜10秒）。Windows で「すぐ切れる」という不満が多いのはこのためと考えられる。

### Gaps
- Windows の音声入力の無音タイムアウトは、MS の一次情報では確かめられなかった。「コマンドを実行するまでの待ち時間」で選べる値（秒数）も、ページには書かれていない。
- macOS の既定のショートカット（Control 2回か Fn 2回か）が、どのバージョンで変わったかは分からなかった。

## 2. 話している間に出るもの・途中の結果をアプリの欄に直接入れるか・いつ確定するか

### Takeaway
Mac は、文字を入れる位置に直接書き込む。自信のない語には青い下線が付く。改行や段落のコマンドは、音声入力を終えたときに反映される。Windows は浮いている小さな窓（マイクと歯車）を出すが、途中の結果をどう見せるかは公式に書かれていない。

### Cited Findings
- Mac: 話した文字は、使っているアプリの挿入位置に入る — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- Mac: 「次の行」は Return を1回押したのと同じ、「次の段落」は2回押したのと同じ。**改行は音声入力が終わったときに出る** — 同上
- Mac: 自信のない文字には青い下線が付き、クリックすると別の候補を選べる — 同上
- Mac（Apple シリコン）: 話しながらキーボードで打ってもよく、音声入力を止める必要はない。打っている間はカーソルの強調と点滅が止まり、打ち終えると戻る — 同上
- Mac: 複数の言語を足しておくと、カーソルの横に言語の表示が出て、クリックか地球儀のキーで切り替えられる — 同上
- Windows: 「聞き取り中...」の表示が出て、浮いているメニューにマイクのボタンと歯車がある。途中の結果をどう見せるかは、ページのどこにも書かれていない — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- Windows の音声アクセスのディクテーションのページにも、確定する前の途中の結果やプレビューのことは書かれていない — [MS サポート「音声でテキストをディクテーションする」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/dictate-text-with-voice)

### Inferences
- Mac の「話しながら打てる（modeless）」は、途中の結果と打った文字が同じ欄に入ることを前提にした作り。Kanade で声と打鍵を混ぜるときの手本になる。
- 改行のコマンドを終わったときに反映するのは、途中の結果が書き換わる間に改行を確定させないための工夫と考えられる。

### Gaps
- Windows の音声入力が、途中の結果を IME の未確定の文字（下線付き）としてアプリの欄に入れるのか、確定してから入れるのか、一次情報が見つからなかった。実機で確かめる必要がある。
- Mac で途中の結果が書き換わるとき、すでに入った文字をどう置き換えているのかは、公式の説明がない。

## 3. 句読点の自動挿入と日本語の句読点

### Takeaway
macOS は日本語でも句読点の自動挿入に対応していて、設定でオフにできる。Windows 11 の音声入力にも設定がある。音声アクセスでは句読点の自動挿入は既定でオフ。英語だけ、さらに言語モデルで文を整える機能があり、Mac では Apple Intelligence、Windows では Copilot+ PC の「流動ディクテーション」がそれに当たる。

### Cited Findings
- Mac: 対応する言語では「カンマ、ピリオド、?マークが入力中に自動で挿入されます」。オフにするには「キーボード設定 > 音声入力 > 自動句読点」 — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- Mac: 句読点の自動挿入（Auto-Punctuation）の対応言語に「Japanese (Japan)」が入っている — [Apple macOS Feature Availability](https://www.apple.com/macos/feature-availability/)
- Mac: 句読点の自動挿入は macOS Ventura 以降の機能と解説されている — [subanana](https://subanana.com/ja/blog/mac-音声入力)
- Mac（macOS 27）: Apple Intelligence が、端末の上のモデルでつづり・句読点・大文字を直す。**英語だけ**、M3 以降で統合メモリ12GB以上の Mac — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)
- Windows 11 音声入力: 句読点の自動挿入（話した内容から句読点を入れる）を歯車から設定できる — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- Windows「流動ディクテーション」（Copilot+ PC だけ）: 話している間に、文法・句読点・言いよどみの言葉を直す。端末の上の小さな言語モデル（SLM）で動く。**英語の地域だけ**。既定でオン。パスワードや PIN の欄では自動でオフになる。直されたのを戻すには「元に戻す」と言うか Ctrl+Z — 同上
- Windows 音声アクセス: 句読点の自動挿入は**既定でオフ**。音声アクセスのバーの設定 >「オプションの管理」でオンにする。不適切な言葉のフィルターは既定でオンで、見つかった語は伏せ字で入る — [MS サポート「音声でテキストをディクテーションする」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/dictate-text-with-voice)

### Inferences
- 日本語の句読点の自動挿入は、両方の OS に「ある」が、どれくらい正しく入るかを示す数値はない。どちらも「自動で入れる」と「声で言う」を併用する前提の作り。

### Gaps
- 日本語で句読点が自動で入る具体的な目安（どれだけ間を空けると「。」になるか、など）は、公式にも報道にも見つからなかった。

## 4. 声で言う句読点とコマンドの言葉（日本語の一覧）

### Takeaway
Windows 11 の音声入力の日本語のコマンドは少なく、止める・消す（「それを削除」）・選ぶ（「それを選択」）・キーを押すだけ。記号は数が多い。音声アクセスには、選ぶ・動かす・直すの日本語のコマンドがそろっている。Mac の日本語のページの一覧は英語の一覧を訳したもので、日本語のページにある「句点」「読点」などの句読点の言葉は載っていない（実際には「まる」「てん」で入ると解説記事にある）。

### Cited Findings
**Windows 11 音声入力（日本語）** — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- 止める: 上の1章を参照
- 直前の語や句を消す: 「それを削除」「それを消去」「それを取り消す」
- 直前の語や句を選ぶ: 「それを選択」
- キー: 「Enter キーを押す」／「BackSpace」「BackSpace キーを押す」／「タブ」「Tab キーを押す」／「Space を挿入」「Space キーを押す」
- 句読点と記号: 、＝「読点」／。＝「句点」「くてん」／?＝「疑問符」「クエスチョンマーク」「はてなマーク」／!＝「びっくりマーク」「エクスクラメーションマーク」「感嘆符」／...＝「三点リーダー」／改行＝「次の行」「新しい段落」「改行」「かいぎょう」／「＝「かぎかっこ開く」「左かぎかっこ」／」＝「かぎかっこ閉じる」「右かぎかっこ」／『』＝「二重かぎかっこ開く／閉じる」「左／右二重かぎかっこ」／(＝「かっこ」「かっこ開く」「左かっこ」／)＝「かっことじ」「右かっこ」「かっこ閉じる」／[＝「左角かっこ」「大かっこ開く」「角かっこ開く」「左大かっこ」／]＝その逆／-＝「マイナス記号」「ハイフン」／#＝「番号記号」／$＝「ドル記号」／%＝「パーセント記号」／&＝「アンド記号」「アンパサンド」／*＝「アスタリスク」／;＝「セミコロン」／@＝「アット記号」「アットマーク」／£＝「ポンド記号」／¥＝「円記号」／€＝「ユーロ記号」／+＝「プラス記号」／<＝「小なり記号」／=＝「等号」／>＝「大なり記号」／±＝「プラスマイナス」／°＝「度記号」

**Windows 10 ディクテーション（日本語の表。ただし Windows 10 では「コマンドは英語（米国）だけ」とも書かれていて、食い違っている）** — 同上
- 選択を外す: 「選択をクリア」「それを選択解除」／消す: 「それを削除」「それを取り消し」「[単語]を削除」
- 動かす: 「[単語]の後に移動する」「[段落]の末尾に移動する」「前の[単語]に戻る」「次の[段落]に進む」「[単語]の先頭に移動」「それの最初に移動」
- 選ぶ: 「[単語]を選択」「それを選択」「次の 3 つの単語を選択します」「前の 2 つの段落を選択します」
- つづり: 「スペリングの開始」「スペリングの停止」
- 記号の機械翻訳らしき項目: スペース＝「宇宙」、*＝「星」「掛ける」、/＝「割る」 など

**Windows 11 音声アクセス（日本語）** — [MS サポート「音声アクセス コマンドの一覧」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/voice-access-command-list)
- 文字を入れる: 「<文字>」そのまま／コマンドの言葉を文字として入れる: 「ディクテーション <コマンド>」（「入力」でもよい — [ディクテーションのページ](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/dictate-text-with-voice)）／前に空白を入れずに入れる: 「空白 <文字>」（No space）／単語の頭を大文字に: 「Caps <文字>」／つづる: 「スペル」
- 句読点: 「ピリオド」「完全停止」「疑問符」「感嘆符」「コロン」「セミコロン」「引用符を開く／閉じる」「ハイフン」「省略記号」「かっこを開く／閉じる」
- 選ぶ: 「選択」（直前に話した文字）「すべて選択」「select <文字>」「前の単語を選択」「次の単語を選択」「単語の選択」「行の選択」「段落の選択」「選択を解除する」「選択のクリア」
- 直す: 「削除する」（選んだ文字か直前に話した文字）「すべて削除」「切り取り」「コピー」「貼り付け」「元に戻す」「やり直す」「空白なし」
- 訂正: 「修正」（選んだ文字か直前の文字の訂正を開く）「正しい <文字>」「ボキャブラリに追加」
- 動かす: 「改行」「新しい段落」「ドキュメントの先頭／最後に移動」「単語の先頭／末尾に移動」「行の先頭／末尾に移動」「段落の先頭／末尾に移動」「左／右 <数> 文字を移動する」
- 一部の訳がぎこちないが、自分でコマンドを作れる。全部の一覧と使い方の案内はヘルプのメニューにある — [窓の杜 2025-10-24](https://forest.watch.impress.co.jp/docs/news/insiderpre/2057245.html)

**macOS 音声入力（日本語のページの一覧）** — [Apple「Macの音声入力のコマンド」(ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40695/mac)
- 書式: 「次の行」「次の段落」「数字」「ローマ数字」「スペースなしオン／オフ」「tabキー」「大文字入」「大文字オフ」「すべて大文字」「すべて大文字オン／オフ」
- 句読点: 「カンマ」「ピリオド／点／ドット／フルストップ」「疑問符」「感嘆符」「コロン」「セミコロン」「省略記号」「ダッシュ」「ハイフン」「引用符」「終わりの引用符」「開き／閉じ丸かっこ」「開き／閉じ角かっこ」「開き／閉じ中かっこ」「開き／閉じ山かっこ」「アポストロフィ」
- 記号: 「中黒」(・)「大きい中黒」(●)「アットマーク」「アンパサンド」「アスタリスク」「スラッシュ」「バックスラッシュ」「キャレット」「度」「ハッシュタグ／ポンド記号」「パーセント記号」「アンダースコア」「垂直バー」「等号」「より大記号」「より小記号」「マイナス記号」「乗算記号」「プラス記号」「ドル記号」「セント記号」「英ポンド記号」「ユーロ記号」「円記号」「著作権記号」「登録商標記号」「商標記号」
- 顔文字: 「笑顔」:-)「しかめ面」:-(「ウインク」;-)「大笑い」XD
- 絵文字: 「ハートの絵文字」「車の絵文字」のように名前を言う（一部の言語だけ）。絵文字を声で入れる機能の対応言語に日本語が入っている — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)、[Feature Availability](https://www.apple.com/macos/feature-availability/)
- 日本語の句読点: 「てん」で「、」、「まる」で「。」が入る。「読点」「句点」でもよいという解説もある — [subanana](https://subanana.com/ja/blog/mac-音声入力)（一次情報ではない）

### Inferences
- Mac の普通の音声入力には「削除」「選択」のような編集のコマンドが、日本語の公式の一覧にない。編集は Voice Control に任せる作り。
- Windows の「それを削除」「それを選択」のように、**「それ」＝直前に話した塊**を対象にするコマンドの形は、直前の結果を単位にして取り消す作りの参考になる。

### Gaps
- Mac の Voice Control の日本語のコマンドの一覧（「削除」「選択」「訂正」など）は、今回は取りに行けなかった。
- Windows 10 の日本語の表と「コマンドは英語（米国）だけ」の記述の食い違いは、解消できなかった。

## 5. 訂正（選んで言い直す・ほかの候補）

### Takeaway
Mac は青い下線をクリックして候補を選ぶか、打ち直すか言い直す。Windows の音声アクセスは「修正」「正しい <文字>」で訂正の窓を開き、番号で選ぶ。選んだ語は Windows の辞書に足され、次からの候補に出る。Windows の音声入力（Win+H）は「それを選択」「それを削除」だけ。

### Cited Findings
- Mac: 自信のない文字には青い下線が付き、クリックすると別の候補を選べる。打ち直すか言い直してもよい — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- 音声アクセス: 「スペル アウト」でつづりの窓を出す。「スペル that」は直前に話した文字を選んで出す。文字・数字・NATO の音標で入れる。候補が出て番号で選ぶ（例: 「1 をクリック」）。入れた語は Windows の辞書に足され、次の候補に出る — [MS「音声でテキストをディクテーションする」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/dictate-text-with-voice)
- 音声アクセス: 「ボキャブラリに追加」で難しい語を登録すると、認識が正しくなる — 同上
- 音声アクセス: 固有名詞を語彙として登録すると、ディクテーションが正しくなる — [窓の杜](https://forest.watch.impress.co.jp/docs/news/insiderpre/2057245.html)
- Windows 流動ディクテーション: 直されたのを「元に戻す」か Ctrl+Z で戻せる — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)

### Inferences
- 「直前の塊を選ぶ → 候補の一覧 → 番号で選ぶ」と「選んだ語を学習の辞書に足す」の組み合わせは、Kanade の変換候補の窓と学習の作りにそのまま重ねられる。

### Gaps
- Mac で文字を選んでから言い直すと置き換わるのか、公式の明言は見つからなかった（「言い直してもよい」とあるだけ）。

## 6. 打鍵と音声入力を混ぜる

### Takeaway
Mac（Apple シリコン）は話しながら打ってよいと公式に書かれていて、「On-Device and Modeless Dictation」の対応言語に日本語が入っている。Windows の音声入力にはそういう記述がない。

### Cited Findings
- Mac: Apple シリコンの Mac では、話しながらキーボードを使ってよく、音声入力を止める必要はない — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)
- 「On-Device and Modeless Dictation」の対応言語に Japanese (Japan) が入っている。音声のモデルのダウンロードが必要 — [Feature Availability](https://www.apple.com/macos/feature-availability/)
- 音声アクセス: パスワードなど辞書に残したくないものは、声でタッチキーボードを出して打つ（「キーボードの表示」「キーボードを非表示にする」） — [MS「音声でテキストをディクテーションする」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/dictate-text-with-voice)

### Gaps
- Windows の音声入力の最中にキーで打ったとき、聞き取りが止まるのか続くのかは、一次情報がない。

## 7. 言語の選び方・日本語と英語を混ぜる

### Takeaway
Mac は複数の言語を足しておき、カーソルの横の表示か地球儀のキーで切り替える（同時に聞くのではなく切り替え）。Windows の音声入力は、入力の言語に合わせて動く。英語と日本語を一度に混ぜて聞く機能は、どちらの公式の説明にもない。

### Cited Findings
- Mac: 「キーボード設定 > 音声入力 > 言語 > 編集」で複数の言語を足し、カーソルの横の言語の表示をクリックするか、地球儀のキーで切り替える — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- Windows 11 音声入力の対応言語に、日本語・韓国語・中国語（簡体・繁体 HK・繁体 TW）、英語・仏・西・葡・独・伊のいくつかの地域が入っている — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- Windows 10 ディクテーション: 日本語は基本のディクテーションの言語の一覧に入っていない（簡体中国語・英語の各地域・仏・独・伊・葡(BR)・西だけ） — 同上
- 音声アクセス: 英語・スペイン語・フランス語・ドイツ語・中国語・日本語 — [MS「音声アクセス コマンドの一覧」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/voice-access-command-list)。一方、MS の FAQ のページには「英語・ドイツ語・スペイン語・フランス語」と古い記述が残っている — [MS FAQ](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/voice-access-frequently-asked-questions-faqs)（検索結果の抜粋による）

### Gaps
- 日本語のディクテーションの最中に英単語を言ったとき、どちらの OS が何として入れるか（カタカナか英字か）を確かめた情報は見つからなかった。

## 8. 端末の上で処理するかクラウドか・プライバシー

### Takeaway
日本語を**ネットなしで**使えるのは、Mac の音声入力（端末の上のモデル。初回にダウンロードが要る）と、Windows 11 の音声アクセス（言語のファイルをダウンロード）。Windows 11 の音声入力（Win+H）は**ネット必須**で、Azure Speech で処理する。

### Cited Findings
- Windows 音声入力: インターネットへの接続・マイク・テキストボックスのカーソルが必要。オンラインの音声認識（Azure Speech サービス）を使う — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- Windows 10 ディクテーションもインターネットが必要 — 同上
- 音声アクセス: 「インターネットに接続せずに」声で PC を操作し、文字を作れる。Windows 11 22H2 以降 — [MS「音声アクセス コマンドの一覧」](https://support.microsoft.com/ja-jp/Accessibility/windows/voice-access/voice-access-command-list)
- 音声アクセスの日本語: 2025年6月から Dev／Beta で試験。Release Preview の Build 26100.7015／26200.7015（KB5067036、24H2／25H2）に入った。初回に言語のファイルのダウンロードとマイクを許可すると、日本語の環境が自動で整う。「音声を認識する処理自体は、ローカルで完結する」 — [窓の杜 2025-10-24](https://forest.watch.impress.co.jp/docs/news/insiderpre/2057245.html)
- 音声アクセスは、非推奨になった「Windows 音声認識」の後継 — 同上
- Mac: 普通の文字の音声入力は、端末で処理されて Siri のサーバーに送られない（キーボード設定の説明で確かめられる）。ただし**検索欄の音声入力はこの限りではない** — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)
- Mac: 「On-Device and Modeless Dictation」に日本語が入っている。「Requires download of speech models.」（音声のモデルのダウンロードが必要） — [Feature Availability](https://www.apple.com/macos/feature-availability/)
- Mac: 「オーディオ収録を共有」を選ぶと、Apple が Siri と音声入力の音声を保存し、「レビューに利用することがあります」。あとから「プライバシーとセキュリティ > 解析と改善 > Siriと音声入力の改善」で変えられる — [Apple (ja)](https://support.apple.com/ja-jp/guide/mac-help/mh40584/mac)
- Windows 流動ディクテーション（英語）は端末の上の SLM で動く — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)

### Inferences
- Windows で日本語をネットなしで声で入れたいなら、Win+H ではなく音声アクセスを使うことになる。
- Mac で「端末の上で処理する」のは、Feature Availability の項目の名前から、Apple シリコンの Mac が前提とみられる（「話しながら打てる」も同じ前提）。

### Gaps
- Windows の音声入力の音声データを保存するか、どれだけの期間残すかは、ページに書かれていない（プライバシーの声明へのリンクだけ）。
- Apple Platform Security の文書は今回確かめられなかった。

## 9. 日本語IME（Microsoft IME／Google 日本語入力／ATOK）の音声入力

### Takeaway
デスクトップでは、どの IME も**自前の音声入力を持っていないようだ**（見つからなかった）。Windows では、OS の音声入力（Win+H、タッチキーボードのマイク）が IME の代わりを務める。

### Cited Findings
- Windows のタッチキーボードのマイクのボタンから、OS の音声入力が始まる — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- 一太郎2024（2024年2月発売）は、Windows 11 の音声認識を使った音声入力に対応した。ATOK そのものの機能ではない — [窓の杜](https://forest.watch.impress.co.jp/docs/news/1659067.html)（検索結果の抜粋による）
- ATOK for Windows（2025年2月、ATOK Passport）の紹介記事にも、音声入力の記述はない — 同上

### Gaps
- Microsoft IME、Google 日本語入力、ATOK の Windows 版・Mac 版に、音声入力が「ない」ことを公式に確かめた資料は見つからなかった。見つからなかったという結果だけ。Android 版の ATOK や Gboard の音声入力は、この調べの範囲の外。

## 10. 知られている限界・不満

### Cited Findings
- Windows Win+H: 少し黙るとすぐ止まり、Win+H を押し直す必要がある。Anki など一部のアプリで起きやすい — [Anki Forums](https://forums.ankiweb.net/t/windows-voice-typing-functionality-is-limited-on-this-app/23255)、[yaps.ai](https://www.yaps.ai/blog/windows-voice-typing-keeps-stopping)（自社の製品を売る会社の情報）
- Windows: 句読点と基本の編集のコマンドはあるが、Dragon や Whisper の道具に比べると機能が限られる — [Voxbooster](https://voxbooster.com/ja/blog/voice-typing-windows-11/)（自社の製品を売る会社の情報）
- Windows 10 の日本語の記号の表には、機械翻訳らしき誤り（スペース＝「宇宙」など）がある — [MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- 音声アクセスの日本語のコマンドには、訳がぎこちないものがある — [窓の杜](https://forest.watch.impress.co.jp/docs/news/insiderpre/2057245.html)
- 言語モデルで文を整える機能は、どちらも英語だけ（Mac の Apple Intelligence は M3 以降・12GB 以上、Windows は Copilot+ PC だけ） — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)、[MS サポート](https://support.microsoft.com/ja-jp/windows/音声入力を使用して-pc-で話す代わりに入力する-fec94565-c4bd-329d-e59a-af033fa5689f)
- Mac: Voice Control がオンだと、普通の音声入力は使えない — [Apple (en)](https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac)

### Gaps
- 日本語の認識がどれだけ正しいかを比べた数値（誤り率など）は、信頼できる情報が見つからなかった。
