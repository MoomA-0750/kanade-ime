# 聞き続けるモードを PC の版（Windows・Linux・macOS）に入れる設計

2026-11-21、design-planner の案。本人の確認前の仮決定（完全自走のため、ここの推す案で作り始めてよい）。
本人に決めてほしいことは末尾の「本人の判断待ち」にまとめた。

## 目的と範囲

- SPEC 8-3（短押し＝聞き続けるモード、長押し＝押している間だけ）、8-11（声のコマンド）、8-13（Esc で話している途中を捨てて終える）、8-15（効果音）を、PC の3つの版で Android と同じにする
- macOS の「短押し＝押すたびに始める・終える」の例外（8-3）をなくす
- 範囲の外（あとで）: 押している間の録音での声のコマンド（8-11 の前半。PC には今も無い）、8-12（直後の Backspace で丸ごと消す・候補で選び直す）、言いよどみ・ユーザー辞書の後処理。これらは今回の土台（エンジン側の区切りと命令）の上に後から足せる

## 今の形（読んで分かったこと）

| 所 | 今 | 聞き続けるモードに要ること |
|---|---|---|
| 録音 | 3つの OS とも **エンジン（JVM）が録る**（`DesktopVoice`、javax.sound、16kHz）。mac の Swift は録らない（キーを見てエンジンへ `voice start/cue/stop/cancel` を送るだけ） | 録っている途中の音を区切り判定へ流す口 |
| 録音のため方 | `ByteArrayOutputStream` に全部ためる。上限 2 分（`MAX_BYTES`）で黙って捨てる。`stop()` で全部を1回だけ認識 | 区切りごとに古い音を捨てる口・区切りを認識する口 |
| 認識 | `worker`（1本のスレッド）で順に。モデルは `start()` で先読み | そのまま使える（順に返るので順序は保てる） |
| 短押し・長押し | Windows: F12 の `key d/u` がエンジンへ届き、**エンジン**が「押したら始め、離したら認識」。短押しの区別なし・押した瞬間に長押しの音。Linux: **アドオン**が 0.3 秒未満の離しを「押し間違い」として `voice cancel quiet`。Mac: **Swift** が 0.3 秒未満なら `voice cue tap` を送ってトグルへ（次の押しで `voice stop`＝まとめて認識） | 判定を1か所に寄せる |
| Esc | Linux・Mac はフロントが自分の状態（`voiceMode_`・`mode`）を見て `voice cancel`。Windows は **録音中の Esc を扱っていない**（`EngineHost.key()` は録音を見ない） | エンジンが勝手に終える（無音・「止めて」）ので、フロントの状態がずれる。エンジンから状態を知らせる口が要る |
| 入力欄が替わった | Windows: `OnSetFocus` で `focus 0/1` を送る。Linux: Kanade の入力方式のときだけ `activate/deactivate` で `focus`。ほかの入力方式では知らせていない。Mac: Kanade のときだけ `activateServer/deactivateServer` で `focus` | どの入力方式でも「書き先が替わった」をエンジンへ |
| 声のコマンド | PC には無い（`VoiceCommand`・`PauseSplitter` は core にあるが使っていない）。Android の判定と実行は `KanadeInputMethodService.kt` の中（core ではない） | Enter・Tab・全部消す・直前の文を消す をフロントで出す口 |
| 設定 | `DesktopStore` は `voice_commands`・`continuous_idle_seconds` を読み書き済み（Android との取り込み用）。エンジンへは渡していない。設定の画面4つ（Linux GTK・Linux Qt・Windows C#・Mac SwiftUI）には項目が無い。**Windows の `Store.cs` はファイルを丸ごと書き直すので、知らない項目を消す**（今でも Android から取り込んだこの2つを消す） | 4つの画面に項目、`Store.cs` に読み書き |

## 決めたこと（論点ごと）

### 1. 録音の途中の音の取り出し

