# Reference evaluations

Two 2026-11-20 documents record the postmortem of the first YoChat implementation. They are not product requirements, but their conclusions are written into this repo's hard constraints.

| Document | Source | Original | Summary |
| --- | --- | --- | --- |
| YoChat postmortem (published) | <https://draft.weiwen.org/p/im-yochat-6qfpamtf2f7yz47s> | [draft.md](2026-11-20-yochat-postmortem/draft.md) | [summary.md](2026-11-20-yochat-postmortem/summary.md) |
| CFA session (working record and 0x1/CFA comparison) | <https://w7.tailb5692.ts.net/chat/s/61a839fd-92fb-4ca1-a6b9-b9abefb781ca> | [session.json](2026-11-20-cfa-session/session.json), [transcript.md](2026-11-20-cfa-session/transcript.md) | [summary.md](2026-11-20-cfa-session/summary.md) |

## Related reading

- [flw's complete YoChat notes package](../references/2026-11-20-flw-yochat-diagrams/README.md) — six diagrams, glossary, offline reading page, and the prompts, 2026-11-20.

## How to cite

- Cite the published postmortem as `docs/evaluations/2026-11-20-yochat-postmortem/draft.md#<heading>`.
- Cite the session as `docs/evaluations/2026-11-20-cfa-session/transcript.md`; the full tool inputs/outputs and thinking are in `session.json`.
- Both are **external observations**, not an independent code audit. The postmortem never read the code or specs; implementation claims are the author's self-report. Keep that caveat when citing.
- Links inside `draft.md`, `transcript.md`, and `session.json` are quoted source text, not repository links. The only local asset is the postmortem diagram `2026-11-20-yochat-postmortem/yochat-loop.png`; paths mentioned by the session's tool results may not exist in this repo.

## Redaction

The CFA session's tool output contained one local development database password. The repository copies of `session.json` and `transcript.md` replace it with `<redacted-db-password>`; all other text is unchanged. The unredacted original is archived outside git at `~/archive/projects/yochat/2026-11/cfa-session-61a839fd.json` and remains in the CFA session store on w7.
