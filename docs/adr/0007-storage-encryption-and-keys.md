# 本地存储：doobie + 应用层 AEAD、密钥层次与回滚边界

**状态**: accepted (2026-11-20，revision 6；内部评审 round 1/2/closure 完成，无剩余 BLOCKER/MAJOR；2026-11-25 接受 Electron `safeStorage` → loopback unlock 的实际密钥路径，关闭 M9；外部密码学评审由 owner 决策记录在评审包)

**前序**: ADR-0002（技术栈）、ADR-0004（测试/性能门）、ADR-0006（S1 加密）。本 ADR 取代 ADR-0002、ADR-0006 与 `design.md` 中「storage 方案待定」的占位；不改写已接受的 ADR-0005/0006。

## 规模与范围

| 维度 | 需求要什么 | 本 ADR 产出 | 倍率 | 说明 |
| --- | --- | --- | --- | --- |
| 新表 | 0（设计已有 6 张表） | 0 | 1× | 复用 `sessions`、`messages`、`contacts`、`invitations`、`conversations`、`meta`；安全行加版本列，不建 outbox/审计表 |
| 核心组件 | 1（`store/`） | 1（`store/` + `KeyProvider`；doobie/Hikari 是库，不是新服务） | 1× | 不新增服务、第二个数据库、ORM 或第二套 effect 栈 |
| 配置项 | 4 组（数据库、密钥、回滚、事务） | 8 组内部常量 | 2× | 无用户可调项；SQLite PRAGMA、Argon2id 参数、三个域标签、两把高水位前缀 |
| 工程师日 | 4–6（ADR-0006 的存储实现估算） | 10–14 | 2.3× | 含 2 人日 spike、`store/`、`KeyProvider`、`identity.bin`、三类高水位、迁移、崩溃/故障测试；仍低于 3×，不再加抽象 |
| 发布波次 | 1 | 1 | 1× | 跟 S1 一次交付，无独立迁移波次 |
| 新协议/算法 | 0 | 0 个新密码算法；3 个磁盘信封格式 | — | 复用 ChaCha20-Poly1305、HKDF-SHA256；Argon2id 只补 JCE 缺口。格式是存储边界，不是网络协议 |

不做：SQLCipher 原生库集成、数据库级 key rotation、跨设备密钥同步、恢复短语、历史同步、全文索引、加密搜索。S1 的产品拓扑是两个用户各一台设备；每个本地实例一个身份、一台设备、一个本地 SQLite 数据库。

**数据库边界**：S1 的数据库是**客户端本地数据库**，不是共享服务端数据库。选 SQLite 是因为它随桌面进程分发、无需独立服务、离线可用；如果以后改成多客户端共享/服务端存储，那是一次存储架构变更，另开 ADR 评估 PostgreSQL，不在本 ADR 里伪装成「换连接串」。

## 背景与约束

ADR-0006 §5 已冻结以下存储契约：

- `sessions.state` 整体加密，AAD 绑定 `(id, peer_user_pub, peer_device_pub, generation, state_version)`。
- `meta` 保存单调 `state_version` 高水位；打开时低于高水位即拒绝。
- `messages.wire` 保存完整 `header || ct || sig`，重发原样使用；`(session_id, device_pub, seq)` 唯一。
- 单一执行器写状态；同一 SQLite 事务写 `sessions`、`messages`、必要的 `meta`；锁序 `sessions` → `messages` → `meta`；没有 outbox。
- 事务失败/崩溃/取消时旧状态保留；不复用 message key。
- 日志不记录正文、密钥、root、chain、联系人名或公钥。

`test-parity.md` 的 `ZTSEXMZQ2VHS21R6` 还要求：对数据目录做标记扫描，找不到消息、联系人或密钥；正确密钥可读回；错误密钥/错误实例解不开。只加密 `sessions.state` 不满足这条，因为 `messages.body`、联系人账号和邀请一次性私钥仍会是明文。

A-first 约束是 **产品代码不写 Rust**，不是「不用任何原生库」。SQLite JDBC 本身带按平台的 native SQLite；关键区别是是否需要再引入一个 SQLCipher 原生构建、JNI 包装和跨平台打包。

### 威胁模型

保护：

- 对应用数据目录的**离线读取**（备份、同步盘、误拷贝、其他用户进程读文件）。所有敏感值在 SQLite 页里都是 AEAD 信封。
- **部分恢复**：只回滚/复制一行时，`meta` 高水位若未被同步回滚，行版本低于高水位即拒绝。覆盖 `sessions`、`contacts`、`invitations`；`messages` 的正文不可变，行删除/`last_applied_seq` 篡改不在保护内（见「已知约束」）。
- `safety_verified` 等安全状态被静默翻转；它位于加密的 contact/session 状态内，并有独立高水位。
- 日志、崩溃输出、错误消息中的正文/密钥泄漏。

不保护：

- 已解锁进程的内存、调试器、同用户恶意进程；内存中的明文和派生密钥是运行时事实。
- 拥有密钥源（系统钥匙串或口令 + 密钥文件）的攻击者。
- **协调回滚**：同时回滚一行和对应 `meta` 高水位，或把整库回滚到较早的一致快照。`meta` 是明文单调值，不是远程/钥匙串锚点；这类攻击需要新基础设施，S1 登记为已知约束。只改一行/只回滚一行的部分恢复才受高水位保护。
- `messages`/`conversations` 的删除、`messages.state` 或 `last_applied_seq` 的本地篡改；它们造成可用性/顺序问题，不造成正文泄漏。
- `identity.bin` 在 S1 的可变版本回滚；S1 的身份密钥创建一次后不可变，身份替换走新目录/master。
- 数据库结构、行数、时间戳、会话/设备公钥等元数据的泄漏；这些是 S1 已知约束。
- 物理介质擦除；`secure_delete` 只减少 SQLite 旧页残留，不清理 WAL 历史帧，也不承诺物理擦除。

