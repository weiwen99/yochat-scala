# S1 两方 Double Ratchet 加密方案

**状态**: accepted (2026-11-20；内部安全评审 PASS WITH FIXES，revision 3 已封口；外部密码学评审仍是发布门)

**前序**: ADR-0002（技术栈/A-first/relay-only）、ADR-0003（S1 范围）、ADR-0004（测试/性能门）、ADR-0005（A-first spike）。ADR-0005 已接受并记录 A-first 可行性，本 ADR 用 0006 号，避免改写已接受的 ADR-0005；最终 S1 方案以本 ADR 为准。

## 规模与范围

| 维度 | 需求 | 本 ADR | 说明 |
| --- | --- | --- | --- |
| 新表 | 0（本 ADR 只做已承诺的 E2E/FS） | 1（`sessions`） | 会话加密状态必须有持久落点；状态整体加密，不落明文密钥 |
| 核心组件 | 1（`core` 加密状态机） | 1 | 纯状态转换；IO 由执行器承担 |
| 配置 | 6 | 6 | `MAX_SKIP_GAP=64`、`MAX_SKIP_TOTAL=256`、`MAX_RETIRED_RATCHETS=64`、`MAX_REKEY_FAILURES=3`、`REKEY_COOLDOWN=60s`、`MAX_REKEY_ATTEMPTS=3` |
| 工程师日 | 4–6（估算） | 4–6 | 实现、测试向量、状态/重启测试；外部密码学评审另计 1–2 人日 |
| 发布波次 | 1 | 1 | S1 一次交付；没有新增产品功能或协议路径 |

不新增群聊、多设备、历史同步或文件传输能力；S1 只有两个用户、每个用户一台设备、relay-only。

## 背景

ADR-0005 的 spike 已证明 JDK 25 JCE 的 Ed25519、X25519、ChaCha20-Poly1305 和本地 HKDF 足够跑通邀请/应答与中继。但 spike 的会话只是**两条对称 hash chain**：没有 DH ratchet，前向保密有限，也不提供 break-in recovery；它明确不是最终协议。

S1 需要：

- 邀请/应答一次性密钥、签名、双方渠道账号绑定；
- 双方之间的端到端加密、消息签名、篡改/重放拒绝；
- 乱序、离线、重启后的状态恢复；
- 前向保密；通道无法解开时有界重建；
- 不把正文、密钥、联系人名或公钥写进日志/崩溃报告。

本 ADR 决定 S1 的最终加密方案，并完成 ADR-0002 要求的 D2 复用分析。

## 决策

### 1. 方案族

采用 **Signal Double Ratchet（spec revision 4, 2025-11-04）的两方数据路径**：

- 一条 root chain + 两条方向独立的 KDF chains；每个消息使用唯一 message key。
- 收到新的 ratchet 公钥时执行 DH ratchet；发送/接收链由 root KDF 输出。
- 保留 `N`/`PN` 与有界 skipped-key 表，支持乱序；不使用群 sender key、不实现 header encryption。
- 邀请/应答是本项目的 X3DH-like 一次性密钥握手，不声称与 Signal 的 X3DH 或 wire format bit-for-bit 兼容。

### 2. 原语与参数

全部通过 JDK 25 JCE，不自写原语：

- 身份/设备签名：Ed25519。
- DH：X25519；拒绝全零共享密钥。
- 根链 KDF：HKDF-SHA256（RFC 5869），本地实现，已有 spike 测试。
- 链 KDF：HMAC-SHA256；`messageKey = HMAC(chainKey, 0x01)`，`nextChainKey = HMAC(chainKey, 0x02)`。
- AEAD：ChaCha20-Poly1305，256-bit key；每个 message key 只使用一次；96-bit random nonce 由 `SecureRandom` 生成并随消息传输。
- 随机：`SecureRandom`；一次性私钥、ratchet 私钥、rekey id 都由它生成。
- 常量：`MAX_SKIP_GAP=64`（单次缺口上限）、`MAX_SKIP_TOTAL=256`（每会话 skipped-key 总上限）、`MAX_RETIRED_RATCHETS=64`、`MAX_REKEY_FAILURES=3`、`REKEY_COOLDOWN=60s`、`MAX_REKEY_ATTEMPTS=3`。

### 3. 身份、邀请与应答

