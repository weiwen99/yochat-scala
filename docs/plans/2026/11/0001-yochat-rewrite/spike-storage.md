# Storage spike: SQLite + application-layer AEAD

- 日期: 2026-11-20
- 对象: `spike/src/yochat/spike/StorageSpike.scala`
- 决策: `docs/adr/0007-storage-encryption-and-keys.md`
- 结论: **PASS**（仅限本文件列出的存储门；不是生产 `store/`，也不是 1,000 条 E2E 回执）

## 环境

- macOS 27.2.1，arm64。
- JDK 25.0.3（Zulu），Scala 3.10.1，Mill 1.3.1。
- `org.xerial:sqlite-jdbc:3.54.1.0`，bundled SQLite 3.54.1。
- `org.typelevel::doobie-core:1.0.0-RC14`、`doobie-hikari:1.0.0-RC14`、HikariCP（传递依赖）、`cats-effect:3.7.1`。
- 临时 APFS 目录；单进程、无并发负载。
- Spike 设置 `wal_autocheckpoint=0`，只为让 `-wal`/`-shm` 在扫描时存在；生产 checkpoint 策略见 ADR。

## 命令

Mill 启动器自身使用 JDK 21；为了在目标 JDK 25 上执行，先取 classpath，再用 JDK 25 直接启动：

```bash
CP=$(./mill show spike.runClasspath | jq -r '.[] | split(":")[-1]' | paste -sd: -)
JAVA_HOME_25=/nix/store/1vv59r63zic4f2l274qanhn29x83hm2g-zulu-ca-jdk-25.0.3/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
"$JAVA_HOME_25/bin/java" -cp "$CP" yochat.spike.SpikeMain storage
```

`./mill spike.run storage` 也可从 `check.sh` 回归；它的运行时是 Mill 自带的 JDK 21，不作为 JDK 25 回执。

## 输出（master 标记已脱敏）

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader in an unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module

STORAGE runtime java=25.0.3 os=Mac OS X arch=aarch64 sqlite=3.54.1
STORAGE pragmas journal_mode=wal synchronous=2
STORAGE session_plan=SEARCH sessions USING INDEX sqlite_autoindex_sessions_1 (id=?)
STORAGE message_plan=SEARCH messages USING INDEX sqlite_autoindex_messages_1 (session_id=? AND device_pub=? AND seq=?)
STORAGE list_plan=SEARCH messages USING INDEX messages_conversation_order (conversation_id=? AND author_pub=?)
STORAGE open_files=yochat.db-wal,yochat.db-shm,yochat.db
STORAGE at_rest_open markers=8 found=0
STORAGE inserts=1000 insert_total_ms=143 insert_p95_us=260 reads=1000 read_total_ms=24 read_p95_us=35 total_ms=168 input_hash=e369edf930d2cfbee890b1c2cc45acec52978b9845f097f95d92b52a5be25b70
STORAGE at_rest_closed markers=8 found=0
STORAGE_CHECKS PASS
```

数字是本次单连接、临时目录的结果；不是端到端消息时延，也不替代 M5 的 1,000 条 `<5s` 硬门。

## doobie 接入层 smoke

命令（JDK 25）：

```bash
CP=$(./mill show spike.runClasspath | jq -r '.[] | split(":")[-1]' | paste -sd: -)
"$JAVA_HOME_25/bin/java" -cp "$CP" yochat.spike.SpikeMain doobie
```

输出：

```text
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by scala.runtime.LazyVals$ ...
WARNING: A restricted method in java.lang.System has been called
WARNING: System::load has been called by org.sqlite.SQLiteJDBCLoader ...
WARNING: Use --enable-native-access=ALL-UNNAMED ...

DOOBIE payload=DOOBIE_PAYLOAD journal=wal synchronous=2
DOOBIE plan=SEARCH probe USING INDEX sqlite_autoindex_probe_1 (id=?)
DOOBIE_CHECKS PASS
```

该 smoke 证明：`HikariConfig(maximumPoolSize=1, autoCommit=true)` + `SQLiteDataSource` + `HikariTransactor.fromHikariConfig[IO]` 可以启动；doobie `Transactor.trans` 能建表、参数化写入、读回；强制失败的事务在 UPDATE 确实影响 1 行后回滚到旧值；`journal_mode=wal`、`synchronous=2 (FULL)` 生效；点查使用唯一索引。`BEGIN IMMEDIATE` 由 `SQLiteConfig.TransactionMode.IMMEDIATE` 配置，但这次 smoke 没有并发证明锁释放；G16 是后续门。它也不证明 1,000 条性能、崩溃恢复、密钥源或并发。

## 覆盖

- 真实 SQLite 文件写入、关闭、重新打开；session state 和 message body 解出原文。
- `sessions.state`、`messages.body`、`contacts.sealed`、`invitations.sealed` 的 AEAD 写入；master 的 hex/base64 不在目录中。
- `-wal` 和 `-shm` 在连接打开时确实存在；对数据库、WAL、SHM 扫描 8 个明文标记，0 命中；关闭后再扫描，0 命中。
- 第二个 master 打开同一数据库：session state 无法解开，返回 `InvalidCiphertext`。
- 交换两个 session 的 state：`InvalidCiphertext`。
- 交换两条 message 的 body：`InvalidCiphertext`。
- session 行版本低于 `meta` 高水位：读返回 `StaleGeneration`；随后尝试更新同一 session 也被高水位检查拒绝，行版本和 `meta` 值不变。
- 删除 session 后重插旧版本：`StaleGeneration`（tombstone 高水位）。
- 先恢复 body 再只交换 wire：`wireHash` 不匹配，单独证明 AAD 绑定而不是顺序副作用。
- `EXPLAIN QUERY PLAN`：session/message 点查和聊天列表均为 `SEARCH ... USING INDEX`，无 `SCAN`/`TEMP B-TREE`。
- 1,000 次插入 + 1,000 次点查，总计 168ms（目标 `<2s`）；输入哈希 `e369…b70`。

## 未覆盖

- `contacts`/`invitations` 的读取、交换/回滚、点查和高水位（G11）；spike 只证明它们的 AEAD 写入与目录扫描。
- 钥匙串/口令解锁、Argon2id 参数、`identity.bin`、`KeyProvider`（G10）。
- 事务失败注入、进程崩溃/重启恢复、并发、单写者 `Semaphore`（G8/G9）；doobie smoke 只做了单事务回滚。
- doobie 的 `BEGIN IMMEDIATE` 运行期行为、1,000 条经 doobie 的端点性能、Hikari 连接复用；这些进生产 store 测试。
- 旧会话 cutover 与 `messages.state=Failed`（G12）。
- 备份、native access 启动参数（G14）。
- 1,000 条真实端到端路径（G13）。
- 外部密码学评审。

这些未覆盖项不得被本 PASS 推断为完成。
