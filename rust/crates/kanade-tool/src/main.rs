//! kanade-tool: 辞書を読んで変換し、Kotlin 版の記録と比べる（docs/RUST-PHASE1.md）
//!
//! ```text
//! kanade-tool check                 N-best・逆引き・表・ローマ字を全部比べる（辞書は KANADE_DICT_DIR）
//! kanade-tool nbest-check [ファイル…] testdata/nbest の N-best を比べる
//! kanade-tool romaji-check          testdata/romaji を比べる（辞書は要らない）
//! kanade-tool convert <かな…>        辞書だけの N-best を出す
//! kanade-tool bench                 評価セットの読みで辞書だけの変換の時間を測る
//! ```

use kanade_dict::{ReverseDictionary, SystemDictionary};
use kanade_tool::*;
use std::path::PathBuf;
use std::process::ExitCode;

fn print(rep: &Report) -> bool {
    println!("{}", rep.summary());
    for d in &rep.diffs {
        println!("  違い: {d}");
    }
    rep.ok()
}

fn need_dict(files: &[PathBuf]) -> Result<PathBuf, String> {
    dict_dir().map_err(|e| {
        format!(
            "{e}。辞書が無いので {} 件の記録を流せない（失敗にする）",
            count_records(files)
        )
    })
}

fn nbest(files: Vec<PathBuf>) -> Result<bool, String> {
    let dir = need_dict(&files)?;
    let d = Dict::open(&dir).map_err(|e| e.to_string())?;
    let mut ok = true;
    let (mut total, mut matched) = (0, 0);
    for f in &files {
        let rep = check_nbest(&d, &dir, f)?;
        total += rep.total;
        matched += rep.matched;
        ok &= print(&rep);
    }
    println!("N-best の計: {matched}/{total} 読みが一致");
    Ok(ok && total > 0)
}

fn others(root: &std::path::Path) -> Result<bool, String> {
    let files = reverse_files(root);
    let dir = need_dict(&files)?;
    let rev = ReverseDictionary::new(
        SystemDictionary::open(dir.join("reverse.kdic")).map_err(|e| e.to_string())?,
    );
    let mut ok = true;
    for f in &files {
        ok &= print(&check_reverse(&rev, &dir, f)?);
    }
    ok &= print(&check_tables(
        &dir,
        &root.join("testdata/nbest/tables.tsv"),
    )?);
    Ok(ok)
}

fn romaji(root: &std::path::Path) -> Result<bool, String> {
    let mut ok = true;
    let files = romaji_files(root);
    for f in &files {
        ok &= print(&check_romaji(f)?);
    }
    Ok(ok && !files.is_empty())
}

fn run() -> Result<bool, String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let root = repo_root();
    match args.first().map(|s| s.as_str()) {
        Some("check") => {
            let a = nbest(nbest_files(&root))?;
            let b = others(&root)?;
            let c = romaji(&root)?;
            Ok(a && b && c)
        }
        Some("nbest-check") => {
            let files = if args.len() > 1 { args[1..].iter().map(PathBuf::from).collect() } else { nbest_files(&root) };
            nbest(files)
        }
        Some("romaji-check") => romaji(&root),
        Some("convert") => {
            let d = Dict::open(&dict_dir()?).map_err(|e| e.to_string())?;
            for input in &args[1..] {
                let r = kanade_text::u16s(input);
                let t = std::time::Instant::now();
                let res = d.converter.convert(&r, 10);
                println!("{input} ({:.2}ms)", t.elapsed().as_secs_f64() * 1000.0);
                for c in res {
                    let segs: Vec<String> = d.segmenter.segments(&c.words).iter().map(|s| kanade_text::lossy(&s.surface)).collect();
                    let words: Vec<String> = c.words.iter().map(|w| kanade_text::lossy(&w.surface)).collect();
                    println!("  {}\t{}\t{}\t文節: {}", c.cost, kanade_text::lossy(&c.text()), words.join("|"), segs.join(" / "));
                }
            }
            Ok(true)
        }
        Some("bench") => {
            let d = Dict::open(&dict_dir()?).map_err(|e| e.to_string())?;
            for f in nbest_files(&root) {
                let text = std::fs::read_to_string(&f).map_err(|e| e.to_string())?;
                let readings = nbest_readings(&text);
                let (med, p95) = bench(&d, &readings, 5);
                println!("{}: {} 読み。辞書だけの変換（10 件）の中央値 {med:.3}ms、95% {p95:.3}ms", f.file_name().unwrap().to_string_lossy(), readings.len());
            }
            Ok(true)
        }
        _ => Err("使い方: kanade-tool check | nbest-check [ファイル…] | romaji-check | convert <かな…> | bench".into()),
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => {
            eprintln!("一致しないものがある");
            ExitCode::FAILURE
        }
        Err(e) => {
            eprintln!("{e}");
            ExitCode::FAILURE
        }
    }
}
