//! かな漢字変換のラティス・ビタビ・N-best の A*・接頭辞で探し直す処理・予測の語（Kotlin の core/convert）。
//! 辞書だけの N-best は Kotlin 版と表記・順番・コストまで同じになるように写している（docs/RUST-PHASE1.md）。

pub mod converter;
pub mod java_pq;
pub mod segmenter;

pub use converter::{Candidate, ExtraWords, KanaKanjiConverter, Word};
pub use segmenter::{Segment, Segmenter};