#### 3.1 密钥

- **用户身份密钥**：Ed25519，长期；签邀请/应答；安全码由双方用户公钥派生。
- **设备密钥**：Ed25519，每台设备一把；由用户密钥在邀请/应答中签名绑定；签每条消息和 rekey 消息。
- **一次性 X25519 密钥**：邀请方 `A_eph`；应答方 `B_eph`；只用于邀请/应答，完成后删除私钥。
- **初始 ratchet 密钥**：邀请方 `A_ratchet0`、应答方 `B_ratchet0`，都是 X25519；公钥进入邀请/应答的签名载荷。

身份密钥不用于 DH；设备密钥不用于 X25519；一次性密钥不复用。

#### 3.2 邀请

邀请是带外文本，规范化字段：

```text
version=1
userPub            Ed25519 32B
devicePub          Ed25519 32B
channelLabel       UTF-8
channelAccount     UTF-8
expiresAt          i64 (unix ms)
ephPub             X25519 32B (A_eph)
ratchetPub         X25519 32B (A_ratchet0)
note               UTF-8
```

- `signature = Ed25519(A_user_priv, "yochat-s1-invite" || canonicalFields)`。
- `invitationHash = SHA256("yochat-s1-invite-hash" || canonicalFields)`。
- 只做定向邀请；接收方账号不符时进待确认并标记。
- 邀请有效期由调用方传入；核心不读墙钟；过期后拒绝。

#### 3.3 应答

应答方生成 `B_eph`、`B_ratchet0`，规范化字段：

```text
version=1
invitationHash     32B
userPub            Ed25519 32B
devicePub          Ed25519 32B
channelLabel       UTF-8
channelAccount     UTF-8
ephPub             X25519 32B (B_eph)
ratchetPub         X25519 32B (B_ratchet0)
note               UTF-8
```

- `replySig = Ed25519(B_user_priv, "yochat-s1-reply" || canonicalReplyFields)`。
- `shared = X25519(B_eph_priv, A_eph_pub)`。
- `replyKey = HKDF-SHA256(ikm=shared, salt=invitationHash, info="yochat-s1-reply", L=32)`。
- `replyEnvelope = (version, B_eph_pub, nonce, ciphertext)`；
  `ciphertext = AEAD(replyKey, nonce, aad="yochat-s1-reply-aad" || version || invitationHash || B_eph_pub, plaintext=canonicalReplyFields || replySig)`。
- `replyHash = SHA256("yochat-s1-reply-hash" || canonicalReplyEnvelope)`。
- `sessionId = SHA256("yochat-s1-session" || invitationHash || replyHash)`。
- `rootKey0 = HKDF-SHA256(ikm=shared, salt=SHA256(invitationHash || replyHash), info="yochat-s1-root", L=32)`。
- `rekeyBase0 = HKDF-SHA256(ikm=shared, salt=sessionId, info="yochat-s1-rekey-base", L=32)`；`rekeyBase` 是整会话共享、不随 root 推进的 rekey 根，只用于 rekey 消息密钥与 new-root salt，不能替代 Double Ratchet 的 root。
- A 解开应答、验 `replySig`、核对 `invitationHash`/账号/有效期，并校验应答 envelope 的 `version`/`B_eph_pub` 与签名载荷中的字段一致；B 在发出应答时完成本方确认，A 在第一条经认证操作后确认。
- 邀请是一次性接受：A 的 `invitations.state` 只允许一个有效应答；成功后标记 consumed，重复/重放应答拒绝；B 每份邀请只生成一个应答。
- A 成功后删除 `A_eph_priv`；B 发出应答后删除 `B_eph_priv`；两把一次性私钥不再出现在状态、日志或备份。
- 安全码：沿用旧 S1 的固定构造：`IKM = sorted(userPubA, userPubB)`（各 32B 原始编码按字节序拼接），`salt = empty`，`info = "yochat/safety-number/v1"`，`HKDF-SHA256` 输出 `L=16`；取 128-bit 无符号十进制 **39 位零填充**，前 20 位/后 19 位两段，按用户公钥字节序决定本方念哪一段；展示每 5 位分组。它用于带外人工比对，不是密钥，也不替代签名。旧测试的 39 位/两段/零填充性质由 `VEC-safety` 保留。
- **安全门**：握手完成后 `contacts.safety_verified=false`；在双方完成安全码比对并置 true 前，不发送/不呈现用户正文，只允许控制/rekey。第一条经认证的业务消息必须携带双方渠道账号与安全码绑定的断言；UI 不把未验证关系当成可聊天关系。

