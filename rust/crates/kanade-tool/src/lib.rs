//! Kotlin 版の記録（testdata/nbest・testdata/romaji。dictool の RustCompare.kt が書く）と、Rust 版の結果を比べる。
//! 記録の各行を Rust で同じ形に書き直し、文字列として比べる（形は docs/RUST-PHASE1.md）。

use kanade_convert::{Candidate, KanaKanjiConverter, Segmenter};
use kanade_dict::{ConnectionMatrix, PosTable, ReverseDictionary, SymbolTable, SystemDictionary};
use kanade_text::u16str::{esc, split_escaped, unesc, U16String};
use kanade_text::{brackets, width, RomajiConverter, RomajiTable};
use sha2::{Digest, Sha256};
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

/// リポジトリの根（rust/crates/kanade-tool から3つ上）
pub fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .expect("リポジトリの根")
}

/// 辞書のフォルダ（環境変数 KANADE_DICT_DIR）。無ければ理由を返す
pub fn dict_dir() -> Result<PathBuf, String> {
    let d = std::env::var_os("KANADE_DICT_DIR").ok_or(
        "環境変数 KANADE_DICT_DIR が無い（scripts/rust-dict-prepare.sh で辞書を展開して指定する）",
    )?;
    let d = PathBuf::from(d);
    if !d.join("system.kdic").is_file() {
        return Err(format!("{} に system.kdic が無い", d.display()));
    }
    Ok(d)
}

/// 比べた結果（ファイル1つ、または種類1つ）
#[derive(Debug, Default)]
pub struct Report {
    pub name: String,
    pub total: usize,
    pub matched: usize,
    /// 種類ごとの (一致, 全部)。N-best なら C（上位 10 件）・P（接頭辞）・E（同じ読みの語）・W（予測）
    pub kinds: Vec<(String, usize, usize)>,
    /// 違いの例（最初のいくつか）
    pub diffs: Vec<String>,
}

impl Report {
    fn new(name: &str) -> Report {
        Report {
            name: name.to_string(),
            ..Default::default()
        }
    }

    fn kind(&mut self, k: &str, ok: bool) {
        let i = match self.kinds.iter().position(|x| x.0 == k) {
            Some(i) => i,
            None => {
                self.kinds.push((k.to_string(), 0, 0));
                self.kinds.len() - 1
            }
        };
        self.kinds[i].2 += 1;
        if ok {
            self.kinds[i].1 += 1;
        }
    }

    fn diff(&mut self, s: String) {
        if self.diffs.len() < 20 {
            self.diffs.push(s);
        }
    }

    pub fn ok(&self) -> bool {
        self.total > 0 && self.total == self.matched
    }

    pub fn summary(&self) -> String {
        let mut s = format!("{}: {}/{} 一致", self.name, self.matched, self.total);
        for (k, ok, all) in &self.kinds {
            let _ = write!(s, "  {k} {ok}/{all}");
        }
        s
    }
}

fn sha256_hex(path: &Path) -> std::io::Result<String> {
    let mut h = Sha256::new();
    h.update(fs::read(path)?);
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// 記録の頭の `# sha256 <ファイル> <値>` を、辞書のフォルダのファイルと比べる
fn check_hashes(text: &str, dict: &Path) -> Result<(), String> {
    for line in text.lines().filter(|l| l.starts_with("# sha256 ")) {
        let f: Vec<&str> = line.split(' ').collect();
        let (name, want) = (f[2], f[3]);
        let got = sha256_hex(&dict.join(name)).map_err(|e| format!("{name} を読めない: {e}"))?;
        if got != want {
            return Err(format!("辞書が記録のときと違う: {name} の sha256 が {got}（記録は {want}）。記録を作り直すか、同じ辞書を使う"));
        }
    }
    Ok(())
}

/// 辞書一式（N-best の比較に使うもの）
pub struct Dict {
    pub converter: KanaKanjiConverter,
    pub segmenter: Segmenter,
}

impl Dict {
    pub fn open(dir: &Path) -> std::io::Result<Dict> {
        let converter = KanaKanjiConverter::new(
            SystemDictionary::open(dir.join("system.kdic"))?,
            ConnectionMatrix::open(dir.join("connection.kcon"))?,
        );
        let segmenter = Segmenter::new(PosTable::read(&fs::read(dir.join("pos.kpos"))?)?);
        Ok(Dict {
            converter,
            segmenter,
        })
    }
}

fn words_field(c: &Candidate) -> String {
    c.words
        .iter()
        .map(|w| format!("{},{},{},{}", w.lid, w.rid, w.cost, esc(&w.surface)))
        .collect::<Vec<_>>()
        .join("|")
}

fn join_esc<'a>(xs: impl IntoIterator<Item = &'a U16String>) -> String {
    xs.into_iter().map(|x| esc(x)).collect::<Vec<_>>().join("|")
}

