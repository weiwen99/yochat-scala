---
version: 1
slug: "ui"
primary_target: "ui"
related_targets: []
---

# Surface: YoChat S1 app

- Scope: one authenticated app surface after the local service is unlocked. Three in-surface screens: unlock/identity, contacts/invitations, chat.
- Mode: Operate.
- Audience and job: two people who already know each other on another channel; each has one device; they need to establish a verified relationship and exchange ordered text without a directory or account server.
- Primary task: unlock the local store, add one contact through an invitation/reply, compare the safety code out of band, then send and read messages.
- Success: a first-time user can see whether a message is only handed to the relay or authenticated by the peer, can copy an invitation, can finish the safety check, and can send after a restart or reconnect.
- Content: S1 labels and states from `PRODUCT.md`; no invented benchmarks or social proof. Screenshot fixtures use a synthetic pair and are labeled as such where they could be mistaken for production data.
- Constraints: WCAG 2.2 AA baseline; system light/dark; keyboard complete path; message text selectable; no network polling in the UI; local service events are pushed.

## Direction contract

THESIS: A private two-person correspondence desk where every message carries its route and its certainty; it refuses the generic chat bubble stack whose only status is a vague "sent".

OWN-WORLD: Score paper, ink, graphite, and a single cinnabar accent; hairline rules instead of shadows; square delivery marks with solid, half-filled, and dotted fills; monospaced safety and route data; a compact humanist sans for the working text.

STORY: The user unlocks a local identity, sees one verified contact and the relay route, reads the delivery state before reading the message, opens the contact ledger to invite someone, and returns to a chat that never pretends a relay handoff is a peer delivery.

FIRST VIEWPORT: A 72px ink rail at the left, a 286px communication list, the conversation in the center, and a 318px communication ledger at the right. The chat header carries the contact and the relay route; each message has a score gutter; the ledger carries the route, safety code, and delivery legend; the composer closes the viewport.

FORM: Code-led; seed:8f6d5ff895dbfe3d. Signature move: the delivery score — a thin vertical rail beside messages whose square marks fill only as far as the truth goes (dotted waiting, half-filled relay handoff, solid peer acknowledgement). Motion is limited to those state fills and the connection dot.

RAISES: From the transit-map challenger, the route must be one legible line with named stops, not a second navigation model. From the boarding-board challenger, a state change must move or fill in place rather than removing and reinserting the row. From the type-specimen challenger, every datum sits on a strict baseline and uses tabular figures.

UNRESOLVED: The final app must decide whether a contact alias is edited in S1 or the channel account is the primary label; the implementation should prefer a local alias if it can be added without widening the protocol.
