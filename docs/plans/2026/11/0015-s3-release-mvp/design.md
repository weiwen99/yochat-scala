# Plan 0015 design: S3 发布级 MVP 的范围

- Status: frozen（2026-11-27；八条裁决已由 owner 委派决定，见「裁决」；实现从 S3-A 开始）
- 前序：ADR-0003（v1 范围与 S2/S3 边界）、0001（重写）、0010/0011/0012/0013（S2 与 S2b 多设备）、0014（桌面门禁）
- 候选来源：`docs/references/2026-11-20-yochat-reference-study/requirements.md` 的 S3 段（35 条，文件自述「是建议，不是已拍板的 S3 清单」）
- 需求编号：正文用 ID 后缀；完整 ID 见来源表。两条前缀不同，按全 ID 写：`REQ_20260919_757FNZE6B8FN15YV`、`REQ_20261103_NS77FQDYNCMNF71P`

## 规模与交付边界

真正的约束是仓库硬上限：**v1 生产代码 ≤30,000 行、测试 ≤15,000 行**（AGENTS.md §5）。

**口径**（上一版未写明，是第 1 轮评审的 P1）：下表只计 `core store transport server ui` 的
`*/src`。不计 `spike/src`（2,143 行，gate 实验代码）与 `shell/*.js`（壳层，不是 v1 产品逻辑）。
若把两者计入，基线 23,990 行，余量相应减少 4,376 行。裁决 6 维持此口径：AGENTS.md §5 的
「v1 生产代码」指产品模块，`spike/` 是可弃实验代码，`shell/*.js` 是壳层；两者单列观测，不占产品额度。

| 项 | 当前 | 上限 | 余量 |
| --- | ---: | ---: | ---: |
| 生产代码（五个模块 `*/src`） | 19,614 | 30,000 | 10,386 |
| 生产代码（含 `spike/src` 与 `shell/*.js`） | 23,990 | 30,000 | 6,010 |
| 测试（五个模块 `*/test`） | 9,845 | 15,000 | 5,155 |
| 表 | 12 | — | — |

**估算方法**（替代上一版无依据的区间）：以 S1 的实际密度为基线——19,614 行覆盖 40 条 S1 需求
与全部基础设施，约 490 行/条——再按每条复杂度乘系数。这是**估算**，量级判断，不是实测。

| 维度 | 需求给的 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 需求条数 | 35 条候选 | 采纳 15（其中 9 条部分采纳）、阻塞待 ADR 2、延后 18 | 0.4× |
| 表数 | 0（现 12） | +1（`blocked_peers`）；裁决 8(a) 维持现状，不再加表 | 需解释 |
| 组件数 | 0 | +1（拉黑名单面板） | 需解释 |
| 配置项 | 0 | +2（通知正文开关、隐私告知已读版本） | 需解释 |
| 工程师日 | 未给 | S3-A 12–18、S3-B 4–6、S3-C 3–4（估算） | — |
| 发布波次 | 1 | 3（A / B / C） | 3× |

- **需求 0.4×**：35 条全做按上法估算 11,700–18,000 行，累计 31,300–37,600 行，**下界已超
  30,000**。所以 S3 必须是子集；延后不是价值判断，是预算结果。
- **表 +1**：拉黑记录独立成表（删除联系人后关系没了、拉黑还在）。上一版还计划 `device_commands`，
  第 1 轮评审认定它不服务任何采纳需求、且与 `device_sync` 构成第二套机制，已删除；跨设备
  「删除历史需逐设备确认」随 `K54NXXBZW1R6415F` 一起延后。
- **组件 +1**：拉黑名单是新的设置面板。搜索复用 store/server/UI，不新增服务。
- **配置 +2**：本地偏好，不参与协议，不进同步。
- **波次 3×**：A、B 各自可独立交付与回退；C 由 ADR-0013 定稿后交付。

## 状态与前序

- 状态：**frozen（2026-11-27）**。八条裁决由 owner 在 2026-11-27 委派决定（原话「请开始你的设计
  和实现。我希望中间不需要我干预。」）；决定与理由见「裁决」，S3-A/B/C 均已实现，
  S3-C 的存储取舍与门见 ADR-0013。
- S1 已交付（ADR-0003）：单用户单联系人、邀请/应答、单聊、先落盘、重启与重连、应用锁、安全码。
- S2 / S2b 已交付（0010–0013）：本人多设备、设备列表与扇出、GBNV 因果序、吊销生效。
- 0013 的 `meta.md` 写「Next: S3 scope decision (Plan 0014)」；0014 已被桌面门禁占用，故用 0015。

## 背景与缺口

S1/S2 之后缺两类东西：

1. **关系的结束**。现在只能建立关系，不能拉黑、不能删除，也不能如实解释「删了之后对方会看到
   什么」。`docs/agents/verification.md` 把这块留给 S3，也是日常最先撞上的缺口。