fn take(s: &[u16], n: usize) -> U16String {
    s[..n.min(s.len())].to_vec()
}

fn push_unique(v: &mut Vec<U16String>, x: U16String) {
    if !v.contains(&x) {
        v.push(x);
    }
}

/// 1つの読みについて、Kotlin の nbestDump と同じ行を作る（R の行を除く）
pub fn nbest_lines(d: &Dict, reading: &[u16]) -> (usize, Vec<String>) {
    let conv = &d.converter;
    let result = conv.convert(reading, 10);
    let mut lines = Vec::new();
    for c in &result {
        let segs = d.segmenter.segments(&c.words);
        lines.push(format!(
            "C\t{}\t{}\t{}\t{}",
            c.cost,
            esc(&c.text()),
            words_field(c),
            join_esc(segs.iter().map(|s| &s.surface))
        ));
    }
    let mut prefixes: Vec<U16String> = Vec::new();
    for c in result.iter().skip(1).take(2) {
        push_unique(&mut prefixes, take(&c.text(), 2));
    }
    if let Some(c) = result.get(1) {
        push_unique(&mut prefixes, c.text());
    }
    push_unique(&mut prefixes, kanade_text::u16s("犬犬犬"));
    for p in &prefixes {
        match conv.convert_with_prefix(reading, p) {
            None => lines.push(format!("P\t{}\t-\t\t", esc(p))),
            Some(c) => lines.push(format!(
                "P\t{}\t{}\t{}\t{}",
                esc(p),
                c.cost,
                esc(&c.text()),
                words_field(&c)
            )),
        }
    }
    lines.push(format!("E\t{}", join_esc(&conv.exact_words(reading))));
    let mut heads: Vec<U16String> = Vec::new();
    for p in [take(reading, 2), take(reading, 3), reading.to_vec()] {
        push_unique(&mut heads, p);
    }
    for p in &heads {
        let w = conv.predict_words(p, 10, |id| d.segmenter.predictable(id));
        let f = w
            .iter()
            .map(|(r, s)| format!("{}={}", esc(r), esc(s)))
            .collect::<Vec<_>>()
            .join("|");
        lines.push(format!("W\t{}\t{}", esc(p), f));
    }
    (result.len(), lines)
}

/// 記録の読み（R の行の2欄目）を全部
pub fn nbest_readings(text: &str) -> Vec<U16String> {
    text.lines()
        .filter(|l| l.starts_with("R\t"))
        .map(|l| unesc(l.split('\t').nth(1).unwrap_or("")).expect("読みの書き方"))
        .collect()
}

