# Domain docs

This repo is **single-context**: one `GLOSSARY.md` and one `docs/adr/`, both at the repo root.

## Before exploring

- Read `GLOSSARY.md`.
- Read the ADRs that touch the area.

If a file does not exist, proceed silently; do not propose creating it upfront. The domain-modeling discipline creates them lazily when a term or decision is actually resolved.

## Use the vocabulary

When naming a concept in a plan, issue, refactor, test, or UI string, use the term defined in `GLOSSARY.md`. Do not drift to a synonym the glossary marks `_Avoid_`.

If the concept is missing, either you are inventing language the project does not use (reconsider), or there is a real gap (note it for a domain-modeling pass).

## Flag ADR conflicts

If a change contradicts an existing ADR, surface it explicitly instead of silently overriding:

> Contradicts ADR-0003 (v1 scope), but worth reopening because…

## ADR criteria

Write an ADR only when all three hold:

1. the decision is hard to reverse;
2. a future reader will wonder why it was made;
3. there were genuine alternatives and a real trade-off.

Otherwise skip it. ADRs are markdown in `docs/adr/NNNN-<slug>.md`; number by the highest existing number + 1. Status is `proposed`, `accepted`, `deprecated`, or `superseded by ADR-NNNN`.
