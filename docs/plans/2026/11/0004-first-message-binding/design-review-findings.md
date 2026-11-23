# Design review findings — Plan 0004

- 对象: `design.md` 初稿 + ADR-0009 计划 + 基线 `4445297`
- Review 预算: 最多 3 轮；第 3 轮问题数不下降即停（R9）
- 当前状态: Round 1 已关闭；Round 2（实现 diff）待复核

## Round 0 基线

| ID | 原句位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- |
| B1 | ADR-0006 §3.3；Plan 0003 `impl.md` 未关闭 | ADR-0006、Y5 | 首条 Data 只加密用户正文，没有双方账号/安全码断言 | `seq==1` 的 wire plaintext 含 canonical binding；固定向量 | 0.5–1d |
| B2 | `AppStep.receiveSuccess` 直接接受 `result.plaintext` | 安全门、D8 | 未绑定/错绑定首条可能落盘或显示 | 无 binding/错账号/错安全码/seq>1 带 binding 均无消息、无 ACK、状态不变 | 0.5–1d |
| B3 | `MessageHeader` 是 relay 可见的 canonical header | ADR-0006 header 边界 | 把账号/安全码放 header 会泄露给中继 | 绑定只在 AEAD plaintext；header/relay 路径不变 | 0.2d |
| B4 | 现有 core/E2E 测试直接用原始 body 构造 wire | Y5、测试真实性 | 改 envelope 后旧测试可能误测或被迫放宽 | 更新 helper/负例，正/负/重放/rekey 断言保留 | 0.5–1d |
| B5 | Q1 已建模 safety/content，但无 binding 字段 | 模型冻结、Y2 | 绑定没有 Quint 表示 | 明确登记模型缺口；Scala 状态机/向量覆盖；不静默改 Q1 | 0.2d |
| B6 | 首条判定依赖 `seq==1` | 恢复、重载、rekey | 若 seq 恢复/重置错误，首条判定会漂移 | 重启、rekey 前后、B-first 测试；`nextSeq` 跨 rekey 不重置 | 0.3d |

## 轮次记录

- Round 0: 六项基线，全部 `open`；尚未进行正式 review。
- Round 1–3: 待设计/实现冻结后追加；每轮记录对象、两轴覆盖、发现、关闭/撤回、升级。
- 规则: review 前冻结 diff（R8）；发现必须带原文、规则、影响、关闭证据、代价（R5/R6）。

## Round 1 findings

对象：`design.md`、ADR-0009、基线 `4445297`。结论：设计需修订；以下七项已在实现/文档中关闭。

| ID | 严重度 | 原句位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- | --- |
| P1-1 | P1 | 设计 §3 `seq>1` 必须不存在 binding | D8、Y5、ADR-0008 | `seq=2` 先到会缓冲/持久化，API/state 预览可能显示未绑定正文；首条失败后仍可见 | `ApiJson.visible` 按 conversation cursor 过滤；`AppService.messagesJson`、contact/conversation preview 共用；`ApiJsonSpec` 覆盖 | 0.5–1d |
| P1-2 | P1 | 设计 §3 接收路径“先把 plaintext 解码” | D2、D8；ADR-0008 | 若在 kind 分派前解码，DataAck/Gap 的空 plaintext 会被拒绝，ACK/Gap 失效 | 解码只在 `receiveSuccess` 的 `MessageKind.Data` 分支；控制帧测试保持通过 | 0.1–0.25d |
| P1-3 | P1 | 设计 §3 contact 可用性 | D8、Y2、Q1 | `AccountMismatch` 可被 SafetyVerified 置 true；空账号也能通过存在性检查 | `relationshipUsable` 要求非 mismatch、非空账号；`DataPayload` 拒绝空账号；BindingSpec 覆盖发送/接收 | 0.25–0.5d |
| P1-4 | P1 | 设计 §1 发送/接收用 peer-keyed contact | D8、Y5 | 同一 peer 第二次配对可替换 contact，旧 session 借用新账号/会话 | `ReplyCreated`/`ReplyReceived` 发现已有 contact 即忽略；BindingSpec 覆盖 | 0.5–1d |
| P2-1 | P2 | ADR-0009 “旧格式新入站 seq==1 拒绝” | D5、D6、D8 | 旧已应用首条、旧 pending seq>1、版本混用边界未说明 | ADR 明确双方升级+重新配对；旧历史可读、旧 pending 失败、无双解码器 | 0.25–0.5d |
| P2-2 | P2 | ADR-0009 “不生成 ACK” | D2、R5、ADR-0008 | 已承认后的重复帧是 DuplicateSeq，可重 ACK；不能把“无 ACK”推广到所有重复 | BindingSpec 覆盖未承认非法首条静默、已承认重复只 ACK；实现只对未承认 invalid 静默 | 0.1–0.25d |
| P2-3 | P2 | 设计 §2 `encryptUser(..., _, body)` | D2、Y5、R5 | 该调用形状不存在；必须把编码后的 envelope 作为唯一 plaintext | 实现 `outboundPayload` 先 encode，再 `encryptUser(..., envelope)`；BindingSpec 解密 UserSend wire 验证 | 0.25–0.5d |

