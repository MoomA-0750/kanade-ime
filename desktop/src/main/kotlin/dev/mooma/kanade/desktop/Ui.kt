package dev.mooma.kanade.desktop

import java.util.Locale

/**
 * 画面に出す文字の言語（設定の ui_language: auto・ja・en）。auto は Windows の表示言語が日本語なら日本語、ほかは英語。
 * 設定の画面（windows/settings の Strings.cs）と DLL（LangBarButton.cpp）も同じ決め方をする
 */
object Ui {
    @Volatile var english: Boolean = isEnglish("auto")

    fun isEnglish(setting: String, displayLanguage: String = Locale.getDefault(Locale.Category.DISPLAY).language): Boolean = when (setting) {
        "ja" -> false
        "en" -> true
        else -> displayLanguage != "ja"
    }

    fun t(ja: String, en: String) = if (english) en else ja
}
