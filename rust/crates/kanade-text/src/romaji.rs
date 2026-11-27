//! ローマ字→ひらがな（Kotlin の `RomajiTable`・`RomajiConverter`）。
//! 文字は UTF-16 の単位で受け取り、Kotlin と同じ規則で「ん」「っ」を決める。

use crate::u16str::{u16s, U16String};
use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

/// 確定したかな1まとまりと、その元になったローマ字。`auto_close` は開き括弧で自動に入れた閉じ括弧
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KanaUnit {
    pub text: U16String,
    pub raw: U16String,
    pub auto_close: bool,
}

impl KanaUnit {
    pub fn new(text: U16String, raw: U16String) -> Self {
        KanaUnit {
            text,
            raw,
            auto_close: false,
        }
    }
}

/// ローマ字の表。Kotlin の `buildMap`（入れた順を覚える）と同じく、同じキーを入れ直しても位置は最初のまま
pub struct RomajiTable {
    /// 入れた順の (綴り, かな)
    ordered: Vec<(U16String, U16String)>,
    index: HashMap<U16String, usize>,
    prefixes: HashSet<U16String>,
    reverse: HashMap<U16String, U16String>,
}

impl RomajiTable {
    /// 共有の表（Kotlin の `object RomajiTable`）
    pub fn get() -> &'static RomajiTable {
        static TABLE: OnceLock<RomajiTable> = OnceLock::new();
        TABLE.get_or_init(RomajiTable::build)
    }

    fn build() -> RomajiTable {
        let mut t = RomajiTable {
            ordered: Vec::new(),
            index: HashMap::new(),
            prefixes: HashSet::new(),
            reverse: HashMap::new(),
        };
        let mut put = |k: &str, v: &str| {
            let (k, v) = (u16s(k), u16s(v));
            match t.index.get(&k) {
                Some(&i) => t.ordered[i].1 = v,
                None => {
                    t.index.insert(k.clone(), t.ordered.len());
                    t.ordered.push((k, v));
                }
            }
        };
        for (k, v) in [
            ("a", "あ"),
            ("i", "い"),
            ("u", "う"),
            ("e", "え"),
            ("o", "お"),
        ] {
            put(k, v);
        }
        let rows = [
            ("k", "かきくけこ"),
            ("s", "さしすせそ"),
            ("t", "たちつてと"),
            ("n", "なにぬねの"),
            ("h", "はひふへほ"),
            ("m", "まみむめも"),
            ("r", "らりるれろ"),
            ("g", "がぎぐげご"),
            ("z", "ざじずぜぞ"),
            ("d", "だぢづでど"),
            ("b", "ばびぶべぼ"),
            ("p", "ぱぴぷぺぽ"),
            ("c", "かしくせこ"),
            ("l", "ぁぃぅぇぉ"),
            ("x", "ぁぃぅぇぉ"),
        ];
        for (consonant, kana) in rows {
            for (v, k) in "aiueo".chars().zip(kana.chars()) {
                put(&format!("{consonant}{v}"), &k.to_string());
            }
        }
        for (k, v) in [
            ("ya", "や"),
            ("yu", "ゆ"),
            ("yo", "よ"),
            ("wa", "わ"),
            ("wo", "を"),
        ] {
            put(k, v);
        }
        for (k, v) in [
            ("shi", "し"),
            ("chi", "ち"),
            ("tsu", "つ"),
            ("fu", "ふ"),
            ("ji", "じ"),
            ("wi", "うぃ"),
            ("we", "うぇ"),
            ("wu", "う"),
            ("ye", "いぇ"),
            ("yi", "い"),
            ("qa", "くぁ"),
            ("qi", "くぃ"),
            ("qu", "く"),
            ("qe", "くぇ"),
            ("qo", "くぉ"),
            ("va", "ゔぁ"),
            ("vi", "ゔぃ"),
            ("vu", "ゔ"),
            ("ve", "ゔぇ"),
            ("vo", "ゔぉ"),
        ] {
            put(k, v);
        }
        let youon = [
            ("ky", "き"),
            ("sy", "し"),
            ("ty", "ち"),
            ("cy", "ち"),
            ("ny", "に"),
            ("hy", "ひ"),
            ("my", "み"),
            ("ry", "り"),
            ("gy", "ぎ"),
            ("zy", "じ"),
            ("jy", "じ"),
            ("dy", "ぢ"),
            ("by", "び"),
            ("py", "ぴ"),
            ("vy", "ゔ"),
            ("fy", "ふ"),
        ];
        for (prefix, base) in youon {
            for (v, small) in [
                ("a", "ゃ"),
                ("u", "ゅ"),
                ("o", "ょ"),
                ("i", "ぃ"),
                ("e", "ぇ"),
            ] {
                put(&format!("{prefix}{v}"), &format!("{base}{small}"));
            }
        }
        for (k, v) in [
            ("sha", "しゃ"),
            ("shu", "しゅ"),
            ("sho", "しょ"),
            ("she", "しぇ"),
            ("cha", "ちゃ"),
            ("chu", "ちゅ"),
            ("cho", "ちょ"),
            ("che", "ちぇ"),
            ("ja", "じゃ"),
            ("ju", "じゅ"),
            ("jo", "じょ"),
            ("je", "じぇ"),
            ("fa", "ふぁ"),
            ("fi", "ふぃ"),
            ("fe", "ふぇ"),
            ("fo", "ふぉ"),
            ("tsa", "つぁ"),
            ("tsi", "つぃ"),
            ("tse", "つぇ"),
            ("tso", "つぉ"),
            ("tha", "てゃ"),
            ("thi", "てぃ"),
            ("thu", "てゅ"),
            ("the", "てぇ"),
            ("tho", "てょ"),
            ("dha", "でゃ"),
            ("dhi", "でぃ"),
            ("dhu", "でゅ"),
            ("dhe", "でぇ"),
            ("dho", "でょ"),
            ("twu", "とぅ"),
            ("dwu", "どぅ"),
            ("wha", "うぁ"),
            ("whi", "うぃ"),
            ("whu", "う"),
            ("whe", "うぇ"),
            ("who", "うぉ"),
            ("kwa", "くぁ"),
            ("gwa", "ぐぁ"),
        ] {
            put(k, v);
        }
        for p in ["x", "l"] {
            for (k, v) in [
                ("ya", "ゃ"),
                ("yu", "ゅ"),
                ("yo", "ょ"),
                ("tu", "っ"),
                ("tsu", "っ"),
                ("wa", "ゎ"),
                ("ka", "ゕ"),
                ("ke", "ゖ"),
            ] {
                put(&format!("{p}{k}"), v);
            }
        }
        for (k, v) in [("xn", "ん"), ("nn", "ん"), ("n'", "ん")] {
            put(k, v);
        }

        for (key, _) in &t.ordered {
            for i in 1..key.len() {
                t.prefixes.insert(key[..i].to_vec());
            }
        }
        // 逆引き: 綴りの短い順（同じ長さは入れた順。Kotlin の sortedBy は安定）に、先に入ったものを残す
        let mut by_len: Vec<&(U16String, U16String)> = t.ordered.iter().collect();
        by_len.sort_by_key(|(k, _)| k.len());
        let mut reverse = HashMap::new();
        for (k, v) in by_len {
            reverse.entry(v.clone()).or_insert_with(|| k.clone());
        }
        reverse.insert(u16s("っ"), u16s("xtu"));
        t.reverse = reverse;
        t
    }

    /// 綴り→かな
    pub fn entry(&self, key: &[u16]) -> Option<&U16String> {
        self.index.get(key).map(|&i| &self.ordered[i].1)
    }

    /// 表の綴りの接頭辞（綴りそのものは、ほかの綴りの接頭辞でなければ含まない）
    pub fn is_prefix(&self, key: &[u16]) -> bool {
        self.prefixes.contains(key)
    }

    /// 表の全部（入れた順）
    pub fn entries(&self) -> &[(U16String, U16String)] {
        &self.ordered
    }

    pub fn prefixes(&self) -> &HashSet<U16String> {
        &self.prefixes
    }

    /// ひらがな1まとまり→ローマ字1通り（F9/F10 で消えた綴りを戻すため）
    pub fn reverse(&self) -> &HashMap<U16String, U16String> {
        &self.reverse
    }
}