## 决策

### 1. 方案：plain SQLite + 应用层 AEAD

采用 **doobie 1.0.0-RC14（`ConnectionIO` / `Transactor[IO]`）+ HikariCP（maximumPoolSize=1）+ `org.xerial:sqlite-jdbc` + 应用层 ChaCha20-Poly1305**：

- 所有安全敏感值在进入 `sql"..."` 参数之前加密，SQLite 只看到字节。
- 复用 JDK 25 JCE 的 ChaCha20-Poly1305 和本地 RFC 5869 HKDF；不新增密码库。
- 不引入 SQLCipher、SQLCipher 的 JNI 包装或第二个数据库引擎。
- doobie 的 `ConnectionIO` 是 SQL 描述；`Transactor[IO]` 提供事务、提交/回滚和连接生命周期；`store/` 对外仍是 cats-effect 3 `IO`/`Resource`，不泄漏 `Future`。
- Hikari 只开 **1 条连接**（SQLite 单写者），不把 Hikari 当成并发池；`SQLiteConfig` 设 `journal_mode=WAL`、`synchronous=FULL`、`busy_timeout=5000`、`transaction_mode=IMMEDIATE`。
- 业务代码不直接拿 JDBC `Connection`；只有 `Meta[Array[Byte]]`、PRAGMA/迁移和驱动配置在边界。SQL 用 doobie 参数化 `sql` 插值，不拼字符串。

使用 doobie 而不是手写 JDBC：事务/回滚、连接释放、`SQLException` 边界和 cats-effect 组合已经有成熟实现；`store/` 不重复造这些。不用 Slick/Hibernate/ORM：S1 只有 6 张表、SQL 明确，ORM 会成为第二套抽象。

拒绝 SQLCipher 的理由：

1. **没有开源/可直接钉版本的桌面 JDBC 方案。** Maven Central 的 `net.zetetic` 组只有 Android 工件（`sqlcipher-android`、`android-database-sqlcipher`）；Zetetic 另有商业 Enterprise JDBC，但需要许可、原生库/平台矩阵和额外打包，不能像 `sqlite-jdbc` 一样作为普通依赖锁定。选型拒绝的是这个交付面，不是「SQLCipher 不存在」。
2. **SQLCipher 仍不能满足 ADR-0006 的行绑定。** 页级加密不绑定 `sessions.state` 的 `(id, peer_user_pub, peer_device_pub, generation, state_version)`；攻击者仍可把一行的有效页复制到另一行。即使使用 SQLCipher，`sessions.state` 仍需要应用层 AEAD；SQLCipher 只会省掉联系人/消息正文的字段加密。
3. **原生访问与 JDK 25 的边界更宽。** `sqlite-jdbc` 已经带 native SQLite；再叠 SQLCipher 会把平台矩阵、JNI 和启动参数扩大一倍。JDK 25 已对 native access 发出警告（见「后果」），能少一层是一层。

拒绝「只加密 `sessions.state`」：不满足 `ZTSE` 的联系人/消息标记扫描；拒绝「只靠 FileVault/BitLocker」：备份和拷贝会脱离磁盘加密。

### 2. 密钥层次

一个 32 字节 **master secret** 保护整个本地实例：

```text
masterSecret (32B, 来自系统钥匙串或口令包裹)
├─ identityKey = HKDF-SHA256(masterSecret, salt="", info="yochat-store/identity/v1", L=32)
└─ databaseKey = HKDF-SHA256(masterSecret, salt="", info="yochat-store/database/v1", L=32)
   ├─ stateKey(sessionId)      = HKDF-SHA256(databaseKey, salt=sessionId, info="yochat-store/session-state/v1", L=32)
   ├─ messageBodyKey(sessionId)= HKDF-SHA256(databaseKey, salt=sessionId, info="yochat-store/message-body/v1", L=32)
   ├─ contactKey               = HKDF-SHA256(databaseKey, salt="", info="yochat-store/contact/v1", L=32)
   └─ invitationKey            = HKDF-SHA256(databaseKey, salt="", info="yochat-store/invitation/v1", L=32)
```

- `masterSecret` 只用于派生；不以明文写入数据库、`meta`、日志或环境变量。
- `identityKey` 加密独立的 `identity.bin`；`databaseKey` 及其子密钥只加密数据库值。身份密钥与数据库密钥分离。
- 每个会话的 state/message 子密钥以 `sessionId` 为 HKDF salt。这不是为了抵御 `databaseKey` 泄漏（那时子密钥都可算），而是让会话 id 混用不会自动变成跨会话 nonce/key 复用；每次操作多一次 HKDF 可接受。域标签必须区分 state、message、contact、invitation。
- 不使用同一个随机 nonce 加密两个值；每个信封从 `SecureRandom` 取 12 字节 nonce。

**密钥源：系统钥匙串优先，Argon2id 口令回退。**