/// testdata/nbest の N-best の記録1つを比べる
pub fn check_nbest(d: &Dict, dict_dir: &Path, path: &Path) -> Result<Report, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    check_hashes(&text, dict_dir)?;
    let mut rep = Report::new(&path.file_name().unwrap().to_string_lossy());
    let mut lines = text.lines().filter(|l| !l.starts_with('#')).peekable();
    while let Some(r) = lines.next() {
        let f: Vec<&str> = r.split('\t').collect();
        if f[0] != "R" {
            return Err(format!("{}: R の行が来るはずの所に {r}", rep.name));
        }
        let reading = unesc(f[1]).ok_or("読みの書き方")?;
        let mut want: Vec<&str> = Vec::new();
        while let Some(l) = lines.peek() {
            if l.starts_with("R\t") {
                break;
            }
            want.push(lines.next().unwrap());
        }
        let (count, got) = nbest_lines(d, &reading);
        rep.total += 1;
        let mut all = count.to_string() == f[2];
        // 種類ごとに数える（C は上位 10 件をまとめて1つ）
        for k in ["C", "P", "E", "W"] {
            let w: Vec<&str> = want.iter().copied().filter(|l| l.starts_with(k)).collect();
            let g: Vec<&str> = got
                .iter()
                .map(|s| s.as_str())
                .filter(|l| l.starts_with(k))
                .collect();
            if k == "C" {
                let ok = w == g && count.to_string() == f[2];
                rep.kind("C(上位10件)", ok);
                if !ok {
                    all = false;
                    rep.diff(format!(
                        "{} N-best: 記録 {} 件 / Rust {count} 件\n  記録: {}\n  Rust: {}",
                        esc(&reading),
                        f[2],
                        w.join("\n        "),
                        g.join("\n        ")
                    ));
                }
            } else {
                for (i, wl) in w.iter().enumerate() {
                    let ok = g.get(i) == Some(wl);
                    rep.kind(k, ok);
                    if !ok {
                        all = false;
                        rep.diff(format!(
                            "{} {k}: 記録 {wl}\n  Rust {}",
                            esc(&reading),
                            g.get(i).copied().unwrap_or("（無し）")
                        ));
                    }
                }
                if g.len() != w.len() {
                    all = false;
                    rep.diff(format!(
                        "{} {k}: 行の数が違う（記録 {} / Rust {}）",
                        esc(&reading),
                        w.len(),
                        g.len()
                    ));
                }
            }
        }
        if all {
            rep.matched += 1;
        }
    }
    Ok(rep)
}

/// 逆引きの記録（reverse-*.tsv）を比べる
pub fn check_reverse(
    rev: &ReverseDictionary,
    dict_dir: &Path,
    path: &Path,
) -> Result<Report, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    check_hashes(&text, dict_dir)?;
    let mut rep = Report::new(&path.file_name().unwrap().to_string_lossy());
    for line in text.lines().filter(|l| l.starts_with("V\t")) {
        let f: Vec<&str> = line.splitn(3, '\t').collect();
        let t = unesc(f[1]).ok_or("文の書き方")?;
        let got = match rev.pieces(&t) {
            None => "-".to_string(),
            Some(p) => p
                .iter()
                .map(|x| format!("{}={}", esc(&x.surface), esc(&x.reading)))
                .collect::<Vec<_>>()
                .join("|"),
        };
        rep.total += 1;
        if got == f[2] {
            rep.matched += 1;
        } else {
            rep.diff(format!("{}: 記録 {} / Rust {got}", f[1], f[2]));
        }
    }
    Ok(rep)
}

/// Kotlin の `lineSequence()` のように \r\n・\n・\r で分ける
fn kotlin_lines(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut rest = text;
    loop {
        match rest.find(['\n', '\r']) {
            Some(i) => {
                out.push(&rest[..i]);
                let skip = if rest[i..].starts_with("\r\n") { 2 } else { 1 };
                rest = &rest[i + skip..];
            }
            None => {
                out.push(rest);
                return out;
            }
        }
    }
}

