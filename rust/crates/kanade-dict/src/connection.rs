//! 品詞の接続コスト kcon（Kotlin の `ConnectionMatrix`）。
//! 形式（ビッグエンディアン）: "KCON" size:int cost:short[size*size]（添字は rid*size+lid、Mozc と同じ並び）

use crate::bytes::{be_i16, be_i32, Bytes, FormatError};

pub const MAGIC: i32 = 0x4B43_4F4E; // "KCON"
const HEADER_SIZE: usize = 8;

#[derive(Clone)]
pub struct ConnectionMatrix {
    buf: Bytes,
    size: usize,
}

impl ConnectionMatrix {
    pub fn new(buf: Bytes) -> Result<ConnectionMatrix, FormatError> {
        if buf.len() < HEADER_SIZE || be_i32(&buf, 0) != MAGIC {
            return Err(FormatError("kcon ではない".into()));
        }
        let size = be_i32(&buf, 4) as usize;
        if HEADER_SIZE + 2 * size * size > buf.len() {
            return Err(FormatError("kcon が途中で切れている".into()));
        }
        Ok(ConnectionMatrix { buf, size })
    }

    pub fn open(path: impl AsRef<std::path::Path>) -> std::io::Result<ConnectionMatrix> {
        Ok(ConnectionMatrix::new(Bytes::map(path)?)?)
    }

    pub fn size(&self) -> usize {
        self.size
    }

    /// cost(前の語の右ID, 次の語の左ID)。ID 0 は文頭・文末
    #[inline]
    pub fn cost(&self, rid: u16, lid: u16) -> i32 {
        be_i16(
            &self.buf,
            HEADER_SIZE + 2 * (rid as usize * self.size + lid as usize),
        ) as i32
    }

    /// Mozc の connection_single_column.txt（先頭行が size、以降 size*size 行のコスト）から書く
    pub fn write_from_mozc<'a>(lines: impl IntoIterator<Item = &'a str>) -> Vec<u8> {
        let mut it = lines
            .into_iter()
            .filter(|l| !l.is_empty() && !l.starts_with('#'));
        let size: i32 = it.next().expect("size の行").trim().parse().expect("size");
        let mut d = Vec::new();
        d.extend_from_slice(&MAGIC.to_be_bytes());
        d.extend_from_slice(&size.to_be_bytes());
        let mut n = 0i64;
        for l in it {
            let mut c: i32 = l.trim().parse().expect("コスト");
            if n == 0 {
                c = 0; // BOS→EOS（Mozc も 0 にしている）
            }
            n += 1;
            d.extend_from_slice(&(c.min(i16::MAX as i32) as u16).to_be_bytes());
        }
        assert_eq!(n, size as i64 * size as i64, "接続行列の要素数が合わない");
        d
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_rid_major() {
        let costs = ["3", "9", "0", "0", "0", "3000", "100", "0", "-5", "40000"];
        let m = ConnectionMatrix::new(Bytes::from_vec(ConnectionMatrix::write_from_mozc(costs)))
            .unwrap();
        assert_eq!(m.size(), 3);
        assert_eq!(m.cost(0, 0), 0); // 先頭は 0 にする
        assert_eq!(m.cost(1, 1), 3000);
        assert_eq!(m.cost(1, 2), 100);
        assert_eq!(m.cost(2, 1), -5);
        assert_eq!(m.cost(2, 2), i16::MAX as i32);
    }
}