- 钥匙串/口令源的实际 S1 路径：Electron shell 用 `safeStorage` 加密保存**口令**（`passphrase.bin`，0600），经 preload IPC 交给 renderer；renderer 通过 loopback `POST /api/unlock` JSON body 交给 JVM；JVM 从 `keystore.bin` 以 Argon2id 解开 master。**不存在一次性继承管道**，master 也不从 shell 直接传给 JVM。renderer 妥协、同用户恶意进程和 HTTP body 在已解锁本机进程内存中的可见性不在本 ADR 的威胁模型内；口令不得进入 argv、环境变量、日志或崩溃报告。该路径由 `shell/main.js`、`shell/preload.js`、`HttpApi.scala` 和 `AppService.scala` 实现，E2E/HTTP 测试覆盖 loopback 解锁；选择它而不是 pipe 是本 revision 的明确决策。
- 回退：`keystore.bin` 文件（0600），Argon2id 派生 KEK，再用 ChaCha20-Poly1305 包裹 master。默认参数 Argon2id v=19、memory 64 MiB、iterations 3、parallelism 1、salt 16B、输出 32B；**接受范围** memory 16–256 MiB、iterations 1–10、parallelism 1–4；超出范围返回 `UnlockFailed`，不得按头里的巨值分配内存。参数和 salt 进入认证头，改参数即解不开。实现使用 BouncyCastle 的 Argon2id（ADR-0002 允许的 JCE 缺口）。
- 文件格式（v1）：`magic "YCKS" || version u8=1 || kdf u8=1 || memoryKiB u32 || iterations u32 || parallelism u32 || salt 16B || nonce 12B || wrappedMaster 32B || tag 16B`；AAD 覆盖 `magic..salt` 和固定域标签。错误口令只返回通用 `UnlockFailed`，不回显密钥/参数。
- `identity.bin`（v1）：`magic "YCID" || version u8=1 || nonce 12B || ciphertext || tag 16B`，明文是 4×32B 顺序：`user_priv || user_pub || device_priv || device_pub`（用户/设备 Ed25519）。AEAD key 为 `identityKey`，AAD 为 `"yochat-store/identity-file/v1" || version`（与 HKDF info 不同的域）。文件权限 0600，目录 0700。S1 中该文件创建后不可变；身份替换走新目录/master，不做旧文件版本回滚保护。
- **首次运行检测**：只有数据库、`identity.bin`、`keystore.bin`、钥匙串项**四者全不存在**时，才生成 master 并创建实例。其余任何部分存在组合返回 `InstancePartiallyPresent`/`UnlockFailed`，失败关闭；尤其：
  - 数据库不存在但 `identity.bin` 存在：不得生成新 master；
  - 数据库存在但 `identity.bin` 缺失：不得重建身份；
  - `identity.bin` 存在但两个密钥源都不可用：不得覆盖/新建。
  S1 没有恢复短语。
- 修改口令 = 用旧口令解开 master、用新口令重新包裹；不改数据库行。钥匙串与口令文件只决定同一 master 的取法。**旧 `keystore.bin` 副本仍可用旧口令解开同一 master**；这是重包裹不是撤销，登记为已知约束。
- **S1 不支持 master 轮换**。master 泄漏需要删除本地实例并重新配对；这不是通过新建表或后台重加密伪造的保证。见「后果」。

### 3. 信封格式与 AAD

所有应用层加密值使用同一信封：

```text
magic "YCE1" (4B) || nonce (12B) || ciphertext (nB) || tag (16B)
```

JCE 的 `doFinal` 输出 ciphertext || tag；写入方不重新编码，读取方不把明文重新序列化后写回。AAD 是固定顺序的字节串：`UTF-8 domain || 0x00 || fixed-length fields || big-endian i64`。域之间不共享 AAD；**所有字段必须定长**，加入变长字段必须换域标签/版本并新开 ADR。magic 不进 AAD；magic 损坏只得到 `InvalidEnvelope`（可用性），不是可伪造的明文。

| 值 | key | AAD |
| --- | --- | --- |
| `sessions.state` | `stateKey(sessionId)` | `"yochat-store/session-state/v1"` + `sessionId(32)` + `peerUserPub(32)` + `peerDevicePub(32)` + `generation(i64)` + `stateVersion(i64)` |
| `messages.body` | `messageBodyKey(sessionId)` | `"yochat-store/message-body/v1"` + `conversationId(32)` + `sessionId(32)` + `authorPub(32)` + `devicePub(32)` + `seq(i64)` + `wireHash(32)` |
| `contacts.sealed` | `contactKey` | `"yochat-store/contact/v1"` + `userPub(32)` + `version(i64)` |
| `invitations.sealed` | `invitationKey` | `"yochat-store/invitation/v1"` + `invitationId(32)` + `version(i64)` |

