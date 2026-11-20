# YoChat postmortem — summary

Source: [draft.md](draft.md). Caveat: external observation, not a code audit.

## What went wrong

The first implementation turned one sentence of user need — 「加好友，单聊」 — into 93 requirements, 144 Quint models, 205 tasks and about 250,000 lines of Rust. It still stopped at add-friend and one-to-one chat.

Four failures, in order of leverage:

1. **Scope.** The author wanted a self-use, serverless friend-to-friend IM. The specs carried release-grade requirements — recovery phrase, device revocation, four-device takeover, WCAG 2.2 AA, 100k-message first paint, stranger admission budgets, a privacy notice. The unfinished tail was almost entirely multi-device work.
2. **Structure.** At 250,000 lines no one could read the whole system. Modularity existed, but the boundaries did not follow change boundaries. The author's own high-leverage moment was reading the structure, locating the cause, and only then letting the AI refactor.
3. **Acceptance.** Three "done" declarations were based on prose: a week of debugging, a line count, and the AI explaining a few points. None was "two devices exchange 100 messages, restart without loss, reconnect after a network drop."
4. **Goal drift.** A self-use tool and an AI-programming exercise were measured by the same schedule. Product standards made the exercise anxious; exercise pace made the product expand.

## Numbers that matter

- 93 requirements, 144 Quint models, 205 tasks, about 250,000 lines.
- First review: over 100 findings; more than 20 review rounds; the AI stopped the loop itself.
- 1,000 messages over two local processes: nearly 300 seconds; later 147/236 blocks in 360 seconds.
- File-size budget was a wish, not a check: 20k, then 50k, then 250k.
- Dependency upgrades turned the whole test suite red; the explanation was that the old tests were incomplete.

## Ten recommendations

1. Cut to the self-use scenario: two devices, one user, online single chat, persistence, restart, reconnect. If deleting a requirement still lets you send a message tomorrow, delete it.
2. Reduce the protocol to one path: A connects to B (relay if direct fails), single chat, write-before-send, retry while offline. Defer multi-device, history sync and revocation propagation.
3. Freeze the Quint models after the protocol is final; the AI may implement, not edit the model. Model changes are a separate, human-signed round.
4. Cut modules by change frequency, not by feature: protocol state machine, storage, transport, UI, test fixtures. One task touches one module plus its tests.
5. Review by risk: crypto, keys, persistence and protocol state machines get a diff plus spec clauses plus invariants; UI, layout, copy and logs get a manual check. Freeze the diff before review.
6. Use behavior contracts: about 15 end-to-end scenarios that survive refactors. Unit tests may be rewritten; every bug fix starts with a failing case.
7. Make performance a standing gate: 1,000 messages under 5 seconds, cold start under 1 second (the old target), idle memory capped. Regressions block.
8. Treat inflation mechanically: file and module limits, no unused public symbols, a delete budget per feature.
9. Do not do a third full rewrite. Replace modules behind end-to-end scenarios and benchmarks.
10. Account for the two goals separately: self-use tool and practice project. Say which one a "done" belongs to.

## Two-week acceptance

Two devices exchange 100 messages, restart without loss, reconnect after a network drop, with no command line. If that fails, cut scope; do not change models or add review rounds.

## What this repo adopts

- v1 = S1, the smallest real friend-to-friend scenario (ADR-0003).
- One protocol path; model freeze; behavior-contract tests; test-parity record.
- File limit 2,000 lines (the user overrode 800); module and total budgets; delete budget.
- Performance gates in the check entry, with numeric receipts.
- This rewrite is the last one; after it, module replacement only.
- Two goals are named explicitly: a usable self-use tool first, practice second.
