# Source map

Line numbers move; use this map to find the review surface, then cite exact lines from the snapshot.

## Core cryptography and protocol

| Concern | File |
| --- | --- |
| JCE wrappers, AEAD, HKDF, hashes, signatures | `core/src/yochat/core/Crypto.scala`, `Jce.scala`, `Key32.scala`, `Nonce.scala` |
| Ed25519/X25519 types | `core/src/yochat/core/Ed25519Private.scala`, `Ed25519Public.scala`, `KeyPairs.scala` |
| Canonical encoding/parsing | `CanonicalWriter.scala`, `CanonicalReader.scala`, `FixedBytes.scala`, `Base64Url.scala`, `ControlExtension.scala` |
| Key-role separation | `KeySeparation.scala`, `SessionKeys.scala` |
| Safety number | `SafetyNumber.scala` |
| Invitation/reply | `Invitation.scala`, `InvitationFields.scala`, `Reply.scala`, `ReplyFields.scala`, `ReplyEnvelope.scala`, `SessionId.scala` |
| Ratchet/wire | `Ratchet.scala`, `RatchetState.scala`, `MessageHeader.scala`, `MessageKind.scala`, `ReceiveError.scala`, `ReceiveResult.scala` |
| First-data binding | `DataPayload.scala` |
| Rekey protocol | `Rekey*.scala` |
| Application state machine | `core/src/yochat/core/app/AppStep.scala`, `AppEvent.scala`, `AppEffect.scala`, `AppState.scala`, `S1Limits.scala` |
| Key/identity persistence | `store/src/yochat/store/Keys.scala`, `StoreCrypto.scala`, `Identity*.scala` |
| Session/message/contact storage | `store/src/yochat/store/SessionRepository.scala`, `MessageRepository.scala`, `ContactRepository.scala`, `InvitationRepository.scala`, `ConversationRepository.scala` |
| Relay boundary | `transport/src/yochat/transport/RelayServer.scala`, `RelayClient.scala`, `RelayPayload.scala` |
| Service/runtime integration | `server/src/yochat/server/AppRuntime.scala`, `AppService.scala`, `HttpApi.scala`, `Main.scala` |

## Tests and models

| Concern | File |
| --- | --- |
| Primitive/vector checks | `core/test/src/yochat/core/VectorsSpec.scala`, `CryptoSpec.scala` |
| Ratchet/ACK/Gap/rekey properties | `core/test/src/yochat/core/RatchetSpec.scala`, `ReplySpec.scala`, `AppRuntimeSpec.scala`, `RekeySpec.scala` |
| Storage envelope/high-water/crash | `store/test/src/yochat/store/*` |
| Relay/reconnect | `transport/test/src/yochat/transport/*` |
| HTTP/service smoke | `server/test/src/yochat/server/HttpApiSpec.scala`, `AppRuntimeSpec.scala` |
| S1 acceptance runner | `server/test/src/yochat/server/e2e/S1E2E.scala`, `E2ESupport.scala`, `FaultRelay.scala` |
| 10k store benchmark | `server/test/src/yochat/server/RestoreBench.scala` |
| Quint model | `models/*.qnt`, `models/check.sh` |