2. **发布级的最低可见性**。通知里能出现什么、能不能看出走的是中继、键盘能不能走完主流程、
   使用前知不知道中转能看到什么——给别人用之前必须有。

其余候选依赖更重的东西：密钥管理（恢复短语）、协议状态（删除墓碑、大范围重发批准、配额、
陌生对端预算）、或我们还没有的功能面（presence、公开资料）。逐条处置见下。

## 目标

### S3-A 关系结束（9 条，其中 5 条部分）

| 需求 | 采纳范围 |
| --- | --- |
| `ZWMFKB381M0AKCYN` 拉黑停止双向通信且不产生响应 | 全条 |
| `TW7PP68XQAKBAVYH` 拉黑可推断性说明 | 全条 |
| `J22VY0VTZVRBY1P6` 解除拉黑补齐期间消息 | **部分**：窗口内的自动补齐；窗口外不取回，界面如实说明（裁决 2(a)） |
| `F7ZBKSBAC1N4KM2K` 删除联系人时询问是否删除历史 | **部分**：询问 + 本机删除 + 关系变更同步；「其他设备各自确认后才删」随 `K54NXXBZW1R6415F` 延后 |
| `SD1XKGXS4DBG5ZTE` 删除已拉黑保留拉黑记录 | 全条 |
| `XS6GKW2ZZ4R1CS6Y` 重新添加不继承校验状态 | 全条（含「提示这是新建立的关系」） |
| `GG1H42XAJXA0M4ZT` 联系人刷新保留拉黑与校验 | **部分**：设备刷新这条路（换设备/吊销）做，拉黑与校验保留。**「经另一份邀请再应答时作为新待确认项、不自动改写既有记录」与现有实现相反**：`AppStep.ReplyReceived` 对已存在联系人的应答走 `AccountMismatch` + `PersistContact`（version 递增），只有联系人不存在才生成 `Pending`；`BindingSpec` 有两条测试断言这个行为。要做成来源要求，得改写该分支、给「同一 user_pub 的第二条待确认项」找承载（`contacts` 主键就是 `user_pub`，现模型放不下），预计 +1 表、+200–400 行，并让两条既有测试变红（需签字）。**裁决 8(a)：维持现状**（见「裁决」），把来源要求登记为已知分歧；本条按「设备刷新做、二次邀请应答不改」验收 |
| `9MKG1QMBXQAG6C12` 无法提供的消息有可验证答复 | **部分**：解除拉黑窗口外那条答复 |
| `0P17VCWKG2FZ26SZ` 别名与备注私有 | **部分**，逐句：①「别名为主显示」已满足（`displayName = alias orElse peerChannelAccount orElse 短公钥`）；①后半句「仍可看到对方自设的用户别名」不成立——v1 没有用户别名，它随 `Z1P2TGCMD0K4W1XP` 延后；②「按别名/备注/用户别名任一检索」本波做前两项（联系人列表在内存，过滤即可），「用户别名」同上延后；③「两者不出现在发给对方的数据」已满足（密封载荷）。备注写入路径本波补 |

### S3-B 发布级可见性（6 条，其中 4 条部分）

| 需求 | 采纳范围 |
| --- | --- |
| `G3JQAH0SXEXVDATA` 桌面通知及其内容边界 | **部分**：**逐会话关闭通知**、**是否含正文（默认不含）**、**点击通知跳到对应会话** 三条做；「跳到会话并定位到具体消息、保留另一会话的草稿与滚动」不做（依赖锚点与滚动状态，与 `NS77` 的降级项同源），见已知约束 8 |
| `377PNHQWJ8PGCN80` 当前连接方式对用户可见 | 全条：显示经中继与中继状态 |
| `3AEMNVEGME49ZT2R` 主界面布局与可达性基线 | **部分**：键盘走完「启动→加好友→安全码→发消息→切换会话→打开联系人详情」、焦点顺序、外观跟随系统；**渲染级对比度与焦点指示测量**不在本波；搜索入口随 S3-C 提供 |
| `Q0MQBNAVS1TB1PBW` 实际暴露的信息须在使用前如实告知 | 全条：出现在**首次添加联系人流程**与邀请生成处；清单含中转可见项与本机中转网络地址 |
| `386RMXBPWJYEXT9P` 在线状态可被持钥者查知须说明 | **部分**：只做告知（不提供隐藏开关、不声称能隐藏），**不做 presence** |
| `REQ_20261103_NS77FQDYNCMNF71P` 迟到补入可察觉且不漏读 | **部分**：「不漏读」由 S2b GBNV 覆盖；「可察觉」只做到「有迟到补入时出现可见提示；同一原件只提示一次」。这条要显式改动：
现有 `notice` 是按 code 的单值，dismiss 后同 code 永久不再出现（`ApiClient`），既不按原件去重、
又会吞掉不同原件的提示；store 的 `upsertCompanionMessage` 对同一 `logicalKey` 也只返回
`Right(record)`、不区分首次与重复。因此需要「首次落盘」这一信号（upsert 返回 insert/update，
或新增 `alreadyPresent(key)`），触发键是原件（`logicalKey` / 消息 id），不是 code。来源验收里的「定位并逐条查看」「保留锚点与草稿」「重启后仍可见」不做，见已知约束 8 |

