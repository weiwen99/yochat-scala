# Review quality

`AGENTS.md` §7 defines the two axes, the seven-round cap, the freeze rule, and the risk tiers. This file defines dispatch inputs, object pinning, finding fields, and record locations.

## Applicable branches

| Branch | Trigger | Object pinning | Argument review |
| --- | --- | --- | --- |
| Git code | diff contains only code and config | commit + paths | not applicable |
| Git with text | diff contains a design, ADR, skill, or analytical comment | commit + paths; record the pre-change baseline | cover the changed paragraphs; full text when requested |
| Non-Git doc | user gives a path and asks for review | absolute path + content hash + line/paragraph | cover the requested range |
| Pure code | refactor or bug fix, no prose | commit + paths | not applicable |

When a batch contains both code and text, give separate conclusions per file. Do not review pure code with the argument-review method.

## Dispatch inputs

For text branches, the lead prepares:

1. the method text and version: `de-ai-flavor` `SKILL.md` and `references/argument-review.md`, with source, observed commit or file hash, and read time;
2. the object package: original request, final text or diff, applicable rule paths, evidence paths, and the pinning above;
3. the actual method text, not a summary;
4. the two-axis responsibilities.

If the method cannot be loaded, stop at "insufficient" and say which file is missing. Do not invent a second method.

## Two axes

- **Standards**: check the applicable rules (`docs/agents/`, `docs/adr/`, `DESIGN-DISCIPLINE.md`, `GLOSSARY.md`), coverage, and evidence records. Format statistics are not an argument or a fidelity conclusion.
- **Spec**: start from the user's actual problem and the source; review paragraph by paragraph and return a disposition record. Findings about task fidelity stay on this axis.

Both axes receive the same object package. No third axis.

## Finding fields

A substantive finding has all five:

| Field | Content |
| --- | --- |
| Original quote and location | readable quote + commit/path/line, or path + hash + line |
| Rule | violated rule, task requirement, or source spec |
| Problem and impact | the concrete defect and the judgment it misleads |
| Closure evidence | the diff or new sentence that closes it; "noted" does not close |
| Cost | estimated person-days / tables / components; if cost > protected value, downgrade to a suggestion |

A claim that a rewrite deleted a fact must quote the old sentence and the new gap. If the location cannot be found, mark "unverified"; do not assert fact loss. Style preferences are suggestions, not blockers.

## Records

- Design review: `docs/plans/<year>/<month>/<NNNN>-<slug>/design-review-findings.md`, append-only.
- Implementation review: same directory, `impl.md`.
- Each record states the object and version, both axes' coverage and gaps, every finding's disposition and closure, and any upgrade to a human decision.
- Maximum 7 rounds. If round 3's finding count has not fallen, stop and re-scope (R9).

## Argument review for analytical text

When the object contains analytical prose (design docs, proposals, report bodies), also run the `de-ai-flavor` argument-review mode. Check whether the claims being rebutted were actually made, and whether the qualifications point at real objects and conditions. Pure code changes skip this; that absence does not block.
