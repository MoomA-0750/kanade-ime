package dev.mooma.kanade.core.voice

/**
 * 言いよどみ（「えー」「えーと」「あのー」など）を消す。Typeless・Simeji などは言語モデルで消すが、
 * Kanade は端末内ですぐ入れたいので、決まった言葉だけを規則で消す（設定で入れる。既定は切る。整えすぎを嫌う人が多いため）。
 * 「ええ」（はい）や「あの人」の「あの」のように意味のある言葉は消さない
 */
object Fillers {
    /** どこにあっても消す（ふつうの言葉の中には出てこない形） */
    private val ANYWHERE = Regex("(?:えーっと|えーと|ええと|えっと|あのー+|あのぉ+|うーんと|うーん|エーット|エート|アノー+|ウーン)[、，,\\s]*")

    /** 文の始めか句読点・空白の後ろだけ消す（「えー」「んー」は言葉の一部にもなるので） */
    private val AT_START = Regex("(?:^|(?<=[、。，．,.！？!?\\s]))(?:えー+|エー+|んー+|えと|エト)[、，,\\s]*")

    fun remove(text: String): String = AT_START.replace(ANYWHERE.replace(text, ""), "").trimStart()
}
