package dev.mooma.kanade.desktop

import java.io.File

/** 動いている OS と、macOS のファイルの置き場所 */
object Os {
    private val name = System.getProperty("os.name")
    val isWindows = name.startsWith("Windows")
    val isMac = name.startsWith("Mac")

    /**
     * macOS の Kanade の置き場所（~/Library/Application Support/Kanade）。設定・辞書・学習・ソケットをここに置き、
     * エンジン（実行環境・辞書・音声のモデル）はその下の engine/ に入れる（install.sh）
     */
    fun macSupport(): File = File(System.getProperty("user.home"), "Library/Application Support/Kanade")

    fun macLibrary(sub: String): File = File(System.getProperty("user.home"), "Library/$sub/Kanade")
}