### S3-C 历史可用性（2 条）

`8F32S19RPRC25471`（全文搜索）、`3TK81N30WS2S6JWB`（大量历史仍可用）。见「设计 · S3-C」。

## 非目标与已知约束

**延后 18 条**（编号见来源表）：

| 需求 | 理由 |
| --- | --- |
| 恢复短语 5 条（`T1H63J7F3QD0WBBT`、`G2S3MXJ3J0HXYXYP`、`VMJVPZMK63123DKH`、`E6NXMN0ABVQD7NSN`、`P2MVJR9GAY96T95W`） | 恢复短语是私钥的可写形式，直接冲击 ADR-0007 的失败关闭边界。需要密钥管理 ADR 与外部密码学评审。 |
| `Z1P2TGCMD0K4W1XP` 公开别名与头像 | v1 没有公开资料面；加它等于新做一个陌生人可读的身份层。 |
| `REQ_20260919_757FNZE6B8FN15YV` 渠道身份保存复用 | 邀请已带渠道身份；「保存复用」是输入便利，不是协议能力。 |
| `F53PQ4FB8VT4SCV9` 公开张贴邀请的代价说明 | 公开张贴不在范围，说明随功能走。 |
| `CE3T62ZJNE2FKYKE` 联系人分组私有 | 真实理由是成本：新字段 + 分组 UI 约 400–600 行，收益低于同预算下的其他项。来源文档认为分组有独立价值，本计划不否认，只按预算排后。 |
| `XBRY5Q81Z4ZPP6ER` 大范围重发需批准 | 需要批准队列与新协议状态。裁决 2(a) 选择不批准，因此解除拉黑的窗口外路径只能如实说明缺口。 |
| `WW7ZXW92D1B6NVH9` 引用回复 | 消息结构要加引用字段，牵动 GBNV 与去重。 |
| 消息删除 5 条（`PE8XM7D0MPZ2XWTV`、`F9J932YWK907YPY7`、`Y4W1WTZE4K8TNEYD`、`K54NXXBZW1R6415F`、`SR68DSXCX4S5TZ3R`） | 墓碑、逐设备确认、跨设备传播是协议级改动，与 GBNV 顺序语义耦合。 |
| `0FAYS8R0Z7A9WF3Q` 配额暂停与恢复 | S1 已有有界队列与固定上限；暂停/恢复面向大流量。 |
| `FBGYS6FJ6X3GHEQ4` 陌生对端资源上限 | v1 只有邀请/应答一条准入路径且已有界。 |

**已知约束**（范围外的保证，不是待补的洞）：

1. **没有消息墓碑**：S3 不做消息删除，所以「删除消息」在 S3 之后仍不可用。若将来做，扇出与
   重传需要消息墓碑才不会把删过的消息再带回。这里说的是**消息**墓碑；本波为联系人删除引入的
   contacts 墓碑是另一件事（见「关系变更的同步」）。出处：`requirements.md` 的「消息默认永久
   保存 vs 用户删除」段。
2. **拉黑窗口外的消息取不回**：对方重试预算耗尽（3 次 / 约 15 秒；wire 失败约 2 秒 / 在途 ≤32 条）
   后进入 Failed，不再重发；`requestResend` 不接受 Failed。**裁决 2(a)**：如实告知取不回，
   `J22VY0VTZVRBY1P6` 按「窗口内自动补齐、窗口外明确告知」降级验收；不引入批准队列
   （`XBRY5Q81Z4ZPP6ER` 仍延后）。
3. **关系变更只在主设备发起**：`device_sync` 的**快照数据**是 A1→A2 单向；A2→A1 已经存在
   sync-status 与 ack 两类帧（`CompanionService.requestLoop` 取 `receiver.statusBytes` →
   `DeviceSyncProtocol.syncStatus`；`CompanionSyncService` 的 ack；A1 侧
   `DeviceSyncScheduler.acceptStatus/acceptAck`）。协议里也定义了 `syncRequest`，但生产代码没有
   发送方（只有测试用），不能当既有先例。
   `DeviceList` 不可能由 A2 发出：构建它需要 A1 才持有的用户私钥（`PeerDeviceListService.build`
   返回 `primary-user-key`）；`FanoutAck` 的方向是对端 companion → 本机 A1，不是 A2 → A1。
   缺的是「关系变更数据的上行携带与 A1 侧应用」，不是通道本身。**裁决 3(a)**：S3-A 在 A2 禁用这些
   操作并提示在主力设备操作；A2 只通过 A1→A2 快照接收联系人状态（`blocked` / `deleted`），不发起。
