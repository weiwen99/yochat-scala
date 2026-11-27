# Plan docs

One owner per artifact. A backlog entry exists only until its effort directory exists; the spec then owns the work.

| Artifact | Location | Format | Lifetime |
| --- | --- | --- | --- |
| Backlog | `TODO.md` (repo root) | markdown | items move out on start |
| Live spec | `.scratch/<effort>/spec.md` | markdown | mutates freely |
| Tickets | `.scratch/<effort>/issues/NN-<slug>.md` | markdown | mutates freely |
| Design record | `docs/plans/<year>/<month>/<NNNN>-<slug>/design.md` | markdown | frozen once implementation starts |
| Design review | `docs/plans/<year>/<month>/<NNNN>-<slug>/design-review-findings.md` | markdown | append-only |
| Test parity | `docs/plans/<year>/<month>/<NNNN>-<slug>/test-parity.md` | markdown | append-only |
| Implementation record | `docs/plans/<year>/<month>/<NNNN>-<slug>/impl.md` | markdown | append-only |
| Metadata | `docs/plans/<year>/<month>/<NNNN>-<slug>/meta.md` | markdown | status/summary |
| ADR | `docs/adr/NNNN-<slug>.md` | markdown | superseded, never rewritten |

`.scratch/` is state; `docs/` is history. It is tracked, so worktrees and implementers see the live spec. Never make one the source of truth for the other.

## Numbering

- A plan directory is `docs/plans/<year>/<month>/<NNNN>-<slug>/`, four-digit number, monotonic per repo, never reused.
- The number is provisional until the design merges to `main`; after that it is immutable.
- One effort = one plan number. Put `Plan: NNNN` in `.scratch/<effort>/spec.md`.
- Do not create flat plan files.

## Required sections

Design record:

1. `## 规模与交付边界` — the D1 scale table first: requirements / tables / components / configs / person-days / release waves, each with "need vs this plan" and a multiplier + reason. Any dimension over 3× needs an explanation.
2. `## 状态与前序` — draft/frozen/superseded; earlier plans and ADRs it builds on.
3. `## 背景与缺口` — what exists and what is actually missing.
4. `## 目标` and `## 非目标与已知约束` — v1 boundaries stated as constraints.
5. Design body: data flow, interfaces, failure modes, lock order, security contracts.
6. `## 测试规格等价` — the T1 mapping and the `test-parity.md` reference.
7. `## 性能门` — metrics, method, thresholds.
8. `## 取代的旧文档` — which docs this plan replaces; `none` is allowed only with a reason.
9. `## 验收` — the fixed scenario and the commands.

Implementation record:

1. `## 状态` — implemented, verified, not verified.
2. `## 交付范围` — behavior actually landed.
3. `## 文件变更` — one row per touched file.
4. `## 关键行为` — invariants, error codes, limits.
5. `## 验证` — commands actually run and results; local tests are not production acceptance.
6. `## 已知缺口` — deliberately left out.

## Docs budget

Every plan must name the old documents it supersedes. A plan that only adds documents and retires none needs a reason. Review findings must close or be withdrawn; an open finding without a closure is not a completed plan.

## Current plan

- `docs/plans/2026/11/0001-yochat-rewrite/` — the rewrite design; live spec at `.scratch/yochat-rewrite/spec.md`.
- `docs/plans/2026/11/0002-m3-structural-hardening/` — draft closure plan for the nine M3 review round-1 risks; live spec at `.scratch/yochat-m3-structural-hardening/spec.md`.
