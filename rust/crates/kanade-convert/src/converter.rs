//! 辞書と接続コストによるかな漢字変換（Kotlin の `KanaKanjiConverter`）。
//!
//! ラティスを作り、前向きのビタビで各ノードの最小コストを求めてから、後ろ向きの A* で N-best を取る。
//! Kotlin 版と同じ答え（表記・順番・コスト）を出すために、次を同じにしている:
//! - ノードの並び（位置の順 → 読みの短い順 → 辞書の中の順 → ユーザー辞書 → 学習 → 未知語）
//! - A* の待ち行列は `java.util.PriorityQueue` と同じ手順（[`crate::java_pq`]）。f が同じ経路の順はこれで決まる
//! - 数は Java の `Int`（i32、桁あふれは折り返し）

use crate::java_pq::JavaPriorityQueue;
use kanade_dict::{ConnectionMatrix, SystemDictionary};
use kanade_text::u16str::U16String;
use std::collections::{HashMap, HashSet};

/// 変換結果の1語（ラティス上の1ノード）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Word {
    pub reading: U16String,
    pub surface: U16String,
    pub lid: u16,
    pub rid: u16,
    pub cost: i32,
}

/// 変換候補。`cost` は文頭から文末までの総コスト（小さいほど良い）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub words: Vec<Word>,
    pub cost: i32,
}

impl Candidate {
    pub fn text(&self) -> U16String {
        self.words
            .iter()
            .flat_map(|w| w.surface.iter().copied())
            .collect()
    }
}

/// ユーザー辞書・学習のように、ラティスに足す語（Kotlin の `UserDictionary` の引くところ）。読み→表記（入れた順）
#[derive(Clone, Debug, Default)]
pub struct ExtraWords {
    by_reading: HashMap<U16String, Vec<U16String>>,
    max_reading_length: usize,
}

impl ExtraWords {
    pub fn new(entries: impl IntoIterator<Item = (U16String, U16String)>) -> ExtraWords {
        let mut w = ExtraWords::default();
        for (r, s) in entries {
            w.max_reading_length = w.max_reading_length.max(r.len());
            w.by_reading.entry(r).or_default().push(s);
        }
        w
    }

    pub fn lookup(&self, reading: &[u16]) -> &[U16String] {
        self.by_reading.get(reading).map_or(&[], |v| v.as_slice())
    }

    pub fn max_reading_length(&self) -> usize {
        self.max_reading_length
    }
}

/// ユーザー辞書の語のコスト（Mozc の一般名詞より十分小さい）
pub const USER_WORD_COST: i32 = 1000;
/// 学習した語のコスト
pub const LEARNED_WORD_COST: i32 = 2500;
pub const PREDICT_MIN_LENGTH: usize = 2;
/// これより多くの読みが当てはまる接頭辞では予測しない
pub const PREDICT_MAX_READINGS: usize = 30000;
/// 予測で、読みが1文字長くなるごとに足すコスト
pub const PREDICT_LENGTH_PENALTY: i32 = 400;
/// 共通接頭辞検索で見る読みの長さの上限（Kotlin の prefixSearch の既定）
const MAX_READING: usize = 64;

#[derive(Clone, Copy)]
enum Src {
    /// 文頭・文末
    Edge,
    /// 辞書の語（項目の添字）
    Dict(u32),
    /// ユーザー辞書・学習の語（`Lattice::extra` の添字）
    Extra(u32),
    /// 辞書にない1文字
    Unknown,
}

#[derive(Clone, Copy)]
struct Node {
    begin: u32,
    end: u32,
    lid: u16,
    rid: u16,
    cost: i32,
    src: Src,
}

struct Lattice {
    nodes: Vec<Node>,
    /// 文頭からこのノードまで（このノードのコストを含む）の最小コスト。届かなければ i32::MAX
    best: Vec<i32>,
    begin_at: Vec<Vec<u32>>,
    end_at: Vec<Vec<u32>>,
    extra: Vec<U16String>,
    bos: u32,
    eos: u32,
}