#### 3.4 Canonical encoding

- 所有签名/哈希对象是**固定顺序、长度前缀的二进制 canonical 字节**，不是 `key=value` 文本，也不是 JSON：`u8 version`、固定字段顺序、`i64/u64` 大端、每个 byte-string/string 前加 `u32` 长度。
- 字段不得重复/缺省；`note` 只允许规范 UTF-8，拒绝 CR/LF/NUL 和其他控制字符；账号/渠道标签做 Unicode NFC 归一化后再编码。
- 邀请文本是 `base64url(canonicalInvitation || sig)`；应答文本是 `base64url(canonicalReplyEnvelope)`。上面的字段块是展示/解析结果，不是签名输入。
- `invitationHash = SHA256("yochat-s1-invite-hash" || canonicalInvitation)`；`replyHash = SHA256("yochat-s1-reply-hash" || canonicalReplyEnvelope)`；签名对象同理带各自 domain 前缀。解析器拒绝非规范编码，不允许“解析后再重序列化”改变签名输入。

#### 3.5 初始 ratchet

双方都持有 `rootKey0`、`A_ratchet0` 公钥、`B_ratchet0` 公钥。

- 邀请方 A（initiator）：`DHs=A_ratchet0`，`DHr=B_ratchet0`；
  `(rootKey1, CKs) = KDF_RK(rootKey0, DH(A_ratchet0_priv, B_ratchet0_pub))`；
  `CKr=None`，`Ns=0`，`Nr=0`，`PN=0`。
- 应答方 B（responder）：`DHs=B_ratchet0`，`DHr=A_ratchet0`；在第一次发送或第一次接收时执行同一 DH 步骤：
  `(rootKey1, CKr) = KDF_RK(rootKey0, DH(B_ratchet0_priv, A_ratchet0_pub))`；
  生成 `B_ratchet1`，`(rootKey2, CKs) = KDF_RK(rootKey1, DH(B_ratchet1_priv, A_ratchet0_pub))`；
  `DHs=B_ratchet1`，`PN=0`，`Ns=0`。
- A 收到 B 的 `B_ratchet1` 后按 Double Ratchet 做 DH 步骤；双方 root/chain 对齐。
- 允许 B 在 A 第一条消息之前先发：B 在第一次发送时执行上述初始化，第一条 B 消息携带 `B_ratchet1`；A 收到后执行 DH 步骤。该 B-first 路径必须在向量和 Q5 中覆盖（Signal spec 的简化版默认 A 先发）。

### 4. Double Ratchet 状态与转换

状态字段（整体加密持久化）：

```text
sessionId
peerUserPub, peerDevicePub
rootKey                 32B
rekeyBase               32B (handshake-derived, session-stable)
rekeyEpoch              u64 (next attempt number)
lastRekeyEpoch          u64 (highest accepted request epoch)
DHs                     X25519 keypair (private/public)
DHr                     X25519 public or None
CKs, CKr                32B or None
Ns, Nr, PN              u64
skipped                 Map[(DHrPub, N) -> messageKey], max MAX_SKIP_TOTAL
retiredDh               Set[X25519 public], max MAX_RETIRED_RATCHETS
rekey                   RekeyState or None (R1/R2 private, rekeyId, epoch, pending new state)
```

KDF：

- `KDF_RK(rk, dh)`: `okm = HKDF-SHA256(ikm=dh, salt=rk, info="yochat-s1-rk", L=64)`；返回 `(okm[0..32], okm[32..64])`。
- `KDF_CK(ck)`: `(HMAC-SHA256(ck, 0x02), HMAC-SHA256(ck, 0x01))`，返回 `(nextCk, messageKey)`。
- 每次 DH ratchet 按 Signal spec 的顺序做两次 `KDF_RK`：先产生接收链，再生成新 `DHs` 并产生发送链；root 连续推进两次。
- 新 ratchet 公钥必须由 X25519 解析且非全零；否则拒绝。

发送：

