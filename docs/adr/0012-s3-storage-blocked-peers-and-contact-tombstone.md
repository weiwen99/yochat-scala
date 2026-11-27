# 存储扩展：拉黑表、联系人墓碑与关系结束

**状态**: accepted (2026-11-27；owner 委派起草者决定，记录原话「请开始你的设计和实现。我希望中间不需要我干预。」；
不修订 ADR-0007 的密钥层次与 AAD 规则)

**前序**: ADR-0007（本地存储与密钥）、ADR-0009（首条消息绑定）、ADR-0011（S2b 扇出与 GBNV）、
Plan 0015（S3 发布级 MVP）

## 规模与范围

| 维度 | 需求要什么 | 本 ADR 产出 | 倍率 | 说明 |
| --- | --- | --- | --- | --- |
| 新表 | 拉黑记录（1 张） | 1（`blocked_peers`） | 1× | 只存公钥与版本/时间戳；不建第二套关系表 |
| 新列 | 0 | 0 | — | 联系人状态在 sealed 载荷里，不加明文列 |
| 新密封域 | 0 | 0 | — | 不新增 `databaseKey` 派生节点、AAD 标签或信封格式 |
| 新协议状态 | 0 | 0 | — | 拉黑与删除不同步到对端；A2 只接收 A1 快照里的状态位 |
| 迁移波次 | 1 | 1（schema v5 → v6） | 1× | 只加表；`contacts` 行按 codec 版本兼容读 |

本 ADR 只覆盖 Plan 0015 的 S3-A。不做：恢复短语、消息墓碑、加密搜索、消息删除的跨设备传播、
拉黑理由/别名等附注字段。那些要么另立 ADR，要么留在 Plan 0015 的延后清单。

## 背景

S3-A 要结束一段关系，但现有存储只有「有联系人 / 没有联系人」两态：

- `contacts` 删除即删行，`meta["contact-version/<hex>"]` 高水位阻止旧行复活（ADR-0007 §4 的删除模式）。
  这满足 S1 的「邀请 tombstone」，但不满足 S3 的三件事：
  1. 拉黑要独立于关系存在：删除联系人后拉黑记录仍在；解除拉黑不能复活已删除的关系。
  2. 删除联系人时可以选择保留本机历史；历史需要会话与消息继续可读。
  3. 重新添加不能继承旧的校验状态，但界面要能说明「这是新建立的关系」。
- `AppStateRestore` 从 `sessions` 或消息作者推断会话的对端设备；`conversations` 表本身没有
  peer device 列。因此**删除联系人时删会话行会让保留历史的会话在下次启动时无法恢复**
  （`AppStateRestore.conversationsFrom` 返回 `NotFound("conversation-device")`，应用打不开）。
  这是本 ADR 不采用「删关系就删 session」的直接原因。

## 决策

### 1. `blocked_peers`：行存在即拉黑，删除前先推高水位

```sql
CREATE TABLE blocked_peers (
  user_pub   BLOB PRIMARY KEY NOT NULL,  -- 32B Ed25519 用户公钥
  version    INTEGER NOT NULL,           -- >= 1，单调递增
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
)
```

- **行存在 = 该用户被拉黑**。解除拉黑 = 先把 `meta["blocked-version/<hex user_pub>"]` 推到
  `version + 1`，再删行。这与 ADR-0007 §4 对 `contacts`/`invitations` 的删除模式一致：
  旧行回滚回来时版本低于高水位，打开时 `StaleGeneration`，失败关闭。
- 首次拉黑插入 `version = 1` 并写高水位；重复拉黑是幂等 no-op（不递增版本，避免无意义的
  写放大）；解除不存在的拉黑同样是 no-op。
- 打开实例时在同一事务内校验每一行的 `version == meta` 高水位；不相等返回 `StaleGeneration`，
  `Store.open` 失败。高水位缺失返回 `MissingHighWater`。这是 ADR-0007 §6 的打开流程，
  不是新的错误语义。
- 索引：主键点查即可；`listBlockedPeers` 是全表顺序扫描，行数上界是本机拉黑过的人数，
  与联系人数量同阶，不建二级索引。
- **不密封载荷**：行里只有公钥、版本和时间戳，没有理由、别名、备注或渠道身份。ADR-0007 §3
  的明文列规则允许「主键/公钥/版本/时间戳」；行数与时间戳的泄漏已在 ADR-0007 的已知约束里。
  因此不新增 `databaseKey` 派生节点，也不新增 AAD 标签或信封格式。
- **不防协调回滚**：同时回滚行与 `meta` 高水位、或整库回滚到旧快照，仍然检测不到。ADR-0007
  已把这类攻击登记为 S1 的已知约束，本 ADR 不改变结论。

### 2. `contacts.sealed` codec v2：状态与「重新建立」标志

`ContactData` 增加两个字段，写进 sealed 载荷，不加明文列：

| 字段 | 取值 | 用途 |
| --- | --- | --- |
| `state` | `active` / `deleted` | 关系是否存在；`deleted` 是墓碑，行保留 |
| `recreated` | `true` / `false` | 该关系是否由删除后的新邀请重新建立；核对安全码后清除 |

