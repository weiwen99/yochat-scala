# Plan 0001 implementation review — core crypto

- 对象: 生产 `core/` 实现与测试，父提交 `64c5039`；本记录随同一提交。
- 审查轮次: 1 轮安全审查（read-only） + 1 轮 Standards 自审；上限 7 轮。
- 审查方法: ADR-0006、`design.md`、`docs/agents/engineering.md`、`docs/agents/verification.md`、`scala-best-practices`；不把自审写成密码学证明。
- 新架构约束: 副作用默认 cats-effect 3 `IO`；`Future` 只在 Pekko 适配层。`core/` 纯函数，不含 `IO`/`Future`/随机/时钟。

## 覆盖

- Standards: `./check.sh` 全绿；`core/src`/`core/test/src` 与 CFA 一致；无 `var`/`while`/`null`/unsafe `.get`/`sealed trait`；公开成员显式类型；`core/` 无 `Future`、`SecureRandom`、`KeyPairGenerator`、裸时钟。
- Spec: ADR-0006 的邀请/应答、canonical、初始 ratchet、B-first、Double Ratchet、跳号/退役、去重/重放、失败计数、rekey request/accept/confirm/ack、状态 blob、安全门。存储 AAD/回滚与外部密码学评审不在本提交。

## 发现与关闭

| ID | 严重度 | 发现（位置） | 影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- |
| R1 | BLOCKER | `acceptConfirm` 只接受 Pending，ack 丢失后无重试；`Rekey.scala:126-142` | 一次丢 ack 可把 rekey 永久卡住 | 允许 Pending/Active；重复 confirm 在签名校验后重新生成 ack；`newDh` 支持 ratchet 后重试；测试 same-n/new-n/after-data | 0.5 人日 |
| R2 | MAJOR | `complete` 重放 accept 会重新派生 `n=0` 的 Pending 会话；`Rekey.scala:92-110` | 状态/消息密钥回退 | `pending.phase == Requested && newSessionId.isEmpty` 才允许；重复 accept 返回 `RekeyPending`；测试 | 0.2 人日 |
| R3 | MAJOR | 无 abort/重试/尝试上限；cooldown/attempts 常量未使用 | 丢 request/accept 后无法恢复 | `abort` 回到 Active 并设 cooldown；`start(nowMs)` 检查 `MAX_REKEY_ATTEMPTS`/cooldown；测试三次上限 | 0.5 人日 |
| R4 | MAJOR | 缺固定 KDF/ratchet/rekey 向量与 restart/retry 覆盖 | 自洽实现可能共同犯错 | 新增 RFC/独立 HKDF 向量、初始 root/chain、rekey root/session/base、固定 handshake transcript、restart、丢 ack/重复 accept | 0.5 人日 |
| R5 | MINOR | 第 4 次 AEAD 失败把计数推到 4，StateCodec 拒绝 | 失败状态无法持久化 | 计数饱和在 `MAX_REKEY_FAILURES`；测试第 4 次仍可编码 | 0.1 人日 |
| R6 | MINOR | 安全门只拦发送，接收正文不看验证状态 | 未验证正文可能被呈现 | `RatchetState.safetyVerified`；`encryptUser` 与 data `decrypt` 都拒绝未验证；测试 | 0.3 人日 |
| R7 | MINOR | `Reply.accept` 无账号比对入口；一次性消费在 core 外 | 账号不符/重放只能由执行器处理 | `Reply.matchesAccount` + 测试；一次性消费明确留给后续 store 事务 | 0.1 人日 |
| R8 | MINOR | 秘密 case class 默认 `toString` 泄露 keys/plaintext | 诊断日志可泄露 | `RatchetState`/`RekeyState`/`SessionKeys`/`ReceiveError`/`ReceiveResult`/`ReplyResult`/联系字段重写 `toString`；测试 | 0.2 人日 |
| R9 | MINOR | 切换后旧 `RekeyState` 仍留 R1/R2 私钥 | 旧行保留时私钥滞留 | `Rekey.clearTerminal` 清 `rekey`；测试；旧行删除仍属 ADR-0007 | 0.1 人日 |
| R10 | MINOR | 同一公钥可当不同角色（user/device、eph/ratchet 等） | RNG/调用错误降低分离性 | `KeySeparation` 在 invitation/reply/session/rekey 创建处拒绝相等公钥；测试 | 0.2 人日 |

