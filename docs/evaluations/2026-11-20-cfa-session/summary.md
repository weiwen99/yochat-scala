# CFA session — summary

Source: [transcript.md](transcript.md), full JSON in [session.json](session.json). Caveat: a working session, not an independent audit.

## What the session did

1. Reviewed the WeChat records of the first YoChat implementation.
2. Published the postmortem at <https://draft.weiwen.org/p/im-yochat-6qfpamtf2f7yz47s>.
3. Compared the same failure modes against 0x1 (`~/projects/project-0x1`) and CFA (m1 checkout `b1410bcd`, observed 2026-11-20).

## The comparison

Six of the nine YoChat complaints exist in 0x1 and CFA in the same shape; one is partial; two do not apply.

| YoChat problem | 0x1 / CFA evidence |
| --- | --- |
| Review never converges | Both have a seven-round cap; 0x1 has seven plans at the cap, and plan 0094 went to 14 rounds after three budget extensions. CFA has 129 findings files / 15,498 lines; 0x1 has 114 / 10,587. |
| Code bloat | CFA: 245,412 Scala lines / 1,323 files, 41 files over 800 lines, largest 5,454. 0x1: 103,557 / 513, 15 over 800. Same order as YoChat's 250,000. |
| Requirement amplification | CFA Plan 77: "add an attachment" became 1,423 lines of design, 10 tables, 88–146 person-days, 12 rounds × 12 gates, four gates failing eight rounds. It was cut to 2 tables and 15–20 person-days. This case produced `DESIGN-DISCIPLINE.md`. |
| Distributed protocol complexity | 0x1's delivery queue has activation readiness, idempotent replay, generation arbitration and namespace isolation; plan 058 reproduced four production defects. Smaller than YoChat, same shape. |
| "Done" then a new bug | 0x1 TODO has three receipt defects: a dead request still asked to verify, a busy-session retry window dropped its failure notice, and a result file could not distinguish a real push from a post-rejection recheck. |
| Tests do not hold | 0x1 uses real PostgreSQL containers and 40,083 test lines (39%); CFA has 144,323 test lines (59%) and a full `check.sh`. But CFA's own "gate red classification" exists because timing-sensitive tests need interpretation. |
| Performance is not gated | CFA and 0x1 both document SLOs and both leave them out of `check.sh`; 0x1's `profile list` p50 is about 0.7 s against a 0.6 s target and sits in TODO. |
| Model speed/quota | Not a repository defect; 0x1 accounts per turn. |
| Judgment of AI coding | Not a fixable defect; it is a conclusion. |

## The new shape: document inflation

| | Plan docs | Findings | Code | Docs/code |
| --- | ---: | ---: | ---: | ---: |
| 0x1 | 41,813 lines | 10,587 | 103,557 | 0.64 |
| CFA | 156,257 lines | 15,498 | 245,412 | 0.64 |

The same requirement → design → review → record loop that inflated YoChat's code inflates these repositories' process documents. Both are at 0.64 docs per code line. The loop has no delete exit: findings are append-only, plans are append-only, ADRs are added but not retired.

## Three immediate fixes proposed

1. Put performance criteria into the automated gate and the delivery receipt (cold-start p50/p95, first byte).
2. Give 0x1 its own `DESIGN-DISCIPLINE.md` (it only has the generic machine rule, without the 3× table and the per-finding rule/cost requirement).
3. Enforce file-size limits and split the two largest files; a size limit that is only a suggestion is not a limit.

## What this repo adopts

- The review cap must come with a **stop rule**: if round 3's finding count has not fallen, stop and re-scope; do not add rounds.
- Every plan must name the old documents it supersedes; review findings must close or be withdrawn. No append-only growth without an exit.
- File and module limits are checks, not prose. The user set the file limit to 2,000 lines; module and total budgets are in ADR-0004.
- Performance gates live in the check entry and store numbers in receipts.
- The rewrite imports the **guards**, not the document volume, from CFA and 0x1.
