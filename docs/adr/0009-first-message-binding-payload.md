# 首条业务消息的账号/安全码绑定 payload

**状态**: accepted (2026-11-23; implementation in Plan 0004)

**前序**: ADR-0003（S1 范围）、ADR-0004（测试门）、ADR-0006 §3.3（安全门）、ADR-0008（Data/ACK/Gap）。

## 背景

ADR-0006 §3.3 已要求：第一条经认证的业务消息必须携带双方渠道账号与安全码绑定的断言。当前实现的 `Application.step` 在安全码验证通过后，直接把用户正文交给 `Ratchet.encryptUser`；wire header 与密文都没有该断言。仅靠本地 `safetyVerified` 不能证明收到的首条消息属于已核对的双方账号。

ADR-0006 同时规定 header 不含用户公钥、联系人名或正文；relay 只看路由和密文。因此绑定不能放入 `MessageHeader`。

## 决策

在 `MessageKind.Data` 的 AEAD plaintext 前增加 canonical `DataPayload` v1：

```text
u8  version = 1
u8  flags            // bit 0: binding present; other bits rejected
if binding:
  u32 senderAccountBytes; UTF-8/NFC
  u32 peerAccountBytes;   UTF-8/NFC
  fixed 39 ASCII digits  // SafetyNumber.digits
u32 bodyBytes
body
```

- 每个作者的第一条 Data（`seq == 1`）必须设置 binding；`seq > 1` 必须不带 binding。
- 发送方从 `AppContact` 取自己的 `channelAccount`、对端 `peerChannelAccount`，并从双方 userPub 派生 `SafetyNumber.digits`。账号为空、contact 为 `AccountMismatch` 或缺失 peer account 时，不生成绑定也不发送。
- 接收方在 `seq == 1` 时要求 binding，并核对发送方/对端账号和本地派生的安全码；任一项不符则不应用、不落盘、不 ACK、不推进棘轮状态。`seq > 1` 的未绑定 Data 仍要求 contact 可用（安全已验证、非 mismatch、账号非空）。
- 绑定是 AEAD plaintext 的一部分；签名与 AEAD 覆盖它，header/relay 字节布局不变。
- `messages.body` 和 HTTP/UI 正文仍是原始用户正文；`wire` 中是 envelope。
- 首条尚未应用前，未来 `seq` 的 Data 可以进入有界 buffer/持久化，但 API/UI 只显示 `seq < conversation.nextSeq` 的 peer 消息；未绑定首条失败时，后续缓冲正文不会提前显示。
- 每个首条 Data 在写入消息的同一事务里写入 `meta` 键 `binding/<conversationId>=1`。恢复时，有 Data 消息但没有该 marker 的 session 被恢复为 `bindingEstablished=false`：历史仍可读，不能继续发送/接收 Data；旧 pending 重发被跳过。无消息的关系没有 marker 也可继续建立首条。
- API 的 peer 消息列表和 contact/conversation preview 使用同一 cursor 过滤；列表按 `(seq, id)` 稳定排序后再应用 `LIMIT`，避免先缓冲的 `seq=2` 在首条到达后以 `[2,1]` 显示。
- `AppRuntime.sendMessage` 在入队前检查 `Application.canSend`；未验证、mismatch、空账号或 legacy session 返回 HTTP 400，composer 保留草稿，不能把静默丢弃当成功。
- 同一 peer 的第二套配对（`ReplyCreated`/`ReplyReceived` 遇到已有 contact）把 contact 标为 `AccountMismatch` 并持久化，显式不可用；不创建第二套 session。

### 单一配对

S1 每个 peer 只保留一个活动配对。`ReplyCreated`/`ReplyReceived` 先用 `result.state.sessionId` 判断是否同一会话的重复事件：同一 session 幂等返回，不改变 contact。该 peer 已有 contact 但 session 不同才视为新配对尝试：contact 标为 `AccountMismatch` 并持久化，显式不可用，不替换账号、不创建第二套会话。这样旧 session 不会借用新配对的账号来生成/验证首条绑定。重新配对需要先移除旧关系，属于后续范围。

## 为什么用 `seq == 1`

`seq` 是每作者、每会话、跨 rekey 不重置的序号。`AppSession.nextSeq` 与 `Conversation.nextSeq` 已经持久化/恢复；用 `seq == 1` 识别首条不需要新表、新字段或第二套首条标记。若首条丢失，发送方重试同一 wire；接收方仍看到 `seq == 1`。

## 失败语义

- 首条缺失绑定、账号不符、安全码不符、`seq > 1` 带绑定：`CoreError.InvalidBinding`。
- 失败帧不进入 `Conversation.accept`，不生成 `PersistInbound`/ACK/Gap，不发布 `Ratchet` 候选状态；发送方按既有有界重试最终进入 `Failed`/通知。
- 未承认的非法首条重复到达仍保持静默；一旦首条被承认，重复同序号 Data 走既有 `DuplicateSeq` 路径，只重发累计 ACK，不再应用正文。
- 错误码、日志、异常 message 不含账号、安全码或正文。

## 升级与旧数据

- 支持边界是**双方升级并重新配对**。本地已应用的旧历史可以继续读；旧历史的 `seq == 1` 不被当作绑定证据。
- 旧格式的新入站首条会被拒绝；旧格式的未确认 `seq > 1` 重试无法被新接收方解释，最终进入失败/通知。
- 旧接收方把新 envelope 当作正文并 ACK 的版本混用不受支持；不实现双解码器。
- 不新增迁移表或兼容开关。

## 备选与否决

| 方案 | 否决理由 |
| --- | --- |
| 放入 `MessageHeader` | header 对 relay 可见，违反 ADR-0006 的 header 边界 |
| 新增 `binding_sent/binding_received` 持久字段 | 需要 schema/恢复/迁移，且 `seq==1` 已足够；v1 不接受额外状态面 |
| 只在 UI 或 HTTP 层校验 | 可绕过本地服务直接投递密文；必须在 core 解密后校验 |
| 接受旧格式首条作为兼容 | 会让缺失绑定的首条绕过安全门；v1 尚未发布，明确不迁移 |

## 后果

- 无绑定 envelope 固定增加 6 字节（version、flags、body length）；首条加上两个账号长度前缀与 39 位数字，共 `53 + senderAccountBytes + peerAccountBytes` 字节。1k/10k 性能门仍需回执，不另设阈值。
- 现有本地已应用消息仍可读；旧格式的**新入站** `seq == 1` 会被拒绝。需要兼容时另写迁移 ADR。
- Q1–Q6 模型不改：Q1 只抽象“内容在安全验证后发送”，不表达 wire envelope。Scala 编码/状态机测试覆盖绑定；若 formal review 要求模型字段，单独一轮变更并重生成 trace。
- 不改变 `MessageHeader.Version`、`MessageKind` 或 relay 路由。

## 规模

| 维度 | 需求 | 本 ADR | 倍率 | 说明 |
| --- | --- | --- | ---: | --- |
| 新表 | 0 | 0 | 1× | 无 |
| 新持久字段 | 0 | 1 个 `meta` marker `binding/<conversation>` | 1× | 不是新表；同一消息事务写入 |
| 新组件 | 0 | 1 个 core payload 类型 | 1× | 不是服务/模块 |
| 新配置 | 0 | 0 | 1× | 常量上限 |
| 工程师日 | 1 条安全断言 | 2–4 实现 + 1–2 review（估算） | — | 含测试适配 |
