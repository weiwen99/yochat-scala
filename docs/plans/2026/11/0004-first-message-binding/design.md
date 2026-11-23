# Plan 0004: S1 首条业务消息绑定

- 状态: draft
- 日期: 2026-11-23
- 前序: Plan 0003（服务层 E2E 已合入 `4445297`）、ADR-0006 §3.3、ADR-0008
- 活规格: `.scratch/yochat-first-message-binding/spec.md`
- Review 预算: 最多 3 轮；协议/安全边界按 R10 正式评审

## 规模与交付边界

本计划只实现 ADR-0006 §3.3 的首条业务消息绑定，不新增表、协议路径、服务组件、配置或 UI 屏幕。

| 维度 | 用户要的 | 本计划产出 | 倍率 | 说明 |
| --- | --- | --- | --- | --- |
| 需求条数 | 1 条安全契约 | 1 个可证伪 gate：首条 Data 必须绑定双方账号与安全码 | 1× | 不把 15 条契约或性能门塞入 |
| 表数 | 0 个新表 | 0 | 1× | `messages.body` 仍存用户正文；wire 只变 plaintext envelope |
| 组件数 | 0 个新组件 | 1 个 core payload 类型 + 校验函数 | 1× | 复用 `Ratchet`、`AppStep`、`AppContact` |
| 配置项 | 0 个新用户配置 | 0 | 1× | 常量上限在 core |
| 工程师日 | 1 条安全断言 | 2–4 实现 + 1–2 review（估算） | — | 不含模型改动 |
| 发布波次 | 1 | 1 | 1× | 一个 worktree、一个可 ff-only 的批次 |
| 生产代码 | 首条消息绑定 | 约 250–350 行 | — | 仍低于模块/文件预算 |
| 测试代码 | 绑定正/负例与回归 | 约 400–600 行 | — | 含现有测试适配 |

结论：这是协议数据面的一次小范围、不可静默绕过的变更；不做多设备、历史同步、UI 或性能功能。

## 背景与缺口

Plan 0003 已证明服务层 `E2E#1–#7` 流程，但明确留下一个安全缺口：ADR-0006 §3.3 要求第一条经认证的业务消息携带双方渠道账号与安全码绑定断言；当前 `UserSend` 直接把用户正文交给 `Ratchet.encryptUser`，没有该断言。仅用 `contacts.safetyVerified` 只能阻止本地未验证发送，不能证明收到的首条消息属于已核对的账号/安全码。

ADR-0006 同时规定 wire header 不含用户公钥、联系人名或正文；因此绑定断言不能放进 `MessageHeader`。它必须位于 `MessageKind.Data` 的 AEAD plaintext 内，由签名和 AEAD 覆盖。

## 目标

1. 在每个 `MessageKind.Data` 的密文 plaintext 前增加规范 `DataPayload` v1 envelope。
2. 每个作者的第一条 Data（`seq==1`）必须携带：
   - 发送方自己的 `channelAccount`；
   - 接收方/对端的 `channelAccount`；
   - 39 位安全码数字（`SafetyNumber.digits`）。
3. 接收方在 `seq==1` 时要求并校验该绑定；缺失、账号不符、安全码不符、或 `seq>1` 仍携带绑定，均拒绝该数据帧：不落盘正文、不推进/发布会话状态、不回 ACK/Gap。
4. 首条未承认时，未来序号的缓冲 Data 不得通过 HTTP/UI 提前显示；`messages.body`、重试、rekey、重启与 100 条顺序语义保留。
5. 空账号、`AccountMismatch`、或同一 peer 的第二套配对不能生成/验证绑定；旧 session 不得借用新配对账号。
6. 增加正/负测试和固定编码向量；E2E#1/#2/#3 继续通过，并能证明首条 wire 带绑定。

## 非目标与已知约束

- 不新增表、`AppSession` 字段、`Conversation` 字段或配置；首条判定使用既有 `seq==1`（每作者跨 rekey 不重置）。
- 不把绑定加入 `MessageHeader`；中继仍不能看到账号/安全码/正文。
- 不修改 Quint Q1–Q6。Q1 已抽象「内容只在安全验证后发送」；本轮绑定是 wire 层断言，由 Scala 编码/状态机测试覆盖。若 formal review 要求建模，另开人签字的模型轮次，不在本轮顺手改模型。
- 支持边界是双方升级并重新配对。v1 尚未发布；已有本地已应用消息仍可读，但旧格式的新入站 `seq=1` 会被拒绝，旧格式的未确认 `seq>1` 重试最终失败；版本混用不支持。无 marker 的 legacy 会话恢复为只读历史，不续发/接收 Data；若未来需要兼容，另写迁移/兼容 ADR。
- S1 每个 peer 只保留一个活动配对。对已有 contact 的 peer，第二个 `ReplyCreated`/`ReplyReceived` 把 contact 标为 `AccountMismatch` 并持久化，显式不可用；重新配对需要先移除旧关系，属后续范围。
- 不改变安全门前“wire 级没有 DATA”这一未证明项；本轮证明的是“即使有 Data，首条也必须带并验证绑定”。
- 不做 `E2E#8` 1k、10k、冷启动/内存、`E2E#9–#15`、UI 自动化。
- 外部密码学评审仍是发布门。