1. 若 `CKs=None`，必须先完成 DH ratchet。
2. `(CKs, mk) = KDF_CK(CKs)`；`n=Ns`；`Ns += 1`。
3. 生成 12B random nonce；构造 header（见 4.1）。
4. `ct = AEAD(mk, nonce, aad=header, plaintext)`。
5. `sig = Ed25519(devicePriv, "yochat-s1-msg" || header || ct)`。
6. 输出 `header || ct || sig`（`nonce` 已在 header 中）；状态只在持久化事务成功后推进。

接收：

1. 验 `devicePub` 属于当前关系；验签名；验 `sessionId`；失败即拒绝，不响应，不推进状态。
2. **按 kind 准入**：`kind=1` 数据帧由执行器按 `messages` 的 `(session_id, device_pub, seq)` 唯一索引查重；已存在则 `DuplicateSeq`/`Replay`，丢弃，不计 AEAD 失败。`kind=2/3` 控制帧按 `(sessionId, kind, rekeyEpoch, rekeyId)` 在 rekey 状态里幂等处理，不写 `messages`、不占应用 `seq`；控制帧 `header.seq=0` 且不参与应用排序。
3. **再查 skipped key**：`TrySkippedMessageKeys(header)` 查 `skipped[(header.dhPub, header.n)]`。命中则用该 message key，删除该条并跳过 ratchet；未命中才继续。
4. 若 `header.dhPub == DHr`：当前接收链，跳到 7。
5. 若 `header.dhPub` 在 `retiredDh`：返回 `StaleRatchet`（旧链重放/过晚），不计 AEAD 失败，不推进状态。
6. 否则是新 ratchet：
   - 对当前接收链 `SkipMessageKeys(header.pn)`；
   - `PN=Ns`；`Ns=0`；`Nr=0`；`oldDh=DHr`；`DHr=header.dhPub`；把 `oldDh` 加入 `retiredDh`（超过 64 淘汰最旧）；
   - `(rootKey, CKr) = KDF_RK(rootKey, DH(DHs_priv, DHr))`；
   - 生成新 `DHs`；`(rootKey, CKs) = KDF_RK(rootKey, DH(DHs_priv_new, DHr))`。
7. 若 `header.n < Nr`：skipped 未命中，返回 `Replay`，不推进、不计失败。若 `header.n > Nr`：`SkipMessageKeys(header.n)`；若 `header.n - Nr > MAX_SKIP_GAP` 或 `skipped.size + (header.n - Nr) > MAX_SKIP_TOTAL`，返回 `GapTooLarge`，不推进状态。
8. `(CKr, mk) = KDF_CK(CKr)`；`Nr += 1`。
9. `plaintext = AEAD_open(mk, nonce, aad=header, ct)`；失败则丢弃候选状态，返回 `InvalidCiphertext`；`kind=2/3` 的失败**永不**计入 `MAX_REKEY_FAILURES`，`kind=1` 只在该帧为当前接收链（`dhPub == DHr`）且新 `seq` 时计入。
10. 只有 1–9 全部成功才提交候选状态；任何失败都不持久化 skipped/ratchet 推进。

`TrySkippedMessageKeys` 必须在 DH ratchet 之前执行；`retiredDh` 在 DH 之前检查，避免旧链重放触发假 ratchet/AEAD 失败。旧链晚到消息若有 skipped key 可解；没有则 `StaleRatchet`，不计入 rekey 失败计数。

`SkipMessageKeys(until)`：`while Nr < until` 将 `(DHr, Nr)` 的 message key 存入 skipped，`Nr += 1`；`CKr=None` 时只允许 `until == Nr`（即 `pn=0`），否则 `GapTooLarge`；受 `MAX_SKIP_GAP`/`MAX_SKIP_TOTAL` 硬上限；不做截断。跳过键在成功解密后删除；不使用就随状态加密落盘。

#### 4.1 消息 header

canonical、作为 AEAD AAD 和签名输入；`kind=1` 使用下列 base header，`kind=2/3` 在 nonce 后追加 control extension：

```text
version            u8 = 1
kind               u8 = 1 data | 2 rekey-confirm | 3 rekey-confirm-ack
sessionId          32B
devicePub          Ed25519 32B (sender device)
dhPub              X25519 32B (sender's current ratchet public)
pn                 u64
n                  u64
seq                u64 (per-author application sequence)
nonce              12B
[仅 kind=2/3] rekeyEpoch  u64
[仅 kind=2/3] rekeyId     16B
```

