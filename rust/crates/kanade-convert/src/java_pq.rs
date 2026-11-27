//! `java.util.PriorityQueue`（二分ヒープ）と同じ手順の優先度つき待ち行列。
//!
//! N-best の A* は、f が同じ経路の取り出し順がヒープの形で決まる（Kotlin 版は比べるのが f だけ）。
//! Rust の `BinaryHeap` は手順が違い同点の順が変わるので、Java の `siftUp`・`siftDown` をそのまま写す。
//! 中の配列の並び（[`JavaPriorityQueue::as_slice`]）も Java の反復の順と同じ。

use std::cmp::Ordering;

#[derive(Clone, Debug, Default)]
pub struct JavaPriorityQueue<T> {
    q: Vec<T>,
}

impl<T> JavaPriorityQueue<T> {
    pub fn new() -> Self {
        JavaPriorityQueue { q: Vec::new() }
    }

    pub fn len(&self) -> usize {
        self.q.len()
    }

    pub fn is_empty(&self) -> bool {
        self.q.is_empty()
    }

    pub fn peek(&self) -> Option<&T> {
        self.q.first()
    }

    /// Java の反復の順（配列の順）
    pub fn as_slice(&self) -> &[T] {
        &self.q
    }

    /// `offer`（siftUpUsingComparator）
    pub fn offer(&mut self, x: T, cmp: impl Fn(&T, &T) -> Ordering) {
        let mut k = self.q.len();
        self.q.push(x);
        while k > 0 {
            let parent = (k - 1) >> 1;
            if cmp(&self.q[k], &self.q[parent]) != Ordering::Less {
                break;
            }
            self.q.swap(k, parent);
            k = parent;
        }
    }

    /// `poll`（最後の要素を頭に置いて siftDownUsingComparator）
    pub fn poll(&mut self, cmp: impl Fn(&T, &T) -> Ordering) -> Option<T> {
        let last = self.q.pop()?;
        if self.q.is_empty() {
            return Some(last);
        }
        let result = std::mem::replace(&mut self.q[0], last);
        let n = self.q.len();
        let half = n >> 1;
        let mut k = 0;
        while k < half {
            let mut child = 2 * k + 1;
            let right = child + 1;
            if right < n && cmp(&self.q[child], &self.q[right]) == Ordering::Greater {
                child = right;
            }
            if cmp(&self.q[k], &self.q[child]) != Ordering::Greater {
                break;
            }
            self.q.swap(k, child);
            k = child;
        }
        Some(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Java の PriorityQueue に (f, 名前) を入れて f だけで比べたときの取り出し順（JDK 21 の jshell で確かめた値）
    #[test]
    fn ties_follow_java_heap_order() {
        let by_f = |a: &(i32, char), b: &(i32, char)| a.0.cmp(&b.0);
        let mut q = JavaPriorityQueue::new();
        for x in [
            (5, 'a'),
            (3, 'b'),
            (5, 'c'),
            (3, 'd'),
            (1, 'e'),
            (5, 'f'),
            (3, 'g'),
        ] {
            q.offer(x, by_f);
        }
        // 配列: offer の後の形
        let arr: Vec<char> = q.as_slice().iter().map(|x| x.1).collect();
        assert_eq!(arr, vec!['e', 'b', 'g', 'a', 'd', 'f', 'c']);
        let mut out = Vec::new();
        while let Some(x) = q.poll(by_f) {
            out.push(x.1);
        }
        assert_eq!(out, vec!['e', 'b', 'd', 'g', 'a', 'c', 'f']);
    }
}
