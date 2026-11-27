//! 読み→単語の辞書 kdic（Kotlin の `SystemDictionary`・`DictionaryBuilder`）。
//!
//! 形式（ビッグエンディアン）:
//! ```text
//! "KDIC" version:int readingCount:int entryCount:int readingChars:int surfaceChars:int
//! readingOffsets:int[N+1]  entryStarts:int[N+1]  readingChars:char[]
//! entries: (lid:short rid:short cost:short surfaceLen:short surfaceOffset:int)[E]
//! surfaceChars:char[]
//! ```
//! 読みは Java の `String` の辞書順（UTF-16 の単位の大小）に並ぶ。`Vec<u16>` の `Ord` は同じ順。
//! surfaceLen が [`SURFACE_SAME`] なら表記＝読み、[`SURFACE_KATAKANA`] なら読みのカタカナ。

use crate::bytes::{be_i16, be_i32, be_u16, Bytes, FormatError};
use kanade_text::u16str::U16String;
use std::collections::{BTreeMap, HashMap};
use std::ops::Range;

pub const MAGIC: i32 = 0x4B44_4943; // "KDIC"
pub const VERSION: i32 = 1;
const HEADER_SIZE: usize = 24;
const ENTRY_SIZE: usize = 12;
pub const SURFACE_SAME: u16 = 0xFFFF;
pub const SURFACE_KATAKANA: u16 = 0xFFFE;

/// kdic を mmap（またはメモリ）のまま読む
#[derive(Clone)]
pub struct SystemDictionary {
    buf: Bytes,
    reading_count: usize,
    entry_count: usize,
    reading_offsets_pos: usize,
    entry_starts_pos: usize,
    reading_chars_pos: usize,
    entries_pos: usize,
    surface_chars_pos: usize,
}

impl SystemDictionary {
    pub fn new(buf: Bytes) -> Result<SystemDictionary, FormatError> {
        if buf.len() < HEADER_SIZE || be_i32(&buf, 0) != MAGIC {
            return Err(FormatError("kdic ではない".into()));
        }
        let version = be_i32(&buf, 4);
        if version != VERSION {
            return Err(FormatError(format!("kdic の版が違う: {version}")));
        }
        let reading_count = be_i32(&buf, 8) as usize;
        let entry_count = be_i32(&buf, 12) as usize;
        let reading_chars = be_i32(&buf, 16) as usize;
        let surface_chars = be_i32(&buf, 20) as usize;
        let reading_offsets_pos = HEADER_SIZE;
        let entry_starts_pos = reading_offsets_pos + 4 * (reading_count + 1);
        let reading_chars_pos = entry_starts_pos + 4 * (reading_count + 1);
        let entries_pos = reading_chars_pos + 2 * reading_chars;
        let surface_chars_pos = entries_pos + ENTRY_SIZE * entry_count;
        if surface_chars_pos + 2 * surface_chars > buf.len() {
            return Err(FormatError("kdic が途中で切れている".into()));
        }
        Ok(SystemDictionary {
            buf,
            reading_count,
            entry_count,
            reading_offsets_pos,
            entry_starts_pos,
            reading_chars_pos,
            entries_pos,
            surface_chars_pos,
        })
    }

    pub fn open(path: impl AsRef<std::path::Path>) -> std::io::Result<SystemDictionary> {
        Ok(SystemDictionary::new(Bytes::map(path)?)?)
    }

    pub fn reading_count(&self) -> usize {
        self.reading_count
    }

    pub fn entry_count(&self) -> usize {
        self.entry_count
    }

    #[inline]
    fn reading_start(&self, i: usize) -> usize {
        be_i32(&self.buf, self.reading_offsets_pos + 4 * i) as usize
    }

    #[inline]
    fn reading_length(&self, i: usize) -> usize {
        be_i32(&self.buf, self.reading_offsets_pos + 4 * (i + 1)) as usize - self.reading_start(i)
    }