| 案 | 中身 | 変える所 | 壊れやすい所 | 試しやすさ |
|---|---|---|---|---|
| A. Android の `VoiceInput` と同じ口（`sampleCount`・`rms(from,to)`・`take()`・`trimTo()`）を `DesktopVoice` に生やし、エンジンが 100ms ごとに見に行く | 引いて読む | DesktopVoice・EngineHost にタイマー | タイマーと録音のスレッドの取り合い。試験で時刻を作りにくい | 偽の時計とタイマーが要る |
| **B. 録音のスレッドが読んだ塊（約 100ms）を `onAudio(FloatArray)` で押し出す**。区切りの判定は受け取った側（エンジン）が持つ | 押して渡す | DesktopVoice に引数1つと小さな口2つ | 少ない（判定は純粋な関数） | 試験で wav を好きな大きさで流せる |
| C. 区切りの判定まで DesktopVoice に入れ、区切りの音だけ返す | 録音の中で完結 | DesktopVoice が大きくなる | 判定が javax.sound と一緒になり、試験で通せない | 悪い |

**推す: B。** `VoiceService` を次のように広げる（既定の実装を付けて、試験の偽物の修正を少なくする）。

```kotlin
interface VoiceService {
    fun start(english: Boolean = false, onPartial: (String) -> Unit = {}, onAudio: ((FloatArray) -> Unit)? = null): Boolean
    fun stop(onText: (String?) -> Unit)        // 今のまま（押している間の録音）
    fun cancel()                                // 今のまま
    /** 聞き続けるモード: マイクを閉じるだけ（認識しない。残りの音は区切りの判定が持っている） */
    fun halt() = cancel()
    /** 聞き続けるモード: ためている音を後ろ [keepSamples] だけ残して捨てる（途中の文字を今の区切りだけで作るため。2分の上限にも当たらなくなる） */
    fun trim(keepSamples: Int) {}
    /** ひと区切りの音を文字にする。録音と並べて worker で順に動かし、呼んだ順に [onText] を返す */
    fun recognize(pcm: FloatArray, english: Boolean, onText: (String?) -> Unit)
}
```

- `DesktopVoice` の録音のスレッドは、`samples` に書くのと同じ所で `onAudio` に float にした塊を渡す（3200 バイト＝1600 サンプル＝100ms）
- 途中の文字（`partial` のスレッド）は今のまま `samples` を読む。エンジンが区切りの終わり・無音のあいだに `trim()` を呼ぶので、途中の文字は今の区切りだけで作られる（Android の `trimTo` と同じ）
- 試験用に、`-Dkanade.voiceStreamWav=<wav>` があれば、マイクの代わりにその wav を実時間で流す（録音のスレッドの読み元を差し替えるだけ）。声を出さずに Linux の PC・to-fu で通しで確かめるため。今の `-Dkanade.voiceWav`（`stop()` で差し替える）は残す

### 2. 無音の区切り（core の部品の使い回し）

| 案 | 中身 | Android と共通にできるか |
|---|---|---|
| A. `VoiceEndpointer` をエンジンでそのまま使い、Android の `continuousTick` の中身（話し始め前 0.5 秒だけ残す・区切りで取り出す・無音 N 秒で終える）を EngineHost に写す | 写しが2つになる | できない |
| **B. core に `ContinuousSegmenter`（新しい純粋なクラス）を足す。`VoiceEndpointer` を中に持ち、押し出された塊を受けて出来事を返す** | 1か所 | Android も後で `continuousTick` をこれに替えられる（今回は Android に触らない） |

**推す: B。**

```kotlin
/** 聞き続けるモードの区切り。録った音を順に渡すと、話し始め・ひと区切り（音つき）・話さないまま時間切れ を返す */
class ContinuousSegmenter(
    sampleRate: Int = 16_000,
    idleMs: Long,                 // 0 なら時間切れにしない（continuous_idle_seconds × 1000）
    endpointer: VoiceEndpointer = VoiceEndpointer(),   // 0.7 秒の無音・0.15 秒で話し始め・20 秒で区切る
    preRollMs: Int = 500,         // 話し始めの前に残す音（Android と同じ 0.5 秒）
) {
    sealed interface Event {
        data object Start : Event                        // 話し始め（途中の文字を出し始める）
        class Segment(val pcm: FloatArray) : Event       // ひと区切り（前 0.5 秒＋話＋後ろの 0.7 秒の無音）
        data object Idle : Event                         // idleMs のあいだ話さなかった
    }
    val inSpeech: Boolean
    fun feed(chunk: FloatArray): List<Event>   // 中で 100ms ずつに切り直して VoiceEndpointer に RMS を渡す
    fun flush(): FloatArray?                    // 終えるとき、話している途中なら、そこまでの音
    fun bufferedSamples(): Int                  // エンジンが DesktopVoice.trim に渡す数
}
```

