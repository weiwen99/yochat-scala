# Plan 0015 test parity: S3-A

- 来源仓库：`~/projects/yochat-reference`（只读快照，无 git 元数据；读取日期 2026-11-27）
- 覆盖范围：S3-A 的 9 条采纳需求（含 5 条部分）与 8 条既有行为契约；S3-B/S3-C 未开工，不在本表
- 规则：T1（ADR-0004）保留功能的旧测试逐条映射；未移植的写理由；人签字。owner 在 2026-11-27
  委派执行者决定（design 的「裁决」段），本表的签收按委派记录，不冒充独立外部评审。

## 映射

| 需求 | 旧测试（来源） | 新测试 | 结论 |
| --- | --- | --- | --- |
| `ZWMFKB381M0AKCYN` 拉黑停止双向且不产生响应 | `tests/blocking.rs`：`两侧拉黑均跨重启保留且旧关系不能覆盖_解除恢复原状态`（`admit` 返回 `Drop(Blocked)`）；`tests/inbound_discard.rs`：按理由计数 | `store/test/.../RelationshipStoreSpec`（block 持久、幂等、跨重启、孤立键/回滚失败关闭）；`server/test/.../e2e/S3E2E`（3 条入站不落盘、会话不推进、出站变 Failed 且不到对端；Reply 入口单独覆盖） | 等价，范围收窄：旧实现的多设备重连队列与分批公平（32/70）不在 v1，见「未移植」；Fanout/FanoutAck/device-list 的负断言仍只有 unit/store 覆盖 |
| `TW7PP68XQAKBAVYH` 拉黑可推断性说明 | 旧 UI spec `blocking.e2e.ts`（只读快照） | 手工点：联系人卡片的拉黑确认两条说明；`S3E2E` 不含 UI 文案 | 部分：文案不占自动门；`S3E2E` 只证明行为，不证明提示语 |
| `J22VY0VTZVRBY1P6` 解除拉黑补齐窗口内消息 | `tests/blocking.rs`：`解除与唤醒同事务_离线重启保持节流_重复不积累_再拉黑取消`；`多设备唤醒分批`；`contact_sync.rs`：`remote_unblock_queues_the_same_bounded_connection_recovery_as_local_unblock` | `S3E2E`：解除后对方仍在重试的消息到达；`core/test/.../InboundSpec`：`GapTooLarge` 触发重建；`store/test`：unblock 幂等 | 等价 + 降级：窗口内补齐有证据；窗口外按裁决 2(a) 如实告知，不实现批准重发 |
| `F7ZBKSBAC1N4KM2K` 删除联系人询问是否删除历史 | `tests/relationship.rs`：`删除时若处于已拉黑则留下拉黑记录`；`contact_sync.rs`：`generation_delete_context_is_immutable_and_converges_in_every_delivery_order`、`bootstrap_scope_change_and_contact_deletion_revoke_only_active_provenance` | `store/test/.../RelationshipStoreSpec`：保留历史时消息仍在且外发变 Failed；删除历史时会话/消息/会话行清空；`S3E2E`：保留历史的会话仍在；UI 删除确认框（手工） | 部分：本机询问与执行有证据；逐设备确认（`K54NXXBZW1R6415F`）延后，其他设备保留各自历史 |
| `SD1XKGXS4DBG5ZTE` 删除已拉黑保留拉黑记录 | `relationship.rs`：`删除时若处于已拉黑则留下拉黑记录` | `S3E2E`：拉黑后删除，`blockedPeers` 仍含该用户；`RelationshipStoreSpec`：block 行独立于 contacts | 等价 |
| `XS6GKW2ZZ4R1CS6Y` 重新添加不继承校验状态 | `contact_sync.rs`：`delayed_ui_verification_cannot_be_retagged_as_consent_for_a_readded_contact`、`cached_verified_relationship_cannot_reverify_a_deleted_and_readded_contact` | `RelationshipStoreSpec`：墓碑 upsert 复活为 `safetyVerified=false`、`recreated=true`；`S3E2E`：重加联系人为未校验且 `recreated=true`；UI 显示「重新建立的关系」（手工） | 等价；「提示这是新建立的关系」只有展示位，不占自动门 |
| `GG1H42XAJXA0M4ZT` 联系人刷新保留拉黑与校验 | `contact_sync.rs`：`current_local_block_survives_remote_metadata_and_can_be_explicitly_cleared`、`the_first_contact_write_already_has_a_device_generation_and_durable_sync_operation` | `RelationshipStoreSpec`（block 与 contact 行分离）；`server.s2e2e`（设备刷新不改 contacts）；`S3E2E`（拉黑跨删除/重加保留） | 部分：设备刷新这条路做了；「经另一份邀请再应答作为新待确认项」按裁决 8(a) 维持 `AccountMismatch`，`S3E2E` 断言该行为 |
| `9MKG1QMBXQAG6C12` 无法提供的消息有可验证答复 | `tests/approval_wait.rs`、`fallback_resend.rs`（旧协议状态；只读快照） | `core/InboundSpec`：`GapTooLarge` → `ChannelRebuild` 通知 + 重建；UI 横幅文案（手工） | 部分：通道级「取不回」说明有证据；消息级「暂不可得/不再提供」状态随批准队列延后 |
| `0P17VCWKG2FZ26SZ` 别名与备注私有 | `contact_sync.rs`：`blocked_page_reads_only_alias_and_rejects_malformed_alias`（页面只读 alias）；`ContactInvitationSpec`（sealed contact 的 AEAD/AAD） | `store/test/.../ContactInvitationSpec`（sealed 载荷、回滚检测）；备注 codec v2 的读写由 `RelationshipStoreSpec` 的墓碑/复活覆盖；UI 筛选（手工） | 等价：别名/备注都在 sealed 载荷里；「按用户别名检索」随公开资料延后 |
| 既有 16 条行为契约 | `tests/*`（S1/S2 的旧集合） | `server.e2e`（S1E2E 16 条）、`server.s2e2e`、`core.test`、`store.test` | 不因 S3 变红；新增的是 S3 契约 |

