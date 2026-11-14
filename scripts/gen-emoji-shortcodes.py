#!/usr/bin/env python3
"""絵文字の短い名前（:rocket: → 🚀）の表を作る。元は GitHub の gemoji（MIT。Slack・GitHub と同じ名前）。
版とハッシュを固定する。上げるときは COMMIT と SHA256 を変えて、もう一度実行する。

  scripts/gen-emoji-shortcodes.py   # core/src/main/resources/dev/mooma/kanade/core/dict/emoji-shortcodes.tsv を書く

出力は1行に「絵文字<TAB>名前（空白区切り）<TAB>言葉（gemoji のタグと英語の説明の語。空白区切り）」。並びは gemoji の順（絵文字の一覧の分類の順）。
"""
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

COMMIT = "fadaeaf1f1a9be82b321316a6c5502e43138b2f6"
URL = f"https://raw.githubusercontent.com/github/gemoji/{COMMIT}/db/emoji.json"
SHA256 = "b174ae2aeb321b52f64adb9ff412f966a7f338839d780784dd15dcad702c2dd6"

root = pathlib.Path(__file__).resolve().parent.parent
cache = root / ".cache" / f"gemoji-{COMMIT[:12]}.json"
out = root / "core/src/main/resources/dev/mooma/kanade/core/dict/emoji-shortcodes.tsv"

if not cache.exists() or hashlib.sha256(cache.read_bytes()).hexdigest() != SHA256:
    print("取得:", URL)
    data = urllib.request.urlopen(URL).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        sys.exit("gemoji のハッシュが合わない")
    cache.parent.mkdir(exist_ok=True)
    cache.write_bytes(data)

lines = []
for e in json.loads(cache.read_text(encoding="utf-8")):
    aliases = [a.lower() for a in e["aliases"]]
    words = []
    for w in e.get("tags", []) + re.split(r"[^a-z0-9]+", e.get("description", "").lower()):
        w = w.strip().lower()
        if len(w) >= 2 and w not in aliases and w not in words:
            words.append(w)
    for field in [e["emoji"], *aliases, *words]:
        assert "\t" not in field and "\n" not in field
    lines.append("\t".join([e["emoji"], " ".join(aliases), " ".join(words)]))
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("# gemoji " + COMMIT + " (MIT, https://github.com/github/gemoji)\n" + "\n".join(lines) + "\n", encoding="utf-8")
print(out, len(lines), "件")