/// Java の `Character.toLowerCase(char)`（単純な対応）に合わせた小文字化。代用対はそのまま
fn lowercase_char(c: u16) -> u16 {
    if c < 0x80 {
        return (c as u8).to_ascii_lowercase() as u16;
    }
    match char::from_u32(c as u32) {
        Some(ch) => {
            // Rust の to_lowercase は特別な対応（İ → i̇）で2文字になることがある。Java の単純な対応は最初の1文字に当たる
            let lower = ch.to_lowercase().next().unwrap_or(ch);
            let mut buf = [0u16; 2];
            let enc = lower.encode_utf16(&mut buf);
            if enc.len() == 1 {
                enc[0]
            } else {
                c
            }
        }
        None => c,
    }
}

fn is_doublable_consonant(c: u16) -> bool {
    (b'a' as u16..=b'z' as u16).contains(&c) && !b"aiueon".contains(&(c as u8))
}

/// ローマ字を1文字ずつ受け取り、かなが決まった分を返す（Kotlin の `RomajiConverter`）
#[derive(Clone, Debug, Default)]
pub struct RomajiConverter {
    pending: U16String,
}

impl RomajiConverter {
    pub fn new() -> Self {
        Self::default()
    }

    /// 綴りの途中（"k"、"ky" など）
    pub fn pending(&self) -> &[u16] {
        &self.pending
    }