## 未移植与理由

1. `blocking.rs` 的 `contact_reconnect` 队列、`claim_contact_reconnect*`、32 台分批与
   `finish_contact_reconnect`：旧实现用持久重连作业队列补齐解除拉黑后的连接。v1 只有单个对端
   设备、没有作业队列；S3-A 采用「被动补齐 + GapTooLarge 重建」并如实说明窗口外取不回
   （裁决 2a）。移植该队列会引入新表和新协议状态，超出 S3-A 预算。
2. `blocking.rs` 的 `blocked_page_reads_only_alias` 的「页面只读 alias」：v1 没有独立的 blocked
   page；拉黑列表只显示联系人显示名或短公钥，不读取备注。
3. `contact_sync.rs` 的共享联系人操作日志（LWW、字段时钟、分页、generation 删除上下文）：
   v1 不使用旧的多设备联系人 op log，A2 只接收 A1 的密封快照（ADR-0011）；这些测试绑定的是
   已不存在的协议。
4. `relationship.rs` 的发起方/应答方完整状态机：v1 的关系只有单联系人 + 确认状态，没有
   Initiator/Replier 双状态机；对应的行为在 `AppStep.ReplyReceived`、`RelationshipStoreSpec`
   和 `S3E2E` 覆盖，状态机穷举性质不适用。
5. `inbound_discard.rs` 的 `record_inbound_dropped` 计数：v1 没有按理由计数的入站丢弃表；
   S3-A 的丢弃是静默的（要求「不产生任何响应」），计数会与「不落盘」相抵，未移植。
6. `CompanionUpsert.inserted`（A2 首次/重复投递信号）没有生产消费者：A2 没有推送通道，
   提示最终由 A1 的 `lateArrival` 按消息 id 去重；该 API 已随实现删除，同 `logicalKey` 的去重
   语义仍由 `S2StoreSpec`（`sourceId==7`、`count==1`）覆盖。
7. 旧 E2E `specs/blocking.e2e.ts`、`blocking-recovery.e2e.ts`、`blocking-sustained.e2e.ts`：
   旧 WebdriverIO 配置只有 5 行 spec 列表，快照里没有 spec 正文；无法逐条对照。新的
   `S3E2E` 覆盖同样的用户路径（拉黑、出站失败、解除补齐、删除保留、重加未校验）。

## 签字

- owner 于 2026-11-27 委派执行者决定 S3 的范围与取舍（原话「请开始你的设计和实现。我希望中间
  不需要我干预。」）。本表按该委派签收；未做独立外部评审。

## S3-B 映射（2026-11-27）

| 需求 | 旧测试 | 新测试 | 结论 |
| --- | --- | --- | --- |
| `G3JQAH0SXEXVDATA` 通知内容边界 | 旧 `specs/blocking*.e2e.ts` 不覆盖通知；旧仓库无等价断言 | `shell/electron-smoke.js` 的 notifications 阶段：通知携带 `conversationId`；默认正文固定为「有一条新消息」（不含消息文本）；打开正文开关后含正文；逐会话静音后不再产生通知；点击通知把界面切回对应会话。负例：删掉主进程 click 转发后该阶段超时失败（已实测） | 等价，覆盖三条采纳子项；「定位到具体消息」按设计不做 |
| `377PNHQWJ8PGCN80` 连接方式可见 | 旧 UI spec（只读快照，无正文） | `shell/ui-smoke.js` 的 keyboard-journey 阶段断言 `.head-route[data-route=relay]` 文本含「经中继」；聊天页与只读页共用 Shell | 等价 |
| `3AEMNVEGME49ZT2R` 可达性基线 | 旧 WebdriverIO 键盘用例（快照缺正文） | `ui-smoke`：modal 焦点陷阱（既有）+ 新增 keyboard journey（键盘切到联系人、Tab 到「打开聊天」、Enter 回到会话、键盘发送）；`contrast-smoke` 保持绿；外观跟随系统由 `color-scheme` + 两套主题对比度检查覆盖 | 部分：渲染级对比度/焦点 3:1 测量按设计不做 |
| `Q0MQBNAVS1TB1PBW` 使用前告知 | 无 | `ui-smoke` 的 disclosure 阶段：添加联系人对话框含 ≥5 条告知（中继可见项、公网地址、中转地址、拉黑可推断与在线状态、无公开目录），点「我已了解」后收起；版本存本地偏好 | 等价；首次添加与邀请生成同处一个对话框，两处都可见 |
| `386RMXBPWJYEXT9P` 在线状态告知 | 无 | 同上：告知文案含「不提供在线状态、没有隐藏开关、单靠用户公钥不能查询在线状态」 | 等价（只告知，不做 presence） |
| `REQ_20261103_NS77FQDYNCMNF71P` 迟到可察觉 | 旧 `history_arrival.rs`（只读快照） | S3-A 的 `InboundSpec.lateArrival` + UI 横幅按消息 id 去重；`StatusBanners` 只保留最近一条 | 部分：定位查看/锚点/重启后仍在按设计不做 |
