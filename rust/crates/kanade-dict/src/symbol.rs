//! 読み→記号・絵文字・英語の表記の表 ksym（Kotlin の `SymbolTable`）。形式（UTF-8 の行）: `読み<TAB>記号<TAB>記号…`

use kanade_text::u16str::{u16s, U16String};
use std::collections::HashMap;

#[derive(Clone, Debug, Default)]
pub struct SymbolTable {
    map: HashMap<U16String, Vec<U16String>>,
}

/// Kotlin の `lineSequence()` と同じく、\r\n・\n・\r で行に分ける
fn kotlin_lines(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text;
    let mut done = false;
    std::iter::from_fn(move || {
        if done {
            return None;
        }
        match rest.find(['\n', '\r']) {
            Some(i) => {
                let line = &rest[..i];
                let skip = if rest[i..].starts_with("\r\n") { 2 } else { 1 };
                rest = &rest[i + skip..];
                Some(line)
            }
            None => {
                done = true;
                Some(rest)
            }
        }
    })
}

impl SymbolTable {
    /// 同じ読みが2行あれば後の行（Kotlin の `associate`）
    pub fn parse(text: &str) -> SymbolTable {
        let mut map = HashMap::new();
        for line in kotlin_lines(text).filter(|l| !l.is_empty()) {
            let mut f = line.split('\t');
            let key = u16s(f.next().unwrap_or(""));
            map.insert(key, f.map(u16s).collect());
        }
        SymbolTable { map }
    }

    pub fn lookup(&self, reading: &[u16]) -> &[U16String] {
        self.map.get(reading).map_or(&[], |v| v.as_slice())
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    pub fn keys(&self) -> impl Iterator<Item = &U16String> {
        self.map.keys()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_and_lookup() {
        let t = SymbolTable::parse("やじるし\t→\t←\r\nぷらす\t＋\n\nやじるし\t⇒\rえ\t");
        assert_eq!(t.len(), 3);
        assert_eq!(t.lookup(&u16s("やじるし")), &[u16s("⇒")]);
        assert_eq!(t.lookup(&u16s("ぷらす")), &[u16s("＋")]);
        assert_eq!(t.lookup(&u16s("え")), &[u16s("")]);
        assert!(t.lookup(&u16s("ない")).is_empty());
    }
}
