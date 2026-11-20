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
