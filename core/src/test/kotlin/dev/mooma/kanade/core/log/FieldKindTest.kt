package dev.mooma.kanade.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldKindTest {
    private fun k(type: Int, ime: Int = 0, hint: String? = null) = FieldKind.fromAndroid(type, ime, hint)

    @Test fun androidClasses() {
        assertEquals(FieldKind.NORMAL, k(0x1))                  // text
        assertEquals(FieldKind.NORMAL, k(0x1 or 0x20))          // email
        assertEquals(FieldKind.SECRET, k(0x1 or 0x80))          // text password
        assertEquals(FieldKind.SECRET, k(0x1 or 0x90))          // visible password
        assertEquals(FieldKind.SECRET, k(0x1 or 0xe0))          // web password
        assertEquals(FieldKind.SECRET, k(0x2 or 0x10))          // number password
        assertEquals(FieldKind.SECRET, k(0x2))                  // number
        assertEquals(FieldKind.SECRET, k(0x3))                  // phone
        assertEquals(FieldKind.SECRET, k(0x4))                  // datetime
        assertEquals(FieldKind.SECRET, k(0))                    // 種類なし
    }

    @Test fun noLearningAndHints() {
        assertEquals(FieldKind.NOLEARN, k(0x1, 0x1000000))
        assertEquals(FieldKind.SECRET, k(0x1, 0, "パスワードを入力"))
        assertEquals(FieldKind.SECRET, k(0x1, 0, "Enter your PIN"))
        assertEquals(FieldKind.SECRET, k(0x1, 0, "認証コード"))
        assertEquals(FieldKind.NORMAL, k(0x1, 0, "メッセージ"))
    }

    @Test fun fld() {
        assertEquals("url", FieldKind.fldOf(0x1 or 0x10))
        assertEquals("email", FieldKind.fldOf(0x1 or 0x20))
        assertEquals("multiline", FieldKind.fldOf(0x1 or 0x20000))
        assertEquals("", FieldKind.fldOf(0x2))
    }

    @Test fun parse() {
        assertEquals(FieldKind.NORMAL, FieldKind.parse("normal"))
        assertEquals(FieldKind.NOLEARN, FieldKind.parse("nolearn"))
        assertEquals(FieldKind.SECRET, FieldKind.parse("secret"))
        assertEquals(FieldKind.SECRET, FieldKind.parse("???"))
    }

    @Test fun secretLookingWords() {
        assertTrue(FieldKind.looksLikeSecret("aB3\$kLm9xQ"))
        assertTrue(FieldKind.looksLikeSecret("sk-abcdefghij"))
        assertTrue(FieldKind.looksLikeSecret("ghp_abcdef1234"))
        assertTrue(FieldKind.looksLikeSecret("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(FieldKind.looksLikeSecret("GitHub"))
        assertFalse(FieldKind.looksLikeSecret("hello"))
        assertFalse(FieldKind.looksLikeSecret("internationalization"))
        assertFalse(FieldKind.looksLikeSecret("a b c d e f g h 1 2"))
    }

    @Test fun limitedApps() {
        assertTrue(LogField.isLimitedApp("org.gnome.Terminal"))
        assertTrue(LogField.isLimitedApp("com.googlecode.iterm2"))
        assertTrue(LogField.isLimitedApp("com.1password.1password"))
        assertFalse(LogField.isLimitedApp("com.google.android.gm"))
        assertFalse(LogField.isLimitedApp(""))
    }
}