- 時刻は**音の長さで数える**（壁の時計を使わない）。無音 N 秒の時間切れも、流した音の長さで決まるので、wav の試験が時刻に左右されない
- 100ms ずつに切り直すので、渡す塊の大きさで結果が変わらない（Android は 100ms ごとに RMS を渡している。`VoiceEndpointer` の 150ms の話し始めはこの粒度で調整されている）
- 声のコマンドの候補かどうか（区切りの中が `PauseSplitter.split(pcm, 600ms, 250ms)` で1つだけ、かつ 2 秒以下）は、Android の `recognizeUtterance` と同じ式を core の小さな関数 `VoiceCommand.isCandidate(pcm, rate)` にして、エンジンから呼ぶ（Android も後でそれに替えられる）

### 3. 区切りごとの認識と入力、順序

- 区切りの判定（`segmenter.feed`）は録音のスレッドの上で行う（軽い。RMS だけ）。出来事が出たときだけエンジンのロックを取る
- 区切りが出たら、エンジンが `voice.recognize(pcm)` を呼ぶ。`DesktopVoice.worker` は1本のスレッドなので、結果は区切りの順に返る
- それでもエンジンは区切りに**通し番号**を付け、`TreeMap` で番号順にそろえてから入れる（偽の音声入力で順番を入れ替えて試せる。将来、認識を並列にしても壊れない）
- さらに**聞き続けるモードの回の番号（generation）**を付け、Esc・入力欄の移動で終えたあとに届いた結果は捨てる（Android の `inputGeneration` と同じ）
- 入れ方は**今の `c 0 commit <文字>` をそのまま使う**（新しいメッセージは要らない）。送る相手は、聞き始めたときのつなぎ（`active`）
- **Kanade で打っている途中（`engine.isComposing`）なら入れずにためる**（Android の `pendingVoice`）。今の `stopVoice` は `engine.commitAll()` で打っている途中の文字を勝手に確定させるが、聞き続けるモードは何度も入るので、それだと打つたびに切られる。ためた文は、キー（`key`）・候補のクリック（`select`）・`commitall` を処理したあと、入力中でなくなっていれば返事の命令の後ろに `commit` を足して入れる
- 確定の文が作れなければ、区切りの終わりのときの途中の文字を使う（Android と同じ）。後処理は今の `spoken()`（話して入れる記号）
- 途中の文字は、話しているあいだだけ「🎤 途中の文字」をカーソルの近くに出す。話していないあいだは「🎤 聞いています」を出したままにする（PC には未確定文字列へ出す形は入れない。打つのとぶつからないように表示だけ）

### 4. 声のコマンド

判定はエンジン（共通）。実行はフロント。**新しい命令 `voiceaction` を1つ足す**（エンジン → フロント）。

| コマンド | エンジン | Windows（TSF） | Linux（fcitx5） | macOS（Kanade のとき / ほかの入力ソースのとき） |
|---|---|---|---|---|
| 送って | ためた文を先に入れ、`voiceaction send` | `SendInput` で Enter（今の `Resend` と同じ形） | `ic->forwardKey(Return)` の押す・離す | 両方とも `CGEvent` で Return を投げる（印 `marker` を付けて自分のタップでは見ない） |
| 次へ | `voiceaction next` | Tab | `forwardKey(Tab)` | Tab |
| 全部消して | `voiceaction clear`。直前の文の記録・左の文脈を消す | 編集セッションで文書の頭から終わりまでの範囲を空にする。できなければ何もしない | 周りの文が取れれば `deleteSurroundingText` で全部。取れなければ何もしない（端末など。Ctrl+A が行頭へ移るだけになるため） | Kanade: `insertText("", replacementRange: 0..<length)`。ほか: Cmd+A・Delete |
| 取り消して | 直前に入れた文があれば `voiceaction undo <その文>`、左の文脈から外す | カーソルの前がその文なら消す（`Replace` の中身と同じ確かめ方。違えば何もしない） | 周りの文で確かめて `deleteSurroundingText`。取れなければ何もしない | Kanade: `selectedRange` の前を確かめて消す。ほか: アクセシビリティで欄の文とカーソルを読んで確かめ、合えば BackSpace を文字数ぶん |
| 止めて | 聞き続けるモードを終える（フロントは何もしない） | — | — | — |