- `sessions.state` 的 AAD 必须包含 ADR-0006 规定的全部五个字段；`state_version` 是**新**版本，读取时从行里取同值重建。
- `contacts.sealed` 包含 `channel_label`、`channel_account`、`peer_channel_account`、`alias`、`note`、`safety_verified`、`confirmation_state`；`user_pub` 是公开主键。`safety_verified` 不放在明文列，避免单个布尔值被翻转。
- **`safety_verified` 的权威副本**是 `contacts.sealed`。`sessions.state.safetyVerified` 是 core 发送/解密门使用的会话副本；安全检查/撤销时，在**同一事务**内更新 contact（version+1）和所有活动 session（state_version+1），锁序 `contacts` → `sessions` → `meta`。打开 session 时若两处不一致，失败关闭 `InvalidSafetyState`；rekey 创建新 session 时从 contact 复制，不从旧 session 的过期副本继承。
- `invitations.sealed` 包含 `ephemeral_priv`、`peer_account`、`state`、`confirm_state`、`payload`、`expires_at`；`id` 是公开/一次性标识。一次性私钥不落明文。
- `messages.wire` 不套第二层 AEAD：它已经是 `header || ct || sig`，重发必须逐字节原样使用。`body` AAD 绑定 `SHA-256(wire)`；写入方同时持有二者，只计算哈希，不需要 message key。这样 body 不能配另一条 wire，wire 也不能配另一条 body；读取时重新计算 `wireHash`。读取/加载时解析 header，校验 `session_id`、`device_pub`、`seq` 与行一致；不一致即拒绝，不重新签名、不重新加密。若 wire 本身损坏，协议验签/AEAD 会拒绝；存储层不尝试解密 wire。
- `messages.state`、`conversations.last_applied_seq`、`created_at` 等保持明文元数据；它们不承载正文/密钥，篡改只造成可用性或显示问题，不在 S1 的防篡改承诺内。安全决策不得只依赖这些明文列。
- `messages.id` 只是本地行代理（可以是自增/随机 id），**不进入 AAD**；消息的身份/绑定由 `(conversation_id, session_id, author_pub, device_pub, seq)` 和 `wireHash` 决定。新建消息不需要在 AEAD 前知道数据库生成的 id。
- 明文列只有：主键/路由/排序标识、公钥、版本号、时间戳、非安全状态。任何新增列先回答「它是否影响发送/展示/安全门」，影响就进 sealed 或进 AAD。

### 4. 表、版本与索引

沿用 6 张表；对安全关键行增加版本列：

| 表 | 明文列（摘） | 加密值 | 版本/高水位 |
| --- | --- | --- | --- |
| `sessions` | `id`, `peer_user_pub`, `peer_device_pub`, `state_version`, `generation`, `updated_at` | `state` | `meta["session-state-version/<hex id>"]` |
| `messages` | `id`, `conversation_id`, `session_id`, `author_pub`, `device_pub`, `seq`, `state`, `created_at`, `wire` | `body` | 无（body 不可变；wire 由协议签名/去重约束；body 绑定 wireHash） |
| `contacts` | `user_pub`, `version`, `created_at` | `sealed` | `meta["contact-version/<hex user_pub>"]` |
| `invitations` | `id`, `version`, `created_at` | `sealed` | `meta["invitation-version/<hex id>"]` |
| `conversations` | `id`, `peer_user_pub`, `created_at`, `last_applied_seq` | 无 | 无 |
| `meta` | `key`, `value` (INTEGER NOT NULL) | 无（不得放秘密） | `schema_version`、`lock-generation`、三类高水位 |

约束：

- 所有 BLOB 长度固定字段在绑定前校验；`state_version`/`version` 为 `i64`，只接受 `>= 1`，递增前检查溢出。`generation` 只接受 `>= 0`。
- `messages` 的 `UNIQUE(session_id, device_pub, seq)` 是准入/去重索引；**另建 `(conversation_id, author_pub, seq)` 索引给聊天列表**。`sessions.id`、`contacts.user_pub`、`invitations.id` 主键用于点查。
- `meta` 的 key 使用固定前缀 + 32 字节 id 的十六进制；不得把用户正文、联系人名或密钥写进 key。`meta.value` 在 S1 只放整数；不放配置快照、JSON 或任意文本。
- `schema_version=1` 写在 `meta`；初始建表与 `schema_version` 在同一事务。打开时发现数据库 `schema_version` 高于本进程支持的版本，返回 `UnsupportedSchema`，不得按 v1 静默操作。迁移按版本单步执行；任何改变 AAD 的迁移必须新开 ADR。
- 旧会话删除：**本地新 session `Active` 不是充分条件**。必须等 rekey `Confirm/Ack` 双向完成，且旧会话没有待发/待确认消息，才在同一事务推进旧 `sessions` 高水位并删行。
  - 发起方：收到 `RekeyConfirmAck` 后，若旧会话无 pending，删旧 state。
  - 应答方：发送 ACK 后无法观察它是否被收到；必须等收到**新 session 上的首个已认证数据帧**（证明发起方已收到 ACK 并完成切换），且旧会话无 pending，才删旧 state。没有收到该帧就保留旧 `ReadOnly` state。
  - 仍有 pending 时保留旧 `ReadOnly` state 和 `wire`，按 ADR-0006 的重发/上限处理；不得用 cutover 偷偷把消息标成终态（那会删掉设计要求的重试）。只有重试上限/用户明确操作才允许 `Failed`。
  - 删除后晚到旧帧按 ADR-0006「按丢失处理」；保留 `messages` 历史行，历史 body 仍可用 `messageBodyKey(sessionId)` 解出，不需要旧 session state。`contacts`/`invitations` 删除同样先推进 tombstone 高水位再删行。
- `messages`/`conversations` 不建高水位：删除消息或抬高 `last_applied_seq` 是本地可用性/顺序攻击，登记为已知约束；不为此每消息写 meta（会撞 1,000 条门）。
- `meta` 的 tombstone 在 S1 不自动清理；每会话/联系人/邀请一行。邀请 tombstone 会随用户创建过的邀请累积，**不是**由「同时只有一个待处理邀请」限制；S1 接受这个有界于本地操作量的增长，清理策略另开 ADR。