pub struct KanaKanjiConverter {
    dictionary: SystemDictionary,
    connection: ConnectionMatrix,
    /// 辞書にない1文字を「名詞,一般」として置くときの品詞とコスト
    unknown_pos_id: u16,
    unknown_cost: i32,
    /// ユーザー辞書（名詞,一般として、強く優先するコストで入れる）
    pub user_dictionary: ExtraWords,
    /// 学習した語
    pub learned_dictionary: ExtraWords,
}

impl KanaKanjiConverter {
    pub fn new(dictionary: SystemDictionary, connection: ConnectionMatrix) -> Self {
        Self::with_unknown(dictionary, connection, 1851, 12000)
    }

    pub fn with_unknown(
        dictionary: SystemDictionary,
        connection: ConnectionMatrix,
        unknown_pos_id: u16,
        unknown_cost: i32,
    ) -> Self {
        KanaKanjiConverter {
            dictionary,
            connection,
            unknown_pos_id,
            unknown_cost,
            user_dictionary: ExtraWords::default(),
            learned_dictionary: ExtraWords::default(),
        }
    }

    pub fn dictionary(&self) -> &SystemDictionary {
        &self.dictionary
    }

    fn lattice(&self, input: &[u16]) -> Lattice {
        let len = input.len();
        let mut l = Lattice {
            nodes: Vec::with_capacity(len * 16 + 2),
            best: Vec::new(),
            begin_at: vec![Vec::new(); len + 1],
            end_at: vec![Vec::new(); len + 1],
            extra: Vec::new(),
            bos: 0,
            eos: 0,
        };
        let push = |l: &mut Lattice, n: Node| {
            let id = l.nodes.len() as u32;
            l.begin_at[n.begin as usize].push(id);
            l.end_at[n.end as usize].push(id);
            l.nodes.push(n);
            id
        };
        // 文頭は end_at[0] にだけ入る
        l.nodes.push(Node {
            begin: 0,
            end: 0,
            lid: 0,
            rid: 0,
            cost: 0,
            src: Src::Edge,
        });
        l.end_at[0].push(0);
        let mut hits = Vec::with_capacity(16);
        for pos in 0..len {
            let mut has_single = false;
            self.dictionary
                .prefix_search_into(input, pos, MAX_READING, &mut hits);
            for &(rl, ri) in &hits {
                for e in self.dictionary.entries_of(ri) {
                    let n = Node {
                        begin: pos as u32,
                        end: (pos + rl) as u32,
                        lid: self.dictionary.lid(e),
                        rid: self.dictionary.rid(e),
                        cost: self.dictionary.cost(e),
                        src: Src::Dict(e as u32),
                    };
                    push(&mut l, n);
                }
                if rl == 1 {
                    has_single = true;
                }
            }
            for (extra, cost) in [
                (&self.user_dictionary, USER_WORD_COST),
                (&self.learned_dictionary, LEARNED_WORD_COST),
            ] {
                for rl in 1..=extra.max_reading_length().min(len - pos) {
                    for surface in extra.lookup(&input[pos..pos + rl]) {
                        let idx = l.extra.len() as u32;
                        l.extra.push(surface.clone());
                        let id = self.unknown_pos_id;
                        push(
                            &mut l,
                            Node {
                                begin: pos as u32,
                                end: (pos + rl) as u32,
                                lid: id,
                                rid: id,
                                cost,
                                src: Src::Extra(idx),
                            },
                        );
                        if rl == 1 {
                            has_single = true;
                        }
                    }
                }
            }
            if !has_single {
                let id = self.unknown_pos_id;
                push(
                    &mut l,
                    Node {
                        begin: pos as u32,
                        end: pos as u32 + 1,
                        lid: id,
                        rid: id,
                        cost: self.unknown_cost,
                        src: Src::Unknown,
                    },
                );
            }
        }
        // 文末は begin_at[len] にだけ入る
        let eos = l.nodes.len() as u32;
        l.nodes.push(Node {
            begin: len as u32,
            end: len as u32,
            lid: 0,
            rid: 0,
            cost: 0,
            src: Src::Edge,
        });
        l.begin_at[len].push(eos);
        l.eos = eos;
        l.best = vec![i32::MAX; l.nodes.len()];
        l.best[l.bos as usize] = 0;
        l
    }

