//! 表記→読みの逆引き（Kotlin の `ReverseDictionary`）。reverse.kdic は kdic の形で、読みの欄に表記、表記の欄に読みが入っている

use crate::system::SystemDictionary;
use kanade_text::u16str::U16String;
use kanade_text::width::{is_ideographic, to_hiragana};

/// 文の区切り。`reading` は `surface` の読み（かな・記号はそのまま、カタカナはひらがなに）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Piece {
    pub surface: U16String,
    pub reading: U16String,
}

pub struct ReverseDictionary {
    dict: SystemDictionary,
}

const MAX_WORD: usize = 8;
/// 区切りを増やすほど高くする（長い語で読むほうを選ぶ）
const PIECE_COST: i64 = 2000;
/// かな1字の重み
const CHAR_COST: i64 = 1500;

impl ReverseDictionary {
    pub fn new(dict: SystemDictionary) -> ReverseDictionary {
        ReverseDictionary { dict }
    }

    /// `text` を辞書の語とかな・記号に分けて読みを付ける。コストが最小の分け方。読めない漢字があれば None
    pub fn pieces(&self, text: &[u16]) -> Option<Vec<Piece>> {
        if text.is_empty() {
            return Some(Vec::new());
        }
        let n = text.len();
        let mut best = vec![i64::MAX; n + 1];
        let mut back: Vec<Option<Piece>> = vec![None; n + 1];
        best[0] = 0;
        let mut hits = Vec::new();
        for i in 0..n {
            if best[i] == i64::MAX {
                continue;
            }
            let c = text[i];
            if !is_ideographic(c) {
                let piece = Piece {
                    surface: vec![c],
                    reading: to_hiragana(&[c]),
                };
                let cost = best[i] + CHAR_COST;
                offer(&mut best, &mut back, i + 1, cost, piece);
            }
            self.dict.prefix_search_into(text, i, MAX_WORD, &mut hits);
            for &(len, index) in &hits {
                let surface = &text[i..i + len];
                for e in self.dict.entries_of(index) {
                    let cost = best[i] + self.dict.cost(e) as i64 + PIECE_COST;
                    if cost < best[i + len] {
                        let piece = Piece {
                            surface: surface.to_vec(),
                            reading: self.dict.surface(e, surface),
                        };
                        offer(&mut best, &mut back, i + len, cost, piece);
                    }
                }
            }
        }
        if best[n] == i64::MAX {
            return None;
        }
        let mut out = Vec::new();
        let mut pos = n;
        while pos > 0 {
            let p = back[pos].clone().expect("経路");
            pos -= p.surface.len();
            out.push(p);
        }
        out.reverse();
        // かな・記号の1字ずつの区切りは、続いていればまとめる
        let mut merged: Vec<Piece> = Vec::new();
        for p in out {
            let single = p.surface.len() == 1
                && !is_ideographic(p.surface[0])
                && p.reading == to_hiragana(&p.surface);
            let last_single = merged.last().is_some_and(|l| {
                !l.surface.iter().any(|&c| is_ideographic(c))
                    && l.reading == to_hiragana(&l.surface)
            });
            if single && last_single {
                let l = merged.last_mut().expect("直前");
                l.surface.extend_from_slice(&p.surface);
                l.reading.extend_from_slice(&p.reading);
            } else {
                merged.push(p);
            }
        }
        Some(merged)
    }

    /// `text` の読み。読めない漢字があれば None
    pub fn reading_of(&self, text: &[u16]) -> Option<U16String> {
        self.pieces(text)
            .map(|p| p.into_iter().flat_map(|x| x.reading).collect())
    }
}

fn offer(best: &mut [i64], back: &mut [Option<Piece>], end: usize, cost: i64, piece: Piece) {
    if cost < best[end] {
        best[end] = cost;
        back[end] = Some(piece);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bytes::Bytes;
    use crate::system::{write_kdic, WordEntry};
    use kanade_text::{lossy, u16s};

    #[test]
    fn reads_kanji_and_merges_kana() {
        // 逆引きの辞書: 読みの欄に表記
        let words = vec![
            WordEntry::new("漢字", 1, 1, 3000, "かんじ"),
            WordEntry::new("漢", 1, 1, 5000, "かん"),
            WordEntry::new("字", 1, 1, 5000, "じ"),
        ];
        let rev = ReverseDictionary::new(
            SystemDictionary::new(Bytes::from_vec(write_kdic(&words))).unwrap(),
        );
        let p = rev.pieces(&u16s("漢字をカナで")).unwrap();
        let shown: Vec<(String, String)> = p
            .iter()
            .map(|x| (lossy(&x.surface), lossy(&x.reading)))
            .collect();
        assert_eq!(
            shown,
            vec![
                ("漢字".into(), "かんじ".into()),
                ("をカナで".into(), "をかなで".into())
            ]
        );
        assert_eq!(lossy(&rev.reading_of(&u16s("漢字")).unwrap()), "かんじ");
        assert!(rev.reading_of(&u16s("猫")).is_none());
    }
}
