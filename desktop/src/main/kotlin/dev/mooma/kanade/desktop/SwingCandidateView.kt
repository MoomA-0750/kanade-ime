package dev.mooma.kanade.desktop

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 候補の窓と、日本語のオン/オフの表示（「あ」「A」）。Android 版の重ねる窓と同じく、入力中の文字のすぐ下に出す。
 * フォーカスを奪わない窓にする（アプリの入力欄からフォーカスが外れると、入力中の文字が確定されてしまうため）。
 *
 * 位置は DLL が送る画面の座標（物理ピクセル）。JVM は -Dsun.java2d.uiScale=1 で動かし、座標をそのまま使う。
 * 文字の大きさは画面の DPI に合わせて自分で拡大する。
 *
 * 候補をクリックで選ぶのは、まだ入れない。Java だけでは「触っても前面にならない窓」（WS_EX_NOACTIVATE）にできず、
 * クリックするとアプリの入力欄からフォーカスが外れて入力中の文字が消えるため（ネイティブの部品を作るフェーズ5で入れる）
 */
class SwingCandidateView : CandidateView {
    private val scale = Toolkit.getDefaultToolkit().screenResolution / 96f
    private val dark = WindowsTheme.isDark()
    private val bg = if (dark) Color(0x2B, 0x2B, 0x2E) else Color(0xFA, 0xFA, 0xFC)
    private val fg = if (dark) Color(0xEE, 0xEE, 0xEE) else Color(0x22, 0x22, 0x22)
    private val dim = if (dark) Color(0x99, 0x99, 0x99) else Color(0x88, 0x88, 0x88)
    private val selectedBg = if (dark) Color(0x2E, 0x5C, 0x8A) else Color(0xC8, 0xDD, 0xF5)
    private val border = if (dark) Color(0x44, 0x44, 0x48) else Color(0xD8, 0xD8, 0xDC)
    private val textFont = Font("Yu Gothic UI", Font.PLAIN, px(15))
    private val smallFont = Font("Yu Gothic UI", Font.PLAIN, px(11))

    private var caret = Rectangle(100, 100, 0, 20)
    private var modeShownAt = 0L
    private var pendingMode: Boolean? = null

    private val list = CandidateList()
    private val listWindow = popup(list)
    private val modeLabel = ModeLabel()
    private val modeWindow = popup(modeLabel)
    private val modeTimer = Timer(900) { modeWindow.isVisible = false }.apply { isRepeats = false }

    private fun px(dp: Int) = (dp * scale).toInt()

    private fun popup(content: JComponent) = JWindow().apply {
        isAlwaysOnTop = true
        focusableWindowState = false
        isAutoRequestFocus = false
        type = java.awt.Window.Type.POPUP
        background = Color(0, 0, 0, 0)
        contentPane = content
    }

    override fun show(candidates: List<String>, selected: Int, labels: List<String?>, anchorOffset: Int) = SwingUtilities.invokeLater {
        list.set(candidates, selected, labels)
        listWindow.size = list.preferredSize
        place(listWindow)
        listWindow.isVisible = true
    }

    override fun hide() = SwingUtilities.invokeLater { listWindow.isVisible = false }

    override fun caret(left: Int, top: Int, right: Int, bottom: Int) = SwingUtilities.invokeLater {
        caret = Rectangle(left, top, maxOf(0, right - left), maxOf(1, bottom - top))
        if (listWindow.isVisible) place(listWindow)
        // モードを切り替えた直後に届いた位置なら、そこに「あ」「A」を出す（DLL は切り替えのあとに位置を送る）
        pendingMode?.let { japanese ->
            if (System.currentTimeMillis() - modeShownAt < 500) showMode(japanese)
            pendingMode = null
        }
    }

    override fun mode(japanese: Boolean) = SwingUtilities.invokeLater {
        pendingMode = japanese
        modeShownAt = System.currentTimeMillis()
        // 位置が届かないアプリもあるので、少し待って届かなければ前の位置に出す
        Timer(120) { pendingMode?.let { showMode(it); pendingMode = null } }.apply { isRepeats = false }.start()
    }

    override fun voice(status: String?) = SwingUtilities.invokeLater {
        if (status == null) {
            modeWindow.isVisible = false
            return@invokeLater
        }
        modeTimer.stop()
        modeLabel.text = status
        modeWindow.size = modeLabel.preferredSize
        place(modeWindow)
        modeWindow.isVisible = true
    }

    private fun showMode(japanese: Boolean) {
        modeLabel.text = if (japanese) "あ" else "A"
        modeWindow.size = modeLabel.preferredSize
        place(modeWindow)
        modeWindow.isVisible = true
        modeTimer.restart()
    }