    fn surface_into(&self, l: &Lattice, input: &[u16], n: &Node, out: &mut U16String) {
        let reading = &input[n.begin as usize..n.end as usize];
        match n.src {
            Src::Edge => {}
            Src::Dict(e) => self.dictionary.surface_into(e as usize, reading, out),
            Src::Extra(i) => out.extend_from_slice(&l.extra[i as usize]),
            Src::Unknown => out.extend_from_slice(reading),
        }
    }

    fn word(&self, l: &Lattice, input: &[u16], id: u32) -> Word {
        let n = &l.nodes[id as usize];
        let mut surface = Vec::new();
        self.surface_into(l, input, n, &mut surface);
        Word {
            reading: input[n.begin as usize..n.end as usize].to_vec(),
            surface,
            lid: n.lid,
            rid: n.rid,
            cost: n.cost,
        }
    }

    /// 前向きのビタビ: 各ノードの最小コスト
    fn forward(&self, l: &mut Lattice, len: usize) {
        for pos in 0..=len {
            for bi in 0..l.begin_at[pos].len() {
                let id = l.begin_at[pos][bi] as usize;
                let lid = l.nodes[id].lid;
                let mut best = i32::MAX;
                for &prev in &l.end_at[pos] {
                    let pb = l.best[prev as usize];
                    if pb == i32::MAX {
                        continue;
                    }
                    let c = pb.wrapping_add(self.connection.cost(l.nodes[prev as usize].rid, lid));
                    if c < best {
                        best = c;
                    }
                }
                if best != i32::MAX {
                    l.best[id] = best.wrapping_add(l.nodes[id].cost);
                }
            }
        }
    }

    /// 辞書だけの N-best（上位 `n` 件、表記が同じものは先に出たほうだけ）
    pub fn convert(&self, input: &[u16], n: usize) -> Vec<Candidate> {
        self.convert_with_limit(input, n, 20000)
    }

    pub fn convert_with_limit(
        &self,
        input: &[u16],
        n: usize,
        max_expansions: usize,
    ) -> Vec<Candidate> {
        if input.is_empty() {
            return Vec::new();
        }
        let len = input.len();
        let mut l = self.lattice(input);
        self.forward(&mut l, len);
        if l.best[l.eos as usize] == i32::MAX {
            return Vec::new();
        }

        // 後ろ向き A*: f = 前向きの最小コスト + 文末側の確定済みコスト。経路は（ノード, back, 次, f）を arena に積む
        struct Path {
            node: u32,
            back: i32,
            next: u32,
        }
        const NONE: u32 = u32::MAX;
        let mut paths: Vec<Path> = Vec::with_capacity(1024);
        let by_f = |a: &(i32, u32), b: &(i32, u32)| a.0.cmp(&b.0);
        let mut queue: JavaPriorityQueue<(i32, u32)> = JavaPriorityQueue::new();
        paths.push(Path {
            node: l.eos,
            back: 0,
            next: NONE,
        });
        queue.offer((l.best[l.eos as usize], 0), by_f);
        let mut results: Vec<Candidate> = Vec::new();
        let mut seen: HashSet<U16String> = HashSet::new();
        let mut expansions = 0usize;
        loop {
            // Kotlin: queue.isNotEmpty() && results.size < n && expansions++ < maxExpansions
            if queue.is_empty() || results.len() >= n {
                break;
            }
            let within = expansions < max_expansions;
            expansions += 1;
            if !within {
                break;
            }
            let (f, pi) = queue.poll(by_f).expect("空でない");
            let p_node = paths[pi as usize].node;
            if p_node == l.bos {
                let mut words = Vec::new();
                let mut q = paths[pi as usize].next;
                while q != NONE && paths[q as usize].node != l.eos {
                    words.push(self.word(&l, input, paths[q as usize].node));
                    q = paths[q as usize].next;
                }
                let c = Candidate { words, cost: f };
                if seen.insert(c.text()) {
                    results.push(c);
                }
                continue;
            }
            let node = l.nodes[p_node as usize];
            let back = paths[pi as usize].back.wrapping_add(node.cost);
            for &prev in &l.end_at[node.begin as usize] {
                let pb = l.best[prev as usize];
                if pb == i32::MAX {
                    continue;
                }
                let g =
                    back.wrapping_add(self.connection.cost(l.nodes[prev as usize].rid, node.lid));
                let id = paths.len() as u32;
                paths.push(Path {
                    node: prev,
                    back: g,
                    next: pi,
                });
                queue.offer((pb.wrapping_add(g), id), by_f);
            }
        }
        results
    }

