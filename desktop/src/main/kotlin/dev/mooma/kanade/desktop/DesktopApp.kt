package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.Snippets
import dev.mooma.kanade.core.dict.UserDictionary
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.concurrent.thread

/**
 * 設定・ユーザー辞書・スニペット・学習をエンジンに入れ、変わったら読み直し、学習を保存する。タスクバーの入力の表示のメニューも受ける
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

    /** タスクバーの入力の表示の右クリックのメニュー（DLL から届く） */
    fun handleMenu(action: String) {
        when (action) {
            "settings" -> open(store.settingsFile)
            "user-dictionary" -> open(store.userDictionaryFile)
            "snippets" -> open(store.snippetsFile)
            "import-android" -> importAndroid()
            "log" -> System.getenv("LOCALAPPDATA")?.let { open(File(it, "Kanade\\engine.log")) }
        }
    }

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
        javax.swing.JOptionPane.showMessageDialog(null, message, "Kanade", javax.swing.JOptionPane.INFORMATION_MESSAGE)
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
}
