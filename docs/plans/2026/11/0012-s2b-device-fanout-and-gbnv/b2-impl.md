# Plan 0012 B2 implementation

Status: core fanout envelope implemented; direct runtime wiring is not complete. A2 still receives peer messages only through A1 device-sync.

## Delivered

- `FanoutEnvelope` in `core/s2`: X25519 ephemeral to recipient fanout public, HKDF-SHA256 per message, AEAD with bound sender/recipient/conversation/author seq, Ed25519 sender signature, canonical encoding and open.
- Core test: round-trip, wrong recipient, wrong sender/device, sequence tamper.

## Pending

- Pairing persistence of A2 fanout private and A1 device fanout public.
- `PeerDeviceList` fanout public field.
- B1 send hook and A2 direct receive/ACK.
- `companion_messages.logical_key` dedupe and `delivery_receipts` schema 5.

## Receipts

- `./mill core.test.testOnly yochat.core.s2.S2CoreSpec` PASS (fanout envelope round-trip and negative cases).