Round 1 结论：P1/P2 均已关闭；没有剩余设计阻塞。实现冻结后进入 Round 2 复核。

## Round 2 findings（实现 diff）

对象：冻结实现 diff、ADR-0009、`impl.md`。结论：无 BLOCKER；P1/P2 共 7 项，均按下列证据关闭，剩余范围限制明确。

| ID | 严重度 | 原句/位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- | --- |
| R2-1 | P1 | `AppStateRestore` 旧会话恢复 | D8、ADR-0009 升级边界 | 旧格式首条已落盘/游标前进，升级后可继续发/收未绑定 Data | 首条 Data 同事务写 `meta binding/<conversation>`；无 marker 且已有 Data 的会话恢复为 `bindingEstablished=false`，历史可读、发送/Data 入站/旧 pending 重发均阻止；`AppStateRestoreSpec` 覆盖 | 1–2d |
| R2-2 | P1 | `ReplyCreated`/`ReplyReceived` 已有 contact | D8、单一配对 | 双向同时接受邀请会产生两套不可互通的 responder session，旧 session 可借用新 contact 账号 | 已有 contact 时不创建新 session，contact 标 `AccountMismatch` 并 `PersistContact`；`BindingSpec` 覆盖两个方向；重新配对需先移除旧关系（S1 外） | 0.75–1.5d |
| R2-3 | P1 | `AppRuntime.sendMessage` 入队前无检查 | B3/B4 | core 静默丢弃但 HTTP 200，UI 清空未发送草稿 | `Application.canSend` 预检查；`RuntimeError("send-rejected")` 经 `ServiceError.BadRequest` 返回 400；E2E 安全门前置已断言 400 | 0.5–1d |
| R2-4 | P1 | `ApiJson.lastVisible`/`AppService.messagesJson` 用 SQLite 插入序 | Y2、验收 4 | 先缓冲 seq2 再到达 seq1 时显示 `[2,1]`，首条正文可见 | `ApiJson.ordered` 按 `(seq,id)` 排序；`lastVisible` 与 API 列表共用；`ApiJsonSpec` 覆盖 | 0.5–1d |
| R2-5 | P2 | `messagesJson` 先 `LIMIT` 后过滤；preview 读 1,000 行 | D2/D5 | `limit=1` 可能返回空；preview 重复解码大量 wire | API 取 `limit + MaxBuffered` 后过滤/排序/`takeRight`；preview 只取 `MaxBuffered + 1`；`ApiJsonSpec` 覆盖排序谓词 | 0.5–1d |
| R2-6 | P2 | `ReplyReceived` 原始账号比较 | D2/D8 | NFC 归一化差异把合法账号判为 mismatch | 使用 `Reply.matchesAccount` 规范化比较；错误关系仍被 mismatch/空账号门挡住 | 0.25d |
| R2-7 | P2 | `BindingSpec` 缺失绑定用 raw legacy,B-first 用 helper | Y5/R5、B2 | 未覆盖 canonical `DataPayload(None)` 和真实 B `UserSend` | 增加 `DataPayload(None)`、仅 sender 错、真实 B `UserSend` 的 B-first 测试;`DataPayloadSpec` 增加非规范解码拒绝 | 0.25-0.5d |
| R2-8 | P1 | `ReplyCreated` 已有 contact 分支 | Q1/D8 | 同一邀请的重复 `ReplyCreated`（双击/重试）会用同一 session 触发 `AccountMismatch`，把合法关系标坏 | 先用 `result.state.sessionId` 判断同一会话并幂等返回;`BindingSpec` 覆盖重复与不同 session 冲突 | 0.25d |

Round 2 结论:R2-1-R2-8 关闭;无剩余 BLOCKER/MAJOR。已知限制:legacy 历史只读、不可续聊;同一 peer 重新配对需移除旧关系;Q1 仍不表达 wire binding。