- 今の `replace <前の文字> <打った文字> <置き換え>` は使い回さない。合わないときに「打った文字」を入れる作りで、取り消しには余計。しかも `typed` の形がフロントで食い違っている（エンジンは文字そのものを送るが、fcitx5 と Mac は数字として読んでいる。スニペットの「合わないとき」に打った文字が落ちる疑い。別件として確かめる）
- どのコマンドも「🎤 送信」などをカーソルの近くに 1.5 秒出す（Android と同じ）
- `voice_commands` が切ってあれば判定しない（文字として入る）
- 英語のモデル（Kanade 以外の入力方式）では、言い方が日本語なので実質はたらかない（そのままでよい）
- 「次へ」で入力欄が替わると、下の 5 のとおり聞き続けるモードは終わる（Android も `onFinishInput` で終わる）

### 5. 短押し・長押しの判定と終わる条件

| 案 | 中身 | 変える所 | よい所 | 悪い所 |
|---|---|---|---|---|
| A. 今のまま、判定は各フロント（Mac・Linux は前から、Windows はエンジン）。フロントが「短押しだった」を `voice continuous` で知らせる | 3か所に同じ判定 | Mac の作りに近い | 0.3 秒・音のタイミングが3か所。Windows はどのみちエンジン |
| **B. 判定はエンジンに1つ。フロントは録音キーの押す・離すをそのまま送る（`voice down <ja\|en>`・`voice up`・`voice toggle <ja\|en>`）。Windows は今の `key d/u F12` がそのまま入口** | エンジン1か所 | 試験（EngineHostTest）で全部の押し方を確かめられる。閾値・音が1か所 | フロントは状態を知らないので、エンジンから `voicestate` を受けて Esc の扱いを決める |

**推す: B。** どちらの案でも、エンジンが自分で終える（無音・「止めて」・入力欄の移動）ので、**エンジン → フロントの `voicestate <off|hold|listen>`（id 0）はどのみち要る**。それならば判定もエンジンに寄せるほうが、閾値と音を1か所で持てる。

エンジンの状態（新しいクラス `VoiceSession`、`EngineHost` から呼ぶ。EngineHost が 370 行あるので分ける）:

```
OFF --down--> PRESSED（すぐ録音を始める。0.3 秒後にまだ押していれば HOLD へ・長押しの音）
PRESSED --up（0.3 秒未満）--> LISTEN（一回押しの音。録った音は捨てて区切りの判定を始める）
HOLD --up--> OFF（今の stopVoice: まとめて認識）
OFF --toggle--> LISTEN（一回押しの音）
LISTEN --down / toggle--> OFF（話している途中なら、そこまでを認識して入れる。続く up は無視）
LISTEN --Esc（voice cancel / Windows の Esc キー）--> OFF（話している途中を捨てる）
LISTEN --blur / focus--> OFF（話している途中を捨て、認識中の結果も捨てる）
LISTEN --無音 N 秒（Idle）--> OFF（「話さなかったので止めました」）
LISTEN --「止めて」--> OFF
PRESSED/HOLD --Esc--> OFF（今の cancelVoice）
```

- 閾値は 0.3 秒（Android の `TAP_MS`・Mac の `tapSeconds` と同じ）。Android と同じく、録音を始め終えた時刻から数える（マイクを開くのに時間がかかっても、長押しに見えないように）
- 時計とタイマー（0.3 秒後の長押しの音）は `EngineHost` の引数で差し替えられるようにする（`clock: () -> Long`、`schedule: (Long, () -> Unit) -> Cancelable`。既定は本物）
- 終わりの音: LISTEN の終わりは、その回で文字が入ったか（かつ Esc でない）で DONE_TEXT / DONE_EMPTY（Android と同じ）。区切りごとには鳴らさない
- `voicestate` は fcitx5 と Mac のつなぎ（今の `fcitxClients`）にだけ送る。Windows の DLL は Esc も F12 もエンジンへ回しているので要らない
- 今の `voice start/stop/cancel/cue` は、古いフロントのために残す（意味は今のまま。新しいフロントは使わない）。全部のフロントを替えたら消してよい
- 「入力欄が替わった」の知らせ:
  - Windows: 今の `focus 0/1` を使う（DLL は変えない）。LISTEN 中に `focus` が届いたら終える。窓を切り替えただけでも終わる（Android もキーボードが閉じれば終わる）。候補・表示の窓は `WS_EX_NOACTIVATE` なのでフォーカスを取らない
  - Linux: アドオンが `instance_->watchEvent(InputContextFocusOut)` を見て、録音を始めた入力欄（`current_`）がフォーカスを失ったら `voice blur` を送る（どの入力方式でも効く）
  - Mac: Kanade のときは今の `deactivateServer` の `focus 0`。ほかの入力ソースのときは知らせが無いので、LISTEN のあいだだけ 0.5 秒ごとにアクセシビリティの「フォーカスのある要素」（`AXFocusedUIElement`、`CFEqual` で比べる）と前面のアプリを見て、替わったら `voice blur`