- `kind`、控制扩展的 `rekeyEpoch`/`rekeyId` 都进入 AEAD AAD 和签名；`RekeyConfirm`/`Ack` 使用同一新会话的 header，应用载荷为空。
- `seq` 是应用层按作者顺序/去重的序号，属于 conversation、每作者严格单调，**跨 rekey 不重置**；`n` 是 ratchet 链内序号；两者独立，均不得省略。控制帧 `seq=0` 不参与应用排序。
- header 不含用户公钥/联系人名/正文；中继只能看到设备公钥、`sessionId`、ratchet 公钥、计数和长度。
- 每条消息的 `devicePub` 必须等于关系里绑定的设备公钥；不匹配即拒绝。
- `nonce` 是 96-bit `SecureRandom`；message key 唯一且只用一次。

#### 4.2 消息签名

- 每一条关系建立后的消息都由发送方**设备密钥**签名；设备密钥由用户密钥在邀请/应答中签名绑定。
- 签名覆盖 `"yochat-s1-msg" || header || ciphertext`；验签先于 AEAD。
- 签名失败是认证失败，不是重建触发；不回包、不推进状态。

#### 4.3 控制帧

`kind=2/3` 的 `RekeyConfirm`/`RekeyConfirmAck` 使用新会话的 header/AEAD/签名，但：

- 不写 `messages`，不占应用 `seq`；`header.seq=0` 只为固定 base header 长度，接收方忽略。
- `rekeyEpoch`/`rekeyId` 在 header 的 control extension 中，幂等键为 `(sessionId, kind, rekeyEpoch, rekeyId)`；重复到达时重新发送对应 ack 或幂等忽略，不重复推进 rekey。
- 重试用新的 `n`/message key，不重放同一密文帧；控制帧 AEAD 失败不计入数据帧失败计数。

### 5. 持久化、事务与锁序

新增 `sessions` 表：

| 列 | 说明 |
| --- | --- |
| `id` | `sessionId`，主键 |
| `peer_user_pub`, `peer_device_pub` | 关系绑定；打开状态时校验 |
| `state` | 加密后的完整会话状态 blob（root/ratchet/chain/skipped/rekey） |
| `state_version` | 单调版本；与 `meta` 高水位比较，拒绝回滚 |
| `generation` | 锁/身份代次；旧代次结果不落地 |
| `updated_at` | 最后提交时间 |

- `state` 整体加密；具体用 SQLCipher 还是应用层 AEAD 由 ADR-0007（storage，待写）决定。无论哪种，密钥、root、chain、skipped key 不以明文列出现。
- `state` 的 AEAD AAD 必须绑定 `(id, peer_user_pub, peer_device_pub, generation, state_version)`；`meta` 保存单调 `state_version` 高水位（优先 keychain，退而求其次 DB `meta`），打开时低于高水位即 `StaleGeneration`，拒绝加载。`generation` 递增同时作废旧 state。整库+meta 同时回滚的本地文件攻击若无法由 keychain 高水位覆盖，登记为 ADR-0007 的已知约束。
- `messages` 增加 `session_id`、`device_pub` 和 `wire`（待发/已发的完整 `header || ct || sig` 字节）；`(session_id, device_pub, seq)` 唯一。`seq` 是**每作者每会话**的序号，两个方向各自从 1 开始，跨 rekey 不重置。重发/重连原样重发 `wire`，禁止用已消费 chain key 重新加密或重新签名；已分配 `n` 的消息不得重加密。
- 单一执行器写状态；一次消息操作把 `sessions` 状态、`messages` 行和必要的 `meta` 计数器放在**同一个 SQLite 事务**。锁序：`sessions` → `messages` → `meta`；没有第二个业务写者，不使用嵌套事务。待发/重发状态在 `messages.state` + `messages.wire`，不新增 outbox 表。
- 入站准入在解密前按 `(session_id, device_pub, seq)` 查重；已存在即 `DuplicateSeq`/`Replay`，不进入密码状态、不计 AEAD 失败。
- 事务失败/崩溃/取消：旧状态保留，候选状态和 skipped key 不发布；重启后从已提交状态继续，不复用 message key。
- 锁定/身份替换：`generation` 递增；旧代次的在线结果/迟到结果不落地；会话状态不跨身份复用。
- 日志/崩溃报告：只记录错误码/会话 id 的不可逆短标识；不记录正文、密钥、root、chain、联系人名或公钥。

