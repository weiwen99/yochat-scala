#!/usr/bin/env python3
"""Validate the T1 per-test CSV. Before signoff it reports remaining decisions and exits 1."""
import csv
import sys
from collections import Counter
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/plans/2026/11/0001-yochat-rewrite/test-parity-tests.csv")
rows = list(csv.DictReader(path.open()))
allowed = {"ACCEPT_PORT", "ACCEPT_REWRITE", "ACCEPT_GAP", "ACCEPT_OMIT", "REJECT"}
s1 = [r for r in rows if r["requirement_ids"]]
missing = [r for r in s1 if not r.get("decision")]
invalid = [r for r in rows if r.get("decision") and r["decision"] not in allowed]
unsigned = [r for r in rows if r.get("decision") and not (r.get("signed_by") and r.get("signed_at"))]
print(f"rows={len(rows)} s1_candidates={len(s1)} missing_decisions={len(missing)} invalid_decisions={len(invalid)} unsigned_decisions={len(unsigned)}")
print("proposed=" + ",".join(f"{k}:{v}" for k, v in Counter(r["proposed_disposition"] for r in s1).items()))
if missing:
    print("S1 rows still needing a decision (first 10):")
    for row in missing[:10]:
        print(f"  {row['row_id']} {row['source']} :: {row['test']}")
if invalid or unsigned or missing:
    sys.exit(1)
print("T1_SIGNOFF PASS")