## 未关闭 / 后续

- ADR-0006 §3.3 的“第一条业务消息携带双方账号与安全码断言”尚未定义 wire schema；当前只实现安全码验证状态和正文门。E2E#1/#2 前必须补。
- 一次性邀请消费、账号不符的待确认状态、旧 session 行删除、SQLCipher/AEAD AAD 与 state_version 高水位属于 store/ADR-0007；本提交不声称完成。
- 外部密码学评审仍是发布门；本记录不是密码学证明。
- 1,000 条 <5s、10k 两进程 E2E、≤800MB、E2E 15 契约不在本提交；10k 协议回放由后续 ADR-0008 记录补入 `core.test`。
- `VectorsSpec` 的 handshake 固定值来自本实现 transcript；它防止回归，不作为独立密码学证明。KDF/初始/rekey 向量与 Python HKDF/HMAC 独立值对拍。

## ADR-0007 storage / doobie review

- 对象: `docs/adr/0007-storage-encryption-and-keys.md`、storage/doobie spike；不把 spike 写成生产 `store/`。
- 轮次: round 1 storage design（1 BLOCKER、10 MAJOR、14 MINOR）+ round 2 doobie（4 MAJOR、5 MINOR）；上限 7 轮。
- 结论: 方案保持 client-local SQLite + 应用层 AEAD + doobie 1.0.0-RC14 + Hikari(max 1) + `sqlite-jdbc`；外部密码学评审仍是发布门。

| ID | 发现 | 关闭证据 | 代价 |
| --- | --- | --- | --- |
| B1 | 首次运行只看 DB/密钥，`identity.bin` 独存时可生成新 master | 只有 DB/identity/keystore/keychain 全无才创建；其余 `InstancePartiallyPresent`（ADR §2） | 0.5 人日 |
| M1 | generation 只在 sessions，contact/invitation 旧写在锁后仍可落 | 全局 `meta.lock-generation` CAS 每个写事务；sessions generation 保留在 AAD（ADR §5） | 0.5 人日 |
| M2 | body/wire 一致性要求在 store 不可实现 | body AAD 绑定 `SHA-256(wire)`；不读 message key（ADR §3） | 0.25 人日 |
| M3 | contact/invitation AAD 的 version 没证据 | 表加 version；G11 明确交接读/交换/回滚/点查；生产 store 测试已覆盖正向读取、swap、回滚、tombstone 与索引 | 0.5 人日 |
| M4 | 威胁模型声称 messages 删除受保护 | 明示只保护 sessions/contacts/invitations 的部分恢复；messages/conversations 是已知约束 | 0.25 人日 |
| M5 | 备份指引复制运行中 DB/WAL | backup 用 Online Backup API 或 `rawTrans` 的 `VACUUM INTO`；口令/钥匙串路径分开 | 0.5 人日 |
| M6 | G1/G2 断言超出 spike 证据 | G1 缩到 session/message；G2 在连接打开时扫描 WAL/SHM，并加第二 master 失败；receipt 更新 | 1–2 小时 |
| M7 | parity/gate 名字与断言漂移 | G8 拆 3 断言；G10/G11 换真实测试名；ZTSE 改 `REWRITE` 并留人签字 | 0.5 人日 |
| M8 | 聊天列表无索引 | `(conversation_id, author_pub, seq)` 索引 + list plan gate | 2 小时 |
| M9 | 本地 Active 就删旧会话会吃掉重试 | cutover 需双向 Confirm/Ack 且无 pending；pending 保留 ReadOnly/wire；G12 加 lost-ACK | 0.5 人日 |
| M10 | `safety_verified` 两份权威 | contact sealed 为权威；session 副本同事务更新，rekey 从 contact 继承；不一致失败关闭 | 0.25 人日 |
| R2-1 | Hikari pool autoCommit=false 会让归还连接继续持写锁 | pool autoCommit=true；doobie 事务内 false/commit/rollback；维护走 RC14 的 `rawTrans`；G16 已由写者阻塞/释放测试验证 | 0.5 人日 |
| R2-2 | G15 没验证 UPDATE 行数/异常类型 | doobie smoke 要求 changed==1 且 forced rollback；重新跑 PASS | 0.1 人日 |
| R2-3 | G3 wire 测试被 body 顺序混淆 | 先恢复 body1 并读成功，再只换 wire；重新跑 PASS | 0.1 人日 |
| R2-4 | ZTSE 的「非明文 SQLite 头」不成立 | test-parity 改 `REWRITE`，写明 header 可见、错误 key 在解锁/首行解密失败；待签字 | 0.25 人日 |
| R2-5 | G12 lost-ACK 下 pending 不能自动终态 | 发起方收 ACK 且无 pending 才删；应答方要等新 session 首个已认证数据帧（ACK 不可观察）且无 pending；G12 加零 pending lost-ACK case | 0.5 人日 |