### 6. 変える場所と順番

規模: 新しいファイル 2（core 1・desktop 1）と試験 1、変えるファイル 約 17、手順 10。不確かな点は下の「リスク」。

1. **core**: `voice/ContinuousSegmenter.kt`（新）、`VoiceCommand.isCandidate`（`VoiceCommands.kt` に足す）。試験 `core/src/test/.../voice/ContinuousSegmenterTest.kt`（新）
   - 確かめ方: `./gradlew :core:test --tests '*ContinuousSegmenterTest'`。作った音（雑音の床 → 1 秒の音 → 1 秒の無音 → 0.5 秒の音 → 61 秒の無音）で区切り2つと Idle の位置、塊の大きさ（160・1600・7 サンプル）で結果が同じこと、20 秒で区切ること、`eval/voice-sample-ja.wav`（8.8 秒、リポジトリにある）で区切りが1つ以上で全部の音が区切りか捨てた無音に入ること
2. **desktop の録音**: `DesktopVoice.kt`（`onAudio`・`halt`・`trim`・`recognize`・`-Dkanade.voiceStreamWav`）
   - 確かめ方: `:desktop:compileKotlin`。下の 3 の試験は偽物で通すので、ここは手で: Linux の PC で `-Dkanade.voiceStreamWav=eval/voice-sample-ja.wav` を付けてエンジンを動かし、記録（log）に区切りと認識が出る
3. **desktop のエンジン**: `VoiceSession.kt`（新。状態・区切り・順序・ためる・コマンド）、`EngineHost.kt`（`voice down/up/toggle/blur` の受け口、`focus` で終える、Windows の F12 と Esc を `VoiceSession` へ、`key/select/commitall` のあとでためた文を足す、`voiceaction`・`voicestate` の命令）、`DesktopApp.kt`（`voiceCommands`・`continuousIdleSeconds` をエンジンへ）、`FakeDll.kt`（`voiceaction` を文書に映す: send は `\n`、next は記録、clear は全部消す、undo は後ろが合えば消す）
   - 確かめ方: `EngineHostTest` に足す（下の「自動テスト」）。`./gradlew :desktop:test`
4. **プロトコルの文書**: `docs/WINDOWS-PROTOCOL.md` に `voice down/up/toggle/blur`・`voicestate`・`voiceaction` を足す
5. **Linux**: `linux/fcitx5/src/kanade.cpp`（録音キーは `voice down/up/toggle` を送るだけにし、0.3 秒の判定と「押し間違いで捨てる」をやめる。Esc は `voicestate` が off 以外のときだけ取る。`InputContextFocusOut` で `voice blur`。`voiceaction` を `apply` に足す。`voicestate` を `onLine` で受ける）
   - 確かめ方: `scripts/linux.sh` で別の Linux の機械に組んで入れる（この PC には cmake が無い）。`-Dkanade.voiceStreamWav` で、Kanade と keyboard-us の両方で、短押し → 区切りごとに入る、もう一度押す・Esc・別の欄で終わる、を見る。「送って」「取り消して」は見本の音声を作って流す（下の「リスク」）
6. **Windows**: `windows/tsf/src/TextService.cpp`・`Composition.cpp`・`TextService.h`（`voiceaction` の実行。F12・Esc・フォーカスはエンジン側で済むので、DLL の変更はここだけ）
   - 確かめ方: `scripts/win.sh engine` → `win.sh tsf` → `win.sh reg`（to-fu で MSVC。この PC には MSVC も mingw も無い）。`win.sh type` で F12 を短く押す手順を足し、エンジンを `-Dkanade.voiceStreamWav` 付きで動かして入力欄の中身を見る