4. v1 的 16 条行为契约不变；S3 只新增契约。
5. 不改中继语义；入站丢弃发生在应用层。
6. 自动更新、安装包矩阵、Windows、签名公证不在 S3。
7. **可达性只做到主流程与焦点顺序**：渲染后的对比度与焦点指示 3:1 测量、深浅两套主题的对照
   验证不在本波（`contrast-smoke` 是 token 级门，不是完整 WCAG 审计）。搜索入口随 S3-C 提供。
   来源验收里这两项是有意降级，不是遗漏。
8. **迟到补入只做到可见提示**：「定位并逐条查看」「排序更新保留消息锚点与草稿」「重启后待查看
   状态仍在」需要持久化的 UI 状态与导航，本波不做。提示的触发键是原件（`logicalKey`/消息 id），
   需要 store 暴露首次/重复信号；现有 `notice` 按 code 去重，不能直接复用。提示只保留最近一条：
   同一会话连续两次迟到补入时，前一条横幅被后一条替换。
9. 行预算：A+B 估算 3,500–5,500 行（19,614 → 23,100–25,100）。若 18 条延后项再做，总量
   31,300–37,600，**下界已超上限**，必须先删等量死代码或另立 ADR 提高上限。

## 设计

### 存储与 ADR 边界

存储扩展由 **ADR-0012**（2026-11-27，owner 委派接受）定稿，不修订 ADR-0007 的 AAD 规则：

- `blocked_peers` **不密封载荷**：行只有 `user_pub`（主键）、`version`、`created_at`、`updated_at`。
  行存在即拉黑；解除拉黑删除行与对应 `meta["blocked-version/<hex>"]` 两处。`user_pub` 是公钥、
  行数是 ADR-0007 已登记的泄漏项，因此不新增密封域，也不新增 `databaseKey` 派生节点或 AAD 标签。
- 回滚保护：行版本必须等于 `meta` 高水位，并且每个残留的高水位键都必须有对应行；否则打开实例
  即 `StaleGeneration`（失败关闭）。单行删除/单行回滚被覆盖，协调回滚（行 + `meta` 一起动）
  仍是 ADR-0007 的已知约束。
- `contacts.sealed` 升到 codec v2：新增 `state`（`active` / `deleted`）与 `recreated` 标志；AAD 不变
  （仍是 `contact/v1` + `userPub` + `version`）。v1 行按 `active` 读。删除联系人 = 行保留、
  `state=deleted`、清空别名/备注/渠道身份、`version+1`；`listContacts`/`snapshot` 过滤 deleted。
  重加时 upsert 把同一行复活为 `state=active`、`safetyVerified=false`、`recreated=true`；
  核对安全码后 `recreated` 清除。
- 删除联系人后关系结束由统一的准入判定保证——入站六个入口要求「存在 active 联系人」，
  出站（Reply 除外）同样要求；拉黑额外由 `blocked_peers` 判定。**保留历史时不删会话行**：
  `conversations` 表没有 peer device 列，删掉 session 后 `AppStateRestore` 无法把保留下来的
  历史会话映射到对端设备，应用会打不开。**删除历史时连 `sessions` 一起删**（先推进每个会话的
  高水位），并删 `messages`、`message_order`、`delivery_receipts`、`companion_messages` 与
  `conversations`（本机行为，ADR-0007 已登记消息删除不在防篡改承诺内）；删除联系人同时移除该
  对端的待处理邀请，避免旧邀请把关系重新建起来。

### S3-A 关系结束

**入站入口表**（上一版只写「一条链」，实际是六条，是第 1 轮评审的 P1）：

| 入口 | 代码位置 | 落盘点 | 拉黑判定 |
| --- | --- | --- | --- |
| Wire（内含 Data / RekeyConfirm / RekeyConfirmAck / DataAck / Gap 五类） | `AppRuntime.translateInbound` → `AppStep` | `messages`、重传与缺口状态 | 帧头无 `user_pub`，须先 `sessions.get(sessionId)` 取 `peer_user_pub` 再查 `blocked_peers` |
| Reply | `AppRuntime` 的 `replyEvent` | `contacts` / 待确认项 | 应答对一次性公钥密封，**必须先开箱**才知道 user_pub；开箱后立即丢弃，不落盘、不生成待确认 |
| RekeyRequest / RekeyAccept | `AppRuntime` 独立分支 | 会话棘轮 | 按会话的 `peer_user_pub` 判定，命中即丢弃 |
| DeviceList | `translateInbound` 返回 `None` → `CompanionSyncService` | `peer_device_lists` | 命中即丢弃，不更新设备列表 |
| Fanout | `CompanionFanoutService.receive` | **`companion_messages`（正文）** | 命中即丢弃，否则「不增长本机存储」直接失败 |
| FanoutAck | `DeviceSyncScheduler.listenRequests` → `FanoutService.handleAck` | `delivery_receipts`（**纯 UPDATE，无 INSERT**） | 发送方是**对端 companion 设备**。`authorizedCompanion` 已按联系人列表匹配设备，但**不排除拉黑**；`handleAck` 只做 `markAcked`，因此它不会增长行数，却能把本机已发出的回执翻成「已确认」。判定仍要加，断言写成「该行的 `state`/`updated_at` 不变」 |

