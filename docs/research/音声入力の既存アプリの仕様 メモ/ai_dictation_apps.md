# AI 音声入力アプリ（Superwhisper・Wispr Flow・Aqua Voice・MacWhisper・VoiceInk・Willow・Typeless ほか）の仕様と UX

調査日: 2026-11-08。出典の多くは各社の公式ページだが、比較記事は競合の会社が書いたものが多い（Willow・Voibe・Spokenly・HyperWhisper・VoiceOS・LumeVoice・Yapper など）。数値のうち「公式の主張」と「第三者の計測」は分けて書く。

## 概要（アプリ別の要点）

### Takeaway
商用の主流（Wispr Flow・Aqua の既定・Superwhisper の既定・Typeless）は「押している間だけ録る → 離したら LLM で整えてから一度に貼る」形。途中の文字を流して見せる機能は後から足された有料の別モード（Aqua の Realtime Mode は Max プラン、Superwhisper の Realtime は Pro で、ストリーミングに対応したクラウドのモデルに限る）で、そこでは整形が弱まるか、別の会社の認識エンジンを使う。

### Cited Findings

**Wispr Flow（クラウド。Mac・Windows・iPhone・Android）**
- 起動: 押している間だけ録る（Mac の既定は Fn、Windows は Ctrl+Win）。ハンズフリーは Fn を2回叩く、または Fn+Space（Windows は Ctrl+Win+Space）を1回押す。止めるときは Fn を1回押すか、Flow Bar の赤い停止ボタン — [aiproductivity.ai guide](https://aiproductivity.ai/guides/wispr-flow-voice-dictation-guide/)、[Waction（日本語）](https://review.waction.org/tools/wisprflow/how-to-use)
- 1回の録音は最長6分。上限が近づくと警告が出て、すぐに録り直せる（第三者のレビュー。公式では未確認） — [easyai review](https://easyai.indevs.in/wispr-flow/)
- Smart Formatting（既定オン）: フィラー・文法・句読点・大文字小文字を整える。**聞き間違えた語は直さない**。整形に失敗したときは、整える前の文字起こしをそのまま貼る — [Wispr docs](https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-and-backtrack)
- Backtrack: 「actually」「scratch that」「never mind」と言うか、言い直すと、言い間違いと言い直しの前の部分を消す。判断には発話の全体を使う。例:「Let's do coffee at 2 actually 3」→「Let's do coffee at 3.」。言い直しでない「I actually enjoyed the movie」は残す — 同上
- 句読点や改行は口で言っても入れられる（「period」「comma」「new line」「new paragraph」など）。改行の指示は Smart Formatting の設定に関係なく効き、間を置いたところで言うとうまく効く。em ダッシュは自分からは入れない。「one… two…」「first… second…」と言うと箇条書きになる — 同上
- 文末の句点を消すかどうかは Writing Style で決まる: Formal は消さない／Casual は10文まで／Very Casual は実質無制限／Excited か未設定ならメッセージのアプリで2文まで／iOS ではメッセージのアプリなら常に消す。選んだ文字があるときと、行にすでに句点・!・? があるときは消さない — 同上
- AI の編集を取り消す: デスクトップの Home にある Recent Activity から「Undo AI edit」で元の文字起こしに戻せる — 同上
- Styles（アプリごとの口調）は英語とデスクトップだけ。辞書は直した綴りを自動で覚え、手でも登録できる。Snippets は短い言葉で長い定型文を入れる。「100+ languages」とあるが、例の言語に日本語は入っていない。ささやき声でも使える。Cursor と Windsurf ではファイル名を言うとタグにする — [wisprflow.ai/features](https://wisprflow.ai/features)
- Command Mode（Pro、Mac と Windows）: 選んだ文字を声で書き換える・訳す・文を作る・書き方の好みを変える — [Spokenly review](https://spokenly.app/blog/wispr-flow-review)、[anintent.com](https://anintent.com/blog/use-wispr-flow-dictation-command-mode/)
- 遅延: Baseten（推論の基盤を出している会社）の事例紹介では、認識から Llama での整形までの全体で p99 が 700ms 未満。第三者の計測では、中央値 880ms・上位の値 1,340ms（Yapper、2026年4月）、平均 1,805ms（LumeVoice）、普通の長さで約 900ms（Willow、2026年9月）。体感は 1〜2秒という評もある — [Baseten](https://baseten.co/customers/wispr-flow)、[Yapper benchmark](https://yapper-web.pages.dev/blog/voice-to-text-latency-benchmark/)、[Willow benchmark](https://willowvoice.com/blog/speech-to-text-latency-benchmark)、[Spokenly](https://spokenly.app/blog/wispr-flow-review)
- 料金（第三者の集計）: Basic は無料で、デスクトップは週2,000語、iPhone は週1,000語、Android は期間限定で無制限。Pro は月$15、年払いなら月$12 — [usevoicy](https://usevoicy.com/blog/wispr-flow-pricing)、[dailyaifixs](https://dailyaifixs.com/blog/wispr-flow-pricing-2026-the-weekly-word-cap)
- プライバシーの不満: 2026年2月、Reddit の r/macapps と r/privacy で「Context Awareness が画面を撮ってクラウドへ送っている」と炎上し、CTO が認めて設定と規約を直したという。会社側は「アクセシビリティの API でカーソル付近の文字を読むだけ」と説明していて、言い分は食い違っている。Privacy Mode（学習に使わない・保存しない）は既定でオフ。Trustpilot は 2.7/5（2026年4月） — [getvoibe review](https://www.getvoibe.com/resources/wispr-flow-review/)、[letterly](https://letterly.app/blog/wispr-flow-review/)

**Aqua Voice（クラウド。Mac・Windows・iOS 17+・Android 10+）** — 出典はすべて [aquavoice.com/llms.txt](https://aquavoice.com/llms.txt)（公式。2026-09-24 更新）
- Instant Mode（既定。全プラン）: キーを押して話し、離すと文字が出る。話し終えてから約 450ms で出る（概要の欄には「約1秒、速ければ約450ms」とある）。起動は 50ms 未満（Modes の欄では 200ms 未満とあり、ページの中で食い違っている）
- Realtime Mode（Max と Business。無料で試せる期間あり。2026年7月に「Streaming Mode」から名前を変えた）: 話している間の言葉をツールバーに出す。Aqua のキー（Mac は Fn、Windows は右 Alt）を2回押すとハンズフリーになり、押しながら Space を叩いても同じ。1回押すと終わる。**Realtime では音声を他社の認識エンジンへ流す**（Instant は自社の Avalon）
- 「Send It」（v0.18.0、2026-07-26、Max）: Realtime で話した最後に「send it」と言うと、文字を入れて送信の操作（Enter や Cmd+Enter など）までする。日本語は「送って」。Instant と Edit Mode では使えない
- Edit Mode（デスクトップ、v0.17.0、2026年7月に全員へ）: 文字を選んでキーを押しながら直し方を言うと、選んだところをその場で書き換える。声での取り消しはできず、アプリの Undo を使う。iOS には、直前の文字起こしを声で直す Voice Edit Mode がある
- Deep Context（既定オフ。Mac と Windows）: 話し始めに画面を1枚だけ撮り、聞き間違えそうな語（人名など）を拾って認識に使う。撮った画像は残さない。例: Jeff→Geoff、Kaitlin→Kaitlyn
- 辞書（大文字小文字も保つ）・Custom Instructions（アプリごとの自然文の規則。Pro 以上）・Replacements（短い言葉を長い文に広げる）。Cursor と Windsurf ではファイル名を言うと `@main.ts` のタグにする
- モデル: Avalon は 2025年8月に出て、2025年10月の Open ASR Leaderboard で全体6位・独自モデルで1位（WER 6.24%）。Avalon 1.5（2026年4月）は WER 5.55% で、Avalon 1 の2倍より速い。AI やプログラミングの用語を集めた試験（AISpeak-10）では 97.4%（Whisper Large v3 は 65.1%）
- 49言語（日本語を含む）。言語を Auto にすると見分ける。料金: Free は 1,000語まで（累計）、Pro は月$10（年払い）か $12、Max は月$19 か $24、Business は1席 月$19〜

**Superwhisper（Mac・Windows・iOS・Android。手元のモデルもクラウドのモデルも選べる）**
- Toggle Recording（既定 ⌥Space）は押すと始まり、もう一度押すと止まる。Push to Talk は押している間だけ録り、さっと叩くと Toggle と同じ動きになる。マウスのボタンでも、短く押すと Toggle、長押しすると Push to Talk。Cancel は、短い録音ならすぐ捨て、長い録音なら確かめてから捨てる。モードは録音の前にも途中にも替えられる（⌥⇧K）。録音中に流れている音を止める・消す・下げる・こもらせる（Muffle）を選べる — [Superwhisper docs: settings](https://superwhisper.com/docs/get-started/settings-shortcuts)
- Realtime（Pro）: 話している間の言葉を画面に出す。使えるのはストリーミングに対応したモデル（Nova 3・Nova 2・Nova Medical・ElevenLabs Scribe v2 Realtime などのクラウドのモデル）だけで、Whisper 系では使えない。Scribe と Nova では話し手の区別と同時に使えない — [Realtime docs](https://superwhisper.com/docs/common-issues/realtime)、[Scribe v2 blog](https://superwhisper.com/blog/scribe-v2)
- Super Mode の文脈: (1) アクセシビリティの API で前面のアプリと、入力欄の文字を読む、(2) 選んでいる文字、(3) 話す前3秒以内か話している間にコピーした文字。選んだ文字を読むのは「文字起こしが済んでから AI で整える前」なので、その時点で前面にある窓のものになる。組み込みのモードの多くは文脈を使わない — [Super mode docs](https://superwhisper.com/docs/modes/super)、[context docs](https://superwhisper.com/docs/common-issues/context)
- 録音の窓を出さず、貼る設定も切ったときは、結果をクリップボードに入れるだけになる — [context/advanced 関連 docs](https://superwhisper.com/docs/get-started/settings-advanced)
- 日本語: 既定では言語モデルが「えー」などの間投詞を消し、言い始めのつまずきを直し、文法を整える。英語の技術用語はおおむね正しく出るが、珍しい人名はよく間違え、単語登録で補う。Pro は月$8.49 — [usevoicy（日本語）](https://usevoicy.com/ja/blog/superwhisper-review/)、[Zenn](https://zenn.dev/ispec_inc/articles/0ab372a60041fe)

**VoiceInk（Mac 15+。オープンソース GPLv3、買い切り）**
- 手元で動くモデル: whisper.cpp・Parakeet（FluidAudio）・SenseVoice Small・GGUF。「100% オフライン」。アプリや URL ごとに設定を切り替える（Power Mode。今は Modes と呼ぶ）。画面の中身を読んで文脈に合わせる AI。個人の辞書と置き換え。キーボードやマウスのショートカットで、すぐ録るのと押している間だけ録るのを選べる — [GitHub Beingpax/VoiceInk](https://github.com/Beingpax/VoiceInk)

**MacWhisper（Goodsnooze）**
- 元はファイルの文字起こしが中心で、Whisper を軸に、後から Parakeet も選べるようになった（Pro $30）。どのアプリでも使える「ディクテーション」は、文字を窓に起こす形だと評されている — [buildbetter](https://blog.buildbetter.ai/best-superwhisper-alternatives-in-2026-top-local-ai-dictation-tools-for-mac/)、[MPU forum](https://talk.macpowerusers.com/t/macwhisper-so-fast-so-helpful/41147)、[HyperWhisper](https://www.hyperwhisper.com/en/blog/speech-to-text-real-time-streaming-comparison)

**Willow Voice（Mac・Windows・iOS）**
- 自社の主張では遅延は約 200ms。2026年9月の自社の計測では、短い発話で約 400ms、普通の長さで約 900ms。書き方の癖を覚える・AI Mode・文脈から候補を出す・辞書。月$15、年$144。無料枠は資料によって「無制限（Frontier Mini）」と「月30分」に分かれる — [Willow blog](https://willowvoice.com/blog/speech-to-text-latency-benchmark)、[getvoibe review](https://www.getvoibe.com/resources/willow-voice-review/)

**Typeless（Mac・Windows・iOS・Android。クラウド）**
- 1回に最長6分話せる。ささやき声のモード。いくつかの言語を混ぜて話しても理解して整え、訳す。無料は週4,000語、Pro は年払いで月$12。履歴は端末の中だけに残し、残す期間は数時間から1カ月で選べる。開発元は、サーバーに残さず学習にも使わないとしている。不満は、普通のキーボードが付いておらず、声を出せない場では別のキーボードへ切り替えないといけないこと（Lifehacker、2026-02-22） — [Lifehacker Japan](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/?page=2)
- フィラーの自動削除と言い直しの整理。整えすぎて言い回しが変わるという不満がある。オフラインでは使えない — [digirise](https://digirise.ai/chaen-ai-lab/typeless/)、[usevoicy（日本語）](https://usevoicy.com/ja/blog/typeless-review/)

**日本の製品**
- ソースネクスト「いきなりAI音声入力 for Mac」（2026-04-24 発売）: オフラインで動く。月額495円・年額4,950円・買い切り9,900円 — [washinmura（発表の転載）](https://aeo.washinmura.jp/ai/sourcenext-co-jp/en/news/2026-04-24-ikinari-ai-voice-typing-for-mac-launched)
- VoiceOS は日本語の比較ページを出している（中身は競合の宣伝） — [voiceos.com/ja](https://www.voiceos.com/ja/compare/aqua-voice-japanese-accuracy)

### Inferences
- 全体の傾向: 起動は「押している間だけ録る」を既定にし、ハンズフリーは「2回叩く」か「キー＋Space」で入り、1回押すと終わる。この形が各社でほぼそろっている（Wispr・Aqua・Superwhisper）。

### Gaps
- 貼り付けのしかた（クリップボードを使うか、キー入力を真似るか）と、元のクリップボードを戻すかどうかは、どの公式資料でも詳しく確かめられなかった。Superwhisper は「貼らない設定ならクリップボードに残る」ことだけ確かめた。
- 話していないことを見て自動で止める機能（無音の検出）の仕様は、どのアプリも見つからなかった（Superwhisper は無音を除く設定が詳細設定にあることだけ）。
- MacWhisper の 2026 年の変更履歴、Willow の公式の料金ページ、Wispr の公式の料金と Command Mode の公式ドキュメントは、今回は取れていない。

## 途中の文字を流して見せるアプリと、離してから入れるだけのアプリの違いと、その理由

### Takeaway
既定はどこも「離してから一度に入れる」。理由は、(1) 発話の全体を見ないと LLM で整えられない（言い直しの消去・句読点・口調。Wispr の Backtrack は「発話の全体を文脈にする」と明記）、(2) 手元のモデルや自社のバッチ用モデルを使える、(3) 入れたあとで書き換えると入力欄が乱れる、の3つ。流して見せる機能は Aqua（Realtime。Max）と Superwhisper（Realtime。Pro・クラウドのモデル）にあり、どちらも他社のストリーミング用の認識エンジンに頼り、音声と文字を送る先が変わる。

### Cited Findings
- Aqua: Instant（離すと約 450ms で出る。自社の Avalon）と Realtime（ツールバーに流して出す。他社の認識エンジン）。「Send It」は Realtime でしか使えない — [aquavoice.com/llms.txt](https://aquavoice.com/llms.txt)
- Superwhisper: Realtime はストリーミングに対応したクラウドのモデルだけ。Whisper 系ではバッチになる — [Superwhisper Realtime docs](https://superwhisper.com/docs/common-issues/realtime)
- Wispr Flow はクラウドに送ったあと AI でもう一度整え、口調を合わせるのでバッチの形 — [HyperWhisper（2026-02-10。競合の記事）](https://www.hyperwhisper.com/en/blog/speech-to-text-real-time-streaming-comparison)
- バッチは「AI で整えて形を変えてから見せたい」ときに向き、手元のモデルや特殊なモードとも合う。離してから 1〜3秒待つ。ストリーミングは約 100ms ごとに音声を送り、「待たされて考えが途切れる」のを防ぐ — 同上
- Wispr は、整形に失敗したら生の文字起こしを貼る。「Undo AI edit」で整える前に戻せる — [Wispr docs](https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-and-backtrack)
- Apple のディクテーションは端末の中で動き、話している間の言葉を出すが、精度は低く、独自の語彙も登録できない — [HyperWhisper](https://www.hyperwhisper.com/en/blog/speech-to-text-real-time-streaming-comparison)

### Inferences
- 流して見せる表示は、入力欄そのものではなくアプリの窓（Aqua のツールバー）に出して、確定したものだけを入れる形が主流に見える。こうすると、入力欄の中身を何度も書き換えずに済む。
- IME に組み込む場合は、変換前の文字（未確定の文字）として途中の文字を見せ、離したら LLM で整えた結果と入れ替えて確定する、という形にすれば両方の良さを取れる（推測）。

### Gaps
- 各社が「なぜストリーミングを既定にしないか」を説明した、創業者の一次の発言は見つからなかった。

## フィラー（「えー」など）・言い直し・句読点の扱い

### Takeaway
LLM の後処理で、フィラーを消し、句読点を補い、言い直しの前の部分を消す（Wispr の Backtrack は「actually」「scratch that」をきっかけにしつつ、発話の全体から言い直しかどうかを判断する）。句読点は口で言っても入れられる。文末の句点はアプリと口調によって消す。どこも「聞き間違えた語は整形では直さない」とし、辞書と画面の文脈で補う。

### Cited Findings
- Wispr: Smart Formatting と Backtrack の詳細と例（上のアプリ別の欄） — [Wispr docs](https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-and-backtrack)
- Superwhisper（日本語）: 既定で間投詞を消し、言い始めのつまずきを直し、文法を整える — [usevoicy ja](https://usevoicy.com/ja/blog/superwhisper-review/)
- Typeless: フィラーを消し、言葉の繰り返しを直す。一方で、言い回しまで変わるという不満がある — [digirise](https://digirise.ai/chaen-ai-lab/typeless/)、[usevoicy ja](https://usevoicy.com/ja/blog/typeless-review/)
- Wispr の日本語での使用: 繰り返しを自動で整理する。声で「もっと丁寧に」と頼むと直す（日本語でも効くが、毎回ではない）。辞書で表記を決められる — [アシカガコウジ note](https://note.com/ashikagacast/n/n42e033c39894)、[WEEL](https://weel.co.jp/media/innovator/wispr-flow/)
- Aqua: 聞き間違いそうな人名を、話し始めに撮った画面から拾って直す（Deep Context） — [aquavoice.com/llms.txt](https://aquavoice.com/llms.txt)

### Inferences
- 「直さない」と「直しすぎない」の両方を守るため、元に戻す手段（Wispr の Undo AI edit、整える前の文字起こしを貼る）を用意するのが定番になっている。

### Gaps
- 日本語の「えーと」「あのー」の消し具合や、「じゃなくて」「やっぱり」による言い直しの扱いを公式に書いたものはない。

## 声で直すモード（command mode）の形

### Takeaway
文字を選ぶ → いつもの音声キーを押しながら直し方を言う → 選んだところをその場で書き換える、が共通の形（Wispr の Command Mode・Aqua の Edit Mode・Superwhisper の Super Mode で選んだ文字を文脈にする）。取り消しはアプリの Undo に任せる。最後に「送って」と言って送信までするもの（Aqua の Send It）や、カーソルを動かすエージェント（Aqua の Computer Control。ベータ）まで広がっている。

### Cited Findings
- Aqua Edit Mode: 文字を選び、キーを押しながら直し方を言うと、その場で書き換える。声での取り消しはない（2026年7月20日に全員へ） — [aquavoice.com/llms.txt](https://aquavoice.com/llms.txt)
- Aqua Send It: 「send it」や「送って」と言うと、入れてから Enter や Cmd+Enter を送る（Auto・Enter・Cmd/Ctrl+Enter・貼るだけ、から選ぶ） — 同上
- Wispr Command Mode（Pro）: 選んだ文字を書き換える・訳す・文を作る・好みを変える — [Spokenly](https://spokenly.app/blog/wispr-flow-review)、[anintent](https://anintent.com/blog/use-wispr-flow-dictation-command-mode/)
- Superwhisper: 選んだ文字は文字起こしが済んだあとに読む。選んでいる窓が前面にないと読めない — [context docs](https://superwhisper.com/docs/common-issues/context)

### Inferences
- 文字を選んでいるかどうかで「入れる」と「直す」を切り替えるので、キーを1つ増やさずに済んでいる。

### Gaps
- Wispr の Command Mode の公式ドキュメント（呼び方・何ができないか）は取れていない。

## 日本語と、日本語と英語が混じった発話の扱い

### Takeaway
日本語の利用者の感想はおおむね良い（Wispr と Typeless は日英が混じっても高い精度で起こすという声）。ただし、口調の機能（Wispr の Styles）は英語だけで、漢字かひらがなかの選び分けや句読点の自然さには弱いという指摘もある（競合の記事）。日本語の精度を数値で比べた資料は見つからなかった。

### Cited Findings
- Wispr: 「雑な日本語と英語を、雑な使い分けも含めて精度高く認識」 — [Yoichi Kawasaki on X](https://x.com/yokawasa/status/2039505142073188863)。セットアップで日本語を選べる。Style は英語だけ — [Waction](https://review.waction.org/tools/wisprflow/how-to-use)。iPhone では日本語のキーボードのすぐ下に置くと、誤認識を直しやすい — [haru note](https://note.com/umu11/n/n8cdcca473b10?hl=en)。スマホで声だけでブログを書いた記事もある — [r7kamura](https://r7kamura.com/articles/2026-01-08-whisper-flow)
- Aqua: 日本語は 49 言語の1つで、Send It は「送って」で効く — [llms.txt](https://aquavoice.com/llms.txt)。漢字の誤変換・不自然な句読点・英語の製品名の表記揺れが起きやすい、という指摘は競合の VoiceOS のもの — [VoiceOS](https://www.voiceos.com/ja/compare/aqua-voice-japanese-accuracy)。専門用語には強く入力も速いが、クラウドでしか動かず、無料枠は 1,000語で尽きる — [aipicks](https://aipicks.jp/mag/aquavoice-review-2026)。Mac 標準の音声入力では漢字とひらがなの選び分けがうまくいかず、Aqua に期待する、という個人のメモ — [scrapbox](https://scrapbox.io/eyehatesns/Aqua_Voice)
- Typeless: いくつかの言語を混ぜて話しても正しく扱う — [Lifehacker Japan](https://www.lifehacker.jp/article/2602-typeless-ai-voice-typing-android/?page=2)
- Superwhisper: 英語の技術用語は正しく出やすいが、珍しい人名は間違えやすい — [usevoicy ja](https://usevoicy.com/ja/blog/superwhisper-review/)

### Inferences
- 日本語では、認識そのものより「表記を決める」（漢字かひらがなか・全角か半角か・英語の製品名の書き方）ところが不満になりやすい。IME の辞書や学習と組み合わせる余地が大きい（推測）。

### Gaps
- 日本語の WER や CER（文字の誤り率）、日本語での遅延を測ったものは見つからなかった。言語を Auto にしたときに日英の切り替えに強いかどうかも、確かな資料がない。