### 6. 重建（rekey）

触发：同一会话连续 `MAX_REKEY_FAILURES=3` 次**已验签、`seq` 未见过、且 `dhPub == DHr`（当前接收链）** 的 AEAD 失败（或用户显式操作）。任何需要改变 ratchet 的帧失败、签名失败、`seq` 重复、`StaleRatchet`、重放、乱序缺口和控制帧失败都不计数；乱序先走等待/重发。成功解密或收到新 `seq` 的明文时清零失败计数。新 ratchet 的失败只返回 `InvalidCiphertext`，由用户或对端签名请求触发 rekey。

`RekeyRequest`：

```text
type=rekey-request
sessionId
rekeyId     16B random
R1Pub       X25519 32B
devicePub   Ed25519 32B
nonce       12B
ct          AEAD(rekeyRequestKey, ...)
sig         Ed25519(devicePriv, transcript)
```

- `rekeyRequestKey = HKDF-SHA256(ikm=rekeyBase, salt=rekeyEpoch || rekeyId, info="yochat-s1-rekey-request", L=32)`。
- `transcriptReq = "yochat-s1-rekey-request" || type || sessionId || rekeyEpoch || rekeyId || R1Pub || devicePub || nonce`；AEAD AAD 和签名都覆盖 `transcriptReq`。
- 接收方验关系/签名/当前会话后，**先查 pending `(rekeyId, rekeyEpoch)`**：命中则重发已生成的 accept，不重复重置；未命中才要求 `rekeyEpoch > lastRekeyEpoch`，否则 `Replay`/静默丢弃，不生成 accept；接受后持久化 `lastRekeyEpoch = rekeyEpoch`。未决期结束后旧 id 不再缓存，旧 epoch 仍被 `lastRekeyEpoch` 拒绝。

`RekeyAccept`：应答方生成 `R2`；用
`rekeyAcceptKey = HKDF-SHA256(ikm=rekeyBase, salt=rekeyEpoch || rekeyId, info="yochat-s1-rekey-accept", L=32)`
加密/签名；包含 `R2Pub`、`rekeyId`、`devicePub`、nonce、sig。发起方只接受 `rekeyEpoch/rekeyId` 与本地 pending attempt 完全一致的 `RekeyAccept`，否则 `RekeyPending`/忽略。`transcriptAccept = "yochat-s1-rekey-accept" || type || sessionId || rekeyEpoch || rekeyId || R1Pub || R2Pub || devicePub || nonce`；AEAD AAD 和签名都覆盖 `transcriptAccept`。

新会话：