### 5. 事务、锁序与崩溃

- **单写者 + 至多一个借用连接**：Hikari 的 `maximumPoolSize=1` + `Semaphore(1)`；`Transactor[IO]` 同一时刻最多借一条连接，读/写串行。Hikari 可以在归还后替换物理连接，不能假设「永远同一条」。Hikari 的池默认 `autoCommit=true`；doobie 的 `Strategy` 在业务事务内设 false、commit/rollback，连接归还时 Hikari 恢复 true，避免空闲连接继续持有 SQLite 写锁。`VACUUM INTO` 等维护操作走 RC14 的 `rawTrans`（非事务解释器；旧草稿写作 `transactRaw`），不包在默认事务里。sqlite-jdbc 的 `Connection` 不是线程安全的；不假装只读连接可以并发。以后加读连接池必须同时给 WAL checkpoint 策略和每线程连接，另写 ADR。
- **开始**：`SQLiteConfig.TransactionMode.IMMEDIATE` 让驱动以 `BEGIN IMMEDIATE` 开始事务；doobie `Transactor` 的默认 `Strategy` 设 autoCommit=false、成功 commit、失败 rollback。不嵌套事务；一次业务操作一个事务。
- **锁序**（全局）：`invitations` → `contacts` → `sessions` → `messages` → `meta`。消息路径是 `sessions` → `messages` → `meta`，与 ADR-0006 一致。邀请接受/安全码确认按上述全序写，不先拿 `sessions` 再回头写 `invitations`。SQLite 单写者下表序不承担并发锁语义，但它是跨表操作的固定顺序，避免实现漂移。
- **锁代次**：`meta["lock-generation"]` 是全局 `i64`，在锁/身份替换事务内递增。每个写事务带 `expectedLockGeneration`，在事务内读 `meta` 并比较；不等返回 `StaleGeneration`，不写任何行。`sessions.generation` 仍是 state AAD 的字段，写候选时使用当前锁代次；`contacts`/`invitations` 不复制 generation，由这个全局 CAS 拦截旧在途结果。
- **高水位写**：更新事务先读旧版本/高水位；要求行版本 = 旧版本 = 高水位（新建时都为空）。`UPDATE ... WHERE id=? AND version=?`（sessions 加 `AND generation=?`）影响行数必须为 1；随后把 `meta` 高水位**精确**设为新版本并检查影响行数。禁止 `WHERE excluded.value > value` 这种静默 no-op：它会把回滚行「洗」成新版本而高水位不动。
- **提交顺序**：先完成验签/解密/状态转换，得到候选；用 doobie `ConnectionIO` 在事务内按锁序写行和 `meta`；`Transactor` 提交成功后才发布候选、才产生 `Send`/`Notify` 效果。网络发送在提交之后；失败只留下可重发的 `messages.state` + `wire`。
- **失败/取消/崩溃**：doobie/SQLite 回滚旧状态；候选和 skipped key 不发布；重启从已提交状态继续。事务失败不得翻译成「空」「未找到」；存在性与可读性分开返回（`StorageFailure` 不是 `NotFound`）。
- **SQLite/doobie 配置**：`SQLiteConfig` 设 `journal_mode=WAL`、`synchronous=FULL`、`busy_timeout=5000`、`transaction_mode=IMMEDIATE`；`secure_delete=ON`。S1 不建外键，`messages` 故意不级联到 `sessions`；如启用 `foreign_keys=ON`，必须明确没有约束会被依赖。不清空 WAL；`secure_delete` 不清理 WAL 历史帧。长读连接（若未来引入）需要 checkpoint 策略，S1 单连接不产生该问题。
- **持久性口径**：`synchronous=FULL` 保证 SQLite 在提交时同步 WAL；应用进程崩溃/重启可恢复。**电源/OS 崩溃**还取决于文件系统：macOS `fsync` 不等于刷硬件缓存，需要 `fullfsync`/`F_FULLFSYNC`；S1 不启用它，因此断电可能丢尾部已提交事务。Linux 上 `FULL` 的同步语义更强。需要电源级持久性时开 `fullfsync` 并重新过 1,000 条性能门；不得在本 ADR 里写一个未入回执的耗时。
- `updated_at` 由注入的 `Clock` 产生；`core/` 不读墙钟。`messages.state` 与 `wire` 是待发表，不新增 outbox 表。`updated_at` 回退不触发错误；它只用于展示/排序提示。
- 每个会增长的查询在加入时用 doobie 的 `sql"EXPLAIN QUERY PLAN ...".query` 验证：点查/列表使用主键或索引，不得对 `sessions`/`messages` 加密 BLOB 做全表 `SCAN`，聊天列表也不得 `TEMP B-TREE` 排序。

### 6. 打开、回滚与删除

打开会话/联系人/邀请时：

1. 读取行和对应 `meta` 高水位；行不存在返回 `NotFound`，不得创建默认值。
2. 高水位不存在：`MissingHighWater`，失败关闭；不隐式用行版本「修复」。
3. `row.version < highWater`：`StaleGeneration`，失败关闭；`row.version > highWater`：`InvalidStateVersion`，失败关闭。
4. 用行内 `generation`/`version` 重建 AAD，解密；AEAD 失败返回 `InvalidCiphertext`，不尝试旧密钥、不跳过。
5. 打开 session 时核对 `contacts.sealed.safety_verified` 与 `sessions.state.safetyVerified`；不一致返回 `InvalidSafetyState`。