## 设计

### 1. `DataPayload` v1

新增 core 类型，位于 `MessageKind.Data` 的 plaintext 内：

```text
u8  version = 1
u8  flags            // 0 = no binding, 1 = binding
if flags & 1:
  u32 senderAccountBytes; UTF-8/NFC
  u32 peerAccountBytes;   UTF-8/NFC
  fixed 39 ASCII digits
u32 bodyBytes
body
```

规则：

- 只允许 `flags = 0` 或 `flags = 1`；未知 bit 拒绝。
- `version != 1` 拒绝。
- 账号做 NFC 归一化，最大 `Text.MaxFieldBytes`；解码后必须仍是规范 NFC。
- 安全码固定 39 个 ASCII `0..9`，不做分组显示。
- body 最大 `262_144` 字节；空 body 允许（API 仍保留非空校验）。
- 解码必须 `finish`，拒绝多余字节。
- `DataPayload.toString` 只显示版本/是否有绑定，不输出账号、安全码或正文。
- `DataPayload` 是应用层 envelope；`messages.body` 继续存原始用户正文，`wire` 内是 envelope 密文。
- 首条 Data 在写入 `messages` 的同一事务里写 `meta` key `binding/<conversationId>=1`；这是“已有首条绑定证据”的持久 marker，不新增表。
- 恢复时，有 Data 消息但没有 marker 的会话得到 `AppSession.bindingEstablished=false`：联系人/历史仍可读，但 `activeSession` 不允许发送、Data 入站被拒绝、旧 pending 不重发。无消息的会话不需要 marker，仍可发首条。

### 2. 发送路径

`AppStep.UserSend` 在 `Ratchet.encryptUser` 之前：

1. 从 `state.identity` 取本地用户公钥；
2. 从当前 `AppSession` 的 peer user 取 `AppContact`；
3. 要求 contact 可用：`safetyVerified`、非 `AccountMismatch`、本地/对端账号非空；否则不发送；
4. `seq = session.nextSeq`；
5. 若 `seq == 1`：
   - 要求 `contact.peerChannelAccount` 存在；
   - 用 `SafetyNumber.derive(localUser, contact.userPub)` 取 `digits`；
   - 构造 `DataPayload.Binding(contact.channelAccount, peerAccount, digits)`；
6. 若 `seq > 1`：`binding = None`；
7. `DataPayload.encode(...).flatMap(Ratchet.encryptUser(..., _, body))`。

任何一步失败（缺/空账号、AccountMismatch、安全码派生失败、编码失败）都返回 `(state, Vector.empty)`，与当前安全门“不发送”一致；不新增异常通道，不发送未绑定首条，也不在 mismatch 关系上继续发后续 Data。

`AppRuntime.sendMessage` 在入队前先用 `Application.canSend` 检查；未验证、mismatch、空账号、legacy session 返回 HTTP 400（`ServiceError.BadRequest`），UI 的 `Failure` 分支保留草稿。这样不会出现“HTTP 成功但 core 静默丢弃”的假成功。

**为什么用 `seq==1`**：`AppSession.nextSeq` 和 `Conversation.nextSeq` 都按作者、跨 rekey 保留；`seq==1` 是双方无需新状态即可识别的首条。不能把首条状态塞进未加密/可回滚的旁路字段。

### 3. 接收路径

`AppStep.InboundFrame` 在 `Ratchet.decrypt` 成功后，只在 `MessageKind.Data` 分支把 `ReceiveResult.plaintext` 交给 `DataPayload.decode`；`DataAck`/`Gap` 的 empty plaintext 仍按原控制路径处理。

- 所有 Data 都要求 contact 可用：`safetyVerified`、非 `AccountMismatch`、本地/对端账号非空；
- `seq == 1`：必须存在 binding，且：
  - `binding.senderAccount == contact.peerChannelAccount`；
  - `binding.peerAccount == contact.channelAccount`；
  - `binding.safetyDigits == SafetyNumber.derive(localUser, contact.userPub).digits`；
