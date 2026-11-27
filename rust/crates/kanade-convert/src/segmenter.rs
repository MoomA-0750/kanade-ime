//! ラティスの経路（語の並び）を文節にまとめる（Kotlin の `Segmenter`）。内容語で新しい文節を始め、付属語は前に付ける

use crate::converter::Word;
use kanade_dict::{Kind, PosTable};
use kanade_text::u16str::U16String;

/// 文節（読みと表記）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Segment {
    pub reading: U16String,
    pub surface: U16String,
}

pub struct Segmenter {
    pos: PosTable,
}

impl Segmenter {
    pub fn new(pos: PosTable) -> Segmenter {
        Segmenter { pos }
    }

    /// 品詞 `id` で終わる語を予測の候補に出してよいか
    pub fn predictable(&self, id: u16) -> bool {
        self.pos.predictable(id)
    }

    pub fn segments(&self, words: &[Word]) -> Vec<Segment> {
        let mut result = Vec::new();
        let mut reading = Vec::new();
        let mut surface = Vec::new();
        let mut after_prefix = false;
        for w in words {
            let kind = self.pos.kind(w.lid);
            if kind != Kind::Attach && !after_prefix && !reading.is_empty() {
                result.push(Segment {
                    reading: std::mem::take(&mut reading),
                    surface: std::mem::take(&mut surface),
                });
            }
            reading.extend_from_slice(&w.reading);
            surface.extend_from_slice(&w.surface);
            after_prefix = kind == Kind::Prefix;
        }
        if !reading.is_empty() {
            result.push(Segment { reading, surface });
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kanade_text::{lossy, u16s};

    fn w(r: &str, s: &str, id: u16) -> Word {
        Word {
            reading: u16s(r),
            surface: u16s(s),
            lid: id,
            rid: id,
            cost: 0,
        }
    }

    #[test]
    fn content_starts_attach_follows_prefix_joins() {
        // 0=内容語 1=付属語 2=接頭詞
        let seg = Segmenter::new(PosTable::from_kinds(vec![0, 1, 2]));
        let words = [
            w("きょう", "今日", 0),
            w("は", "は", 1),
            w("お", "お", 2),
            w("ちゃ", "茶", 0),
            w("を", "を", 1),
        ];
        let s: Vec<String> = seg
            .segments(&words)
            .iter()
            .map(|x| lossy(&x.surface))
            .collect();
        assert_eq!(s, vec!["今日は", "お茶を"]);
        assert!(seg.segments(&[]).is_empty());
    }
}