错误码是 ADR-0006 §7 的扩展：`NotFound`、`MissingHighWater`、`StaleGeneration`（低版本/锁代次）、`InvalidStateVersion`（高版本）、`WriteConflict`、`InvalidCiphertext`、`InvalidEnvelope`、`InvalidSafetyState`、`InstancePartiallyPresent`、`UnlockFailed`、`UnsupportedSchema`、`StorageFailure`。低层 JDBC 消息不向上泄漏。

`generation`：

- `meta["lock-generation"]` 是所有写事务的全局 CAS；锁定/身份替换时在同一事务递增。
- `sessions.generation` 是 state AAD 的一部分；候选写入取当前锁代次，读取用行内值。`contacts`/`invitations` 的旧在途写由全局 CAS 拦截，不加各自 generation。
- 数据目录属于一个本地身份；身份替换使用新的 master/目录，不复用旧数据库。

**已知约束（明确不伪造保证）**：

- **部分恢复**：只回滚一行、只复制一行、删除后重插旧行，若 `meta` 高水位未被一起修改，受高水位保护。**协调回滚**：同时回滚行与对应 `meta` 高水位，或整库回滚到旧快照，S1 无法检测。`meta` 是明文且未加 MAC；有写权限的攻击者可以改它。要检测协调回滚需要钥匙串/远程锚点，写入不能与 SQLite 提交原子化，且每条消息写钥匙串会破坏 1,000 条 `<5s` 门；S1 不做，登记为已知约束。
- `messages` 删除、`messages.state` 篡改、`conversations.last_applied_seq` 抬高：本地可用性/顺序损坏，不承诺检测；不会泄漏正文。
- 断电可能丢尾部提交（见「事务、锁序与崩溃」）；应用崩溃/重启由 WAL 恢复。
- 数据库被整体删除后重新创建是新的本地实例；S1 没有恢复短语。
- 本地明文元数据（行数、时间、公钥、会话 id）泄漏；不承诺对取证隐藏数据库结构。

### 7. 日志、错误、密钥生命周期与备份

- 只记录稳定错误码、表名和会话 id 的不可逆短标识；不记录 SQL 参数、明文、密钥、`state`/`body` 字节、联系人名/账号。JDBC 异常在 `store/` 边界映射为 `StorageError`，不把驱动消息原样向上抛。
- 解锁后 master/子密钥只在内存；不写临时文件、不写 `meta`、不进 Electron 渲染进程。进程退出/锁定时丢弃引用；Java 不能保证擦除所有副本，这是已知限制。
- 口令回退的 Argon2id 只在解锁时运行；参数和 salt 从认证头读取并按 §2 的上下界校验。口令错误、密钥文件损坏、钥匙串拒绝统一返回 `UnlockFailed`，不泄露是哪一步失败。旧 `keystore.bin` 副本在改口令后仍可用旧口令解开同一 master；S1 不承诺撤销。
- 应用锁/空闲锁定由状态机 `lock-generation` 驱动；存储层拒绝旧代次写入，但不自行判断 UI 锁状态。
- **备份**：运行中的 SQLite 不得直接拷贝 `data.db + -wal + -shm`（三份文件不是原子快照）。`store/` 提供 `backup`，使用 SQLite Online Backup API 或 doobie RC14 `rawTrans` 中的 `VACUUM INTO` 生成一致副本（不能用默认事务包 `VACUUM INTO`）；复制 `identity.bin`。口令路径额外复制 `keystore.bin`（0600；它是包裹后的 master，不是明文密钥，恢复仍需原口令）；钥匙串路径不复制钥匙串项，恢复需要同一钥匙串。只复制 `data.db` 会丢身份/密钥源。备份中的数据库仍是密文；密钥源不在钥匙串备份里时不承诺可恢复。
- **JDK 25 native access**：`sqlite-jdbc` 加载 native SQLite；JDK 25 会警告 `System::load` 受限。发布启动器必须加 `--enable-native-access=ALL-UNNAMED`（或升级到不触发警告的驱动）；否则未来 JDK 可能拒绝加载。该参数进 shell 启动配置，不进用户可调配置。

## 备选与否决

| 方案 | 拒绝/选择理由 | 代价（估算） |
| --- | --- | --- |
| SQLCipher 原生 JDBC，全库加密 | 无桌面 JDBC 工件；要自建 native/JNI/打包；仍不能提供 `sessions.state` 行 AAD | 3–5 人日，且引入平台矩阵 |
| SQLCipher + 应用层 state AAD | 能满足行绑定，但数据库双重加密；联系人/消息收益不足以抵消原生驱动 | 4–6 人日 |
| 只加密 `sessions.state` | `messages.body`/联系人/邀请私钥明文，`ZTSE` 不过 | 0.5 人日，但安全失败 |
| 只靠 FileVault/BitLocker | 备份/拷贝/同步盘脱离磁盘保护，测试不过 | 0 |
| PostgreSQL（本地或远程服务） | S1 是单机离线客户端，PG 会引入服务部署/启动/凭据/备份面；远程 PG 还会把元数据移出本机。共享/多客户端需要新架构，另开 ADR | 4–8 人日，外加服务生命周期 |
| 应用层 AEAD + 单写者 SQLite（选中） | 复用 JCE/HKDF/SQLite；行 AAD 直接；无 SQLCipher 原生面 | 10–14 人日 |
| 钥匙串高水位 + 每条写同步 | 可检测整库回滚，但非事务、每次写钥匙串，1,000 条门失败 | 2–3 人日，且可靠性差 |
| 仅口令，无钥匙串 | 可用但弱口令可离线爆破；不利用平台原生保护 | 1 人日，UX/安全变差 |
| 仅钥匙串，无口令回退 | headless/无钥匙串环境无法启动 | 0.5 人日，可用性失败 |