7. **macOS**: `mac/ime/Sources/Voice.swift`（`voice down/up/toggle` を送るだけにし、0.3 秒の判定・`cueTimer`・`mode` をやめて `voicestate` を写す。Esc はそれを見て取る。LISTEN のあいだのフォーカスの見張り。`apply` に `voiceaction`）、`InputController.swift`（`apply` に `voiceaction`）、`EngineLink.swift`（`voicestate` を `voicekeys` と同じく特別に受けて VoiceInput へ渡す。今の入力欄が Kanade でもそうでなくても）
   - 確かめ方: `ssh mac` で `swiftc -typecheck`。pkg を組んで cramshell で本人に見てもらう（Mac の実機の確かめは本人）
8. **設定の画面**（各 2 項目: 「声のコマンド」の入り切り `voice_commands`、「話さなければ止める時間」30 秒・1 分・2 分・止めない `continuous_idle_seconds` = 30/60/120/0）: `linux/settings/kanade-settings-gtk.py`（既定とコメントの表・音声のページ）、`linux/settings/qt/src/store.cpp`・`qml/VoicePage.qml`、`windows/settings/Store.cs`（読む `switch` と `Save()` の両方。今は書き直しで消している）・`Pages/VoicePage.xaml(.cs)`・`Strings.cs`、`mac/settings/Store.swift`（`defaults`）・`App.swift`
   - 確かめ方: Linux の GTK の試験（`linux/settings/tests`）に既定値の行を足す。`DesktopStoreTest` は既にこの2つを読み書きしている。Windows・Mac は組んで画面を撮る
9. **SPEC・記録**: SPEC 8-3（Mac の例外を消し、PC の録音キーの短押し・トグルのキー・入力欄の移動の知らせ方を書く）、8-11（PC は聞き続けるモードのコマンドだけ。押している間の録音のコマンドは Android だけ）、8-13（Windows の Esc）、8-15（Windows の F12 と fcitx5 の「押した瞬間に長押しの音」を消し、全部の版で 0.3 秒で決める。聞き続けるモードの終わりの音を全部の版に）、未決事項の「Windows 版の音声入力は…コマンドは Android 版だけ」を直す。`docs/decisions.md` にこの設計の要点と捨てた案。`docs/HANDOFF.md`
10. （あとで・別のフェーズ）押している間の録音での声のコマンド（8-11 の前半）を PC に。Android の `recognizeWithCommands` の組み立てを core に移し、エンジンの `stopVoice` から使う

1〜4 はエンジンだけで閉じ、試験で確かめられるので先に1つの commit 群にする。5〜7 はフロントごとに分けて commit する（どれかがうまくいかなくても、ほかの版は出せる。古い `voice start/stop` を残すので、フロントを替えていない版は今のまま動く）。

### 7. 自動テストの方針

- **core `ContinuousSegmenterTest`**: 上の 1 の確かめ方。純粋な関数なので時刻・スレッドが要らない
- **desktop `EngineHostTest`**（`FakeVoice` を広げる: `onAudio` を覚えて試験から `push(FloatArray)` で流す、`recognize` は区切りの長さ→文の表、または試験が後から答える（順番を入れ替えるため）、偽の時計とタイマー）
  1. 短押し（down → 0.1 秒 → up）で `voicestate listen`、一回押しの音。音と無音を流すと `commit` が届く。もう一度押すと `voicestate off`、DONE_TEXT
  2. 長押し（down → 0.4 秒 → up）は今のまま、まとめて1回 `commit`。0.3 秒で長押しの音
  3. Windows の形（`key d F12`・`key u F12`）でも 1・2 と同じ。録音中の `key d Esc` は使われ（consumed）、話している途中の音は入らない
  4. 区切り2つの認識を逆の順に答えても、入る順は話した順
  5. `focus 1`（または `voice blur`）で終わり、そのあとに届いた認識の結果は入らない
  6. Kanade で打っている途中（`ka`）に結果が届いても入らず、Enter で確定したあとに続けて入る
  7. コマンド: 「送って」→ `voiceaction send`、「取り消して」→ `voiceaction undo <直前の文>` で `FakeDll` の文書から消える、「全部消して」→ 空、「止めて」→ `voicestate off`。`voice_commands` を切ると「送って」が文字で入る。長い区切りの中の「送って」は文字のまま
  8. 時間切れ: 無音を 61 秒ぶん流すと終わる（`continuousIdleSeconds = 60`）。0 なら終わらない
  9. 古い `voice start/stop/cancel` は今の試験（`voiceFromAnyInputMethod`）がそのまま通る
