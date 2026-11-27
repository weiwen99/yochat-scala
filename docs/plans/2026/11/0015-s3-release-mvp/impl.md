# Plan 0015 implementation record: S3-A

## 状态

- **S3-A/B/C 均已实现**；S3-A 完成两轮实现评审，S3-B/C 见下节。
- 存储扩展按 `docs/adr/0012-s3-storage-blocked-peers-and-contact-tombstone.md`；设计记录按
  `design.md` 的「裁决」冻结（2026-11-27，owner 委派）。
- 全量 `./check.sh` 回执见 `tmp/checks/`（本文件只记已实际跑过的命令与结果）。

## 交付范围

| 需求 | 落地 |
| --- | --- |
| `ZWMFKB381M0AKCYN` 拉黑停止双向且不产生响应 | 六类入站入口统一判定；八种出站 kind 发送前判定；拉黑后入站不落盘、不推进游标、不产生 ACK/拒绝 |
| `TW7PP68XQAKBAVYH` 可推断性说明 | 拉黑确认框两条说明（可推断、只绑定一把公钥）；界面不声称「对方不会知道」 |
| `J22VY0VTZVRBY1P6` 解除拉黑补齐 | 窗口内自动补齐（对方仍在重试）；窗口外 `GapTooLarge` → 重建通道 + 明确提示取不回（裁决 2a） |
| `F7ZBKSBAC1N4KM2K` 删除询问历史 | 删除确认框可选「同时删除本机历史」；保留历史时会话/消息仍在，未完成外发标 Failed；删除历史时连会话一起删 |
| `SD1XKGXS4DBG5ZTE` 删除已拉黑保留记录 | `blocked_peers` 独立于联系人；删除后仍出现在「已拉黑」 |
| `XS6GKW2ZZ4R1CS6Y` 重加不继承校验 | 墓碑 upsert 复活为 `safetyVerified=false`、`recreated=true`；界面显示「重新建立的关系」 |
| `GG1H42XAJXA0M4ZT` 刷新保留拉黑与校验 | 拉黑表按 userPub、联系人校验按 userPub，设备列表刷新不改它们；二次邀请应答按裁决 8a 维持 `AccountMismatch` |
| `9MKG1QMBXQAG6C12` 无法提供的消息有答复 | 通道级：`GapTooLarge` 提示「通道已重建、间隔期间可能取不回」；消息级批准队列延后 |
| `0P17VCWKG2FZ26SZ` 别名/备注私有 | 两者都在 sealed contact 载荷；备注补齐写入路径；联系人列表按名称/账号/别名/备注内存过滤 |

## 文件变更

| 文件 | 变化 |
| --- | --- |
| `docs/adr/0012-...md` | 新增存储 ADR：blocked_peers、contacts codec v2、删除语义、锁序、已知约束 |
| `docs/plans/2026/11/0015-s3-release-mvp/design.md` | 八条裁决、冻结、存储/验收口径 |
| `docs/plans/2026/11/0015-s3-release-mvp/meta.md` | 状态与下一步 |
| `docs/plans/2026/11/0015-s3-release-mvp/test-parity.md` | S3-A 旧测试逐条映射、未移植理由、委派签收 |
| `store/.../Records.scala` | `ContactState`、`ContactData.state/recreated`、`BlockedPeerRecord` |
| `store/.../StoreData.scala` | contact codec v2（v1 兼容读，AAD 不变） |
| `store/.../Schema.scala` | schema v6：`blocked_peers`、复合索引、迁移路径 |
| `store/.../BlockedPeerRepository.scala` | 拉黑行/高水位双向核对、范围查询 |
| `store/.../RelationshipRepository.scala` | 联系人墓碑 + 可选历史/会话删除 + 邀请清理 + 锁定顺序 |
| `store/.../ContactRepository.scala` | active-only 读、`listAll`、同步 upsert、墓碑复活 |
| `store/.../SessionRepository.scala` | `deleteForPeerProgram`（删除历史用） |
| `store/.../SnapshotRepository.scala` | 快照只含 active 联系人 |
| `store/.../CompanionMessageRepository.scala` | 恢复原返回类型（去掉未消费的 inserted） |
| `store/.../Store.scala` | 拉黑缓存与 API、`applyContactFromSync`、`deleteRelationship` |
| `store/.../StoreRuntime.scala` | `blocked-version` 高水位键 |
| `core/.../AppTypes.scala` | `AppContact.note`、`LateArrival`、`Notification.ChannelRebuild` |
| `core/.../AppEvent.scala` | `SetContactNote`、`ContactDeleted` |
| `core/.../AppStep.scala` | GapTooLarge → 重建 + 提示；迟到补入；备注；删除事件 |
| `server/.../AppRuntime.scala` | 入站六入口与出站 send 的 `denied` 判定；Reply 开箱后判定 |
| `server/.../PeerDeviceListService.scala` | 入站/A2 接收 guard；出站 publish 跳过拉黑/无联系人目标 |
| `server/.../FanoutService.scala` | 发消息/重发/回执按联系人判断 |
| `server/.../CompanionFanoutService.scala` | A2 收到 Fanout 先判定发送方 |
| `server/.../CompanionSyncService.scala` | 快照写联系人状态 + 拉黑（单事务）；note |
| `server/.../DeviceSyncService.scala` | 快照携带 state/recreated/note/blocked |
| `server/.../ApiJson.scala` | blocked/note/recreated、blockedPeers、lateArrival；存储失败传播 |
| `server/.../CompanionView.scala` | A2 视图同字段；错误传播 |
| `server/.../AppService.scala` | block/unblock/note/delete API；状态发布 |
| `server/.../HttpApi.scala` | 四个新路由 |
| `server/.../AppStateRestore.scala` | note 映射 |
| `server/.../AppEffectExecutor.scala` | note 持久化 |
| `server/.../DeviceSyncScheduler.scala` | 注释改为实际行为 |
| `server/test/.../e2e/S3E2E.scala` | 新增 S3-A 双进程契约 |
| `build.mill` | `server.s3e2e`；`server.e2e` 同时跑 S1+S3 |
| `ui/.../Model.scala`、`ApiClient.scala`、`UiState.scala`、`ContactsPanel.scala`、`ChatPanel.scala`、`StatusBanners.scala` | 拉黑/解除/删除/备注/筛选/迟到提示/只读禁用 |
| store/core/server 测试 | 上述行为的单元、迁移、EXPLAIN 与状态机断言 |