表的范围是**对端可发起的帧**。第 7 种 `DeviceSync` 只由本人设备发出（`CompanionSyncService.receive`
落 `contacts/conversations/messages` 与 `device_sync` 检查点），不按联系人判定，**不加 guard**；
它仍需通过自有设备鉴权，这是既有行为。

实现要求：判定收敛到一个 store 层 guard（`isBlocked(userPub)`），上表六处入口都调它，不许各写一份。
`## 验收` 用与下表同一份逐项清单，不写成「一条断言」：`messages` 与 `companion_messages` 的行数
增量、`peer_device_lists` 的 `version`/`received_at`、`delivery_receipts` 的 `state`/`updated_at`、
待确认项列表、会话棘轮进度、八种出站 kind 的发送计数。每删掉对应入口的 guard，该项必须变红。
注意 `peer_device_lists` 是 upsert、`delivery_receipts` 只有 UPDATE，对这两张表数行数恒真。

**出站拦截**：任何以被拉黑公钥为收端的帧一律不发。现有出站 kind 八种：Data、Reply、
RekeyRequest、RekeyAccept、DeviceSync、DeviceList、Fanout、FanoutAck。发送前统一查
`blocked_peers`，命中返回 `BlockedPeer` 错误码；自动重传、ACK、重建握手走同一判定。

**解除拉黑与补齐的真实窗口**（上一版写错，第 1 轮评审 P1）：

- 棘轮单帧跳号上限 `MaxSkipGap=64`、累计 `MaxSkipTotal=256`（`core/src/yochat/core/S1Limits.scala`），
  超出返回 `GapTooLarge`。
- 发送方重试上限 `MaxSendAttempts=3`、`DeliveryTimeoutMs=5000`；投递超时路径由 `TimerId.Message`
  直接重发、**不走退避**，因此上限是 3×5s ≈ **15 秒**（`RetryBackoffMs=2000` 只在 `SendFailed`
  分支用，wire 失败路径约 2 秒即 Failed）；`requestResend` 只接受 `Queued|InFlight|Sent`，**不含 Failed**（`AppStep.scala`）。
- 在途窗口 `MaxInFlight=32`。

因此在「拉黑期间不落盘」的前提下，可自动补齐的只有：解封时对方仍在重试、且棘轮跳号不超限的
那一小段（约 15 秒 / 32 条以内）。超出后：

- 解封后第一帧可能直接 `GapTooLarge`。当前实现里 `GapTooLarge` 不触发 `RekeyRequired`、也不置
  `gapRequested`，会**静默丢帧、永久失步**。S3-A 必须补一条：`GapTooLarge` 映射到可重建通道的
  状态（复用 S1 的 rekey 路径），或在解封时主动重建。这是 S3-A 的核心改动，不再是「零成本复用」。
- 窗口外的消息要么走批准重发（`XBRY5Q81Z4ZPP6ER`，本计划延后），要么如实告诉用户取不回。
  **裁决 2(a)：如实告知取不回**；不把批准路径拉进 S3-A。

**关系变更的同步**：现有 `device_sync` 的联系人载荷是固定 JSON（含 alias、safetyVerified），
接收端 `applyContact` 只 upsert、**没有删除分支**。所以：

- 联系人记录在 `records` 这层 JSON 里（`DeviceSyncProtocol` 把整个 `records` 密封），记录本身
  没有版本字段（行有 `contacts.version` 列，是行级乐观锁，两者不同），因此加 `state` 是**密文内
  的扩展**：旧接收端忽略未知键，外层信封 `version == 1` 不变（改外层版本会让 A2 的 `open` 直接
  失败）。本地 `ContactData` 自身是严格版本化编解码，加字段要走 `ContactVersion` 提升。
- `deleted` 需要墓碑：现有发送端快照来自 `store.contacts`，删除即删行，快照里不会出现该记录。
  本波把删除实现为 contacts 墓碑：**保留行**，密封载荷只写 `state=deleted`（清空别名、备注、
  渠道身份），`contacts.version` 递增。这需要 `ContactData` 增加 `state`、`ContactVersion` 提升
  并兼容读旧行——是一次存储 codec 变更，必须并入存储 ADR 与 `store.test` 的迁移测试，不能只写
  「加一列」（当前 `contacts` 表没有 `state` 列，`sealed/created_at` 都是 NOT NULL）。