- `seq > 1`：必须不存在 binding。
- 任一项失败：返回 `(state, Vector.empty)`；不调用 `conversation.accept`、不生成 `PersistInbound`/ACK/Gap、不推进 `Ratchet` 状态。这样同一个非法首条会在发送方重试后最终进入既有失败/通知路径，而不会显示正文或建立错误关系。
- 首条尚未承认时，未来 `seq` 的 Data 可以进入有界 buffer 并持久化，但 HTTP/UI 读取必须按 `conversation.nextSeq` 过滤：peer 消息只在 `seq < nextSeq` 时可见。`AppService.messagesJson`、contact preview、conversation preview 共用 `ApiJson.visible`，避免缓冲正文提前显示。
- 读取先过滤再排序/截断：`AppService` 取 `limit + MaxBuffered` 行、过滤、按 `(seq,id)` 排序后 `takeRight(limit)`；preview 只取 `MaxBuffered + 1` 行并共用 `ApiJson.ordered`。这样 `limit=1` 不会返回空白，缓冲首条到达后也不会以 `[2,1]` 显示。
- 首条已承认后的重复同序号 Data 走既有 `DuplicateSeq` 路径，只重发累计 ACK；不再应用正文。
- `AppStateRestore` 对没有 marker 的 legacy conversation 设置 `bindingEstablished=false`，保留历史但阻止 Data 续发/接收；`outgoingFrom` 跳过该会话的旧 pending，避免恢复后重放无绑定 wire。

合法时 `DataPayload.body` 才是 `BufferedMessage.body`；wire 原文保留在 `BufferedMessage.wire`，重发原样使用。

### 4. 错误与边界

- 新增 `CoreError.InvalidBinding`，用于绑定形状/账号/安全码不符；解码形状错误仍用 `InvalidEncoding`。
- 不把账号、安全码、body 放进异常 message 或日志；错误码稳定且无秘密。
- 不改变 `MessageHeader.Version` 或 `WireFrame` 布局；relay 看到的字节只增加 AEAD ciphertext 长度。
- 非绑定 envelope 固定增加 6 字节（version、flags、body length）；首条增加 `53 + senderAccountBytes + peerAccountBytes` 字节（两个账号长度前缀与 39 位数字）。S1 性能门仍需回执，本计划不新增阈值。

### 5. 事务与锁序

首条 Data 的 marker 与消息写入共用现有 `MessageRepository.append` 事务，顺序仍是 `sessions → messages → meta`；不新增旁路写入。`AppStateRestore` 只读 snapshot，不新开写事务。`adoptLockGeneration` 仍沿用 Plan 0003 的 `sessions → meta`，本计划不重排其他事务。

### 6. ADR

新增 `docs/adr/0009-first-message-binding-payload.md`，状态 `accepted`，记录：

- 为什么绑定在 AEAD plaintext 而非 header；
- `DataPayload` v1 格式、`seq==1` 判定、失败即拒绝；
- 旧格式不迁移的已知约束；
- 与 ADR-0006 §3.3、ADR-0008 的关系。

ADR-0006 不改写既有决策，只在实现记录中被引用；若需要改 ADR-0006 正文，另开文档轮。

### 7. 与现有模型的关系

- Q1 已有 `contentSent implies safetyVerified`；绑定是 wire 层补充，不新增状态机分支。
- 不修改 Q1–Q6 的 `.qnt`/trace/checks；`models/check.sh` 仍应通过。
- 新增 Scala 属性/状态机测试，覆盖首条正/负例、B-first、rekey 后非首条、重启后首条判定。
- 这是已知模型缺口：Q1 不表达 `bindingValid`。如果 formal review 要求，必须单独一轮并重生成 trace，不在本计划静默修改。

## 测试

### `DataPayloadSpec`

- binding / 非 binding 的 encode-decode 往返；
- 固定字节向量（首条、后续各一份）；
- 未知 version/flags、截断、多余字节、超长账号/body、非 NFC、非 39 位数字拒绝；
- `toString` 不含账号/安全码/正文。

### `Application.step` 绑定测试

