//! 品詞 ID ごとの文節の区切り方と、予測に出してよい形か（Kotlin の `PosTable`）。
//! 形式: "KPOS" size:int kind:byte[size]（下の4ビットが [`Kind`]、0x10 が立っていれば予測に出さない）

use crate::bytes::{be_i32, FormatError};

pub const MAGIC: i32 = 0x4B50_4F53; // "KPOS"
const KIND_MASK: u8 = 0x0F;
const NOT_PREDICTABLE: u8 = 0x10;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// 新しい文節を始める（名詞・動詞自立など）
    Content,
    /// 前の文節に付く（助詞・助動詞・非自立・接尾・記号）
    Attach,
    /// 次の語と同じ文節になる（接頭詞）
    Prefix,
}

#[derive(Clone, Debug, Default)]
pub struct PosTable {
    kinds: Vec<u8>,
}

impl PosTable {
    pub fn from_kinds(kinds: Vec<u8>) -> PosTable {
        PosTable { kinds }
    }

    pub fn read(b: &[u8]) -> Result<PosTable, FormatError> {
        if b.len() < 8 || be_i32(b, 0) != MAGIC {
            return Err(FormatError("kpos ではない".into()));
        }
        let n = be_i32(b, 4) as usize;
        let kinds = b
            .get(8..8 + n)
            .ok_or_else(|| FormatError("kpos が途中で切れている".into()))?;
        Ok(PosTable {
            kinds: kinds.to_vec(),
        })
    }

    pub fn len(&self) -> usize {
        self.kinds.len()
    }

    pub fn is_empty(&self) -> bool {
        self.kinds.is_empty()
    }

    /// 表に無い ID は内容語（Kotlin の getOrElse 0）
    pub fn kind(&self, id: u16) -> Kind {
        match self.kinds.get(id as usize).copied().unwrap_or(0) & KIND_MASK {
            1 => Kind::Attach,
            2 => Kind::Prefix,
            _ => Kind::Content,
        }
    }

    /// この品詞で終わる語を予測の候補に出してよいか（活用の途中の形は出さない）
    pub fn predictable(&self, id: u16) -> bool {
        self.kinds.get(id as usize).copied().unwrap_or(0) & NOT_PREDICTABLE == 0
    }

    pub fn write(&self) -> Vec<u8> {
        let mut d = Vec::with_capacity(8 + self.kinds.len());
        d.extend_from_slice(&MAGIC.to_be_bytes());
        d.extend_from_slice(&(self.kinds.len() as i32).to_be_bytes());
        d.extend_from_slice(&self.kinds);
        d
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_and_predictable() {
        let t = PosTable::from_kinds(vec![0, 1, 2, 0x10 | 1]);
        let t = PosTable::read(&t.write()).unwrap();
        assert_eq!(t.kind(1), Kind::Attach);
        assert_eq!(t.kind(2), Kind::Prefix);
        assert_eq!(t.kind(3), Kind::Attach);
        assert_eq!(t.kind(99), Kind::Content);
        assert!(t.predictable(1) && !t.predictable(3) && t.predictable(99));
    }
}
