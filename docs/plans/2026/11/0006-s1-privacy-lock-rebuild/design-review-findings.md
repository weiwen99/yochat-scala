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
