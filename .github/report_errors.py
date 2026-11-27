"""Turns compiler errors in a build log into GitHub annotations (readable via the API)."""
import re
import sys

PAT = re.compile(r"(^e: |error:|What went wrong|FAILED|Could not |Unresolved reference|> Task .* FAILED|undefined symbol|ld: )")
lines = open(sys.argv[1], errors="replace").read().splitlines() if len(sys.argv) > 1 else []
hits = []
for i, line in enumerate(lines):
    if PAT.search(line):
        ctx = [line.strip()]
        if "What went wrong" in line:
            ctx += [l.strip() for l in lines[i + 1:i + 6]]
        hits.append(" | ".join(c for c in ctx if c)[:900])
seen, uniq = set(), []
for h in hits:
    if h not in seen:
        seen.add(h)
        uniq.append(h)
if not uniq:
    uniq = [l.strip()[:900] for l in lines[-40:] if l.strip()]
chunks = [uniq[i:i + 12] for i in range(0, min(len(uniq), 120), 12)]
for n, chunk in enumerate(chunks[:10]):
    msg = "%0A".join(c.replace("%", "%25").replace("\r", "").replace("\n", " ") for c in chunk)
    print(f"::error title=Build errors {n + 1}/{len(chunks[:10])}::{msg}")
print(f"{len(uniq)} distinct error lines")