## ADR-0007 未关闭 / 后续

- ADR-0007 已 accepted（内部 round 1/2/closure，无剩余 BLOCKER/MAJOR）；外部密码学评审未做。不得把 spike PASS 当 S1 完成。
- 生产 `store/`、`KeyProvider`、`identity.bin`、联系人/邀请高水位、备份、崩溃注入、G8–G14/G16 在 ADR-0007 接受时未实现；本文件末尾的“生产 store 与 G8–G16 实现记录”关闭其中可执行部分，G13/G10 shell/G14 launcher 仍开放。
- `ZTSE` 的 `REWRITE` 与人签字仍未完成；G10 的合法 key source 重开和 G11 的 contact/invitation 点查已由生产 store 测试覆盖，keychain shell 仍属 G10 外部。
- 1,000 条 <5s、10k 两进程 E2E、≤800MB、15 条 E2E 未跑；10k 协议回放已由 ADR-0008 的 `core.test` 覆盖。

## 生产 `store/` 与 G8–G16 实现记录

- 对象: `store/src/yochat/store`、`store/test/src/yochat/store`、`build.mill`、`check.sh`；父提交 `bc3a9f0`。
- 范围: 生产 doobie `ConnectionIO`/`Transactor[IO]`、Hikari(max 1)、SQLite WAL/FULL/IMMEDIATE/secure_delete、应用层 AEAD、`master → identity/database → 域密钥`、`KeyFile`（Argon2id）、`identity.bin`、首次运行部分实例、`meta` 高水位/锁代次、sessions/messages/contacts/invitations/conversations、安全状态同一事务、旧会话 cutover、`VACUUM INTO` 备份。没有新增表、服务或第二套 effect 栈；复用 ADR-0007 的 6 张表和同一 doobie 接入层。
- 事务锁序: `invitations → contacts → sessions → messages → meta`；状态候选在事务内封印，提交成功后才返回 `SessionRecord`/`AppendResult`；失败由 `StoreAbort`/SQLite 回滚，不发布候选。
- 测试: `./mill store.test` 20 个测试，全部通过。覆盖 G8（重复 seq 回滚、损坏 state 报错不返回空）、G9（真实子进程 halt-before/after-commit，after 的 wire 原样读回）、G10（keyfile 创建/错误口令/参数上界/部分实例/provider 拒绝）、G11（contact/invitation swap、version 回滚、删除重插、安全状态不一致、rekey 继承、索引计划）、G12（initiator/responder、pending、lost-ACK、tombstone、历史 body）、G14（运行中备份 + native-access 子进程）、G15（PRAGMA、schema fail-closed、索引计划、生产事务）、G16（max-one 写者阻塞/释放、强制回滚、维护路径）。
- 性能回执: `STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1441 read_ms=119 total_ms=1561`（一次完整 `./check.sh` 的 `./mill store.test`；临时目录；多次运行约 1.5–1.8s；不是两进程 E2E，不替代 G13）。
- 发现并修复: `keystore.bin` 曾把已含 `YCE1` 的 AEAD 信封再塞进无 magic 的文件格式，导致正确口令也解不开；`identity.bin` 曾重复保存 nonce/信封，长度与 ADR 格式不符；邀请 codec 用 `fixed(32)` 读取带 u32 长度前缀的 ephemeral key，导致正向读取失败。三处都有正向加载测试，不再只测错误路径。
- 未关闭: G13 的两进程 1,000 条 E2E 待 M3/M5；G10 的 Electron keychain shell 与 G14 的 Electron launcher 在 shell 模块实现；外部密码学评审仍是发布门。

