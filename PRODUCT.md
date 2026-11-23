# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Existing repository: Scala 3 + Mill + ScalaJS/Laminar for the interface, Pekko HTTP for the local service, Electron for the desktop shell. Web and Electron share one interface; Electron adds the desktop shell, single-instance behavior, and native notifications. This was confirmed with the user for M4.

## Users

Two people who already know each other on another channel, each with one device. They use YoChat for private one-to-one conversation, not for discovery, broadcast, or group chat. The current S1 test pair is the author and one friend; the interface must still make the invitation and safety-code steps understandable without a manual.

## Product Purpose

YoChat (有聊) is a friend-to-friend, end-to-end-encrypted messenger. It lets two people establish a relationship from a signed invitation and reply, verify each other out of band, and exchange ordered text messages that survive restart and reconnect. Success means a person can add one friend, send a message, restart, lose the network, reconnect, and continue without a command line.

## Positioning

There is no account server and no directory. The only shared infrastructure is a minimal relay that forwards opaque ciphertext; the two devices hold the relationship, keys, and message history. The interface must say this plainly instead of claiming full peer-to-peer operation. Message delivery distinguishes "handed to the relay" from "authenticated by the peer"; the latter is the only delivery claim the product makes.

## Operating Context

- Two people, each with one device, using a local desktop app or a browser pointed at the local service.
- Adding a friend happens through a copied invitation text and a copied reply, often pasted through an existing channel such as WeChat.
- The safety code is compared out of band before user text is sent or shown.
- The app can restart; unsent and unconfirmed messages must remain visible and continue automatically.
- The relay may be unreachable. The interface shows connection and waiting states without turning them into errors that demand a decision.

## Capabilities and Constraints

- S1 scope: one user, one contact, one device per user; invite/reply; single conversation; write-before-send; restart; reconnect; authenticated data ACK and gap recovery; text only.
- Not in S1: multiple devices, history sync, revocation propagation, groups, file transfer, read receipts, message editing, reactions, contact deletion/blocking.
- The relay sees device endpoints, timing, frame counts, and opaque ciphertext; it does not see message content.
- The local store is encrypted. The app has an unlock screen; the process must not expose message content before unlock.
- A message is shown as sent only after the local write; "delivered" waits for an authenticated peer ACK. A retry budget exists and failure is visible, never silently dropped.
- No UI timer may poll network frames. The service pushes state changes; the UI may use a local clock only for relative time.
- The UI must keep message text selectable with mouse and keyboard; no global `user-select: none`.
- The interface is Chinese-first. The product name is YoChat (有聊). Technical identifiers and protocol jargon stay out of the primary copy.
- The interface must show the relay dependency and connection state. It must not claim that messages travel directly between devices.
- If the local service is unavailable, the UI must say what is unavailable and how to recover; it must not fabricate a connected state.

## Brand Commitments

- Name: YoChat (有聊).
- Voice: direct, calm, specific. Prefer "已交给中继 / 已送达 / 等待对方" over vague "发送中".
- The product is private by construction; copy must not imply that the relay cannot see metadata.
- The user asked for a modern interface, not a generic chat clone; the visual system should feel deliberate rather than assembled from stock components.

## Evidence on Hand

- Product truth comes from `AGENTS.md`, `docs/adr/0003-v1-scope.md`, `docs/adr/0008-s1-delivery-ack-and-window.md`, `docs/spec/product/`, and the M3 runtime/store implementation.
- There are no real customer testimonials, benchmarks, or production usage numbers. Do not invent them.
- Any demo conversation shown for a screenshot or fixture is synthetic and must be labeled as such in the fixture or surrounding copy.

## Product Principles

1. Tell the truth about the route: local → relay → peer, with delivery separately acknowledged.
2. Keep the relationship small: one person, one conversation, no directory or discovery.
3. Make the next action obvious; do not turn waiting, retry, or reconnect into a decision the user must make.
4. Protect the content: no plaintext in logs, diagnostics, notifications, or the locked screen.
5. Restraint over decoration; every visual element should help someone understand, trust, or act.

## Accessibility & Inclusion

Target WCAG 2.2 AA for text, controls, focus indicators, and non-text interface elements. Both light and dark appearances must follow the system setting. Keyboard access must cover the full path from unlock through sending; focus must remain visible. The S1 delivery does not claim a complete formal accessibility audit.
