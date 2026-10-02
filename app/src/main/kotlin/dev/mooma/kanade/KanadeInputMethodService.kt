package dev.mooma.kanade

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.KeyInput

/**
 * 物理キーボード専用の IME。画面キーボードも候補欄も出さず、キーをすべて onKeyDown/onKeyUp で受ける。
 * 入力の判断は [InputEngine]（core）に任せ、ここは Android との受け渡しだけをする。
 */
class KanadeInputMethodService : InputMethodService() {
    private lateinit var settings: KanadeSettings
    private lateinit var keyLog: KeyLog
    private val engine = InputEngine()
    private val mapper = KeyMapper(emptySet())

    /** onKeyDown で止めたキーは、対応する onKeyUp も止める */
    private val consumedDown = HashSet<Int>()

    override fun onCreate() {
        super.onCreate()
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)
        // 前回のモードを引き継ぐ。ModeChanged を出させるため、反対の状態から切り替える
        if (settings.lastJapanese) engine.handle(KeyInput.SetJapanese(true))
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine.settings = settings.engineSettings()
        mapper.triggers = settings.toggleTriggers
        if (!restarting) engine.reset()
        updateStatusIcon()
    }

    override fun onFinishInput() {
        engine.reset()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // クリックなどで未確定文字列が外から確定・消去されたら、こちらの状態も捨てる
        if (engine.isComposing && candidatesStart == -1 && candidatesEnd == -1) engine.reset()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val input = mapper.onKeyDown(event)
        if (input == null) {
            val consume = keyCode == KeyEvent.KEYCODE_ALT_RIGHT && mapper.consumesRightAlt()
            log(event, if (consume) "hold" else "pass")
            if (consume) {
                consumedDown += keyCode
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        val consumed = apply(input)
        log(event, "$input consumed=$consumed")
        if (consumed) {
            consumedDown += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val input = mapper.onKeyUp(event)
        if (input != null) apply(input)
        val consumed = consumedDown.remove(keyCode)
        log(event, if (input != null) "$input" else if (consumed) "consumed" else "pass")
        return consumed || super.onKeyUp(keyCode, event)
    }

    private fun apply(input: KeyInput): Boolean {
        val result = engine.handle(input)
        val ic = currentInputConnection
        ic?.beginBatchEdit()
        for (command in result.commands) {
            when (command) {
                is ImeCommand.SetComposing ->
                    if (command.text.isEmpty()) {
                        ic?.setComposingText("", 1)
                        ic?.finishComposingText()
                    } else {
                        ic?.setComposingText(command.text, 1)
                    }
                is ImeCommand.Commit -> ic?.commitText(command.text, 1)
                is ImeCommand.ModeChanged -> {
                    settings.lastJapanese = command.japanese
                    updateStatusIcon()
                }
            }
        }
        ic?.endBatchEdit()
        return result.consumed
    }

    private fun updateStatusIcon() {
        if (engine.japanese) showStatusIcon(R.drawable.ic_mode_ja) else hideStatusIcon()
    }

    private fun log(event: KeyEvent, note: String) {
        if (settings.keyLogEnabled) keyLog.record(event, note)
    }
}