## M2 应用状态机（第一段）

- 对象: `core/src/yochat/core/app`、`core/test/src/yochat/core/app`；父提交 `77df124`。
- 纯核心: `Application.step(AppState, AppEvent): (AppState, Vector[AppEffect])`；不读时钟、不取随机数、不做 IO。时间、nonce、DH 密钥、Reply/Rekey 结果、持久化完成/失败都由事件注入。
- 覆盖: 应用锁/空闲锁定/旧代次结果丢弃（`AppLockSpec`）；邀请/应答、账号不符、拒绝静默、过期、安全码门（`RelationshipSpec`）；写前落盘：`PersistOutbound` 成功前不产生 `SendWire`，持久化失败不发（`OutboxSpec`）；单作者顺序、缺口缓冲、连续冲刷、去重（`ConversationSpec`、`InboundSpec`）；无匹配/锁定入站静默；重连有界重放、重试上限（`OutboxSpec`）；Q5 rekey request/accept/confirm/ack、旧 ReadOnly 会话在 cutover 后删除（`RekeyAppSpec`）。
- 测试: `./mill core.test` 全绿；新增 app 测试 16 个。`AppEffect` 的持久化组由后续执行器按事务/顺序落地；当前 `PersistSession`/`PersistInbound` 是描述，不是已接 store 的 executor。
- Quint: `models/` 有 Q1–Q5 五个有限模型与 `checks.json`；ADR-0008 追加 Q6（ACK/缺口），现为 Q1–Q6、9 个 check。`./models/check.sh` 用 Quint 0.33.1 + Apalache 0.62.4 检查 `safe`，Q2/Q4/Q5 的 temporal 用 TLC；Q4 活性前提是连接最终稳定，Q3 只证明尝试预算，Q6 覆盖 ACK/缺口。模型抓到了 Q5 删除后追加 pending 的反例并已修正，也让 Q4 的公平性前提收紧。
- Replay: `models/traces/{q1_relationship,q2_order,q3_resend,q4_reconnect,q5_rekey}.itf.json` 都是 seed=0x1 的固定轨迹，SHA-256 固定在 `checks.json` 与测试中。`ReplaySpec` 重放 Q2；`ReplayModelsSpec` 重放 Q1/Q3/Q4/Q5。Q1/Q3/Q4 比较逐状态观测投影；Q5 因 `start`/`firstData` 是抽象动作、与真实 control-frame 顺序不能一一对应，只执行核心 rekey 序列并检查每个 trace 状态的 Q5 安全不变量，不声称逐字段等价。所有重放直接调用真实 `Application.step`。
- 未关闭: `AppState` 尚未接 `store/`、transport、server/UI；Q5 的 control-frame 路由由执行器传入显式事件；没有真实 E2E/性能回执。下一步是阶段 review；review 通过后再接 transport/runtime。

## AppEffect 执行器（第二段）

- 对象: `server/src/yochat/server/AppEffectExecutor.scala`、`server/src/yochat/server/AppTransport`、`server/test`；父提交 `89390f4`。
- 执行: `executeAll(Vector[AppEffect])` 顺序执行，首个失败即停；`AppExecutionError` 只保留稳定 code。持久化走生产 `Store`，网络/定时器/通知走显式 `AppTransport`，执行器自己不打开 socket。
- Store 补充: `appendInbound`（收件消息与 session 同事务）与 `setMessageStateByKey`（按 conversation/session/author/device/seq CAS，避免把 app 消息 id 当作 SQLite 行 id）。
- AppEffect 调整: `PersistInbound(message, session)` 让消息与候选 session 同事务；`DeleteSession(id, retirement)` 携带 initiator/responder proof；`UpdateMessageState` 用业务键而不是行 id；本地邀请携带 ephemeral private 以写入 `invitations.sealed`。
- 测试: 4 个 executor 测试，覆盖 PersistOutbound→SendWire 精确 wire、SendSucceeded→store `Sent`、PersistInbound→`Received`、DeleteSession proof、邀请私钥落库；`./server.test` 全绿。
- 未关闭: `AppTransport` 只是接口，真实 relay client 未实现；收到的邀请不持久化（本地邀请持久化）；`AppEffect` 到 transport 的失败回灌、事务分组、应用层消息 id 与 store 行 id 的展示映射、UI/E2E 仍未接。外部密码学评审仍是发布门。

