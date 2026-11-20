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
- 1,000 条 <5s / ≤800MB / E2E 14 契约不在本提交；`./check.sh` 只覆盖单元与 spike 门。
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
- 1,000 条 <5s、≤800MB、14 条 E2E 未跑；storage/doobie spike 不是这些门的替代。

## 生产 `store/` 与 G8–G16 实现记录

- 对象: `store/src/yochat/store`、`store/test/src/yochat/store`、`build.mill`、`check.sh`；父提交 `bc3a9f0`。
- 范围: 生产 doobie `ConnectionIO`/`Transactor[IO]`、Hikari(max 1)、SQLite WAL/FULL/IMMEDIATE/secure_delete、应用层 AEAD、`master → identity/database → 域密钥`、`KeyFile`（Argon2id）、`identity.bin`、首次运行部分实例、`meta` 高水位/锁代次、sessions/messages/contacts/invitations/conversations、安全状态同一事务、旧会话 cutover、`VACUUM INTO` 备份。没有新增表、服务或第二套 effect 栈；复用 ADR-0007 的 6 张表和同一 doobie 接入层。
- 事务锁序: `invitations → contacts → sessions → messages → meta`；状态候选在事务内封印，提交成功后才返回 `SessionRecord`/`AppendResult`；失败由 `StoreAbort`/SQLite 回滚，不发布候选。
- 测试: `./mill store.test` 20 个测试，全部通过。覆盖 G8（重复 seq 回滚、损坏 state 报错不返回空）、G9（真实子进程 halt-before/after-commit，after 的 wire 原样读回）、G10（keyfile 创建/错误口令/参数上界/部分实例/provider 拒绝）、G11（contact/invitation swap、version 回滚、删除重插、安全状态不一致、rekey 继承、索引计划）、G12（initiator/responder、pending、lost-ACK、tombstone、历史 body）、G14（运行中备份 + native-access 子进程）、G15（PRAGMA、schema fail-closed、索引计划、生产事务）、G16（max-one 写者阻塞/释放、强制回滚、维护路径）。
- 性能回执: `STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1576 read_ms=145 total_ms=1721`（一次完整 `./check.sh` 的 `./mill store.test`；临时目录；不是两进程 E2E，不替代 G13）。
- 发现并修复: `keystore.bin` 曾把已含 `YCE1` 的 AEAD 信封再塞进无 magic 的文件格式，导致正确口令也解不开；`identity.bin` 曾重复保存 nonce/信封，长度与 ADR 格式不符；邀请 codec 用 `fixed(32)` 读取带 u32 长度前缀的 ephemeral key，导致正向读取失败。三处都有正向加载测试，不再只测错误路径。
- 未关闭: G13 的两进程 1,000 条 E2E 待 M3/M5；G10 的 Electron keychain shell 与 G14 的 Electron launcher 在 shell 模块实现；外部密码学评审仍是发布门。