## 关键行为

- **准入判定**：`denied(userPub) = blocked || 无 active 联系人`；Reply 在开箱后只查 blocked；
  DeviceSync 是本人设备，不查对端。单行拉黑删除/回滚在打开实例时失败关闭
  （`StaleGeneration` / `MissingHighWater`）；协调回滚是已登记已知约束。
- **拉黑**：写拉黑成功即发布状态并触发 A1→A2 快照；重复拉黑幂等；解除删除行与高水位键。
- **删除联系人**：联系人墓碑（version 递增、清空别名/备注/渠道身份）→ 删收到的邀请 →
  可选删会话 → 删消息（按 conversation 与 session 两条键）→ 可选删会话行。
- **重加**：只能由新邀请触发；复活为未校验、`recreated=true`；核对安全码后清除。
- **迟到补入**：缓冲消息在缺口补齐时按消息 id 记 `lateArrival`，UI 按 id 去重。
- **GapTooLarge**：`Notify(ChannelRebuild)` + `StartRekey`，不再静默丢帧。
- **错误码**：store 失败返回 `store-failed:<code>`；`/api/session` 不再把存储失败折叠成空值或
  `RuntimeError`。

## 验证

| 命令 | 结果 |
| --- | --- |
| `./mill core.test` | 145/145 SUCCESS（含 GapTooLarge、lateArrival） |
| `./mill store.test` | 198/198 SUCCESS（含拉黑/墓碑/历史/孤儿会话/EXPLAIN） |
| `./mill server.test` | 399/399 SUCCESS |
| `./mill server.s3e2e` | PASS（block/unblock/delete/re-add/Reply guard） |
| `./mill server.s2e2e` | PASS（A2 同步与 B2 扇出未回归） |
| `./mill server.e2e` | 18/18 PASS（S1 既有契约 + S3；含 1k/10k/PERF#RATE） |
| `./mill core|store|server|ui.checkFormat`、`.fix --check` | 全绿 |
| `./mill ui.fastLinkJS` | SUCCESS |
| `./check.sh` | **EXIT=0**（2026-11-27，m1）；S1 E2E 18/18、S3E2E、S2E2E、S2PERF、S2BPERF、CONTRAST、COLD_START p50=1190ms/p95=1255ms、UI_SMOKE、ELECTRON_UI、Quint、spike crypto/storage/doobie 全 PASS |

回执在 `tmp/checks/`（`desktop-gate-*.json`、`ui-*.json`、`electron-ui-*.json`、
`desktop-*.json`、`s1-e2e.log`）。工作树里 `shell/node_modules` 需要指向主检出的 node_modules
（新工作树不带 npm 依赖）；这是环境前置，不是代码通过条件。

## 实现评审记录

- **第 1 轮（Standards + Spec）**：1 条 P1（DeviceList 出站无 guard）、8 条 P2（拉黑行删除
  不可检测、存储失败被吞、锁序、ADR 与实现矛盾、A2 半应用、A2 按钮、状态不发布、查询计划）、
  9 条 P3。全部处置：P1/P2 关闭；P3 中 `CompanionUpsert` 死 API 回退、GapTooLarge 恒真断言
  修正、会话口径统一，其余登记为已知缺口或文档修正。
- **第 2 轮（复审修复 diff）**：9 条关闭、4 条部分（锁序声明、ADR 自相矛盾、A2 同类按钮、
  查询计划），新增 1 条 P2（A2 的核对/别名按钮）与 8 条 P3。按 §7「第 3 轮问题数不降就停」
  不再开全量轮次；收尾提交关闭：A2 按钮、锁序/ADR 文档、孤儿会话消息删除、meta 范围查询、
  复合索引与 EXPLAIN 断言、`/api/session` 错误码、注释与 test-parity 记录。
