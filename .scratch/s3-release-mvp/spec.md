# S3 发布级 MVP：范围与状态

- Plan: 0015（设计记录 `docs/plans/2026/11/0015-s3-release-mvp/design.md`）
- 需求编号用 ID 后缀；完整 ID 见 `docs/references/2026-11-20-yochat-reference-study/requirements.md`
- Status: **draft，未开工**。七条待裁决未定前不动代码。

## 一句话

S3 = 关系结束（拉黑/删除）+ 发布级可见性（通知、连接方式、可达性、使用前告知）+ 历史可用性
（搜索/十万条，阻塞于 ADR）。从 35 条候选里采纳 15（其中 8 条部分）、阻塞 2、延后 18。

## 采纳

**S3-A 关系结束（9，其中 4 条部分）**
`ZWMFKB381M0AKCYN` `TW7PP68XQAKBAVYH` `J22VY0VTZVRBY1P6`（部分）`F7ZBKSBAC1N4KM2K`（部分）
`SD1XKGXS4DBG5ZTE` `XS6GKW2ZZ4R1CS6Y` `GG1H42XAJXA0M4ZT` `9MKG1QMBXQAG6C12`（部分）
`0P17VCWKG2FZ26SZ`（部分：别名为主显示与私有性已满足；备注写入、按别名/备注检索本波做；按用户别名检索随公开资料延后）

**S3-B 发布级可见性（6，其中 4 条部分）**
`G3JQAH0SXEXVDATA`（部分：逐会话关闭、正文默认关、点击跳转会话；不含定位到消息）`377PNHQWJ8PGCN80` `3AEMNVEGME49ZT2R`（部分）`Q0MQBNAVS1TB1PBW`
`386RMXBPWJYEXT9P`（部分：只做告知，不做 presence）`REQ_20261103_NS77FQDYNCMNF71P`（部分：只做可见提示与不重复提醒）

**S3-C 历史可用性（2，阻塞）**
`8F32S19RPRC25471` `3TK81N30WS2S6JWB`

## 延后 18（要点）

恢复短语 5 条（冲击 ADR-0007 失败关闭边界）；消息删除 5 条（墓碑与逐设备确认，协议级）；
公开别名头像、公开张贴、渠道身份复用（v1 无公开面）；联系人分组、引用回复（成本高于收益）；
大范围重发批准、配额暂停、陌生对端预算（需新协议状态）。逐条理由见设计记录。

## 关键约束

- **拉黑窗口外取不回**：对方重试 3 次 / 约 15 秒（投递超时路径无退避）/ 在途 32 条后进 Failed，
  `requestResend` 不接受 Failed；棘轮跳号上限 64 / 累计 256。解封后 `GapTooLarge` 当前会静默失步，
  S3-A 必须补重建通道。
- **关系变更只在主设备发起**：`device_sync` 快照数据单向 A1→A2；A2→A1 只有 sync-status/ack
  （`syncRequest` 协议已定义但无生产发送方；DeviceList 要用户私钥、FanoutAck 方向相反），缺的是关系变更数据的上行承载与
  A1 应用。
- **没有消息墓碑**：S3 不做消息删除；将来若要，扇出与重传需要消息墓碑才不复活。联系人删除用的
  contacts 墓碑是另一件事。
- 行预算：19,614 行基线（只计五个模块 `*/src`），A+B 估算 3,500–5,500 行。
- **可达性只到主流程与焦点**：渲染级对比度/焦点 3:1、搜索入口不做。
- **迟到补入只到可见提示**：定位查看、锚点与草稿保留、重启后待查看不做。
- v1 的 16 条行为契约不变，S3 只新增。

## 七条待裁决

1. 采纳 15（含 8 部分）/ 阻塞 2 / 延后 18 是否接受。
2. 解除拉黑窗口外：如实告知取不回，还是把最小批准路径拉进 S3-A。
3. A2 上能否发起关系变更：禁用并提示，还是补反向通道。
4. 搜索存储取舍：有界解密扫描 / 明文索引 / 不做。
5. 十万条指标是否采纳。
6. 行预算口径与上限。
7. 存储 ADR（新密封域与 AAD）由谁写、走 ADR-0007 revision 还是新 ADR。

## 下一步

裁决通过后：写 `test-parity.md`（对照旧 `blocking.rs`、`contact_sync.rs`、`relationship.rs`、
`deletion_directive.rs`、`inbound_discard.rs` 与 `wdio.blocking*.conf.ts`），再按 S3-A 开实现计划。