    /// 表記が `prefix` で始まる経路のうち、コストが最小のもの（Zenzai の制約付きの探し直し）。なければ None。
    /// 前向きのビタビで、各ノードについて「`prefix` の何文字目まで合ったか」ごとに最小コストを持つ
    pub fn convert_with_prefix(&self, input: &[u16], prefix: &[u16]) -> Option<Candidate> {
        if input.is_empty() {
            return None;
        }
        let l = self.lattice(input);
        let p = prefix.len();
        struct State {
            cost: Vec<i32>,
            prev: Vec<u32>,
            prev_matched: Vec<u32>,
        }
        const NONE: u32 = u32::MAX;
        let new_state = || State {
            cost: vec![i32::MAX; p + 1],
            prev: vec![NONE; p + 1],
            prev_matched: vec![0; p + 1],
        };
        let mut states: Vec<Option<State>> = (0..l.nodes.len()).map(|_| None).collect();
        let mut s0 = new_state();
        s0.cost[0] = 0;
        states[l.bos as usize] = Some(s0);
        // 合った文字数 m の経路にこの表記を足したときの新しい m。合わなければ None
        let advance = |m: usize, surface: &[u16]| -> Option<usize> {
            if m == p {
                return Some(p);
            }
            let rest = p - m;
            if surface.len() <= rest {
                (prefix[m..m + surface.len()] == *surface).then_some(m + surface.len())
            } else {
                (surface[..rest] == prefix[m..]).then_some(p)
            }
        };
        let mut surface = Vec::new();
        for pos in 0..=input.len() {
            for &node in &l.begin_at[pos] {
                let nd = l.nodes[node as usize];
                surface.clear();
                self.surface_into(&l, input, &nd, &mut surface);
                for &prev in &l.end_at[pos] {
                    let Some(ps) = &states[prev as usize] else {
                        continue;
                    };
                    let conn = self.connection.cost(l.nodes[prev as usize].rid, nd.lid);
                    let mut updates: Vec<(usize, i32, usize)> = Vec::new();
                    for m in 0..=p {
                        let c = ps.cost[m];
                        if c == i32::MAX {
                            continue;
                        }
                        let Some(next) = advance(m, &surface) else {
                            continue;
                        };
                        updates.push((next, c.wrapping_add(conn).wrapping_add(nd.cost), m));
                    }
                    if updates.is_empty() {
                        continue;
                    }
                    let ns = states[node as usize].get_or_insert_with(new_state);
                    for (next, total, m) in updates {
                        if total < ns.cost[next] {
                            ns.cost[next] = total;
                            ns.prev[next] = prev;
                            ns.prev_matched[next] = m as u32;
                        }
                    }
                }
            }
        }
        let end = states[l.eos as usize].as_ref()?;
        if end.cost[p] == i32::MAX {
            return None;
        }
        let mut words = Vec::new();
        let mut node = end.prev[p];
        let mut m = end.prev_matched[p] as usize;
        while node != NONE && node != l.bos {
            words.push(self.word(&l, input, node));
            let s = states[node as usize].as_ref().expect("通った状態");
            let prev = s.prev[m];
            m = s.prev_matched[m] as usize;
            node = prev;
        }
        words.reverse();
        Some(Candidate {
            words,
            cost: end.cost[p],
        })
    }

    /// `reading` とちょうど同じ読みの辞書の語（コストの小さい順、同じ表記は1つ）
    pub fn exact_words(&self, reading: &[u16]) -> Vec<U16String> {
        let hits = self.dictionary.prefix_search(reading, 0, reading.len());
        let Some(&(_, index)) = hits.iter().rev().find(|h| h.0 == reading.len()) else {
            return Vec::new();
        };
        let mut out: Vec<U16String> = Vec::new();
        for e in self.dictionary.entries_of(index) {
            let s = self.dictionary.surface(e, reading);
            if !out.contains(&s) {
                out.push(s);
            }
        }
        out
    }