    pub fn reading_length_of(&self, index: usize) -> usize {
        self.reading_length(index)
    }

    pub fn reading(&self, index: usize) -> U16String {
        let from = self.reading_start(index);
        (0..self.reading_length(index))
            .map(|k| be_u16(&self.buf, self.reading_chars_pos + 2 * (from + k)))
            .collect()
    }

    pub fn entries_of(&self, reading_index: usize) -> Range<usize> {
        let a = be_i32(&self.buf, self.entry_starts_pos + 4 * reading_index) as usize;
        let b = be_i32(&self.buf, self.entry_starts_pos + 4 * (reading_index + 1)) as usize;
        a..b
    }

    #[inline]
    pub fn lid(&self, entry: usize) -> u16 {
        be_u16(&self.buf, self.entries_pos + ENTRY_SIZE * entry)
    }

    #[inline]
    pub fn rid(&self, entry: usize) -> u16 {
        be_u16(&self.buf, self.entries_pos + ENTRY_SIZE * entry + 2)
    }

    #[inline]
    pub fn cost(&self, entry: usize) -> i32 {
        be_i16(&self.buf, self.entries_pos + ENTRY_SIZE * entry + 4) as i32
    }

    /// 表記を `out` の後ろに足す（読みを知っている所から呼ぶので、読みを渡す）
    pub fn surface_into(&self, entry: usize, reading: &[u16], out: &mut U16String) {
        let len = be_u16(&self.buf, self.entries_pos + ENTRY_SIZE * entry + 6);
        match len {
            SURFACE_SAME => out.extend_from_slice(reading),
            SURFACE_KATAKANA => out.extend(reading.iter().map(|&c| {
                if (0x3041..=0x3096).contains(&c) {
                    c + 0x60
                } else {
                    c
                }
            })),
            _ => {
                let off = be_i32(&self.buf, self.entries_pos + ENTRY_SIZE * entry + 8) as usize;
                let base = self.surface_chars_pos + 2 * off;
                out.extend((0..len as usize).map(|k| be_u16(&self.buf, base + 2 * k)));
            }
        }
    }

    pub fn surface(&self, entry: usize, reading: &[u16]) -> U16String {
        let mut out = Vec::new();
        self.surface_into(entry, reading, &mut out);
        out
    }

    /// 表記が読みと同じ・読みのカタカナ・それ以外のどれか（表記を作らずに比べたいとき）
    pub fn surface_kind(&self, entry: usize) -> u16 {
        be_u16(&self.buf, self.entries_pos + ENTRY_SIZE * entry + 6)
    }

    /// 読み i の depth 文字目。短くて無ければ -1（短い読みが先に並ぶ）
    #[inline]
    fn char_at(&self, i: usize, depth: usize) -> i32 {
        let start = self.reading_start(i);
        let len = be_i32(&self.buf, self.reading_offsets_pos + 4 * (i + 1)) as usize - start;
        if depth < len {
            be_u16(&self.buf, self.reading_chars_pos + 2 * (start + depth)) as i32
        } else {
            -1
        }
    }

    fn lower_bound(&self, from: usize, to: usize, depth: usize, c: u16) -> usize {
        let (mut l, mut h) = (from, to);
        while l < h {
            let m = (l + h) >> 1;
            if self.char_at(m, depth) < c as i32 {
                l = m + 1
            } else {
                h = m
            }
        }
        l
    }

    fn upper_bound(&self, from: usize, to: usize, depth: usize, c: u16) -> usize {
        let (mut l, mut h) = (from, to);
        while l < h {
            let m = (l + h) >> 1;
            if self.char_at(m, depth) <= c as i32 {
                l = m + 1
            } else {
                h = m
            }
        }
        l
    }