/// 記号・絵文字・英語の表と品詞の表（tables.tsv）を比べる
pub fn check_tables(dict_dir: &Path, path: &Path) -> Result<Report, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut rep = Report::new("tables.tsv");
    for line in text.lines().filter(|l| !l.starts_with('#')) {
        let f: Vec<&str> = line.split('\t').collect();
        let got = match f[0] {
            "S" => {
                let src = fs::read_to_string(dict_dir.join(f[1]))
                    .map_err(|e| format!("{}: {e}", f[1]))?;
                let t = SymbolTable::parse(&src);
                let mut keys: Vec<U16String> = kotlin_lines(&src)
                    .into_iter()
                    .filter(|l| !l.is_empty())
                    .map(|l| kanade_text::u16s(l.split('\t').next().unwrap()))
                    .collect();
                keys.sort();
                keys.dedup();
                let mut h = Sha256::new();
                for k in &keys {
                    let vals = t
                        .lookup(k)
                        .iter()
                        .map(|v| esc(v))
                        .collect::<Vec<_>>()
                        .join("\t");
                    h.update(format!("{}\t{}\n", esc(k), vals).as_bytes());
                }
                let hex: String = h.finalize().iter().map(|b| format!("{b:02x}")).collect();
                format!("S\t{}\t{}\t{hex}", f[1], t.len())
            }
            "K" => {
                let bytes = fs::read(dict_dir.join(f[1])).map_err(|e| format!("{}: {e}", f[1]))?;
                let pos = PosTable::read(&bytes).map_err(|e| e.to_string())?;
                let n = bytes.len() - 8;
                let mut s = format!("K\t{}\t{n}\t", f[1]);
                for id in 0..n + 2 {
                    let k = match pos.kind(id as u16) {
                        kanade_dict::Kind::Content => 0,
                        kanade_dict::Kind::Attach => 1,
                        kanade_dict::Kind::Prefix => 2,
                    };
                    let _ = write!(
                        s,
                        "{k}{}",
                        if pos.predictable(id as u16) { "p" } else { "x" }
                    );
                }
                s
            }
            _ => return Err(format!("tables.tsv の知らない行: {line}")),
        };
        rep.total += 1;
        if got == line {
            rep.matched += 1;
        } else {
            rep.diff(format!("記録 {line}\n  Rust {got}"));
        }
    }
    Ok(rep)
}

fn units_field(units: &[kanade_text::KanaUnit]) -> String {
    units
        .iter()
        .map(|u| format!("{}={}", esc(&u.text), esc(&u.raw)))
        .collect::<Vec<_>>()
        .join("|")
}

/// ローマ字の1つの入力について、Kotlin の romajiDump と同じ行を作る
pub fn romaji_line(input: &[u16]) -> String {
    let mut conv = RomajiConverter::new();
    let mut units = Vec::new();
    let mut pendings = Vec::new();
    for &c in input {
        units.extend(conv.feed(c));
        pendings.push(esc(conv.pending()));
    }
    let flushed = conv.flush();
    format!(
        "I\t{}\t{}\t{}\t{}",
        esc(input),
        units_field(&units),
        pendings.join("|"),
        units_field(&flushed)
    )
}

/// testdata/romaji の記録（入力ごとの行・表・幅）を比べる
pub fn check_romaji(path: &Path) -> Result<Report, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let name = path.file_name().unwrap().to_string_lossy().to_string();
    let mut rep = Report::new(&name);
    let want: Vec<&str> = text.lines().filter(|l| !l.starts_with('#')).collect();
    let got: Vec<String> = if text.starts_with("# kanade romaji-table") {
        romaji_table_lines()
    } else {
        want.iter()
            .map(|l| {
                let f: Vec<&str> = l.split('\t').collect();
                let input = unesc(f[1]).expect("入力の書き方");
                match f[0] {
                    "I" => romaji_line(&input),
                    "W" => width_line(&input),
                    _ => format!("知らない行 {}", f[0]),
                }
            })
            .collect()
    };
    for (i, w) in want.iter().enumerate() {
        rep.total += 1;
        let ok = got.get(i).map(|s| s.as_str()) == Some(*w);
        rep.kind(w.split('\t').next().unwrap_or(""), ok);
        if ok {
            rep.matched += 1;
        } else {
            rep.diff(format!(
                "記録 {w}\n  Rust {}",
                got.get(i).map(|s| s.as_str()).unwrap_or("（無し）")
            ));
        }
    }
    if got.len() != want.len() {
        rep.diff(format!(
            "行の数が違う（記録 {} / Rust {}）",
            want.len(),
            got.len()
        ));
        rep.total = rep.total.max(got.len());
    }
    Ok(rep)
}