    /** 入力中の文字の下に。画面の下にはみ出すなら上に、右にはみ出すなら左へずらす */
    private fun place(window: JWindow) {
        val gap = px(4)
        val screen = screenBounds(caret.x, caret.y)
        var x = caret.x
        var y = caret.y + caret.height + gap
        if (y + window.height > screen.y + screen.height) y = caret.y - window.height - gap
        if (x + window.width > screen.x + screen.width) x = screen.x + screen.width - window.width
        window.setLocation(maxOf(screen.x, x), maxOf(screen.y, y))
    }

    private fun screenBounds(x: Int, y: Int): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val all = devices.map { it.defaultConfiguration.bounds }
        return all.firstOrNull { it.contains(x, y) } ?: all.firstOrNull() ?: Rectangle(0, 0, 1920, 1080)
    }

    private fun antialias(g: Graphics): Graphics2D = (g as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
    }

    private fun roundBackground(g: Graphics2D, w: Int, h: Int) {
        val r = px(8).toFloat()
        g.color = bg
        g.fill(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), r, r))
        g.color = border
        g.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, r, r))
    }

    /** 縦に並べた候補。9件ずつのページにして、選んでいる候補のあるページを出す（数字で選べる） */
    private inner class CandidateList : JComponent() {
        private var items: List<String> = emptyList()
        private var labels: List<String?> = emptyList()
        private var selected = 0
        private val rowHeight = px(28)
        private val padX = px(10)

        fun set(items: List<String>, selected: Int, labels: List<String?>) {
            this.items = items
            this.labels = labels
            this.selected = selected
            preferredSize = measure()
            repaint()
        }

        private val pageStart get() = selected / PAGE * PAGE
        private val pageItems get() = items.subList(pageStart, minOf(items.size, pageStart + PAGE))

        private fun measure(): Dimension {
            val fm = getFontMetrics(textFont)
            val sm = getFontMetrics(smallFont)
            val numberWidth = fm.stringWidth("9") + padX
            val textWidth = pageItems.maxOfOrNull { fm.stringWidth(it) } ?: 0
            val labelWidth = pageItems.indices.maxOfOrNull { i -> labels.getOrNull(pageStart + i)?.let { sm.stringWidth(it) + padX } ?: 0 } ?: 0
            val footer = if (items.size > PAGE) px(20) else 0
            val w = padX + numberWidth + maxOf(textWidth, px(60)) + labelWidth + padX
            return Dimension(w, pageItems.size * rowHeight + px(8) + footer)
        }

        override fun paintComponent(g0: Graphics) {
            val g = antialias(g0)
            roundBackground(g, width, height)
            val fm = getFontMetrics(textFont)
            val sm = getFontMetrics(smallFont)
            val numberWidth = fm.stringWidth("9") + padX
            var y = px(4)
            for ((i, text) in pageItems.withIndex()) {
                val index = pageStart + i
                if (index == selected) {
                    g.color = selectedBg
                    g.fillRoundRect(px(4), y, width - px(8), rowHeight, px(6), px(6))
                }
                val baseline = y + (rowHeight + fm.ascent - fm.descent) / 2
                g.font = textFont
                g.color = dim
                g.drawString("${i + 1}", padX, baseline)
                g.color = fg
                g.drawString(text, padX + numberWidth, baseline)
                labels.getOrNull(index)?.let { label ->
                    g.font = smallFont
                    g.color = dim
                    g.drawString(label, width - padX - sm.stringWidth(label), baseline)
                }
                y += rowHeight
            }
            if (items.size > PAGE) {
                val page = "${selected + 1}/${items.size}"
                g.font = smallFont
                g.color = dim
                g.drawString(page, width - padX - sm.stringWidth(page), y + sm.ascent + px(2))
            }
        }
    }

    private inner class ModeLabel : JComponent() {
        var text = "あ"
            set(value) {
                field = value
                val w = getFontMetrics(textFont).stringWidth(value) + px(16)
                preferredSize = Dimension(maxOf(px(30), w), px(30))
                repaint()
            }

        init {
            preferredSize = Dimension(px(30), px(30))
        }

        override fun paintComponent(g0: Graphics) {
            val g = antialias(g0)
            roundBackground(g, width, height)
            g.font = textFont
            g.color = fg
            val fm = g.fontMetrics
            g.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
        }
    }

    private companion object {
        const val PAGE = 9
    }
}

/** Windows の「アプリのモード」（ライト/ダーク） */
object WindowsTheme {
    fun isDark(): Boolean = runCatching {
        val p = ProcessBuilder(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x(\\d+)").find(out)?.groupValues?.get(1) == "0"
    }.getOrDefault(false)
}