- `newRoot = HKDF-SHA256(ikm=X25519(R1_priv, R2_pub), salt=SHA256(rekeyBase || rekeyEpoch || rekeyId), info="yochat-s1-rekey-root", L=32)`。
- `newSessionId = SHA256("yochat-s1-rekey-session" || oldSessionId || rekeyEpoch || rekeyId || R1Pub || R2Pub)`。
- `newRekeyBase = HKDF-SHA256(ikm=X25519(R1_priv, R2_pub), salt=newSessionId, info="yochat-s1-rekey-base", L=32)`；新会话的 `rekeyBase` 从这里开始，`rekeyEpoch=0`。
- `R1_priv` 在发 `RekeyRequest` 前持久化；同一 `rekeyId` 未决期间不得重新生成；切换或放弃后删除。`R2_priv` 在发出 `RekeyAccept` 前持久化，切换或放弃后删除；两者都比照 `A_eph/B_eph` 的生命周期。
- `lastRekeyEpoch` 记录已接受的最高请求 epoch；发送方每次新 attempt 取 `rekeyEpoch = max(localNextAttempt, lastRekeyEpoch + 1)` 并回写本地计数（失败重试也算一次），避免对端已接受后本端 epoch 被压死；`rekeyId` 随 epoch 唯一且永不复用；newSessionId/newRoot salt 都含 epoch；新会话的 `rekeyEpoch=0`、`lastRekeyEpoch=0`。
- 以 R1/R2 作为新的初始 ratchet 密钥对，重新初始化 Double Ratchet；发起方为 initiator。
- **提交阶段**：发起方收到 `RekeyAccept` 后双方仍保留旧会话为 read-only（旧状态不再发送新消息，但可解密在途旧帧）。**只有发起方**在新会话发 `kind=2` 的 `RekeyConfirm`（header 的 control extension 含 `rekeyId`/`rekeyEpoch`，应用载荷为空）；应答方验签/AEAD 后把新会话标为 active，回 `kind=3` 的 `RekeyConfirmAck`；发起方收到 ack 后 active。应答方不发送 `kind=2`，避免双方互等。pending 的新会话状态始终接受入站数据/控制帧并提交；`active` 只约束发送和旧 `sessions` 行删除。双方 active 后删除旧行，晚到旧消息按丢失处理；不再使用旧状态发送。
- `RekeyConfirm/Ack` 丢失时在新会话重试（新的 `n`/message key，不重放同一帧）；`REKEY_COOLDOWN=60s` 内未完成则保留旧会话并提示/重试；超过 `MAX_REKEY_ATTEMPTS=3` 则提示重新配对。任何时刻不静默切换或静默丢消息。
- 旧 `rootKey`/`rekeyBase` 丢失或状态损坏时不能 rekey，只能重新走邀请/应答（用户重新配对）；这是 S1 的已知约束。
- 冷却 `REKEY_COOLDOWN=60s`，每会话最多 `MAX_REKEY_ATTEMPTS=3` 次；超过则提示用户，不再自动重试。

### 7. 错误语义

错误码至少：

`InvalidInvitation`, `InvalidReply`, `InvalidSignature`, `InvalidCiphertext`, `DuplicateSeq`, `Replay`, `StaleRatchet`, `GapTooLarge`, `UnknownSession`, `StaleGeneration`, `RekeyRequired`, `RekeyPending`, `RekeyFailed`。

- 未授权/无匹配关系：静默丢弃，不产生应用层响应。
- 签名/AEAD/重放/缺口失败：不推进状态；只返回错误码。
- `GapTooLarge`：应用层先等待/请求重发；超过有界重试再考虑 rekey。
- 不把错误原文、密文、密钥或联系人名写入日志。

### 8. 验证门

以下全部有固定命令/回执才算通过：

1. **KAT**：HKDF RFC 5869、X25519 RFC 7748、Ed25519 RFC 8032、ChaCha20-Poly1305 RFC 8439、HMAC-SHA256。
2. **Ratchet 向量**：固定 root/rekeyBase/DHs/DHr/CKs/CKr/Ns/Nr/PN/skipped/retiredDh，覆盖正常、乱序、跳号、DH ratchet、B-first、旧链重放、重启；与独立参考实现或手算向量对拍。
3. **Rekey 向量**：从握手 `rekeyBase0` 出发，覆盖 mid-ratchet、RekeyRequest/Accept、Confirm/Ack、重试、重启、失败回退、旧 `RekeyRequest`/重复 `RekeyConfirm` 拒绝/幂等、对端已接受后本端发起（`rekeyEpoch = max(localNextAttempt, lastRekeyEpoch + 1)`）；验证双方 `newRoot`/`newRekeyBase` 收敛，而不是依赖两侧 root 相等。
4. **属性测试**：message key 不复用；篡改/签名/重放必拒；skipped 上限；`retiredDh`/`seq` 去重；状态序列化往返；状态回滚被拒；事务失败不发布候选；重发原样 `wire`。
5. **Quint**：Q1 邀请/应答、Q5 重建的协议性质；Double Ratchet 内部只由 KAT/属性测试验证，不用 Quint 冒充密码证明。
6. **E2E**：100 条消息 + 重连；1,000 条 <5s；重启后继续；日志不含秘密；安全码未验证时不发正文。安全门是 S1 显式安全增强，`E2E#1/#2/#3` 与 `test-parity` 的 `FXAWEFJ6Q0S22E5S` 行同步。
7. **外部密码学评审**：发布门；本 ADR 的接受不等于已证明安全。

### 9. D2 复用分析

