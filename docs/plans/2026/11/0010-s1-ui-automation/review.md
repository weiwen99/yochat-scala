# Plan 0010 review

Fixed point: `fe9e126`. Axes: Standards and Spec, independent reviewers.

| ID | Axis | Finding | Closure |
| --- | --- | --- | --- |
| U1 | Spec | Safety was confirmed before the gate was tested. | Open both chats first; assert 19/20-digit local segments differ; attempt `pre-safety-ui`; assert zero rendered bodies on both; then verify safety. |
| U2 | Spec | Only A sent; S1 requires two devices to exchange. | Added 100 B->A messages and exact rendered sequence assertions in both directions. |
| U3 | Spec/Standards | `.last().waitFor()` accepted any row, not the 100th exact transcript. | Replaced with exact count waits and exact `ui-*` / `ui-b-*` ordered bubble comparisons. |
| U4 | Standards | Invitations were not directed. | A fills expected peer `@B`; B fills expected inviter `@A` and own `@B`. |
| U5 | Standards | Colliding ports or another listener could satisfy HTTP readiness. | Wait for each child’s own bind log; page waits for relay-online state. |
| U6 | Standards | JVMs could be deleted while still shutting down; failures leaked signed invitation data via Playwright stack. | Bounded TERM/KILL/exit before directory removal; failures report only stage and error class. |
| U7 | Standards | Receipt had no commit/version/duration and was written before teardown. | Receipt now records commit, Node, browser, duration, jars, counts, verdict after cleanup. |
| U8 | Spec | Acceptance overstated “S1 acceptance evidence complete.” | Narrowed to tested web/service surfaces; Electron window automation and UI restart/reconnect remain open; T1 parity sign-off and external cryptography review remain human gates. |

Verdict after fixes: PASS for the two-device web UI path.