- 接收端 `applyContact` 目前只 upsert 且硬编码 `note = None`，要重写为读 `state`、`blocked`、
  `note`：`deleted` 只删关系不删历史，`note` 保留；`blocked` 由 A1 快照权威设置（A2 不发起关系
  变更，因此没有并发写者，不需要「blocked 不被 active 覆盖」的合并规则）。
- 删除历史仍只在本机执行：本机删除即删 `messages`；其他设备保留各自历史（`K54NXXBZW1R6415F` 延后），
  且 A2 不做关系操作（已知约束 3），所以 A2 上的历史只能等 A2 恢复能力后自查。
- A2 发起的变更见已知约束 3。

**不变量**

1. 被拉黑者的六类入站帧：不落盘、不推进游标、不产生任何应用层出站（含拒绝与 ACK）。
2. 出站八种 kind 对被拉黑公钥零发送。
3. 删除已拉黑的联系人：关系删除、`blocked_peers` 记录保留到用户在拉黑名单里显式移除。
4. 重新添加是新关系：`safety_verified=false`，界面提示「这是新建立的关系」。
5. 刷新（换设备/吊销）保留拉黑与校验。「经另一份邀请再次应答」按裁决 8(a) 维持现状：
   `AccountMismatch` + version 递增，登记为已知分歧。
6. 任何界面不得声称「对方不会知道」；拉黑确认页含两条说明（可推断、绑定单把公钥）。

**失败模式**

- 写拉黑失败 → 整体失败，不留「本地已拉黑、同步未发生」被当成成功的中间态。
- 解封后 `GapTooLarge` → 进入重建通道，界面说明「通道已重建，拉黑期间的消息取不回（或需对方
  批准）」，不停在「等待」。
- 跨设备同步失败 → 该设备保留旧状态并在界面标未同步，不静默。

### S3-B 发布级可见性

- **通知**（`G3JQAH0SXEXVDATA`）：逐会话关闭；正文开关默认关；点击通知跳到对应会话。
  改动：`notify` 的 IPC 载荷带 `conversationId`（不透明标识）、标题（联系人显示名）与可选正文，
  正文默认不填；`shell/main.js` 的 `notification.on("click")` 除 `restore/show/focus` 外经新 IPC
  通知渲染端选中会话。三条都是渲染层/主进程行为，`server.e2e` 看不见窗口，断言放在 Electron 级 gate
  （`./desktop-gate.sh node shell/electron-smoke.js`，Playwright `_electron` 可在主进程注入并断言）。
  `ui-smoke` 走 Chrome、没有 Electron `Notification`，不能承担这条。
- **连接方式**（`377PNHQWJ8PGCN80`）：只显示经中继与中继在线状态，复用现有状态。
- **可达性**（`3AEMNVEGME49ZT2R`，部分）：键盘走完主流程、切换会话、打开联系人详情；焦点顺序
  可预测；跟随系统外观。**不覆盖**：渲染级对比度与焦点 3:1 测量（现有 `contrast-smoke` 是 token
  级门，文件自述不是完整 WCAG 审计）不在本波。搜索入口随 S3-C 提供。
- **使用前告知**（`Q0MQBNAVS1TB1PBW`）：在首次添加联系人流程与邀请生成处展示，清单照来源：
  中转可见（端点、时间、帧数、密文）、直连对端可见本机公网地址、联系人可见全部设备公钥与别名、
  中转不知道设备属于谁且看不到用户公钥，以及本机中转的网络地址；「拉黑可被推断、我们不提供在线状态、
  没有隐藏开关、用户公钥本身不能用来查询在线状态」（`386RMXBPWJYEXT9P` 的告知部分并入此处）。版本化重提示是显式追加项，
  约 80 行 + 1 个本地配置，不需要可去掉。
- **迟到可察觉**（`REQ_20261103_NS77FQDYNCMNF71P`，部分）：出现可见提示，且同一原件不重复提醒；
  不改排序逻辑。「定位并逐条查看 / 锚点与草稿 / 重启后仍在」不做（已知约束 8）。

### S3-C 历史可用性

裁决 4 选 **A 有界解密扫描**；ADR-0013 定稿扫描上限（5,000 行 / 50 条）、`scanned` 与
`truncated` 语义，以及「大量历史」的数据形状与阈值：

- 形状：一个会话、100,000 条消息、正文 128 字节；最新 5,000 条走正常加密写入，更旧的 95,000
  条只用于撑大表且不会被任何门读取。
