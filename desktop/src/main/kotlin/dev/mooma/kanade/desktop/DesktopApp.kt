package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.Snippets
import dev.mooma.kanade.core.dict.UserDictionary
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
        Ui.english = Ui.isEnglish(s.uiLanguage)
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
        voice?.finalModel = VoiceFinal.of(s.voiceFinal)
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

    /** タスクバーの入力の表示の右クリックのメニュー（DLL から届く）。設定の画面（WinUI 3）を、選んだものの画面で開く */
    fun handleMenu(action: String) {
        val page = when (action) {
            "settings" -> "input"
            "user-dictionary" -> "dictionary"
            "snippets" -> "snippets"
            "import-android" -> "backup"
            "log" -> {
                System.getenv("LOCALAPPDATA")?.let { open(File(it, "Kanade\\engine.log")) }
                return
            }
            else -> return
        }
        val exe = System.getProperty("kanade.settings")?.let(::File)
        if (exe != null && exe.exists()) {
            ProcessBuilder(exe.absolutePath, page).start()
        } else {
            // 設定の画面がない（開発中など）ときは、ファイルをメモ帳で開く
            log("設定の画面がない: ${exe?.path}")
            open(
                when (page) {
                    "dictionary" -> store.userDictionaryFile
                    "snippets" -> store.snippetsFile
                    else -> store.settingsFile
                },
            )
        }
    }

    /** 設定の画面からの頼みごと */
    fun handleAdmin(command: String, argument: String): Pair<Boolean, String> = when (command) {
        "import-android" -> {
            val r = store.importAndroid(File(argument))
            apply(first = false)
            true to Ui.t("設定 ${r.settings} 件、ユーザー辞書・スニペット ${r.files} 件を読み込みました", "Imported ${r.settings} settings and ${r.files} dictionary/snippet files")
        }
        "export-android" -> {
            store.exportAndroid(File(argument))
            true to Ui.t("書き出しました: $argument", "Exported: $argument")
        }
        "clear-learning" -> {
            host.locked {
                if (source.learning != null) {
                    source.learning = LearningHistory()
                    source.learningChanged = true
                }
            }
            save()
            true to Ui.t("覚えたものを消しました", "Cleared the learned conversions")
        }
        else -> false to Ui.t("知らない頼みごとです: $command", "Unknown request: $command")
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