## 后果

- 数据目录中只有密文正文/联系人/私钥；数据库结构、行数、时间戳、公钥等元数据仍可见。产品文案不得声称「整个文件系统不可见」。
- `messages.body` 和 `wire` 是两条不同的数据：`wire` 用于协议重发，`body` 用于本地展示/重发副本；body AAD 绑定 `SHA-256(wire)`，二者不能互换；重发不重新加密/不重新签名。
- 每个安全关键行多一次 AEAD 和一次 `meta` 写入；实测在 JDK 25、SQLite 3.54.1、APFS 临时目录上 1,000 次插入总 143ms、1,000 次点查总 24ms，合计 168ms；p95 约 260µs/35µs；输入哈希 `e369edf930d2cfbee890b1c2cc45acec52978b9845f097f95d92b52a5be25b70`。该数字不是生产 1,000 条 E2E 回执。
- 钥匙串/口令、`identity.bin`、数据库三者的丢失组合都失败关闭；S1 用户没有恢复短语。这是与 S3 的明确边界。
- 不实现 master/字段级 key rotation；泄漏处置是删除实例并重新配对。新增轮换必须单独 ADR，不得在迁移里偷偷重加密。
- `sqlite-jdbc` 的 native 警告需要发布启动器处理；JDK 版本升级要复测驱动。
- doobie `1.0.0-RC14` 仍是 release candidate；精确版本钉在 `build.mill`，升级要重跑 storage/doobie smoke 与查询计划。Hikari `maximumPoolSize=1` 意味着 S1 的读也串行；这是本地单用户的取舍，不是通用连接池配置。
- Hikari/doobie 需要 SLF4J 绑定；生产加 logback，日志不得带 SQL 参数或明文。

## 可证伪 gate

| ID | Gate | PASS 证据 | 当前状态 |
| --- | --- | --- | --- |
| G1 | 真实 SQLite 落盘并重开 | spike 写 session/message 后关闭，新连接解出并比对原文；contact/invitation 只验证 AEAD 写入与目录标记，读取路径见 G11 | PASS（spike revision 4） |
| G2 | 数据目录无明文标记 + 错误实例解不开 | 连接打开时文件列表含 `-wal`/`-shm`，扫描数据库、WAL、SHM 无 8 个标记；关闭后再扫 0 命中；第二个 master 读取同一 session 返回 `InvalidCiphertext`；SQLite 文件头本身可见，旧 `store_at_rest` 的「非明文头」断言按 `REWRITE` 处理 | PASS（spike revision 4；header 断言改写） |
| G3 | 行绑定（session/message） | 交换两个 session 的 `state`、两条 message 的 `body`，解密均返回 `InvalidCiphertext`；先恢复 body 再只交换 wire，`wireHash` 不匹配返回 `InvalidCiphertext` | PASS（spike revision 4） |
| G4 | 单行部分回滚（session） | 读：低版本返回 `StaleGeneration`；写：低版本/高水位不一致时拒绝，行与高水位都不变；contact/invitation 见 G11 | PASS（spike revision 4） |
| G5 | 删除 tombstone（session） | 删除后重插旧 session 版本，返回 `StaleGeneration`；contact/invitation 见 G11 | PASS（spike revision 4） |
| G6 | 查询计划 | session/message 去重点查、聊天列表 `(conversation_id, author_pub, seq)` 均无 `SCAN`/`TEMP B-TREE`；contact/invitation 点查在 G11 | PASS（spike revision 4） |
| G7 | 1,000 次插入+读取总耗时 | 1,000 次插入与 1,000 次点查**总计** `<2s`；实测 168ms；输入哈希 `e369…b70`、命令和版本见 receipt | PASS（spike revision 4） |
| G8 | 事务失败不发布候选 | 强制回滚后旧状态可读；读失败不返回空；写失败不多不少；候选/消息 key 不落盘 | PASS（`ST-store-failure`：重复 seq 回滚、损坏 state 报错不返回空） |
| G9 | 崩溃/重启 | 进程在提交前后被终止，重启只看到完整提交；待发 wire 原样重发 | PASS（`ST-crash-recovery`：真实子进程 halt-before/after-commit） |
| G10 | 密钥源与部分实例 | 部分存在组合失败关闭（含 `identity.bin` 独存）；DB + identity + **任一合法密钥源**可重开；钥匙串拒绝/口令错误/identity 缺失均失败；口令不出现在 argv/env/日志 | PASS（`UT-keyfile-create`、`UT-keyfile-bounds`、`UT-partial-instance`、`UT-provider-refusal`；Electron `shell/main.js` 的 `safeStorage` 口令路径与 `keychain-smoke.js`；renderer/HTTP body 暴露按 revision 6 威胁模型接受） |
| G11 | 联系人/邀请绑定、回滚与安全状态 | 交换 contact/invitation sealed、回滚 version、删除后重插旧版本均被拒；contact/invitation 点查与聊天列表走索引；`safety_verified` 翻转被拒；contact/session 不一致失败关闭；rekey 后从 contact 继承 true | PASS（`ST-contacts-invitations`，含 swap/rollback/tombstone/safety/mismatch/rekey） |
| G12 | 旧会话删除 | 双向 Confirm/Ack 完成且有 pending 时不删；lost-ACK + **零 pending** 时应答方仍保留旧 state，直到收到新 session 首个已认证数据帧；发起方收到 ACK 且无 pending 才删；旧 state 不能复活；历史 body 仍可读 | PASS（`ST-cutover`：initiator/responder、pending、lost-ACK、tombstone、历史 body） |
| G13 | 1,000 条 E2E `<5s` | 两进程真实消息路径，含本存储方案；硬门，不校准 | 未实现（M5；`ST-store-throughput` 1,000 写+读约 1.5s，不替代两进程 E2E） |
| G14 | 备份与 native 启动 | 运行中 `backup` 生成一致副本；恢复身份/密钥源按路径说明；JDK 25 启动带 native access 参数 | PARTIAL（`ST-backup`、`ST-native-access` PASS；Electron launcher 不在本仓） |
| G15 | doobie/SQLite 接入 | Hikari(max 1) + `Transactor[IO]`；建表、参数化写入、读回、强制回滚（确认 update 影响 1 行且异常是 forced rollback）、PRAGMA WAL/FULL、点查走索引 | PASS（生产 `store/` 测试覆盖 PRAGMA、事务回滚、索引计划、schema fail-closed） |
| G16 | Hikari 归还连接/维护路径 | 事务持有写锁时第二条连接被阻塞；commit/rollback 后连接可写；`VACUUM INTO` 走 `rawTrans` | PASS（`ST-store-transaction`、`ST-backup`；RC14 实际 API 是 `rawTrans`） |