## ADR-0008 ACK / 缺口 / 有界状态（2026-11-22）

- 对象: `core/src/yochat/core/MessageKind.scala`、`MessageHeader.scala`、`Ratchet.scala`、`core/src/yochat/core/app`、`store/src/yochat/store/Records.scala`、`server/src/yochat/server/AppEffectExecutor.scala`、`models/q6_delivery.qnt`；父提交 `192f1b8`。
- 协议: 新增 `DataAck`/`Gap` 控制帧，均在会话棘轮下加密和签名；`DataAck.seq` 是累计确认，`Gap.seq` 是期望的下一条数据序号。`DataAck`/`Gap` 不占数据序号，不进入 `messages` 表。
- 状态: `OutgoingState` 增加 `InFlight`；`MessageState` 增加 `Delivered`（码 5，保留 Pending=1/Sent=2/Received=3/Failed=4 的旧编码）；`Delivered` 只由认证 ACK 触发，`Sent` 仍只表示 transport 接受。
- 窗口: `MaxInFlight = 32`、`MaxOutgoing = 1024`、`MaxFailedRetained = 64`；`seen` 只保留未应用的未来序号，`applied` 不再留在核心，`outgoing` 在 Delivered 时删除。
- 测试: `DeliverySpec` 覆盖 ACK、Gap 重发、重复帧只回 ACK、32 条在途窗口、ACK 泵出下一条、1024 出站上限；`TenKReplaySpec` 以 32 条一批跑 10,000 条真实 `Application.step`，ACK 逐个回送，结果为 `TENK_REPLAY messages=10000 elapsed_ms=11504 outgoing=0 next_seq=10001`，低于 50s 门；`./mill core.test` 全绿。
- 模型: 新增 Q6 `q6_delivery.qnt`，Apalache `safe` 通过；`./models/check.sh` 全部 9 个 check `[ok]`，`QUINT_CHECKS PASS`。Q3 仍只验证尝试预算；Q6 承担 ACK/缺口语义。
- 未关闭: 真实 relay/transport 与两进程 10k E2E 仍未接；10k 协议回放不是 E2E，不替代 G13；UI 的“已交给中继/已送达/失败”显示在 M4 实现。

## M3 transport / runtime / restore（2026-11-22）