    /// `prefix` で始まり、それより長い読みの辞書の語（予測の候補）。（読み, 表記）。
    /// コストに読みが長くなった分の重みを足して小さい順。品詞が `predictable` でない語は出さない
    pub fn predict_words(
        &self,
        prefix: &[u16],
        n: usize,
        predictable: impl Fn(u16) -> bool,
    ) -> Vec<(U16String, U16String)> {
        if prefix.len() < PREDICT_MIN_LENGTH {
            return Vec::new();
        }
        let range = self.dictionary.prefix_range(prefix);
        if range.is_empty() || range.end - 1 - range.start > PREDICT_MAX_READINGS {
            return Vec::new();
        }
        // Kotlin: PriorityQueue(compareByDescending { score })。頭が一番悪い（大きい）もの
        let desc = |a: &(i32, U16String, U16String), b: &(i32, U16String, U16String)| b.0.cmp(&a.0);
        let mut best: JavaPriorityQueue<(i32, U16String, U16String)> = JavaPriorityQueue::new();
        for r in range {
            let rl = self.dictionary.reading_length_of(r);
            if rl <= prefix.len() {
                continue;
            }
            let extra = (rl - prefix.len()) as i32;
            let mut reading: Option<U16String> = None;
            for e in self.dictionary.entries_of(r) {
                if !predictable(self.dictionary.rid(e)) {
                    continue;
                }
                let score = self
                    .dictionary
                    .cost(e)
                    .wrapping_add(extra.wrapping_mul(PREDICT_LENGTH_PENALTY));
                if best.len() >= n * 2 && score >= best.peek().expect("空でない").0 {
                    continue;
                }
                let rd = reading
                    .get_or_insert_with(|| self.dictionary.reading(r))
                    .clone();
                let surface = self.dictionary.surface(e, &rd);
                best.offer((score, rd, surface), desc);
                if best.len() > n * 2 {
                    best.poll(desc);
                }
            }
        }
        // 配列の順（Java の反復の順）から、スコアで安定に並べ、表記が同じものはよいほうだけ残す
        let mut all: Vec<&(i32, U16String, U16String)> = best.as_slice().iter().collect();
        all.sort_by_key(|x| x.0);
        let mut seen: HashSet<&U16String> = HashSet::new();
        all.into_iter()
            .filter(|x| seen.insert(&x.2))
            .take(n)
            .map(|x| (x.1.clone(), x.2.clone()))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kanade_dict::{write_kdic, Bytes, WordEntry};
    use kanade_text::{lossy, u16s};

    fn words() -> Vec<WordEntry> {
        vec![
            WordEntry::new("はし", 1, 1, 3000, "橋"),
            WordEntry::new("はし", 1, 1, 3200, "箸"),
            WordEntry::new("はし", 1, 1, 3500, "端"),
            WordEntry::new("は", 2, 2, 1000, "は"),
            WordEntry::new("し", 1, 1, 4000, "市"),
            WordEntry::new("を", 2, 2, 500, "を"),
            WordEntry::new("わたる", 1, 1, 3000, "渡る"),
            WordEntry::new("てすと", 1, 1, 3000, "てすと"),
            WordEntry::new("てすと", 1, 1, 2000, "テスト"),
            WordEntry::new("てすとけーす", 1, 1, 3000, "テストケース"),
            WordEntry::new("てすとちゅう", 1, 1, 6000, "テスト中"),
        ]
    }

    /// 名詞→助詞・助詞→名詞は安く、名詞→名詞は高い（ConverterTest と同じ）
    fn connection() -> ConnectionMatrix {
        let lines = ["3", "0", "0", "0", "0", "3000", "100", "0", "100", "3000"];
        ConnectionMatrix::new(Bytes::from_vec(ConnectionMatrix::write_from_mozc(lines))).unwrap()
    }

    fn converter(w: &[WordEntry]) -> KanaKanjiConverter {
        let d = SystemDictionary::new(Bytes::from_vec(write_kdic(w))).unwrap();
        KanaKanjiConverter::with_unknown(d, connection(), 1, 12000)
    }

    fn texts(c: &[Candidate]) -> Vec<String> {
        c.iter().map(|x| lossy(&x.text())).collect()
    }

    #[test]
    fn n_best_in_cost_order() {
        let c = converter(&words());
        let result = c.convert(&u16s("はしをわたる"), 3);
        assert_eq!(texts(&result), vec!["橋を渡る", "箸を渡る", "端を渡る"]);
        let w: Vec<String> = result[0].words.iter().map(|w| lossy(&w.surface)).collect();
        assert_eq!(w, vec!["橋", "を", "渡る"]);
        assert!(result.windows(2).all(|p| p[0].cost <= p[1].cost));
    }

    #[test]
    fn unknown_characters_still_connect() {
        let c = converter(&words());
        let best = &c.convert(&u16s("ぬはし"), 1)[0];
        assert_eq!(lossy(&best.words[0].surface), "ぬ");
        assert_eq!(best.words.iter().map(|w| w.reading.len()).sum::<usize>(), 3);
    }

    #[test]
    fn user_words_enter_lattice_with_priority() {
        let mut c = converter(&words());
        assert_eq!(
            texts(&c.convert(&u16s("はしをわたる"), 1)),
            vec!["橋を渡る"]
        );
        c.user_dictionary = ExtraWords::new([(u16s("わたる"), u16s("亘る"))]);
        assert_eq!(
            texts(&c.convert(&u16s("はしをわたる"), 1)),
            vec!["橋を亘る"]
        );
    }

    #[test]
    fn convert_with_prefix_follows_the_models_prefix() {
        let c = converter(&words());
        let t = |p: &str| {
            c.convert_with_prefix(&u16s("はしをわたる"), &u16s(p))
                .map(|x| lossy(&x.text()))
        };
        assert_eq!(t("箸").as_deref(), Some("箸を渡る"));
        assert_eq!(t("端を渡").as_deref(), Some("端を渡る"));
        assert_eq!(t("は市").as_deref(), Some("は市を渡る"));
        assert_eq!(t("犬"), None);
    }

    #[test]
    fn exact_words_and_predictions() {
        let c = converter(&words());
        let ex: Vec<String> = c
            .exact_words(&u16s("はし"))
            .iter()
            .map(|s| lossy(s))
            .collect();
        assert_eq!(ex, vec!["橋", "箸", "端"]);
        let p: Vec<(String, String)> = c
            .predict_words(&u16s("てす"), 5, |_| true)
            .iter()
            .map(|(r, s)| (lossy(r), lossy(s)))
            .collect();
        let want = [
            ("てすと", "テスト"),
            ("てすと", "てすと"),
            ("てすとけーす", "テストケース"),
            ("てすとちゅう", "テスト中"),
        ];
        assert_eq!(
            p,
            want.iter()
                .map(|(a, b)| (a.to_string(), b.to_string()))
                .collect::<Vec<_>>()
        );
        assert!(c.predict_words(&u16s("て"), 5, |_| true).is_empty());
        assert!(c
            .predict_words(&u16s("てすとけーす"), 5, |_| true)
            .is_empty());
    }

    #[test]
    fn predictions_skip_unpredictable_pos() {
        let w = vec![
            WordEntry::new("きどう", 1, 1, 3000, "起動"),
            WordEntry::new("きどうにのっ", 2, 2, 3000, "軌道に乗っ"),
            WordEntry::new("きどうにのる", 1, 1, 3500, "軌道に乗る"),
        ];
        let c = converter(&w);
        let p: Vec<String> = c
            .predict_words(&u16s("きど"), 5, |id| id != 2)
            .iter()
            .map(|(_, s)| lossy(s))
            .collect();
        assert_eq!(
            p.into_iter()
                .filter(|s| s.starts_with("軌道"))
                .collect::<Vec<_>>(),
            vec!["軌道に乗る"]
        );
    }
}
