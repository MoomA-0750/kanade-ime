//! ひらがな・カタカナ・全角・半角の変換（Kotlin の `CharWidth`）。UTF-16 の単位ごとに見る

use crate::u16str::{u16s, U16String};
use std::collections::HashMap;
use std::sync::OnceLock;

const HIRA_FIRST: u16 = 0x3041; // ぁ
const HIRA_LAST: u16 = 0x3096; // ゖ
const KATA_FIRST: u16 = 0x30A1; // ァ
const KATA_LAST: u16 = 0x30F6; // ヶ

pub fn to_katakana(s: &[u16]) -> U16String {
    s.iter()
        .map(|&c| {
            if (HIRA_FIRST..=HIRA_LAST).contains(&c) {
                c + 0x60
            } else {
                c
            }
        })
        .collect()
}

pub fn to_hiragana(s: &[u16]) -> U16String {
    s.iter()
        .map(|&c| {
            if (KATA_FIRST..=KATA_LAST).contains(&c) {
                c - 0x60
            } else {
                c
            }
        })
        .collect()
}

pub fn to_full_width_ascii(s: &[u16]) -> U16String {
    s.iter()
        .map(|&c| match c {
            0x20 => 0x3000,
            0x21..=0x7E => c + 0xFEE0,
            _ => c,
        })
        .collect()
}

/// 全角英数・記号（！〜～と全角スペース）を半角に。それ以外はそのまま
pub fn to_half_width_ascii(s: &[u16]) -> U16String {
    s.iter()
        .map(|&c| match c {
            0x3000 => 0x20,
            0xFF01..=0xFF5E => c - 0xFEE0,
            _ => c,
        })
        .collect()
}

/// 漢字（読みを字から決められない文字）。々・〆・ヶ と代用対も含める
pub fn is_ideographic(c: u16) -> bool {
    (0x4E00..=0x9FFF).contains(&c)
        || (0x3400..=0x4DBF).contains(&c)
        || (0xF900..=0xFAFF).contains(&c)
        || c == 0x3005
        || c == 0x3006
        || c == 0x30F6
        || (0xD800..=0xDFFF).contains(&c)
}

pub fn has_full_width_ascii(s: &[u16]) -> bool {
    s.iter().any(|c| (0xFF01..=0xFF5E).contains(c))
}

pub fn has_half_width_ascii(s: &[u16]) -> bool {
    s.iter().any(|c| (0x21..=0x7E).contains(c))
}

fn half_katakana() -> &'static HashMap<u16, U16String> {
    static MAP: OnceLock<HashMap<u16, U16String>> = OnceLock::new();
    MAP.get_or_init(|| {
        let full = "ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロワヲンヴー。「」、・";
        let half = [
            "ｧ", "ｱ", "ｨ", "ｲ", "ｩ", "ｳ", "ｪ", "ｴ", "ｫ", "ｵ", "ｶ", "ｶﾞ", "ｷ", "ｷﾞ", "ｸ", "ｸﾞ", "ｹ", "ｹﾞ", "ｺ", "ｺﾞ", "ｻ", "ｻﾞ", "ｼ", "ｼﾞ",
            "ｽ", "ｽﾞ", "ｾ", "ｾﾞ", "ｿ", "ｿﾞ", "ﾀ", "ﾀﾞ", "ﾁ", "ﾁﾞ", "ｯ", "ﾂ", "ﾂﾞ", "ﾃ", "ﾃﾞ", "ﾄ", "ﾄﾞ", "ﾅ", "ﾆ", "ﾇ", "ﾈ", "ﾉ", "ﾊ", "ﾊﾞ",
            "ﾊﾟ", "ﾋ", "ﾋﾞ", "ﾋﾟ", "ﾌ", "ﾌﾞ", "ﾌﾟ", "ﾍ", "ﾍﾞ", "ﾍﾟ", "ﾎ", "ﾎﾞ", "ﾎﾟ", "ﾏ", "ﾐ", "ﾑ", "ﾒ", "ﾓ", "ｬ", "ﾔ", "ｭ", "ﾕ", "ｮ", "ﾖ",
            "ﾗ", "ﾘ", "ﾙ", "ﾚ", "ﾛ", "ﾜ", "ｦ", "ﾝ", "ｳﾞ", "ｰ", "｡", "｢", "｣", "､", "･",
        ];
        let full = u16s(full);
        assert_eq!(full.len(), half.len());
        full.into_iter().zip(half.iter().map(|h| u16s(h))).collect()
    })
}

pub fn to_half_width_katakana(s: &[u16]) -> U16String {
    let map = half_katakana();
    let mut out = Vec::with_capacity(s.len());
    for c in to_katakana(s) {
        match map.get(&c) {
            Some(h) => out.extend_from_slice(h),
            None => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::u16str::lossy;

    fn f(g: fn(&[u16]) -> U16String, s: &str) -> String {
        lossy(&g(&u16s(s)))
    }

    #[test]
    fn kana_and_width() {
        assert_eq!(f(to_katakana, "ひらがなゔー"), "ヒラガナヴー");
        assert_eq!(f(to_hiragana, "カタカナヶヷ"), "かたかなゖヷ");
        assert_eq!(f(to_full_width_ascii, "a b!~"), "ａ　ｂ！～");
        assert_eq!(f(to_half_width_ascii, "ａ　ｂ！～"), "a b!~");
        assert_eq!(f(to_half_width_katakana, "がっこう。"), "ｶﾞｯｺｳ｡");
    }

    #[test]
    fn ideographic() {
        for c in u16s("漢々〆ヶ") {
            assert!(is_ideographic(c));
        }
        assert!(!is_ideographic(u16s("か")[0]));
        assert!(u16s("😀").iter().all(|&c| is_ideographic(c)));
        assert!(has_full_width_ascii(&u16s("ａ")) && !has_full_width_ascii(&u16s("a")));
        assert!(has_half_width_ascii(&u16s("a")) && !has_half_width_ascii(&u16s(" ")));
    }
}