/// ローマ字の表・接頭辞・逆引き（Kotlin の romajiTableDump と同じ並び）
pub fn romaji_table_lines() -> Vec<String> {
    let t = RomajiTable::get();
    let mut out = Vec::new();
    let mut e: Vec<&(U16String, U16String)> = t.entries().iter().collect();
    e.sort();
    for (k, v) in e {
        out.push(format!("E\t{}\t{}", esc(k), esc(v)));
    }
    let mut x: Vec<&U16String> = t.prefixes().iter().collect();
    x.sort();
    for p in x {
        out.push(format!("X\t{}", esc(p)));
    }
    let mut v: Vec<(&U16String, &U16String)> = t.reverse().iter().collect();
    v.sort();
    for (k, r) in v {
        out.push(format!("V\t{}\t{}", esc(k), esc(r)));
    }
    out
}

/// CharWidth と Brackets の結果（Kotlin の widthDump と同じ行）
pub fn width_line(s: &[u16]) -> String {
    let flags: String = s
        .iter()
        .map(|&c| if width::is_ideographic(c) { '1' } else { '0' })
        .collect();
    let fields = [
        esc(&width::to_katakana(s)),
        esc(&width::to_hiragana(s)),
        esc(&width::to_full_width_ascii(s)),
        esc(&width::to_half_width_ascii(s)),
        esc(&width::to_half_width_katakana(s)),
        width::has_full_width_ascii(s).to_string(),
        width::has_half_width_ascii(s).to_string(),
        flags,
        brackets::closer_of(s).map_or("-".to_string(), |c| esc(&c)),
        brackets::pair_index(s).to_string(),
        join_esc(&brackets::alternatives(s)),
    ];
    format!("W\t{}\t{}", esc(s), fields.join("\t"))
}

/// 辞書だけの変換（10 件）の時間。温めてから、全部の読みを `rounds` 回ずつ。中央値と 95% をミリ秒で
pub fn bench(d: &Dict, readings: &[U16String], rounds: usize) -> (f64, f64) {
    for _ in 0..3 {
        for r in readings {
            std::hint::black_box(d.converter.convert(r, 10));
        }
    }
    let mut times = Vec::with_capacity(readings.len() * rounds);
    for _ in 0..rounds {
        for r in readings {
            let t = Instant::now();
            std::hint::black_box(d.converter.convert(r, 10));
            times.push(t.elapsed().as_secs_f64() * 1000.0);
        }
    }
    times.sort_by(|a, b| a.partial_cmp(b).unwrap());
    (times[times.len() / 2], times[times.len() * 95 / 100])
}

/// testdata/nbest の N-best の記録のファイル（読みの一覧・逆引き・表を除く）
pub fn nbest_files(root: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(root.join("testdata/nbest"))
        .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    v.retain(|p| {
        let n = p.file_name().unwrap().to_string_lossy();
        n.ends_with(".tsv") && !n.starts_with("reverse-") && n != "tables.tsv"
    });
    v.sort();
    v
}

pub fn reverse_files(root: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(root.join("testdata/nbest"))
        .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    v.retain(|p| {
        p.file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("reverse-")
    });
    v.sort();
    v
}

pub fn romaji_files(root: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(root.join("testdata/romaji"))
        .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    v.retain(|p| p.extension().is_some_and(|e| e == "tsv"));
    v.sort();
    v
}

/// 記録の読みの数（辞書が無いときに「流せなかった数」として出す）
pub fn count_records(files: &[PathBuf]) -> usize {
    files
        .iter()
        .map(|p| {
            fs::read_to_string(p)
                .map(|t| {
                    t.lines()
                        .filter(|l| {
                            l.starts_with("R\t")
                                || l.starts_with("V\t")
                                || l.starts_with("S\t")
                                || l.starts_with("K\t")
                        })
                        .count()
                })
                .unwrap_or(0)
        })
        .sum()
}

/// `split_escaped` を外から使うため（記録の欄を分けたいとき）
pub fn split_field(s: &str) -> Vec<&str> {
    split_escaped(s, '|')
}