- 门：首屏 `listRecentMessages(50)` p95 ≤ 250 ms；最坏情况搜索（唯一命中在 5,000 行扫描的
  最旧一行）p95 ≤ 1,500 ms。2026-11-27 实测 `first_p95_ms=11`、`search_p95_ms=50`
  （以 ADR-0013 / `store.searchbench` 输出为准）；阈值是约 20–60 倍的回归护栏。
- 门由 `./mill store.searchbench` 执行，超阈值非零退出；`./mill store.test` 固定匹配语义、
  上限截断与查询计划（按 rowid `SEARCH`，不得 `SCAN messages`）。
- UI：会话列表顶部搜索框（≥2 字，200 ms 去抖），结果是联系人 + 片段；点结果切会话并清空；
  结果不完整（截断或达到结果上限）时显示已扫描条数与「更早的消息没有搜索」；搜索失败有独立
  错误态，不渲染成「没有匹配」。只读设备不显示搜索框。

## 测试规格等价

旧实现的对应来源（`~/projects/yochat-reference`）：`crates/yochat-core/tests/blocking.rs`、
`contact_sync.rs`、`relationship.rs`、`deletion_directive.rs`、`deletion_parity.rs`、
`inbound_discard.rs`；`e2e/wdio.blocking.conf.ts`、`e2e/wdio.blocking-sustained.conf.ts`。

逐条映射在实现开始时的 `test-parity.md` 里写，本设计不预填（未读全旧用例前预填等于假装等价）。
要求：

- S3-A 每条采纳需求至少一条状态机/属性测试 + 一条 E2E；
- 旧 `blocking.rs` 的断言逐条对照，省略写理由并取得签字；
- 既有 16 条行为契约不因 S3 改动变红；变红按「测试耦合了实现细节」处理，不是改契约。

## 性能门

- S3-A / S3-B 不新增吞吐门。既有 1k/5s、10k/60s、10k 回放 50s、空闲内存 800MB、冷启动 p50/p95
  保持，S3 后复跑并存回执。
- 拉黑路径单独校验：丢弃发生在解密之前的入口（Data/DeviceList/Fanout 等）不得比正常准入更慢；
  用一条「被拉黑者持续发送」的负载测试确认没有排队放大。Reply 必须先开箱，这条不能省，单独测成本。
- S3-C 需自己的门（首屏与滚动），阈值待 ADR 与基准确定。

## 取代的旧文档

- **已裁决（2026-11-27）**：本计划取代 `docs/references/2026-11-20-yochat-reference-study/requirements.md`
  的 S3 分批表作为范围依据（该文件自述是建议而非清单）。
- 不取代 ADR-0003；它的「S3 = 重新论证的子集」由本计划落地。S3 完成后另立 ADR 固化采纳/延后清单。
- 与 ADR-0007 的边界：恢复短语仍不做；`blocked_peers` 与 contacts 墓碑已由 ADR-0012 定稿，
  不改变 ADR-0007 §3 的 AAD 规则。
- 与 ADR-0004：S3-C 的门与形状见 ADR-0013（owner 委派接受）；阈值是回归护栏，实测数字随机器变。

## 裁决（2026-11-27，owner 委派）

owner 在 2026-11-27 以「请开始你的设计和实现。我希望中间不需要我干预。」把八条决定委派给执行者。
以下决定、理由与代价都按可证伪口径写；后续若要推翻，按 ADR 的取代流程走，不静默改写。

1. **范围：接受** 采纳 15（含 9 条部分）/ 阻塞 2 / 延后 18。理由：30,000 行硬上限决定必须取
   子集；恢复短语需要密钥管理 ADR 与外部密码学评审，消息删除需要消息墓碑与逐设备确认，
   二者都是协议/密钥层改动，不应为了「必须有」跳过评审。代价：v1 没有恢复短语与消息删除，
   写进已知约束与发布说明。
2. **解除拉黑窗口外：选 (a) 如实告知取不回。** 理由：批准路径是新协议状态（模型 + 单独签字），
   窗口只有约 15 秒 / 32 条；诚实告知不伪造保证。代价：`J22VY0VTZVRBY1P6` 的验收降级为
   「窗口内自动补齐 + 窗口外明确说明缺口」，不实现批准重发。
3. **A2 发起关系变更：选 (a) 先禁用并提示在主力设备操作。** 理由：A2 不持有用户私钥，
   `PeerDeviceListService.build` 返回 `primary-user-key`，A2 无法签关系变更所需的设备列表；
   单写者避免关系状态并发合并。A2 通过 A1→A2 快照接收 `blocked` / `deleted` 状态并只读应用。
   代价：A2 上不能拉黑/删除，界面必须给出可执行提示。
4. **搜索存储：选 A 有界解密扫描。** 理由：保留 ADR-0007 的静态加密；明文倒排索引等于放弃一半
   静态加密，除非改存储方案否则不接受；不做搜索不满足需求意图。代价：S3-C 的延迟随历史增长，
   需要可测上界与自己的门；本波不实现。
