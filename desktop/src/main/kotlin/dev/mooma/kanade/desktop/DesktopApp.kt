package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.Snippets
import dev.mooma.kanade.core.dict.UserDictionary
import java.awt.Color
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import kotlin.concurrent.thread

/**
 * 設定・ユーザー辞書・スニペット・学習をエンジンに入れ、変わったら読み直し、学習を保存する。タスクトレイのメニューも出す
 */
class DesktopApp(
    private val store: DesktopStore,
    private val host: EngineHost,
    private val source: DictionaryCandidateSource,
    private val voice: DesktopVoice?,
    private val log: (String) -> Unit,
) {
    private var known = emptyList<Long>()
    private var settings = DesktopSettings()

    fun start() {
        store.ensureFiles()
        apply(first = true)
        thread(isDaemon = true, name = "store") {
            var tick = 0
            while (true) {
                Thread.sleep(2000)
                runCatching {
                    if (store.version() != known) apply(first = false)
                    if (++tick % 15 == 0) save()
                }.onFailure { log("設定の読み直し・保存: $it") }
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { save() } })
    }

    /** 設定・辞書を読んでエンジンに入れる。学習は最初の1回だけファイルから読む（あとはエンジンが持つものを保存する） */
    private fun apply(first: Boolean) {
        known = store.version()
        val s = store.loadSettings()
        settings = s
        val dictionary = UserDictionary.parse(store.read(store.userDictionaryFile))
        val snippets = Snippets.parse(store.read(store.snippetsFile))
        host.locked {
            host.engine.settings = s.engineSettings()
            source.userDictionary = dictionary
            host.engine.snippets = snippets
            source.learning = when {
                !s.learning -> null
                first || source.learning == null -> LearningHistory.parse(store.read(store.learningFile))
                else -> source.learning
            }
            if (first && s.lastJapanese) host.engine.handle(KeyInput.SetJapanese(true))
        }
        host.toggles = s.toggles
        voice?.spokenPunctuation = s.spokenPunctuation
        log(if (first) "設定を読んだ" else "設定を読み直した")
    }

    /** 学習と、前回の日本語のオン/オフを保存する（変わったときだけ） */
    @Synchronized
    fun save() {
        val (learning, japanese) = host.locked {
            val l = source.learning?.takeIf { source.learningChanged }?.write()
            if (l != null) source.learningChanged = false
            l to host.engine.japanese
        }
        if (learning != null) store.write(store.learningFile, learning)
        if (japanese != settings.lastJapanese) {
            settings = settings.copy(lastJapanese = japanese)
            store.saveSettings(settings)
            known = store.version()
        }
    }

    fun installTray() {
        if (!SystemTray.isSupported()) return
        val menu = PopupMenu()
        fun item(label: String, action: () -> Unit) = menu.add(MenuItem(label).apply { addActionListener { runCatching(action).onFailure { log("$label: $it") } } })
        val title = MenuItem("Kanade（日本語入力）").apply { isEnabled = false }
        menu.add(title)
        menu.addSeparator()
        item("ユーザー辞書を編集") { open(store.userDictionaryFile) }
        item("スニペットを編集") { open(store.snippetsFile) }
        item("設定を編集") { open(store.settingsFile) }
        item("Android 版の設定を読み込む…") { importAndroid() }
        menu.addSeparator()
        item("記録を開く") { System.getenv("LOCALAPPDATA")?.let { open(File(it, "Kanade\\engine.log")) } }
        item("Kanade を終了") {
            save()
            Runtime.getRuntime().halt(0)
        }
        val icon = TrayIcon(trayImage(), "Kanade", menu).apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
    }

    private var trayIcon: TrayIcon? = null

    private fun importAndroid() {
        val dialog = FileDialog(null as Frame?, "Android 版の設定のファイル（kanade-settings-*.json）", FileDialog.LOAD).apply {
            file = "*.json"
            isVisible = true
        }
        val name = dialog.file ?: return
        val result = runCatching { store.importAndroid(File(dialog.directory, name)) }
        val message = result.fold(
            { "設定 ${it.settings} 件、辞書・スニペット ${it.files} 件を読み込みました" },
            { "読み込めませんでした: ${it.message}" },
        )
        log(message)
        trayIcon?.displayMessage("Kanade", message, TrayIcon.MessageType.INFO)
        if (result.isSuccess) apply(first = false)
    }

    /** メモ帳で開く（保存するとエンジンが読み直す） */
    private fun open(file: File) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            ProcessBuilder("notepad.exe", file.absolutePath).start()
        } else {
            java.awt.Desktop.getDesktop().open(file)
        }
    }

    /** タスクトレイの「あ」 */
    private fun trayImage(): BufferedImage {
        val size = 32
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x2E, 0x5C, 0x8A)
            g.fillRoundRect(0, 0, size, size, 10, 10)
            g.color = Color.WHITE
            g.font = Font("Yu Gothic UI", Font.BOLD, 22)
            val fm = g.fontMetrics
            g.drawString("あ", (size - fm.stringWidth("あ")) / 2, (size + fm.ascent - fm.descent) / 2)
            g.dispose()
        }
    }
}