- フロント（C++・Swift）は自動テストを持たないので、SPEC に確かめる手順を書く（`-Dkanade.voiceStreamWav` で声を出さずに通す手順）。8-3 は「仮」のまま。本人の実機で見たら手順つきで「確定」に

## リスク・不確かな点

- **ビルドできる所**: この PC には cmake・MSVC・mingw・swiftc・dotnet が無い。Windows の DLL と設定の画面は to-fu（`scripts/win.sh tsf` / `settings`）、Linux の fcitx5 アドオンは別の Linux の機械（`scripts/linux.sh`）、Swift は `ssh mac` で型チェック。Kotlin（core・desktop）だけ手元で組んで試験できる
- **コマンドの見本の音声**: `eval/voice-sample-ja.wav` には「送って」などが入っていない。通しで確かめるには、見本の音声（間を空けた「送って」）が要る。合成音声で作るか、本人に録ってもらう。エンジンの試験は偽の認識で通すので、見本が無くても作れる
- **Windows の `focus` の数**: TSF の `OnSetFocus`・`OnSetThreadFocus` が、入力欄を移っていないのに呼ばれるアプリがあると、聞き続けるモードがすぐ切れる。to-fu で、メモ帳・ブラウザ・ターミナルで確かめる。切れすぎるなら、DLL が文書（`ITfDocumentMgr`）が替わったときだけ `voice blur` を送る形に替える（DLL の変更が1つ増える）
- **Mac のフォーカスの見張り**: Electron など、アクセシビリティでフォーカスのある要素を返さないアプリでは、移っても気づけない（前面のアプリが替わったことだけは分かる）。取りこぼしても、無音の時間切れ・もう一度押す・Esc で終えられる
- **Mac のほかの入力ソースでの「取り消して」**: アクセシビリティで欄の文が読めないアプリでは何もしない
- **端末で「全部消して」**: Linux で周りの文が取れない欄（端末など）では何もしない。Windows・Mac のキーでの代わり（Ctrl+A / Cmd+A と Delete）は、Windows の端末では Ctrl+A が全選択にならないことがある（効かないだけで、壊しはしない見込み）
- **Linux の押し間違い**: 今は 0.3 秒未満の押しを「押し間違い」として捨てているが、仕様どおり聞き続けるモードに入るようになる（Android・Mac と同じ）。誤って入ったら、もう一度押すか Esc
- **マイクを開く時間**: javax.sound の `open` が遅い機械では、短く押したのに長押しに数えられるのを避けるため、録音が始まってから数える（Android と同じ）。逆に、押し続けた時間が少し短く数えられる
- **2 分の上限**: 聞き続けるモードでは `trim` で捨て続けるので当たらない。押している間の録音は今のまま 2 分
- **スニペットの `replace` の `typed` の食い違い**（今回とは別）: エンジンは文字そのもの、fcitx5 と Mac は数字として読む。合わないときに打った文字が落ちる疑い。別に確かめる
- 読んでいない所: Android 側の `StreamingRecognizer`（Nemotron。PC には無い）、Windows の `LangBarButton`・`EngineClient` の中、Linux の Qt の設定の画面の C++ の残り、Mac の `Panels`・`StatusItem` の中身

## 本人の判断待ち（仮決定して進める）

1. **トグルのキー（Linux・Mac の Control+Alt+Space）を聞き続けるモードにするか**。仮: する（押すと聞き始め、もう一度押すと終える。区切りごとに入り、コマンドも効く）。今の「もう一度押したらまとめて文字にする」は無くなる
2. **Windows の F12・Linux の録音キーの始めの音**を、押した瞬間の長押しの音から、0.3 秒で決める形（Android・Mac と同じ）に替える。仮: 替える（8-15 を一本にする）
3. **入力欄を移って終わったときの音**。Android は鳴らさない。仮: PC は他の終わり方と同じく、文字が入ったかで鳴らす（Android もそろえるなら別に直す）
4. **「次へ」で聞き続けるモードが終わる**（入力欄が替わるため。Android と同じ）。仮: 終わる
5. カーソルの前の文を確かめられないときの「取り消して」は何もしない（BackSpace を文字数ぶん送る形は、違う所を消すおそれがあるので取らない）。Linux で周りの文が取れないときの「全部消して」も何もしない。仮: そのとおり