    /// `text` の `start` から始まる読みのうち、辞書にあるものをすべて（短い順に）`out` に入れる。
    /// 組は (読みの長さ, 読みの添字)。Kotlin の `prefixSearch` と同じ
    pub fn prefix_search_into(
        &self,
        text: &[u16],
        start: usize,
        max_length: usize,
        out: &mut Vec<(usize, usize)>,
    ) {
        out.clear();
        let (mut lo, mut hi) = (0usize, self.reading_count);
        let end = text.len().min(start + max_length);
        let mut depth = 0;
        while start + depth < end && lo < hi {
            let c = text[start + depth];
            lo = self.lower_bound(lo, hi, depth, c);
            hi = self.upper_bound(lo, hi, depth, c);
            depth += 1;
            if lo < hi && self.reading_length(lo) == depth {
                out.push((depth, lo));
            }
        }
    }

    pub fn prefix_search(
        &self,
        text: &[u16],
        start: usize,
        max_length: usize,
    ) -> Vec<(usize, usize)> {
        let mut out = Vec::with_capacity(8);
        self.prefix_search_into(text, start, max_length, &mut out);
        out
    }

    /// `prefix` で始まる読みの添字の範囲（読みは辞書順なので連続する）
    pub fn prefix_range(&self, prefix: &[u16]) -> Range<usize> {
        let (mut lo, mut hi) = (0usize, self.reading_count);
        for (depth, &c) in prefix.iter().enumerate() {
            if lo >= hi {
                break;
            }
            lo = self.lower_bound(lo, hi, depth, c);
            hi = self.upper_bound(lo, hi, depth, c);
        }
        lo..hi.max(lo)
    }
}

/// 辞書の1語（Mozc の dictionary*.txt の1行: 読み 左ID 右ID コスト 表記）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WordEntry {
    pub reading: U16String,
    pub lid: i32,
    pub rid: i32,
    pub cost: i32,
    pub surface: U16String,
}

impl WordEntry {
    pub fn new(reading: &str, lid: i32, rid: i32, cost: i32, surface: &str) -> WordEntry {
        WordEntry {
            reading: kanade_text::u16s(reading),
            lid,
            rid,
            cost,
            surface: kanade_text::u16s(surface),
        }
    }
}

/// `WordEntry` の集まりから kdic を書く（Kotlin の `DictionaryBuilder.write` と同じバイト列）
pub fn write_kdic(words: &[WordEntry]) -> Vec<u8> {
    // 読みごとにまとめ（読みは UTF-16 の辞書順）、同じ読みの中はコストの小さい順（安定）
    let mut by_reading: BTreeMap<&[u16], Vec<&WordEntry>> = BTreeMap::new();
    for w in words {
        by_reading.entry(&w.reading).or_default().push(w);
    }
    for list in by_reading.values_mut() {
        list.sort_by_key(|w| w.cost);
    }
    let mut surface_chars: Vec<u16> = Vec::new();
    let mut surface_index: HashMap<&[u16], usize> = HashMap::new();
    let mut refs: Vec<Vec<(u16, usize)>> = Vec::new();
    for list in by_reading.values() {
        let mut r = Vec::new();
        for w in list {
            let kata: U16String = kanade_text::width::to_katakana(&w.reading);
            let rf = if w.surface == w.reading {
                (SURFACE_SAME, 0)
            } else if w.surface == kata {
                (SURFACE_KATAKANA, 0)
            } else {
                assert!(
                    w.surface.len() < SURFACE_KATAKANA as usize,
                    "表記が長すぎる"
                );
                let off = *surface_index.entry(&w.surface).or_insert_with(|| {
                    let o = surface_chars.len();
                    surface_chars.extend_from_slice(&w.surface);
                    o
                });
                (w.surface.len() as u16, off)
            };
            r.push(rf);
        }
        refs.push(r);
    }
    let mut d = Vec::new();
    let put_i32 = |d: &mut Vec<u8>, v: i32| d.extend_from_slice(&v.to_be_bytes());
    let put_u16 = |d: &mut Vec<u8>, v: u16| d.extend_from_slice(&v.to_be_bytes());
    put_i32(&mut d, MAGIC);
    put_i32(&mut d, VERSION);
    put_i32(&mut d, by_reading.len() as i32);
    put_i32(&mut d, words.len() as i32);
    put_i32(
        &mut d,
        by_reading.keys().map(|r| r.len()).sum::<usize>() as i32,
    );
    put_i32(&mut d, surface_chars.len() as i32);
    let mut off = 0;
    for r in by_reading.keys() {
        put_i32(&mut d, off);
        off += r.len() as i32;
    }
    put_i32(&mut d, off);
    let mut e = 0;
    for list in by_reading.values() {
        put_i32(&mut d, e);
        e += list.len() as i32;
    }
    put_i32(&mut d, e);
    for r in by_reading.keys() {
        for &c in r.iter() {
            put_u16(&mut d, c);
        }
    }
    for (list, r) in by_reading.values().zip(&refs) {
        for (w, &(len, s_off)) in list.iter().zip(r) {
            // DataOutputStream.writeShort は下の16ビットを書く
            put_u16(&mut d, w.lid as u16);
            put_u16(&mut d, w.rid as u16);
            put_u16(&mut d, w.cost.min(i16::MAX as i32) as u16);
            put_u16(&mut d, len);
            put_i32(&mut d, s_off as i32);
        }
    }
    for c in surface_chars {
        put_u16(&mut d, c);
    }
    d
}

