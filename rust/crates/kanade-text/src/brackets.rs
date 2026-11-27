//! 括弧の対（Kotlin の `Brackets`）

use crate::u16str::{u16s, U16String};

/// 替えていく順（全角を先に。半角は最後）。どれも UTF-16 で2単位
pub const PAIRS: [&str; 14] = [
    "（）", "「」", "『』", "【】", "［］", "｛｝", "〔〕", "〈〉", "《》", "“”", "‘’", "()", "[]",
    "{}",
];

/// 打ったときに閉じ括弧も入れる開き括弧の数（PAIRS の先頭から。半角の ( [ { は入れない）
const AUTO_CLOSE_COUNT: usize = 9;

fn pair(i: usize) -> (u16, u16) {
    let p = u16s(PAIRS[i]);
    (p[0], p[1])
}

/// 開き括弧1文字なら、自動で入れる閉じ括弧
pub fn closer_of(open: &[u16]) -> Option<U16String> {
    let [c] = open else { return None };
    (0..AUTO_CLOSE_COUNT)
        .map(pair)
        .find(|(o, _)| o == c)
        .map(|(_, close)| vec![close])
}

/// `text` が括弧1つの対（「（）」など）なら、その位置。でなければ -1
pub fn pair_index(text: &[u16]) -> i32 {
    PAIRS
        .iter()
        .position(|p| u16s(p) == text)
        .map_or(-1, |i| i as i32)
}

/// 括弧1文字の、ほかの括弧（開きなら開き、閉じなら閉じ）。括弧でなければ空
pub fn alternatives(text: &[u16]) -> Vec<U16String> {
    let [c] = text else { return Vec::new() };
    let pairs: Vec<(u16, u16)> = (0..PAIRS.len()).map(pair).collect();
    if pairs.iter().any(|(o, _)| o == c) {
        pairs.iter().map(|(o, _)| vec![*o]).collect()
    } else if pairs.iter().any(|(_, cl)| cl == c) {
        pairs.iter().map(|(_, cl)| vec![*cl]).collect()
    } else {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn brackets() {
        assert_eq!(closer_of(&u16s("「")), Some(u16s("」")));
        assert_eq!(closer_of(&u16s("(")), None);
        assert_eq!(pair_index(&u16s("『』")), 2);
        assert_eq!(pair_index(&u16s("『")), -1);
        assert_eq!(alternatives(&u16s("」")).len(), PAIRS.len());
        assert_eq!(alternatives(&u16s("」"))[0], u16s("）"));
        assert!(alternatives(&u16s("あ")).is_empty());
    }
}
