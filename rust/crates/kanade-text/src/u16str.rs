//! Kotlin（Java）の `String` と同じ UTF-16 の単位で文字列を扱うための小道具。
//!
//! Kanade の辞書（kdic）・プロトコルの数・ラティスの位置はすべて UTF-16 の単位なので、
//! Rust の側も `Vec<u16>`（`U16String`）で持ち、外に出すときだけ `String` に直す。
//! 片割れの代用対（lone surrogate）も Kotlin と同じく1単位としてそのまま持てる。

/// UTF-16 の単位の並び（Kotlin の `String` に当たる）
pub type U16String = Vec<u16>;

/// `&str` を UTF-16 の単位にする
pub fn u16s(s: &str) -> U16String {
    s.encode_utf16().collect()
}

/// UTF-16 の単位を `String` にする。片割れの代用対は U+FFFD にする（表示用。比較には [`esc`] を使う）
pub fn lossy(s: &[u16]) -> String {
    String::from_utf16_lossy(s)
}

/// 代用対の前半か
pub fn is_high_surrogate(c: u16) -> bool {
    (0xD800..=0xDBFF).contains(&c)
}

/// 代用対の後半か
pub fn is_low_surrogate(c: u16) -> bool {
    (0xDC00..=0xDFFF).contains(&c)
}

/// 比較の記録（testdata/nbest・testdata/romaji）の文字列の書き方。Kotlin の `rcEsc`（dictool の RustCompare.kt）と同じ。
/// `\` タブ 改行 `|` `=` を `\` で、制御文字と片割れの代用対を `\uXXXX`（大文字の16進）で書き、ほかはそのまま。
pub fn esc(s: &[u16]) -> String {
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < s.len() {
        let c = s[i];
        match c {
            0x5C => out.push_str("\\\\"),
            0x09 => out.push_str("\\t"),
            0x0A => out.push_str("\\n"),
            0x7C => out.push_str("\\|"),
            0x3D => out.push_str("\\="),
            _ if c < 0x20 || c == 0x7F => out.push_str(&format!("\\u{c:04X}")),
            _ if is_high_surrogate(c) => {
                if i + 1 < s.len() && is_low_surrogate(s[i + 1]) {
                    let cp = 0x10000 + (((c as u32) - 0xD800) << 10) + ((s[i + 1] as u32) - 0xDC00);
                    out.push(char::from_u32(cp).expect("正しい代用対"));
                    i += 1;
                } else {
                    out.push_str(&format!("\\u{c:04X}"));
                }
            }
            _ if is_low_surrogate(c) => out.push_str(&format!("\\u{c:04X}")),
            _ => out.push(char::from_u32(c as u32).expect("BMP の文字")),
        }
        i += 1;
    }
    out
}

/// [`esc`] で書いた文字列を UTF-16 の単位に戻す。形が崩れていれば `None`
pub fn unesc(s: &str) -> Option<U16String> {
    let mut out = Vec::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            let mut buf = [0u16; 2];
            out.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        match chars.next()? {
            '\\' => out.push(0x5C),
            't' => out.push(0x09),
            'n' => out.push(0x0A),
            '|' => out.push(0x7C),
            '=' => out.push(0x3D),
            'u' => {
                let hex: String = chars.by_ref().take(4).collect();
                if hex.len() != 4 {
                    return None;
                }
                out.push(u16::from_str_radix(&hex, 16).ok()?);
            }
            _ => return None,
        }
    }
    Some(out)
}

/// `\|` を区切りと見なさずに `|` で分ける（[`esc`] で書いた欄の中の並び）
pub fn split_escaped(s: &str, sep: char) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut start = 0;
    let mut escaped = false;
    for (i, c) in s.char_indices() {
        if escaped {
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else if c == sep {
            parts.push(&s[start..i]);
            start = i + c.len_utf8();
        }
    }
    parts.push(&s[start..]);
    parts
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn esc_round_trips_lone_surrogates_and_specials() {
        let mut s = u16s("a\\b\tc\n|=😀");
        s.push(0xD83D); // 片割れの前半
        s.extend(u16s("x"));
        s.push(0xDE00); // 片割れの後半
        s.push(0x01);
        let e = esc(&s);
        assert_eq!(e, "a\\\\b\\tc\\n\\|\\=😀\\uD83Dx\\uDE00\\u0001");
        assert_eq!(unesc(&e).unwrap(), s);
    }

    #[test]
    fn split_keeps_escaped_separators() {
        assert_eq!(split_escaped("a\\|b|c", '|'), vec!["a\\|b", "c"]);
        assert_eq!(split_escaped("", '|'), vec![""]);
    }
}
