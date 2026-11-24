#!/usr/bin/env python3
"""Verify batch signatures and materialize per-test T1 decisions.

Default is a dry run. Use --apply after filling test-parity-batches.csv.
"""
import argparse
import csv
import hashlib
from collections import defaultdict
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "docs/plans/2026/11/0001-yochat-rewrite/test-parity-tests.csv"
BATCHES = ROOT / "docs/plans/2026/11/0001-yochat-rewrite/test-parity-batches.csv"
ALLOWED = {"ACCEPT_PORT", "ACCEPT_REWRITE", "ACCEPT_GAP", "ACCEPT_OMIT", "REJECT"}

def hash_batch(source, req, rows):
    material = "\n".join(f"{r['row_id']}\0{source}\0{r['test']}\0{req}" for r in rows)
    return hashlib.sha256(material.encode()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    tests = list(csv.DictReader(TESTS.open()))
    batches = list(csv.DictReader(BATCHES.open()))
    groups = defaultdict(list)
    for row in tests:
        if row["requirement_ids"]:
            groups[(row["source"], row["requirement_ids"])].append(row)
    problems = []
    ready = True
    for batch in batches:
        key = (batch["source"], batch["requirement_ids"])
        rows = groups.get(key, [])
        if len(rows) != int(batch["test_count"]):
            problems.append(f"{batch['batch_id']}: count mismatch")
            ready = False
            continue
        if hash_batch(batch["source"], batch["requirement_ids"], rows) != batch["batch_sha256"]:
            problems.append(f"{batch['batch_id']}: hash mismatch")
            ready = False
        if batch["batch_decision"] not in ALLOWED:
            ready = False
        if not batch["signed_by"] or not batch["signed_at"]:
            ready = False
    if problems:
        print("T1_BATCH_CHECK FAIL")
        print("\n".join(problems))
        return 1
    if not ready:
        unsigned = sum(1 for b in batches if b["batch_decision"] not in ALLOWED or not b["signed_by"] or not b["signed_at"])
        print(f"T1_BATCH_CHECK PENDING batches={len(batches)} unsigned_or_incomplete={unsigned} tests={sum(int(b['test_count']) for b in batches)}")
        return 1
    if not args.apply:
        print(f"T1_BATCH_CHECK PASS batches={len(batches)} tests={sum(int(b['test_count']) for b in batches)} (dry-run; use --apply)")
        return 0
    by_key = {(b["source"], b["requirement_ids"]): b for b in batches}
    for row in tests:
        if not row["requirement_ids"]:
            continue
        batch = by_key[(row["source"], row["requirement_ids"])]
        if row.get("decision") and row["decision"] != batch["batch_decision"]:
            print(f"refusing to overwrite existing row decision {row['row_id']}")
            return 1
        row["decision"] = batch["batch_decision"]
        row["signed_by"] = batch["signed_by"]
        row["signed_at"] = batch["signed_at"]
    with TESTS.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=tests[0].keys())
        writer.writeheader()
        writer.writerows(tests)
    print(f"T1_BATCH_APPLY PASS batches={len(batches)} tests={sum(int(b['test_count']) for b in batches)}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
