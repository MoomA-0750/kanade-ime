//! ローマ字・かな・全角半角・括弧（Kanade のコアの文字の扱い）。
//! 文字列は Kotlin の `String` と同じ UTF-16 の単位（[`u16str::U16String`]）で扱う。

pub mod brackets;
pub mod romaji;
pub mod u16str;
pub mod width;

pub use romaji::{KanaUnit, RomajiConverter, RomajiTable};
pub use u16str::{esc, lossy, u16s, unesc, U16String};