    pub fn feed(&mut self, c: u16) -> Vec<KanaUnit> {
        let table = RomajiTable::get();
        self.pending.push(lowercase_char(c));
        let mut out = Vec::new();
        while !self.pending.is_empty() {
            let exact = table.entry(&self.pending);
            let is_prefix = table.is_prefix(&self.pending);
            if let (Some(kana), false) = (exact, is_prefix) {
                out.push(KanaUnit::new(
                    kana.clone(),
                    std::mem::take(&mut self.pending),
                ));
                break;
            }
            if is_prefix {
                break;
            }
            // 表にない綴り。先頭1文字だけ確定させて残りを読み直す
            let head = self.pending[0];
            let next = self.pending.get(1).copied();
            let unit = match next {
                Some(_) if head == b'n' as u16 => KanaUnit::new(u16s("ん"), vec![head]),
                Some(n) if head == n && is_doublable_consonant(head) => {
                    KanaUnit::new(u16s("っ"), vec![head])
                }
                // 「matcha」のように t の次が ch なら「っ」
                Some(n) if head == b't' as u16 && n == b'c' as u16 => {
                    KanaUnit::new(u16s("っ"), vec![head])
                }
                _ => KanaUnit::new(vec![head], vec![head]),
            };
            out.push(unit);
            self.pending.remove(0);
        }
        out
    }

    /// 確定・記号入力の直前に呼ぶ。残った "n" は「ん」、それ以外はそのまま返す
    pub fn flush(&mut self) -> Vec<KanaUnit> {
        let p = std::mem::take(&mut self.pending);
        if p.is_empty() {
            Vec::new()
        } else if p == [b'n' as u16] {
            vec![KanaUnit::new(u16s("ん"), p)]
        } else {
            vec![KanaUnit::new(p.clone(), p)]
        }
    }

    /// 綴りの途中を1文字消す。消せたら true
    pub fn delete_last(&mut self) -> bool {
        self.pending.pop().is_some()
    }

    /// 綴りの途中の末尾 `n` 文字を消す
    pub fn drop_last(&mut self, n: usize) {
        let keep = self.pending.len().saturating_sub(n);
        self.pending.truncate(keep);
    }

    pub fn clear(&mut self) {
        self.pending.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::u16str::lossy;

    fn kana(s: &str) -> String {
        let mut c = RomajiConverter::new();
        let mut out = Vec::new();
        for u in u16s(s) {
            for k in c.feed(u) {
                out.extend(k.text);
            }
        }
        for k in c.flush() {
            out.extend(k.text);
        }
        lossy(&out)
    }

    #[test]
    fn basic_romaji() {
        assert_eq!(kana("nihongo"), "にほんご");
        assert_eq!(kana("shinbun"), "しんぶん");
        assert_eq!(kana("kyatto"), "きゃっと");
        assert_eq!(kana("chotto"), "ちょっと");
        assert_eq!(kana("fairu"), "ふぁいる");
        assert_eq!(kana("tuduku"), "つづく");
    }

    #[test]
    fn n_rules() {
        assert_eq!(kana("konnnichiha"), "こんにちは");
        assert_eq!(kana("kin'en"), "きんえん");
        assert_eq!(kana("hon"), "ほん");
        assert_eq!(kana("sanpo"), "さんぽ");
        assert_eq!(kana("konnya"), "こんや");
    }

    #[test]
    fn small_kana_and_sokuon() {
        assert_eq!(kana("xaltuxya"), "ぁっゃ");
        assert_eq!(kana("matcha"), "まっちゃ");
        assert_eq!(kana("KA"), "か");
    }

    #[test]
    fn pending_and_delete() {
        let mut c = RomajiConverter::new();
        assert!(c.feed(b'k' as u16).is_empty());
        assert!(c.feed(b'y' as u16).is_empty());
        assert_eq!(c.pending(), u16s("ky").as_slice());
        assert!(c.delete_last());
        assert_eq!(c.pending(), u16s("k").as_slice());
        c.drop_last(5);
        assert!(!c.delete_last());
    }

    #[test]
    fn reverse_prefers_short_spellings() {
        let t = RomajiTable::get();
        assert_eq!(t.reverse().get(&u16s("し")), Some(&u16s("si")));
        assert_eq!(t.reverse().get(&u16s("っ")), Some(&u16s("xtu")));
        assert!(t.is_prefix(&u16s("ts")));
        assert!(!t.is_prefix(&u16s("ka")));
    }
}
