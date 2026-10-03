package dev.mooma.kanade

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 設定とキー記録の確認画面。段階1では見た目を作り込まず、動作確認に要るものだけを置く。
 */
class SettingsActivity : Activity() {
    private lateinit var settings: KanadeSettings
    private lateinit var keyLog: KeyLog
    private lateinit var logView: TextView
    private lateinit var overlayStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)
        val prefs = getSharedPreferences("kanade_ui", MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(Button(this).apply {
            text = "使い方を見る"
            setOnClickListener { showUsage() }
        })

        root.addView(heading("有効化"))
        root.addView(Button(this).apply {
            text = "入力方法の設定を開く"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "入力方法を選ぶ"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })

        root.addView(heading("候補の帯"))
        root.addView(note("変換候補と、Termux などで入力中の文字を出す帯は「他のアプリの上に重ねて表示」で出す（カーソルの近くに出る）。許可しないと IME の窓に出すが、Galaxy で物理キーボードを使っていると出ないことがある"))
        overlayStatus = TextView(this)
        root.addView(overlayStatus)
        root.addView(Button(this).apply {
            text = "重ねて表示を許可する"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }
        })

        root.addView(heading("画面キーボード"))
        root.addView(note(
            "物理キーボードがないときに出す画面キーボード（Unexpected Keyboard と同じ考え方）。キーを払うと隅の文字（数字・記号）が出る。" +
                "Shift・Ctrl・Alt・Fn は次の1打だけ効き、2回続けて押すと固定。押したまま別のキーを押す同時押しもできる。Alt を上に払うと Meta（◆）。" +
                "修飾は物理キーボードと同じく修飾キーの押す・離すも送るので、Shift＋矢印の選択やアプリのショートカットがそのまま効く。Fn＋数字で F1〜F10、Fn＋- = で F11・F12。" +
                "🎤 は押している間だけ録音（録音キーと同じ）。Space を左右に払うと矢印、⇥ のキーは上下左右が矢印・四隅が Home/End/PgUp/PgDn、⌫ を右に払うと Delete"
        ))
        root.addView(android.widget.RadioGroup(this).apply {
            for ((value, label) in listOf("AUTO" to "物理キーボードがないときだけ出す", "ALWAYS" to "いつも出す", "NEVER" to "出さない")) {
                addView(android.widget.RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = settings.softKeyboard == value
                    setOnCheckedChangeListener { _, checked -> if (checked) settings.softKeyboard = value }
                })
            }
        })

        val heightLabel = TextView(this).apply { text = "高さ: ${settings.softKeyboardHeight}%" }
        root.addView(heightLabel)
        root.addView(android.widget.SeekBar(this).apply {
            // 60〜150% を 5% 刻み
            max = 18
            progress = (settings.softKeyboardHeight - 60) / 5
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    settings.softKeyboardHeight = 60 + value * 5
                    heightLabel.text = "高さ: ${settings.softKeyboardHeight}%"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        })
        val bottomLabel = TextView(this).apply { text = "下の余白: ${settings.softKeyboardBottom}dp（ナビゲーションバーに重なるときに増やす）" }
        root.addView(bottomLabel)
        root.addView(android.widget.SeekBar(this).apply {
            // 0〜64dp を 4dp 刻み
            max = 16
            progress = settings.softKeyboardBottom / 4
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    settings.softKeyboardBottom = value * 4
                    bottomLabel.text = "下の余白: ${settings.softKeyboardBottom}dp（ナビゲーションバーに重なるときに増やす）"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        })

        root.addView(heading("日本語オン/オフのキー（US 配列用）"))
        root.addView(note("JIS 配列の変換（オン）・無変換（オフ）・かな（オン）・半角/全角（切り替え）は常に有効"))
        for (trigger in ToggleTrigger.entries) {
            root.addView(CheckBox(this).apply {
                text = trigger.label
                isChecked = trigger in settings.toggleTriggers
                setOnCheckedChangeListener { _, checked ->
                    settings.toggleTriggers =
                        if (checked) settings.toggleTriggers + trigger else settings.toggleTriggers - trigger
                }
            })
        }

        root.addView(heading("入力"))
        root.addView(CheckBox(this).apply {
            text = "数字・記号を全角で入力する"
            isChecked = settings.fullWidthSymbols
            setOnCheckedChangeListener { _, checked -> settings.fullWidthSymbols = checked }
        })

        root.addView(note("句読点（, と . で入るもの）"))
        root.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (style in listOf("、。", "，．", "、．", "，。")) {
                addView(android.widget.RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = style
                    isChecked = settings.punctuation == style
                    setOnCheckedChangeListener { _, checked -> if (checked) settings.punctuation = style }
                })
            }
        })
        root.addView(CheckBox(this).apply {
            text = "ライブ変換（打つたびに変換結果を出す）"
            isChecked = settings.liveConversion
            setOnCheckedChangeListener { _, checked -> settings.liveConversion = checked }
        })
        root.addView(CheckBox(this).apply {
            text = "学習する（前に選んだ候補を次から上に出す）"
            isChecked = settings.learningEnabled
            setOnCheckedChangeListener { _, checked -> settings.learningEnabled = checked }
        })
        root.addView(Button(this).apply {
            text = "学習を消す"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setMessage("覚えた候補をすべて消す（ユーザー辞書は消えない）")
                    .setPositiveButton("消す") { _, _ ->
                        java.io.File(filesDir, "learning.tsv").delete()
                        settings.learningResetAt = System.currentTimeMillis()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        })
        root.addView(note("入力中の文字を帯に出すアプリ（未確定文字列をうまく出せない端末アプリなど）。パッケージ名をカンマ区切りで"))
        root.addView(EditText(this).apply {
            setText(settings.previewInBarPackages.sorted().joinToString(", "))
            isSingleLine = true
            setOnFocusChangeListener { _, focused ->
                if (!focused) settings.previewInBarPackages = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        })

        root.addView(heading("ユーザー辞書"))
        root.addView(note(
            "読み（ひらがな）と語を登録する。登録した語は変換で優先され、候補の先頭に「ユーザー辞書」と付いて出る（macOS のユーザ辞書と同じ）。" +
                "文の途中でも、その読みの部分に使われやすくなる。\n" +
                "テキストで編集するときは1行に「よみ 語」（最初の空白で区切る）。# で始まる行は無視される。"
        ))
        root.addView(PairEditor(this, TextStore.userDictionary(this), "よみ（ひらがな）", "語", prefs, "user_dictionary_text_mode"))

        root.addView(heading("スニペット"))
        root.addView(note(
            "キーと出力を登録する。キーを打ち終わった瞬間に出力へ置き換わる（例: @@ → メールアドレス、-> → →）。日本語入力中も直接入力中も効く。\n" +
                "キーは打つ文字そのもの（日本語入力中でも、かなや全角になる前の文字で見る）。英字だけのキーは、日本語入力中のローマ字にも反応するので、記号を含むキーがおすすめ。" +
                "長いキーが優先される（-> と --> があれば --> ）。\n" +
                "テキストで編集するときは1行に「キー 出力」（最初の空白で区切る）。出力の中の \\n は改行。# で始まる行は無視される。"
        ))
        root.addView(PairEditor(this, TextStore.snippets(this), "キー（打つ文字）", "出力", prefs, "snippets_text_mode"))

        addVoiceSection(root)

        root.addView(heading("試し打ち"))
        root.addView(EditText(this).apply { hint = "ここで入力を試す" })

        root.addView(heading("変換の試験"))
        root.addView(note("ひらがなの読みを入れて、辞書と zenz の結果と時間を見る"))
        val readingInput = EditText(this).apply { hint = "例: きょうはいいてんきですね" }
        root.addView(readingInput)
        val convertResult = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val convertButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        convertButtons.addView(Button(this).apply {
            text = "変換"
            setOnClickListener {
                val reading = readingInput.text.toString().trim()
                if (reading.isEmpty()) return@setOnClickListener
                convertResult.text = "変換中…"
                background { ConversionBench.tryConvert(this@SettingsActivity, reading) }.let { task ->
                    task { convertResult.text = it }
                }
            }
        })
        convertButtons.addView(Button(this).apply {
            text = "評価セットで測る"
            setOnClickListener {
                convertResult.text = "準備中…"
                background {
                    ConversionBench.runEval(this@SettingsActivity) { msg -> runOnUiThread { convertResult.text = msg } }
                }.let { task -> task { convertResult.text = it } }
            }
        })
        root.addView(convertButtons)
        root.addView(convertResult)

        root.addView(heading("キー記録"))
        root.addView(note("IME に届いたキーを記録する。DeX が先に取るキーはここに出ない"))
        root.addView(CheckBox(this).apply {
            text = "記録する"
            isChecked = settings.keyLogEnabled
            setOnCheckedChangeListener { _, checked -> settings.keyLogEnabled = checked }
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "再読み込み"
            setOnClickListener { refreshLog() }
        })
        buttons.addView(Button(this).apply {
            text = "消去"
            setOnClickListener {
                keyLog.clear()
                refreshLog()
            }
        })
        root.addView(buttons)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        refreshVoice()
        overlayStatus.text = if (OverlayBar.permitted(this)) "許可済み（重ねる窓に出す）" else "未許可（IME の窓に出す）"
    }

    private lateinit var voiceStatus: TextView
    private var voiceKeyButton: Button? = null

    /** 音声入力: マイクの権限、モデルの取得、録音キー、見本の音声での計測 */
    private fun addVoiceSection(root: LinearLayout) {
        root.addView(heading("音声入力"))
        root.addView(note(
            "録音キーを押している間だけ録音し、離すと端末内の whisper で文字にして入れる（音声は外へ送らない）。" +
                "モデルは大きいので APK に入れていない。「ブラウザで取得」で落としてから「取得したファイルを選ぶ」で選ぶと、アプリ内へ写す" +
                "（Kanade 自身はネットにつながない）。速さは「見本の音声で測る」で確かめられる"
        ))
        voiceStatus = TextView(this).apply { textSize = 13f }
        root.addView(voiceStatus)
        root.addView(Button(this).apply {
            text = "マイクを許可する"
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }
        })
        val group = android.widget.RadioGroup(this)
        for (m in VoiceModel.entries) {
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = m.label
                isChecked = settings.voiceModel == m
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        settings.voiceModel = m
                        refreshVoice()
                    }
                }
            })
        }
        root.addView(group)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@SettingsActivity).apply {
                text = "ブラウザで取得"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(settings.voiceModel.url)))
                }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "取得したファイルを選ぶ"
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQUEST_MODEL,
                    )
                }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "状態を更新"
                setOnClickListener { refreshVoice() }
            })
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@SettingsActivity).apply {
                voiceKeyButton = this
                text = "録音キー: ${keyName(settings.voiceKey)}（変える）"
                setOnClickListener { assignVoiceKey() }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "スレッド数を変える"
                setOnClickListener {
                    val options = listOf(2, 4, 6, 8)
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("認識に使うスレッド数")
                        .setItems(options.map { "$it" }.toTypedArray()) { _, i ->
                            settings.voiceThreads = options[i]
                            refreshVoice()
                        }.show()
                }
            })
        })
        root.addView(note(
            "話しながら文字を出すには ReazonSpeech（約 73MB、4 つのファイル）を取り込む。話している途中は ReazonSpeech の結果を出し、" +
                "離したら whisper（取り込んであれば）で句読点の付いた文にして確定する。whisper がなければ ReazonSpeech の結果で確定する"
        ))
        root.addView(CheckBox(this).apply {
            text = "話しながら文字を出す（ReazonSpeech）"
            isChecked = settings.voiceStreaming
            setOnCheckedChangeListener { _, checked -> settings.voiceStreaming = checked }
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@SettingsActivity).apply {
                text = "ReazonSpeech をブラウザで取得"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(StreamingRecognizer.PAGE_URL)))
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setMessage("開いたページから次の 4 つを落とす:\n" + StreamingRecognizer.FILES.keys.joinToString("\n"))
                        .setPositiveButton("閉じる", null).show()
                }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "取得した4つを選ぶ"
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                        REQUEST_REAZON,
                    )
                }
            })
        })
        val result = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(Button(this).apply {
            text = "見本の音声で測る（8.8秒の日本語）"
            setOnClickListener {
                val whisperReady = settings.voiceModel.isReady(this@SettingsActivity)
                val reazonReady = StreamingRecognizer.isReady(this@SettingsActivity)
                if (!whisperReady && !reazonReady) {
                    result.text = "モデルがまだない"
                    return@setOnClickListener
                }
                result.text = "認識中…（初回はモデルの読み込みも含む）"
                val pcm = readSampleWav()
                // ReazonSpeech（あれば）→ whisper（あれば）の順に測る。2回目は読み込み済みの速さ
                fun runWhisper() {
                    if (!whisperReady) return
                    val started = System.nanoTime()
                    VoiceInput.transcribe(this@SettingsActivity, pcm, settings.voiceModel, settings.voiceThreads) { text, ms ->
                        val total = (System.nanoTime() - started) / 1_000_000
                        runOnUiThread {
                            result.append("whisper ${settings.voiceModel.name}・${settings.voiceThreads}スレッド: 認識 ${ms}ms（読み込み込みで ${total}ms）\n${text ?: "（認識できなかった）"}\n")
                        }
                    }
                }
                result.text = ""
                if (reazonReady) {
                    val started = System.nanoTime()
                    StreamingRecognizer.recognize(this@SettingsActivity, pcm, settings.voiceThreads) { first ->
                        val firstMs = (System.nanoTime() - started) / 1_000_000
                        val again = System.nanoTime()
                        StreamingRecognizer.recognize(this@SettingsActivity, pcm, settings.voiceThreads) { _ ->
                            val ms = (System.nanoTime() - again) / 1_000_000
                            runOnUiThread {
                                result.append("ReazonSpeech: 認識 ${ms}ms（初回は読み込み込みで ${firstMs}ms）\n${first ?: "（認識できなかった）"}\n")
                                runWhisper()
                            }
                        }
                    }
                } else {
                    runWhisper()
                }
            }
        })
        root.addView(result)
        refreshVoice()
    }

    @Deprecated("Activity Result API を入れるほどではないので、昔の形で受ける")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_REAZON && resultCode == RESULT_OK && data != null) {
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            voiceStatus.text = "写している…"
            Thread {
                val n = runCatching { StreamingRecognizer.importFrom(this, uris) }.getOrDefault(0)
                runOnUiThread {
                    refreshVoice()
                    voiceStatus.append("\nReazonSpeech: ${n} 個を取り込んだ（名前と大きさが合うものだけ）")
                }
            }.start()
            return
        }
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK || uri == null) return
        val model = settings.voiceModel
        voiceStatus.text = "写している…"
        Thread {
            var last = 0L
            val ok = runCatching {
                model.importFrom(this, uri) { total ->
                    if (total - last > 20_000_000) {
                        last = total
                        runOnUiThread { voiceStatus.text = "写している… ${total / 1_000_000}MB / ${model.bytes / 1_000_000}MB" }
                    }
                }
            }.getOrDefault(false)
            runOnUiThread {
                refreshVoice()
                if (!ok) voiceStatus.append("\n写せなかった（${model.file} の大きさが合わない。選んだモデルと同じファイルか確かめる）")
            }
        }.start()
    }

    private fun refreshVoice() {
        if (!::voiceStatus.isInitialized) return
        val m = settings.voiceModel
        val file = m.path(this)
        val state = when {
            m.isReady(this) -> "取得済み"
            file.exists() -> "大きさが合わない（${file.length() / 1_000_000}MB / ${m.bytes / 1_000_000}MB）"
            else -> "未取得"
        }
        voiceKeyButton?.text = "録音キー: ${keyName(settings.voiceKey)}（変える）"
        voiceStatus.text = "マイク: ${if (VoiceInput.hasPermission(this)) "許可済み" else "未許可"}　" +
            "モデル: ${m.name}（$state）　録音キー: ${keyName(settings.voiceKey)}　" +
            "スレッド: ${settings.voiceThreads}　ReazonSpeech: ${StreamingRecognizer.readyCount(this)}/${StreamingRecognizer.FILES.size}"
    }

    private fun keyName(keyCode: Int) = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    /**
     * 録音キーを選ぶ。物理キーボードがあれば次に押したキーにする。一覧からも選べる（画面キーボードだけのときや、押せないキー用）。
     * 画面キーボードの 🎤 は、どのキーにしても録音できる
     */
    private fun assignVoiceKey() {
        val choices = listOf(
            KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F13, KeyEvent.KEYCODE_SCROLL_LOCK,
            KeyEvent.KEYCODE_BREAK, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_RIGHT,
        )
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("録音キー（今: ${keyName(settings.voiceKey)}）")
            .setItems(arrayOf("物理キーボードのキーを押して決める（このまま押す。Esc でやめる）") + choices.map { keyName(it) }.toTypedArray()) { _, i ->
                if (i > 0) {
                    settings.voiceKey = choices[i - 1]
                    refreshVoice()
                }
            }
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            if (keyCode != KeyEvent.KEYCODE_ESCAPE && keyCode != KeyEvent.KEYCODE_BACK) {
                settings.voiceKey = keyCode
                refreshVoice()
            }
            d.dismiss()
            true
        }
        dialog.show()
    }

    /** 同梱の見本の音声（16kHz・モノラル・16bit の wav）を -1〜1 の float にする */
    private fun readSampleWav(): FloatArray {
        val bytes = assets.open("voice-sample-ja.wav").readBytes()
        val n = (bytes.size - 44) / 2
        return FloatArray(n) { i ->
            val lo = bytes[44 + 2 * i].toInt() and 0xFF
            val hi = bytes[45 + 2 * i].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    /** 使い方（docs/USAGE.md を assets に入れたもの）を出す。表の罫線などの記号は読みやすく省く */
    private fun showUsage() {
        val raw = runCatching { assets.open("usage.md").bufferedReader().readText() }.getOrDefault("（使い方が見つからない）")
        val text = raw.lines().filter { !it.startsWith("|---") && it != "```" }.joinToString("\n") { line ->
            line.removePrefix("## ").removePrefix("# ").replace("**", "").replace("`", "")
                .let { if (it.startsWith("|")) it.trim('|').split('|').joinToString("  ") { c -> c.trim() } else it }
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val view = ScrollView(this).apply {
            addView(TextView(this@SettingsActivity).apply {
                this.text = text
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
            })
        }
        android.app.AlertDialog.Builder(this).setTitle("Kanade の使い方").setView(view).setPositiveButton("閉じる", null).show()
    }

    /** 重い処理を別スレッドで動かし、結果を UI スレッドで受け取る */
    private fun background(work: () -> String): ((String) -> Unit) -> Unit = { onDone ->
        Thread {
            val result = runCatching(work).getOrElse { "失敗: $it" }
            runOnUiThread { onDone(result) }
        }.start()
    }

    private fun refreshLog() {
        logView.text = keyLog.read().lines().takeLast(200).joinToString("\n")
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }

    private companion object {
        const val REQUEST_MODEL = 10
        const val REQUEST_REAZON = 11
    }
}
