#!/usr/bin/env python3
"""Verify T1 split-group signatures and apply them to per-test rows."""
import argparse
import csv
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/plans/2026/11/0001-yochat-rewrite"
TESTS = BASE / "test-parity-tests.csv"
SPLIT = BASE / "test-parity-split.csv"
MEMBERSHIP = BASE / "test-parity-split-membership.csv"
GROUPS = BASE / "test-parity-split-groups.csv"
ALLOWED = {"ACCEPT_PORT", "ACCEPT_REWRITE", "ACCEPT_GAP", "ACCEPT_OMIT", "REJECT"}

def read(path):
    return list(csv.DictReader(path.open()))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--bulk-sign", nargs=2, metavar=("NAME", "DATE"), help="sign all groups with one T1-owner attestation")
    args = parser.parse_args()
    tests = read(TESTS)
    split = read(SPLIT)
    groups = read(GROUPS)
    membership = {r["row_id"]: r["split_batch_id"] for r in read(MEMBERSHIP)}
    split_by_id = {r["split_batch_id"]: r for r in split}
    seen = set()
    pending = 0
    for group in groups:
        ids = group["split_batch_ids"].split()
        rows = " ".join(x for sid in ids for x in split_by_id[sid]["row_ids"].split())
        if hashlib.sha256(rows.encode()).hexdigest() != group["row_ids_sha256"]:
            print(f"T1_SPLIT_CHECK FAIL hash {group['group_id']}")
            return 1
        if group["decision"] not in ALLOWED or not group["signed_by"] or not group["signed_at"]:
            pending += 1
        seen.update(ids)
    if seen != set(split_by_id):
        print("T1_SPLIT_CHECK FAIL split batch not covered exactly once")
        return 1
    if args.bulk_sign:
        missing = [g["group_id"] for g in groups if g["decision"] not in ALLOWED]
        if missing:
            print("T1_SPLIT_SIGN failed: missing decisions " + " ".join(missing))
            return 1
        name, date = args.bulk_sign
        for group in groups:
            group["signed_by"] = name
            group["signed_at"] = date
        with GROUPS.open("w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=groups[0].keys())
            w.writeheader(); w.writerows(groups)
        pending = sum(1 for g in groups if g["decision"] not in ALLOWED or not g["signed_by"] or not g["signed_at"])
        print(f"T1_SPLIT_SIGN groups={len(groups)} signer={name} date={date}")

    if pending:
        print(f"T1_SPLIT_CHECK PENDING groups={len(groups)} pending_groups={pending} split_batches={len(split)} s1_tests={sum(1 for t in tests if t['requirement_ids'])}")
        return 1
    if not args.apply:
        print(f"T1_SPLIT_CHECK PASS groups={len(groups)} split_batches={len(split)} (dry-run; use --apply)")
        return 0
    group_for = {}
    for group in groups:
        for sid in group["split_batch_ids"].split():
            group_for[sid] = group
    for batch in split:
        group = group_for[batch["split_batch_id"]]
        batch["decision"] = group["decision"]
        batch["signed_by"] = group["signed_by"]
        batch["signed_at"] = group["signed_at"]
    for row in tests:
        if not row["requirement_ids"]:
            continue
        batch = split_by_id[membership[row["row_id"]]]
        row["decision"] = batch["decision"]
        row["signed_by"] = batch["signed_by"]
        row["signed_at"] = batch["signed_at"]
    for path, rows in ((SPLIT, split), (TESTS, tests)):
        with path.open("w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=rows[0].keys())
            w.writeheader(); w.writerows(rows)
    print(f"T1_SPLIT_APPLY PASS groups={len(groups)} split_batches={len(split)} tests={sum(1 for t in tests if t['requirement_ids'])}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