Gate 口径：G1–G7 是 storage spike 的可执行证据；G15 已由生产 `store/` 测试覆盖，不再只是 doobie smoke。G8–G12/G16 由 `store/test/src/yochat/store` 的实现测试覆盖；G10 的 Electron `safeStorage`/loopback 路径已在本仓并有 smoke/E2E 证据；G14 的 Electron launcher 不在本仓，保持 PARTIAL；G13 仍是 M5 的两进程 E2E，store-only 回执不替代。未跑就是未跑，不写成 PASS。

## 评审与未关闭

- 内部设计评审 round 1：1 个 BLOCKER（首次运行部分实例）、10 个 MAJOR、14 个 MINOR；revision 2 已处理 blocker/MAJOR，并修正 G1/G2/G3/G7 的虚假 PASS 口径。
- 内部设计评审 round 2（doobie）+ closure：4 个 MAJOR（Hikari autoCommit/写锁、G15 未验证 UPDATE 行数、G3 wire 测试被顺序混淆、G12 本地 Active 过早删旧会话）已修；G12 的应答方 closure 已确认：必须等新 session 首个已认证数据帧且无 pending；MINOR（G7 输入哈希、stale write、G10/G11 缺项、SQLCipher 措辞、tombstone 增长）已并入 revision 4/5。Hikari 归还连接/维护路径另立 G16，现由生产 store 测试验证。最终复核没有剩余 BLOCKER/MAJOR。
- round 1 的 MINOR 处理：人日改为 10–14；保留按会话子密钥并说明目的；邀请 tombstone 承认会累积、不假装由单待处理邀请限制；`meta.value` 只放整数并拒绝更高 schema；Argon2 上下界；高水位精确写；单连接串行；`fullfsync` 不提未入回执数字；AAD 定长/身份域分离；高水位逃生条款写入取代关系；错误码扩展；`messageId` 从 AAD 删除。
- 外部密码学评审：仍是发布门；本 ADR 的密钥层次、Argon2id 参数、AEAD AAD 必须和 ADR-0006 一起送审。内部评审不是密码学证明。
- 已实现：生产 `store/`（doobie/Hikari/SQLite、AEAD、高水位、锁代次、联系人/邀请/会话/消息/聊天仓）、`MasterKeyProvider`/`KeyFile`/`identity.bin`、备份、真实子进程崩溃注入，以及 G8–G12/G15/G16 测试。
- 未实现：Electron launcher 的 native-access 配置（G14；store 子进程探针已过）、M5 两进程 1,000 条 E2E（G13）；外部密码学评审由 owner 决策关闭（见评审包）。
- 明确的后续输入：电源级持久性（`fullfsync`）、master 轮换、恢复短语、S2 多设备；不在 S1 里用新表/新协议预支。

## 取代关系

本 ADR 取代：

- `docs/adr/0002-technology-stack.md` 中「存储加密方式由 spike 决定」的占位；
- `docs/adr/0006-s1-encryption-scheme.md` §5 中「SQLCipher 还是应用层 AEAD 由 ADR-0007 决定」的占位；
- ADR-0006 §5 中「高水位优先 keychain，退而其次 DB meta」的默认路径：本 ADR 选择 DB `meta` 高水位，并按 ADR-0006 的逃生条款登记整库+meta 回滚为已知约束；
- `docs/plans/2026/11/0001-yochat-rewrite/design.md` 的存储加密占位。

不取代 ADR-0003 的范围、ADR-0004 的测试/性能门、ADR-0006 的会话加密与 rekey 方案。