| 候选 | 事实 | 判定 |
| --- | --- | --- |
| `signalapp/libsignal-protocol-java` | GPLv3；2022-02 归档；Java | 拒绝：许可证不兼容、已归档 |
| `signalapp/libsignal` | AGPL-3.0；Rust 核心 + Java 绑定；活跃 | 拒绝：AGPL + Rust，违反 A-first 产品代码边界 |
| `VirgilSecurity/virgil-ratchet-kotlin` | BSD-3（README）；Kotlin/JVM；依赖 Virgil PFS/Cards 服务和 native `com.virgilsecurity.crypto:ratchet` | 拒绝：需要外部账号/PFS 服务和 native 依赖，不适合自包含 relay-only S1 |
| `rweather/noise-java` | MIT；纯 Java；Noise 参考实现 | 拒绝：Noise 是握手/传输框架，不提供异步乱序 Double Ratchet；邀请/应答已自建 |
| `Google Tink` / BouncyCastle | 原语库，不是 ratchet | 不作为会话方案；原语用 JDK JCE |
| `p2panda-encryption` | Rust 群棘轮 | 拒绝：Rust/群语义，S1 不需要 |

结论：没有一个候选同时满足「宽松许可证 + 自包含 JVM + 无外部账号/PFS 服务 + 异步乱序 Double Ratchet」。因此实现 Signal spec 的两方子集，原语全部用 JCE；不手写原语；以测试向量、属性测试和外部评审补偿实现风险。

### 10. 备选与否决

- **只保留 spike 的对称 hash chain**：没有 DH ratchet/PCS；不满足最终方案；拒绝。
- **Noise KK/XX 直接当会话**：需要在线握手，不解决离线/乱序/长期异步；拒绝。
- **群棘轮/DCGKA**：S1 两方，不需要；S2 再设计。
- **PQ/TreeKEM**：S1 不要求；后续版本。
- **header encryption**：S1 不做；中继可见 header 元数据，列入已知约束。

## 已知约束

- 中继可见：发送/接收设备公钥、`sessionId`、ratchet 公钥、`pn`/`n`/`seq`、消息长度、时间；不可见正文、message key、root、chain、联系人名或用户公钥。
- 不做 header encryption；不做流量填充。
- 不做群聊/多设备/历史同步；S2 的多设备需要每设备会话或群方案，本 ADR 不假装已满足。
- 不做后量子；不做设备吊销传播。
- 消息签名提供作者绑定/不可否认性；不是可否认协议。
- rekey 依赖会话级 `rekeyBase`/`rootKey`；丢失/损坏只能重新配对；旧会话晚到消息可能丢失。
- `retiredDh` 只保留最近 64 个 ratchet 公钥；更旧的、且没有 skipped key 的晚到消息会 `StaleRatchet`，由发送方在新会话重发。
- 若本机同时回滚数据库与 `meta` 高水位且没有 keychain 高水位，状态回滚无法检测；ADR-0007 必须决定并记录。
- 无外部密码学评审；发布前必须补。

## 后果

- 新增 1 张 `sessions` 表、1 个核心加密状态机、6 个上限配置；S1 的功能面不增加。
- 每条消息增加约 64B 签名和 header 开销；1,000 条仍应在 ADR-0004 的 <5s 门内，M1 必须用固定回执验证。
- Double Ratchet 的 DH 步骤在方向切换时发生，不是每条消息一次 X25519；性能风险可控，但必须有基准。
- S2 可在每设备会话之上扩展；本方案不承诺群棘轮。
- 生产实现不得复用 spike 的可变链；spike 只保留为原语/传输可行性证据。

## 参考

- Signal, *The Double Ratchet Algorithm*, revision 4, 2025-11-04: <https://signal.org/docs/specifications/doubleratchet/>
- RFC 5869 (HKDF), RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 8439 (ChaCha20-Poly1305)
- `signalapp/libsignal-protocol-java`: <https://github.com/signalapp/libsignal-protocol-java>
- `signalapp/libsignal`: <https://github.com/signalapp/libsignal>
- `VirgilSecurity/virgil-ratchet-kotlin`: <https://github.com/VirgilSecurity/virgil-ratchet-kotlin>
- `rweather/noise-java`: <https://github.com/rweather/noise-java>
- `com.virgilsecurity:ratchet` POM: <https://repo1.maven.org/maven2/com/virgilsecurity/ratchet/0.4.2/ratchet-0.4.2.pom>