- codec 版本字节从 1 升到 2，新字段追加在末尾；解码 v1 行时 `state = active`、
  `recreated = false`。AAD **不变**：仍是 `"yochat-store/contact/v1" || userPub(32) || version(i64)`。
  这是同一域内的载荷扩展，不是 AAD 变更；ADR-0007 §3 的「加入变长字段必须换域标签」不适用
  （新字段是定长单字节，且载荷自带 codec 版本）。
- 删除联系人：行保留，`state = deleted`，清空 `channelLabel`、`channelAccount`、
  `peerChannelAccount`、`alias`、`note`，`safetyVerified = false`，`version + 1`。
  `ContactRepository.list`、`StoreSnapshot.contacts` 只返回 `active`；`loadContact` 先做高水位与
  AEAD 校验，再对 `deleted` 返回 `NotFound("contact")`（所以回滚一个旧行仍然先报
  `StaleGeneration`，不会伪装成「不存在」）。
- 重新添加：`ReplyReceived` 在 `AppState.contacts` 里找不到该 `user_pub`，走新联系人的分支；
  `ContactRepository.upsertProgram` 发现同主键的墓碑行，更新为 `state = active`、
  `safetyVerified = false`、`recreated = true`，版本继续递增。旧的校验状态、别名、备注都不继承。
- `setSafetyVerified(true)` 清 `recreated`；其它更新保留 `recreated`，直到用户核对安全码。
- `upsertProgram` 是唯一能把 `deleted` 变回 `active` 的路径；`insertContact` 仍先查
  `contact-version` 高水位，墓碑存在时返回 `StaleGeneration`（既有测试断言不变）。

### 3. 关系结束的准入规则

删除联系人与拉黑都不删除 `sessions` 行（理由见「背景」）。关系结束由统一的准入判定保证：

- **入站**：Wire、Reply、RekeyRequest/RekeyAccept、DeviceList、Fanout、FanoutAck 六个入口，
  除了各自的签名/AEAD 校验外，都要过同一个判定：
  `activeContact(peerUserPub) && !blocked(peerUserPub)`。
  Reply 在开箱拿到 `userPub` 之后判定；Fanout/FanoutAck 用发送方所属用户判定。
- **出站**：八种 kind（Data、Reply、RekeyRequest、RekeyAccept、DeviceSync、DeviceList、Fanout、
  FanoutAck）在发送前查同一判定；`Reply` 例外——它发生在联系人建立之前，只查 `blocked`
  （邀请文本本身是用户当面给的）。
- `DeviceSync` 只发给本人设备，不走对端判定；它仍由配对授权兜底。
- 删除联系人时同时删除该对端的待处理邀请（`InvitationData.payload` 里的 `userPub` 命中），
  防止旧邀请的迟到应答把关系重新建起来。
- 删除联系人时，把该对端未完成的外发消息标为 `Failed`（保留行），避免重启时
  `AppStateRestore.outgoingFrom` 找不到会话而让应用打不开；`Failed` 行在界面显示为发送失败。

### 4. 历史删除

`deleteHistory = true` 时在同一个事务里删除：

```text
messages         WHERE conversation_id = ?
message_order    WHERE conversation_id = ?
delivery_receipts WHERE conversation_id = ?
companion_messages WHERE conversation_id = ?   -- A2 库同 schema，A1 上通常为空
conversations    WHERE id = ?
```

锁序沿用 ADR-0007 §5：`contacts` → `sessions` → `messages` → `meta`。历史删除只作用于本机；
其他设备保留各自的历史（`K54NXXBZW1R6415F` 延后）。`messages` 删除不在 ADR-0007 的防篡改
承诺内，这里不新增保证。

## 后果

- 迁移是 v5 → v6：只加 `blocked_peers` 表；`contacts` 行按 codec 版本兼容读，不需要重写行。
  打开旧库后 `schema_version = 6`。
- 拉黑名单的读写是单表点查/顺序扫描，不碰加密 BLOB；丢弃路径不会因为解密而放大。
- 会话行在关系结束后仍保留，`peer_device_lists`、`delivery_receipts` 也保留；这是
  「历史仍可读」与「重启可恢复」的代价，登记为已知约束：会话状态里仍留有对端的棘轮密钥，
  它与历史消息的解密材料同寿命。
- A2 只从 A1 快照接收 `state` / `recreated` / `note` / `blocked` 四个展示位；A2 不发起关系变更，
  也不删除自己的历史。

## 已知约束

1. 协调回滚（行 + `meta` 一起回滚）检测不到，沿用 ADR-0007。
2. 拉黑与删除不同步给对端；对端只观察到「发不出去 / 收不到 ACK」，这与「不产生任何响应」一致。
3. 删除联系人不清理 `sessions`、`peer_device_lists`、`delivery_receipts`；它们不再被使用，
   但仍在磁盘上。要清理需另立 ADR（先解决会话恢复的对端设备映射）。
4. `recreated` 是展示位，不是安全门；任何安全决策不得只依赖它。