- 对象: `transport/`（新增模块）、`server/src/yochat/server/AppRuntime.scala`、`server/src/yochat/server/AppStateRestore.scala`、`server/test/src/yochat/server/AppRuntimeSpec.scala`、`core`/`store` 的 M3 接口调整、`build.mill`、`check.sh`；父提交 `18ca98a`。
- 传输: 最小 TCP relay 只按注册 device key 路由 `Wire`/`Reply`/`RekeyRequest`/`RekeyAccept` 负载，帧和连接数有界；`RelayClient` 单 supervisor、最多一个注册 socket、断线后按 100ms–5s 退避重连，incoming/update 队列有界。relay 不解析密文、不提供认证；这是 S1 已知约束。
- Runtime: 单写者、有界事件队列；`Application.step` 的 effects 顺序执行，`PersistOutboundSucceeded/Failed`、`SendSucceeded/Failed`、`TimerFired`、`ConnectionChanged` 回灌同一队列。入站 `Data`/`DataAck`/`Gap`/Reply/RekeyRequest/RekeyAccept/RekeyConfirm/RekeyConfirmAck` 由 runtime 路由；每个入站帧生成新 response nonce，DH 变化时生成新 X25519 对。`SendWire` 携带目标 device 与 generation，transport 接受后才回 `SendSucceeded`。
- 邀请/恢复: `InvitationCreated` 同时保存 ephemeral 与 ratchet 私钥；`InvitationReceived` 产生持久化效果；`InvitationData` 升到 v2（可选私钥，兼容 v1）；`Invitation.fromCanonical` 直接解析存储的原样 canonical。`AppStateRestore` 从 store 恢复 contacts/sessions/conversations/outgoing/invitations；conversation 的 `nextSeq` 与 gap buffer 由持久化入站消息重放得到。
- 修复: `ReplyCreated`/`ReplyReceived` 在核心状态中创建 conversation；`PersistSession` 确保 conversation 行；`SafetyVerified` 改为单条 `PersistSafetyVerified`，由 store 同一事务更新 contact 与全部 session，避免 `checkSafety` 读取旧 session 时失败。
- 测试: `AppRuntimeSpec` 用两个真实 Store、两个 `AppRuntime`、真实 TCP relay 和独立身份，覆盖 invitation/reply、收到邀请无本地私钥仍落库、message ACK 后 `Delivered`、rekey cutover、relay 重启后重连、`AppStateRestore`，并断言重连后的发送与接收。`./check.sh` 全绿（87.29s）：`core.test` 145；`store.test` 20；`transport.test` 7；`server.test` 5；Q1–Q6 9 个 Quint check；spike compile/crypto/storage/doobie/demo 100 条 + reconnect `ok=true`。回执：`TENK_REPLAY messages=10000 elapsed_ms=11873 outgoing=0 next_seq=10001`；`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1444 read_ms=119 total_ms=1563`；`SPIKE messages=100 total=101 reconnect=true aliceSent=101 aliceAcked=101 elapsedMs=57 reconnectMs=34 bobReceived=101 ok=true`。
- 未关闭: UI/shell、15 条行为契约、两进程 1k/10k E2E 与外部密码学评审；runtime 尚未调度 idle timeout，也尚未把 `Lock` 的 generation bump 落到 store；relay 无认证；`AppRuntimeSpec` 是同 JVM、两 Store/两 Runtime 的集成测试，不是两进程 E2E；旧 M2 数据中无 conversation 行且无消息可推导的 session 会在 restore 时 fail-closed。

## M3 review round 1（2026-11-22）

- 结论: 首轮只读审查 request changes；未合入 `main`。
- 已修: 恢复按 conversation 统计 outbound seq（跨 rekey 不重置）、session 优先按自身 message 绑定 conversation、retired session 的 Failed 历史跳过回放、restore 恢复 responder `retireOldOnFirstData`、v1 issued invitation 不冒充 incoming；`SendSucceeded`/`SendFailed` 在 offline/locked 时忽略，`pump`/`sendNow` 锁态不发送，`ReplyReceived`/`RekeyAckReceived` 锁态不落状态；fatal 后拒绝 submit 与 API；PersistOutbound 成功直接回灌，SendWire 由 runtime 直接取结果，timer 在 sleep 后取时间；RelayClient 写超时并关闭退役 socket，RelayServer 注册 5s 超时、先 ACK 后发布；relay payload 上限按核心最大 wire 收紧；邀请 tombstone 阻止 API 重插；邀请 API 等待状态可见并校验 signed account；rekey control 按 role 匹配；AppRuntimeSpec 断言重连后的收发。
- 未关闭（按优先级）: (1) Reply/Rekey 握手状态仍是多条独立事务，contact/session/conversation/invitation 的崩溃窗口未消除；(2) `AppRuntime` 在 store 失败后 reload + fatal，而不是“提交后发布”的事务模型；(3) 接受邀请后 `SendReply` 失败没有持久化重试；（4）rekey 四个控制帧丢失后没有 runtime 重试/超时 abort；(5) `Store.snapshot`/`listAllMessages` 仍是多事务、100k 前缀读取，不是分页快照；(6) restore 不重发 outstanding Gap、不恢复 retry attempts；(7) Reply 匹配仍按邀请列表逐个尝试，候选数无上限；(8) timer fiber registry 保留已触发句柄；(9) idle timeout 与 Lock generation 持久化仍未接。以上均记录为 M3 后续输入，不把当前提交当作 S1 完成或可合并状态。