- 首条合法绑定：接收方应用正文、发送方 wire 的第一条 plaintext 含 binding。
- 首条缺失 binding：无 `PersistInbound`、无 ACK、conversation 不前进、session 不前进；重复非法首条仍静默。
- 账号不符、安全码不符、空账号、`AccountMismatch`：同上拒绝；后续 Data 也不能绕过。
- `seq>1` 携带 binding：拒绝；不带 binding 且关系可用时正常通过。
- 首条已承认后的重复 Data：只重发累计 ACK，不重复应用/落盘。
- B-first：B 的首条带绑定，A 用 `newDh` 验证；用 B 的真实 `UserSend` 生成，不只手写 helper。
- rekey 后 `nextSeq>1` 的消息不带绑定，仍正常；若 rekey 前未发过首条，则新会话首条仍 `seq==1` 并带绑定。
- 重启恢复：`nextSeq` 恢复后，首条/非首条判定不变；有 Data 无 marker 的 legacy 会话 `bindingEstablished=false`，历史可读但不能再发/收 Data。
- 同一 peer 的第二个 `ReplyCreated`/`ReplyReceived` 将 contact 标为 `AccountMismatch`，不创建第二套 session。
- 首条 Data 的 marker 在同一事务出现；`StoreSnapshot.bindings` 可读，删除 marker 的旧库恢复为只读。
- API/UI 可见性：peer 的缓冲未来 seq 在首条未应用时不可见；contact/conversation preview 同样为空/回退到已应用消息；列表按 `(seq,id)` 排序后截断。
- 安全门前发送通过 HTTP 返回 400（而不是假 200）；UI 保留草稿。
- 旧格式首条被拒绝、旧历史仍可读；版本混用不支持（文档/测试边界）。
- 现有 `RelationshipSpec`、`InboundSpec`、`DeliverySpec`、`RekeyAppSpec`、`ReplayModelsSpec` 全部适配 envelope，不删除/放宽断言。

### `E2E`/层间

- `server.e2e` 的 `E2E#1–#7` 继续全绿；`E2E#3` 的 wire 首条现在有绑定。
- `server.test`/`store.test` 全绿；`./check.sh` 全绿。
- 不新增 1k/10k 数字；不改 `server.bench`。

## 性能与预算

- 不新增性能阈值；10k replay 仍 <50s。
- 生产文件/模块预算不变；本计划生产代码预计 250–350 行，测试 400–600 行；超 600 行先砍测试夹具/重复。
- 首条绑定是每作者一次，后续每消息 5 字节 envelope 开销；不在本计划用微基准替代 1k 门。

## 取舍与替代方案

- **Header 字段**：拒绝。会向 relay/日志暴露账号或安全码，违反 ADR-0006 的 header 边界。
- **新表/新持久字段**：拒绝。`seq==1` 已足够识别首条，新增字段会扩大状态/恢复/迁移面。
- **只在 UI/服务层校验**：拒绝。必须由 core 在接收密文后验证，否则绕过 HTTP/数据库直接投递即可。
- **修改 Q1**：暂不做。模型变更需要人签字、重生成 trace 和 source hash；本轮先把可执行 wire 断言落地并登记模型缺口。

## 取代的旧文档

**none（有理由）**：本计划实现 ADR-0006 §3.3 的既有要求，不取代 ADR-0006；新增 ADR-0009 只记录具体 payload 表示。

## Review 预算

| 轮 | 对象 | 通过条件 | 失败/停止 |
| --- | --- | --- | --- |
| 1 | 本设计 + ADR-0009 + 现有 wire/AppStep | 绑定位置、seq 判定、失败路径、模型缺口均有可证伪证据 | 修正后进第 2 轮 |
| 2 | 冻结实现 diff | 无 header/表/新状态；首条负例不落盘/不推进；现有测试适配无放宽 | 修正后进第 3 轮 |
| 3 | 最终 diff + 回执 | 绑定正/负例、E2E、check 全绿；开放项明确 | 第 3 轮问题数不降即停 |

## 验收

1. `DataPayloadSpec` 固定向量与负例通过；首条/后续开销分别符合 53+账号、6 字节。
2. `Application.step`：首条无绑定/账号不符/安全码不符/空账号/`AccountMismatch` 被拒绝，无正文、无 ACK、无会话推进；后续无绑定正常；重复非法首条静默，已承认重复只 ACK。
3. B-first、rekey、重启路径的首条判定正确；同一 session 的重复 reply 事件幂等；同一 peer 第二套配对标记 `AccountMismatch`，不创建新 session。
4. 未承认首条前，缓冲未来正文不出现在 HTTP/UI 预览；首条承认后按 `seq` 顺序显示；`limit` 过滤先于截断。
5. 首条 marker 与消息同事务；无 marker 的 legacy 会话恢复为只读历史，历史可读但不能发/收 Data 或重发旧 pending。
6. 安全门/legacy/mismatch 的 HTTP 发送返回 400，UI 保留草稿；不再有静默丢弃的成功响应。
7. `./mill server.e2e` `E2E#1–#7` 全绿；`./check.sh` 全绿。
8. 没有新增表、header 字段、配置、UI 屏幕或 Quint 文件变更；ADR-0009 记录格式、marker、单一配对与旧数据约束。
9. 开放项（Q1 模型缺口、旧格式/版本混用不支持、wire 级安全门前无 DATA 未证明、#8/10k/#9–#15）写进 `impl.md`。
