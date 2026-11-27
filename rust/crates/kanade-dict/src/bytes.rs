//! 辞書のバイト列の持ち方。ファイルは mmap で読み（Android の APK の非圧縮 asset と同じく、読み込みも展開もしない）、
//! 試験ではメモリ上の `Vec<u8>` を使う

use memmap2::Mmap;
use std::fs::File;
use std::io;
use std::ops::Deref;
use std::path::Path;
use std::sync::Arc;

/// 読み取り専用のバイト列（複製しても中身は共有する）
#[derive(Clone)]
pub struct Bytes(Arc<Storage>);

enum Storage {
    Mapped(Mmap),
    Owned(Vec<u8>),
}

impl Bytes {
    /// ファイルを mmap する
    pub fn map(path: impl AsRef<Path>) -> io::Result<Bytes> {
        let file = File::open(path.as_ref())?;
        // SAFETY: 辞書のファイルは読むだけで、Kanade の動いている間に書き換えない（書き換えると読み違える）
        let map = unsafe { Mmap::map(&file)? };
        Ok(Bytes(Arc::new(Storage::Mapped(map))))
    }

    pub fn from_vec(v: Vec<u8>) -> Bytes {
        Bytes(Arc::new(Storage::Owned(v)))
    }
}

impl Deref for Bytes {
    type Target = [u8];
    fn deref(&self) -> &[u8] {
        match &*self.0 {
            Storage::Mapped(m) => m,
            Storage::Owned(v) => v,
        }
    }
}

/// ビッグエンディアンの読み（Java の ByteBuffer の既定と同じ）
#[inline]
pub(crate) fn be_u16(b: &[u8], pos: usize) -> u16 {
    u16::from_be_bytes([b[pos], b[pos + 1]])
}

#[inline]
pub(crate) fn be_i16(b: &[u8], pos: usize) -> i16 {
    i16::from_be_bytes([b[pos], b[pos + 1]])
}

#[inline]
pub(crate) fn be_i32(b: &[u8], pos: usize) -> i32 {
    i32::from_be_bytes([b[pos], b[pos + 1], b[pos + 2], b[pos + 3]])
}

/// 形式の誤り
#[derive(Debug)]
pub struct FormatError(pub String);

impl std::fmt::Display for FormatError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for FormatError {}

impl From<FormatError> for io::Error {
    fn from(e: FormatError) -> io::Error {
        io::Error::new(io::ErrorKind::InvalidData, e)
    }
}