- **定向复核**：重读收尾 diff；`RelationshipStoreSpec` 的 EXPLAIN 断言覆盖
  `conversations`/`sessions`/`meta` 三个查询；S3E2E 重跑通过。未做第三次全量评审。

## S3-B 交付（2026-11-27）

| 需求 | 落地 |
| --- | --- |
| `G3JQAH0SXEXVDATA` 通知内容边界 | 渲染端决定是否通知（页面隐藏、逐会话静音、正文开关），载荷只有 `conversationId` 与可选正文；主进程默认固定文案「有一条新消息」，点击把会话 id 回送渲染端并切回该会话。正文默认关；逐会话静音存本地偏好。 |
| `377PNHQWJ8PGCN80` 连接方式可见 | 聊天页头部 `data-route=relay`，文本「经中继 · 在线/离线」，路由条「本机 → 中继 → 对方」。 |
| `3AEMNVEGME49ZT2R` 可达性基线 | 键盘旅程：Tab 到联系人 → Enter → Tab 到「打开聊天」→ Enter 回到会话 → 键盘发送；弹窗焦点陷阱继续由 ui-smoke 覆盖；外观跟随系统由 `color-scheme` 与两套主题对比度门覆盖。 |
| `Q0MQBNAVS1TB1PBW` / `386RMXBPWJYEXT9P` 使用前告知 | 添加联系人对话框内置「使用前须知」：中继可见项、对方可见公网地址与设备公钥、本机中转地址、拉黑可推断且无在线状态开关、无公开目录；版本化，点「我已了解」后收起。 |
| `REQ_20261103_NS77FQDYNCMNF71P` | 沿用 S3-A：`lateArrival` 按消息 id 触发一次可见横幅。 |

新增门禁断言：`ui-smoke` 的 disclosure 与 keyboard-journey 阶段；`electron-smoke` 的 notifications 阶段
（默认无正文、正文开关、逐会话静音、点击切回会话）。负例实测：删掉主进程 click 转发后，
electron-smoke 在 notifications 阶段超时失败。

## S3-C 交付（2026-11-27）

| 需求 | 落地 |
| --- | --- |
| `8F32S19RPRC25471` 全文搜索 | `MessageRepository.search`：按 rowid keyset 分页、每页 256、扫描上限 5,000、结果上限 50；正文解密后内存子串匹配（`Locale.ROOT`），`scanned`/`truncated` 如实返回。`GET /api/search`（≥2 字、≤50 条，未解锁 401、坏参 400、存储失败 `store-failed`）。UI 会话列表搜索框（200 ms 去抖），结果显示联系人/片段/时间，点击切会话并清空。 |
| `3TK81N30WS2S6JWB` 大量历史 | ADR-0013 定义形状（100,000 条 × 128 字节）与两道门（首屏 p95 ≤ 250 ms、最坏情况搜索 p95 ≤ 1,500 ms）；`./mill store.searchbench` 实测首屏 p50 9 / p95 10 ms、搜索 p50 32 / p95 50 ms（2026-11-27，m1），超阈值非零退出；`check.sh` 覆盖。 |

新增门禁：`store.searchbench`（已进 `check.sh`）、`MessageSearchSpec`（6 条：匹配/上限/参数/
删除后搜不到/查询计划）、`ui-smoke` 的 search 阶段。旧 `scale.e2e.ts` 的 UI 级阈值（500 ms 首屏 /
100 ms 帧 / 1 s 搜索）不继承，口径不同（ADR-0013、裁决 5）；滚动帧与内存增长不在本波。

## 已知缺口

1. **Fanout / FanoutAck / device-list 的双设备负断言**只有 store/unit 覆盖：A2 的 Fanout
   丢弃与 A1 的 FanoutAck 判定没有放进 `S3E2E`（需要 companion 配对）。设计验收里的四张表
   负断言（`companion_messages`、`peer_device_lists`、`delivery_receipts`、待确认项）尚未全部
   自动化；`messages`/`sessions` 已有。
2. **A2 没有实时推送通道**：拉黑/删除同步到 A2 后，A2 的只读界面要等下一次刷新才显示；
   S3-B 的通知/可达性也不在 A2 上。
3. **窗口外消息取不回**：裁决 2a，批准重发队列（`XBRY5Q81Z4ZPP6ER`）延后。
4. **恢复短语与消息删除**仍延后；`contacts` 墓碑不是消息墓碑。
5. **S3-C 的测量边界**：门是 store 级（解密 + 查询），不覆盖 UI 滚动帧与内存增长；很旧的命中
   可能被 5,000 行扫描上限漏掉，界面显示 `truncated` 提示；A2 只读库不提供搜索。
6. 同一数据目录不支持同时打开两个 `Store`；第二个实例的写入对第一个实例的拉黑缓存不可见
   （ADR-0012 已知约束 5）。
