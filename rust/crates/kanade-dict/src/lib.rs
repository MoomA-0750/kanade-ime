//! Kanade の辞書の読み書き。形式は Kotlin の dictool が作るもの（kdic・kcon・ksym・kpos・reverse.kdic）をそのまま読む。
//! 大きいファイル（kdic・kcon）は mmap で読み、起動のときに展開しない。

pub mod bytes;
pub mod connection;
pub mod pos;
pub mod reverse;
pub mod symbol;
pub mod system;

pub use bytes::{Bytes, FormatError};
pub use connection::ConnectionMatrix;
pub use pos::{Kind, PosTable};
pub use reverse::{Piece, ReverseDictionary};
pub use symbol::SymbolTable;
pub use system::{write_kdic, SystemDictionary, WordEntry};