#[cfg(test)]
mod tests {
    use super::*;
    use kanade_text::{lossy, u16s};

    fn dict(words: &[WordEntry]) -> SystemDictionary {
        SystemDictionary::new(Bytes::from_vec(write_kdic(words))).unwrap()
    }

    fn sample() -> Vec<WordEntry> {
        vec![
            WordEntry::new("はし", 1, 1, 3000, "橋"),
            WordEntry::new("はし", 1, 1, 3200, "箸"),
            WordEntry::new("は", 2, 2, 1000, "は"),
            WordEntry::new("てすと", 1, 1, 3000, "てすと"),
            WordEntry::new("てすと", 1, 1, 2000, "テスト"),
            WordEntry::new("てすとけーす", 1, 1, 3000, "テストケース"),
        ]
    }

    #[test]
    fn prefix_search_finds_all_lengths() {
        let d = dict(&sample());
        let hits: Vec<(usize, String)> = d
            .prefix_search(&u16s("はしを"), 0, 64)
            .into_iter()
            .map(|(l, i)| (l, lossy(&d.reading(i))))
            .collect();
        assert_eq!(hits, vec![(1, "は".to_string()), (2, "はし".to_string())]);
        assert!(d.prefix_search(&u16s("ぬ"), 0, 64).is_empty());
    }

    #[test]
    fn surfaces_are_stored_compactly_in_cost_order() {
        let d = dict(&sample());
        let (_, idx) = d.prefix_search(&u16s("てすと"), 0, 64)[0];
        let s: Vec<String> = d
            .entries_of(idx)
            .map(|e| lossy(&d.surface(e, &u16s("てすと"))))
            .collect();
        assert_eq!(s, vec!["テスト", "てすと"]);
        assert_eq!(d.prefix_range(&u16s("てす")).len(), 2);
        assert_eq!(d.prefix_range(&u16s("ぬ")).len(), 0);
    }

    #[test]
    fn readings_sort_by_utf16_units_not_code_points() {
        // U+FF61（半角の句点）は UTF-16 で 0xFF61、😀 は 0xD83D 0xDE00。コードポイント順なら 😀 が後、UTF-16 順なら前
        let words = vec![
            WordEntry::new("｡", 1, 1, 1, "x"),
            WordEntry::new("😀", 1, 1, 1, "y"),
        ];
        let d = dict(&words);
        assert_eq!(lossy(&d.reading(0)), "😀");
        assert_eq!(lossy(&d.reading(1)), "｡");
        assert_eq!(d.prefix_search(&u16s("😀"), 0, 64), vec![(2, 0)]);
    }
}
