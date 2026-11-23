# Design review findings — Plan 0006

对象：`design.md`、冻结实现 diff、E2E#12/#13/#14 结果。

## Round 1

| ID | 严重度 | 原句/位置 | 规则 | 问题与影响 | 关闭证据 | 规模代价 |
| --- | --- | --- | --- | --- | --- | --- |
| R1-1 | BLOCKER | §5「解密失败触发自动重建」 | #14、ADR-0006 §6、R5 | 原 E2E 只篡改密文字节而不重签；B 在签名验证阶段拒绝，AEAD 失败计数不增长，自动重建永远不会触发。 | 新增 `TamperCiphertextSigned`：测试进程读取 A 的持久身份/会话，生成合法首条 Data，代理篡改密文后用 A 设备私钥重签；3 次注入后 `E2E#14` active session id 改变，新消息送达。 | 0.25–0.5 人日 / 0 表 / 0 组件 |
| R1-2 | P1 | `AppService.open` 错误映射 | #13、D8 | 错误口令返回 `StoreFailed` → HTTP 500；锁契约要求 401，且 500 会误导灾难恢复。 | `StorageError.UnlockFailed` 映射为 `ServiceError.UnlockFailed`；`E2E#13` `wrong=401`。 | 0.05–0.1 人日 / 0 表 / 0 组件 |
| R1-3 | P1 | `AppEffect.StartRekey` 执行 | #14、Y3 | 自动重建必须走现有 `Rekey.start` 的 pending/cooldown/attempt 上限，不能另造无界循环。 | 运行时只生成 r1/rekeyId/nonce 并提交现有 `StartRekey` 事件；`RekeySpec`/`RekeyTimerSpec` 保持；`InboundSpec` 阈值断言 `StartRekey(sessionId)`。 | 0.2–0.4 人日 / 0 表 / 0 组件 |
| R1-4 | P2 | #12「diagnostics」范围 | ADR-0003、R5 | `/api/state` 按 UI 需要携带 public key；把它当日志会导致错误地要求删除路由数据，或无法构造可证伪测试。 | 文档和测试明确只扫描服务日志 + HTTP 错误体；`E2E#12` 扫 body/alias/user/device key/safety，`/api/state` 不在范围内。 | 0.05 人日 / 0 表 / 0 组件 |
| R1-5 | P2 | #13 空闲锁定 | verification #13、R5 | E2E 只跑手动锁，不能声称空闲路径已由两进程验证。 | 保留 `AppLockSpec` 的注入时钟测试；`impl.md` 明确 E2E 仅手动锁，空闲是单元/状态机层证据。 | 0 人日 / 0 表 / 0 组件 |
| R1-6 | P2 | #14 旧 pending 跨 session | Q5、R5 | 自动重建后旧会话未确认消息不自动重映射；若把它写成已保证会过度声明。 | `impl.md` 已知约束明确不承诺旧 pending 自动重发；E2E 用注入帧避免把旧 pending 混入重建断言；新消息在重建后送达。 | 0 人日 / 0 表 / 0 组件 |

Round 1 结论：R1-1/R1-2/R1-3 已关闭；R1-4–R1-6 作为范围/声明限定关闭。无剩余 BLOCKER/MAJOR。残余风险：E2E#14 的注入帧使用测试读取的身份/会话，是测试辅助，不是生产后门；外部密码学评审仍是发布门。

## Round 2 (implementation review)

| ID | 严重度 | 原句/位置 | 规则 | 问题与影响 | 关闭证据 | 规模代价 |
| --- | --- | --- | --- | --- | --- | --- |
| R2-1 | P1 OPEN | `S1E2E.scala` E2E#14 | #14 | 注入帧使用 A 的真实持久身份，但 A 服务没有先消费一个真实序号；因此没有证明真实发送方在 cutover 后能继续发下一条（旧 pending 跨会话重映射未实现）。 | 当前只证明 3 次有效签名 AEAD 失败触发自动 rekey、新 session 可用、假正文不可见。需要普通 send 先消费 seq，再断言 cutover 后下一条送达。 | 1–3 人日 |
| R2-2 | P1 | `AppRuntime.scala` StartRekey | #14 | `tryOffer` 失败现在设置 `fatal=rekey-queue-full`，不再静默丢弃；但还没有专门队列满回归。 | 代码路径存在；E2E 未覆盖队列满。 | 0.25 人日 |
| R2-3 | P1 | `Ratchet.scala:181` | ADR-0006 §6 | 现在仅 `header.n == state.nr` 的当前链 AEAD 失败计数；前向 gap 不触发重建。 | 新增 `RatchetSpec` 前向 gap 用例；core.test 全绿。 | 0 表 |
| R2-4 | P1 OPEN | `AppRuntime` notification | #14 | `RekeyFailed` 仍只进入内部 queue，无生产消费者/服务边界可见；重建失败可能不可见。 | 未关闭。 | 0.5–1 人日 |
| R2-5 | P2 OPEN | `Rekey` 成功 cutover | verification §20 | 每次成功 rekey 重置 per-session attempts/cooldown，跨成功 cutover 无窗口 cap。 | 未关闭；当前只有 per-session 上限。 | 0.5–1 人日 |
| R2-6 | P2 | `S1E2E.scala` E2E#12 | #12 | 已扫描正文/alias/public/private/passphrase/safety 与错误体；错误请求现在带唯一 marker 且超长。 | `E2E#12` 通过。 | 0 表 |
| R2-7 | P2 | `S1E2E.scala` E2E#13 | #13 | 锁响应已断言无 identity/contacts/conversations；受保护/错误响应为 code-only；失败断言不再插入完整 state/history。 | `E2E#13` 通过。 | 0 表 |

Round 2 结论：R2-1、R2-4、R2-5 为剩余范围/产品缺口；#14 不能按完整契约签收。R2-2/R2-3 已实现，R2-6/R2-7 已关闭。不得把本计划的“14 条 E2E”写成 #14 的完整行为证明。