5. **十万条指标：不继承旧数字，保留「大量历史可用」目标。** 理由：旧项目的 10 万条是旧验收
   口径，数据形状、硬件与测量方法未记录，直接继承会把估算当实测。S3-C 的 ADR 定义数据形状
   （条数 × 大小）、首屏与滚动阈值，按 ADR-0004 签。
6. **行预算：维持 30,000，只计五个模块 `*/src`。** `spike/` 是可弃实验代码，`shell/*.js` 是壳层；
   两者单列观测（含则 23,990），不占产品额度。A+B 估算后 23,100–25,100，仍在限内。
7. **存储 ADR：新写 ADR-0012，由执行者起草，owner 委派接受；不修订 ADR-0007。**
   `blocked_peers` 不密封载荷（只有公钥与版本/时间戳），因此不改 ADR-0007 §3 的 AAD 规则；
   `contacts.sealed` 的 codec v2 也不改 AAD（仍绑定 `userPub` + `version`）。
8. **二次邀请应答：选 (a) 维持现状，登记为已知分歧。** 理由：`AccountMismatch` + version 递增
   是失败关闭的提示；同一 `user_pub` 的第二条待确认项没有承载（`contacts` 主键就是 `user_pub`），
   且自动接受会绕过安全码重新核对（ADR-0009 的绑定语义）。重新建立关系走「删除后重加」的新
   邀请路径。代价：来源要求的「另一份邀请作为新待确认项」不实现，写进已知分歧。

## 验收

命令与场景都固定；回执落 `tmp/checks/`。

- **S3-A**
  - `./mill server.e2e` 全绿（16 条既有契约 + 新增 S3 契约）。
  - 新增 E2E：拉黑后 `messages`、`companion_messages` 行数增量为 0；`peer_device_lists` 的
    `version`/`received_at` 不变（它是 upsert，数行数恒真）；`delivery_receipts` 的
    `state`/`updated_at` 不变（只 UPDATE，无 INSERT）；待确认项不新增；会话棘轮不推进；
    八种出站 kind 零帧。删掉对应的 guard 后每条断言都必须变红。解除拉黑后
    窗口内补齐、窗口外按裁决 2(a) 如实告知取不回；删除联系人询问历史、保留时消息仍在（搜索断言随
    S3-C 延后，本波只断言会话视图与本地库）；删除已拉黑保留拉黑记录且新应答不出现在界面；
    重加为未校验并提示新关系；对方换设备后拉黑与校验仍在；备注可写入并按别名/备注过滤联系人
    （按用户别名检索不在本波）；迟到补入出现可见提示，同一原件只提示一次。「另一份邀请的应答」
    不在本清单里——按裁决 8(a) 维持现状，见下一条子场景。
  - Reply 入口单独一条子场景：**拉黑期间**对方另发一份邀请并应答，解封前不得生成新的待确认项、
    `contacts` 行 version 不变（这条能删 guard 变红）。解封后同一帧按裁决 8(a) 走
    `AccountMismatch` + version 递增。普通已建立关系的 Reply 不产生待确认项，不能拿它当 Reply
    guard 的覆盖证据。
  - `./mill store.test`：`blocked_peers` 迁移、回滚、高水位测试；`ContactData` v1→v2 兼容读；
    删除/重加/历史删除。
  - 被拉黑者持续发送的负载测试：丢弃路径无排队放大。
- **S3-B**
  - `./desktop-gate.sh node shell/electron-smoke.js` 新增断言：通知载荷不含正文（默认关）；
    逐会话关闭后该会话不产生通知；点击通知后渲染端选中的会话等于通知携带的 `conversationId`。
    删掉主进程的 click 转发后这条必须变红。
  - `node shell/contrast-smoke.js` 保持绿；键盘旅程的 E2E 用
    `./desktop-gate.sh node shell/ui-smoke.js` 的同一套驱动。
  - 首次添加联系人与邀请生成处的告知可见。
- **S3-C**
  - `./mill store.searchbench` 全绿并保存回执（100k 行；首屏与搜索 p95 均在阈值内）。
  - `./mill store.test`：匹配大小写不敏感、按新到旧、结果/扫描上限、参数校验与查询计划。
  - `./desktop-gate.sh node shell/ui-smoke.js`：搜索框输入后出现命中，点击后当前会话 id 与命中
    的 `conversationId` 相同并清空查询；注入截断响应时显示「已扫描 N 条 / 未找到」而不是
    「没有匹配」；注入 500 时显示「搜索失败」而不是「没有匹配」。
- **全波次**
  - m1 上 `./check.sh` 全绿并保存回执；Linux 只作功能验证（w7），不作性能判决。
  - `./models/check.sh` 不变；本次不动协议模型（裁决 2(a)/3(a)/8(a) 都不引入新协议状态）。
