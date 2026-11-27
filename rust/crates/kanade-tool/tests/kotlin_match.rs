//! Kotlin 版の記録と全件一致すること（docs/RUST-PHASE1.md）。
//! 辞書（KANADE_DICT_DIR）が無いときは飛ばさず、流せなかった件数を出して失敗にする。

use kanade_dict::{ReverseDictionary, SystemDictionary};
use kanade_tool::*;

fn dict_or_fail(files: &[std::path::PathBuf]) -> std::path::PathBuf {
    match dict_dir() {
        Ok(d) => d,
        Err(e) => panic!(
            "{e}。辞書が無いので {} 件の記録を流せない（飛ばしにはしない）",
            count_records(files)
        ),
    }
}

fn assert_all(rep: &Report) {
    assert!(rep.ok(), "{}\n{}", rep.summary(), rep.diffs.join("\n"));
}

#[test]
fn romaji_and_width_match_kotlin() {
    let files = romaji_files(&repo_root());
    assert_eq!(files.len(), 4, "testdata/romaji の記録の数");
    for f in files {
        assert_all(&check_romaji(&f).unwrap());
    }
}

#[test]
fn dictionary_only_nbest_matches_kotlin() {
    let files = nbest_files(&repo_root());
    assert!(files.len() >= 6, "testdata/nbest の記録の数");
    let dir = dict_or_fail(&files);
    let d = Dict::open(&dir).unwrap();
    let mut total = 0;
    for f in &files {
        let rep = check_nbest(&d, &dir, f).unwrap();
        total += rep.total;
        assert_all(&rep);
    }
    assert!(total >= 699, "N-best の読みの数 {total}");
}

#[test]
fn reverse_and_tables_match_kotlin() {
    let root = repo_root();
    let mut files = reverse_files(&root);
    files.push(root.join("testdata/nbest/tables.tsv"));
    let dir = dict_or_fail(&files);
    let rev = ReverseDictionary::new(SystemDictionary::open(dir.join("reverse.kdic")).unwrap());
    for f in reverse_files(&root) {
        assert_all(&check_reverse(&rev, &dir, &f).unwrap());
    }
    assert_all(&check_tables(&dir, &root.join("testdata/nbest/tables.tsv")).unwrap());
}
