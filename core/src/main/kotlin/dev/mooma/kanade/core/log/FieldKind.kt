package dev.mooma.kanade.core.log

/** 入力欄の種類。[NORMAL] 以外では、入力の記録を何も書かない */
enum class FieldKind {
    /** ふつうの入力欄 */
    NORMAL,

    /** パスワードなど秘密の欄 */
    SECRET,

    /** 学習しないよう求める欄（シークレットモードなど） */
    NOLEARN,

    /** まだ分からない（フロントが知らせてくるまで） */
    UNKNOWN;

    companion object {
        /** プロトコルの `field` 行の kind。知らない値は [SECRET]（記録しない側に倒す） */
        fun parse(s: String?): FieldKind = when (s) {
            "normal" -> NORMAL
            "nolearn" -> NOLEARN
            "secret" -> SECRET
            else -> SECRET
        }

        // android.text.InputType / EditorInfo の値（core は Android に頼らないので数で持つ）
        private const val MASK_CLASS = 0xf
        private const val MASK_VARIATION = 0xff0
        private const val CLASS_TEXT = 1
        private const val CLASS_NUMBER = 2
        private const val CLASS_PHONE = 3
        private const val CLASS_DATETIME = 4
        private const val TEXT_PASSWORD = 0x80
        private const val TEXT_VISIBLE_PASSWORD = 0x90
        private const val TEXT_WEB_PASSWORD = 0xe0
        private const val NUMBER_PASSWORD = 0x10
        private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

        private val SECRET_HINTS = listOf("パスワード", "password", "passcode", "暗証", "pin", "コード", "code", "otp", "認証", "セキュリティ")

        /**
         * Android の入力欄の種類（`onStartInput` の inputType・imeOptions・hint）から決める。
         * パスワード、数字・電話・日時の欄（認証コードや PIN）、hint にそれらしい言葉がある欄は [SECRET]。
         * 学習しない指定は [NOLEARN]
         */
        fun fromAndroid(inputType: Int, imeOptions: Int, hint: CharSequence?): FieldKind {
            val cls = inputType and MASK_CLASS
            if (cls == 0) return SECRET // 入力の種類が無い欄（読めない）は記録しない
            val variation = inputType and MASK_VARIATION
            if (cls == CLASS_TEXT && (variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD)) return SECRET
            if (cls == CLASS_NUMBER || cls == CLASS_PHONE || cls == CLASS_DATETIME) return SECRET
            val h = hint?.toString()?.lowercase().orEmpty()
            if (h.isNotEmpty() && SECRET_HINTS.any { it in h }) return SECRET
            if (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return NOLEARN
            return NORMAL
        }

        /** 入力欄の細かい種類（`url`・`email` など。丸めたものだけ） */
        fun fldOf(inputType: Int): String {
            if (inputType and MASK_CLASS != CLASS_TEXT) return ""
            return when (inputType and MASK_VARIATION) {
                0x10 -> "url"
                0x20, 0xd0 -> "email"
                0x30 -> "subject"
                0x40 -> "message"
                0x60 -> "person"
                0x70 -> "address"
                0xb0 -> "filter"
                else -> if (inputType and 0x20000 != 0) "multiline" else "text"
            }
        }

        private val SECRET_PREFIXES = listOf("sk-", "ghp_", "gho_", "github_pat_", "xox", "akia", "asia", "glpat-", "eyj", "bearer")

        /**
         * 直接入力の語が秘密の鍵に見えるか。8 文字以上・空白なしで、小文字・大文字・数字・記号のうち3種以上、
         * または鍵によくある前置きで始まる
         */
        fun looksLikeSecret(word: String): Boolean {
            if (word.any { it.isWhitespace() }) return false
            val lower = word.lowercase()
            if (SECRET_PREFIXES.any { lower.startsWith(it) } && word.length >= 8) return true
            if (word.length < 8) return false
            val kinds = listOf(
                word.any { it in 'a'..'z' }, word.any { it in 'A'..'Z' }, word.any { it in '0'..'9' },
                word.any { it.code in 0x21..0x7e && !it.isLetterOrDigit() },
            ).count { it }
            return kinds >= 3
        }
    }
}

/**
 * 今の入力欄について、記録のために知っていること。[kind] が [FieldKind.NORMAL] のときだけ記録する。
 * [limited] は、ターミナル・パスワード管理アプリなど（確定と切り替えだけ残し、キー・取り消し・英字の語は残さない）
 */
data class LogField(
    val kind: FieldKind = FieldKind.UNKNOWN,
    val app: String = "",
    val fld: String = "",
    val limited: Boolean = false,
) {
    companion object {
        /** 標準の「確定と切り替えだけ残す」アプリ（名前に含まれていれば当たる。小文字で比べる） */
        const val DEFAULT_LIMITED_APPS = "terminal,iterm,alacritty,kitty,wezterm,konsole,foot,ghostty,warp,windowsterminal,cmd.exe,powershell,pwsh,putty,termux,tmux," +
            "1password,bitwarden,keepass,lastpass,dashlane,enpass,proton.pass"

        fun isLimitedApp(app: String, list: String = DEFAULT_LIMITED_APPS): Boolean {
            if (app.isEmpty()) return false
            val a = app.lowercase()
            return list.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { it in a }
        }

        fun of(kind: FieldKind, app: String, fld: String, limitedApps: String = DEFAULT_LIMITED_APPS) =
            LogField(kind, app, fld, isLimitedApp(app, limitedApps))
    }
}
