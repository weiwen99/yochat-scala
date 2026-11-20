# 技术栈：Scala 3 + Pekko + ScalaJS/Laminar + Electron，A-first 无 Rust

**状态**: accepted (2026-11-20；同日增补 effect boundary)

## 背景

旧实现是 Rust + Tauri + Svelte + SQLite/SQLCipher + iroh/p2panda。用户决定重写不再用 Rust，技术栈与 0x1、CFA 对齐。旧 `docs/spec/engineering/decisions/technology-stack.md` 因此被本 ADR 取代。

约束：

- 桌面三端优先，移动端预留；
- 常在线设备可无人值守长期运行；
- 端到端加密、多设备是产品方向，但 v1 只做 S1（ADR-0003）；
- 代码量可控，单人可读；
- 产品代码不写 Rust。

## 决策

- **语言/构建**：Scala 3.10.x + Mill 1.3.x + JDK 25。精确版本在 `build.mill`。
- **本地服务**：Pekko 1.7.x + Pekko HTTP 1.4.x，绑定本机回环；Electron 通过 HTTP/IPC 调用。
- **UI**：ScalaJS + Laminar，复用 CFA 的组件与协议 ADT；不做 Svelte 第二套 UI。
- **桌面壳**：Electron 只承担窗口、通知、钥匙串、单实例与进程生命周期；业务逻辑全在 JVM 服务。
- **存储**：SQLite；ADR-0007 已落地为 doobie 1.0 + `sqlite-jdbc` + 应用层 AEAD（SQLCipher 否决）；S1 是客户端本地单实例。
- **密码学**：JDK 25 JCE 提供 Ed25519、X25519、ChaCha20-Poly1305；HKDF 用本地 RFC 5869 实现。BouncyCastle 只用于 JCE 缺少的算法（如 Argon2id）或作为经过审查的备选；不手写原语。邀请/应答与两方 ratchet 所需的状态机在 `core/` 中以纯函数实现，并有 Quint 模型与测试向量。
- **网络（v1）**：relay-only。客户端通过 WebSocket/TCP 连接中继，中继转发密文；不做 NAT 打洞、不做直连。中继可见端点、时间、字节数，内容端到端加密。
- **A-first**：JVM-only。若 A-first 传输/加密 spike 的命名 gate 失败，切 B：iroh/p2panda 作为外部 sidecar，经窄 IPC 调用；产品代码仍不写 Rust。冷启动/内存、存储/重启、壳/UI 的失败各自走自己的 spike 决策，不自动触发 B。

### 副作用与 Future 边界（2026-11-20 增补）

- **纯核心不选 effect 类型**：`core/` 仍是 `Either[DomainError, A]` + 纯数据；无 `IO`、`Future`、`SecureRandom`、时钟或 IO。
- **副作用默认 cats-effect 3**：store、transport、执行器、身份/密钥生命周期、文件与网络副作用一律用 cats-effect 的 `IO`；生命周期用 `Resource`，并发原语用 cats-effect 的 `Ref`/`Queue`/`Semaphore`。
- **Future 只存在于 Pekko 适配层**：Pekko actor/stream/HTTP 只接受/返回 `Future` 的 API 放在 `server/`（以及直接包装 Pekko 的适配文件）；`Future` 不得向上泄漏到 `core/`、`store/`、`transport/` 或 UI 服务接口。
- **边界转换**：Pekko `Future` → `IO` 用 `IO.fromFuture`（显式 runtime）；`IO` → Pekko `Future` 只在 Pekko 边界用 `unsafeToFuture`。`Await`、`runBlocking`、在业务代码里直接 `unsafeRun*` 都禁止。
- **单一 effect 栈**：不混用 ZIO、Future 业务接口或其他 effect 系统；Laminar/Airstream 是 UI 的响应式流，不是副作用代数，异步边界仍按上面的 `IO` 规则。
- cats-effect 的精确版本在第一次引入 effectful 模块时从最新稳定版钉到 `build.mill`；当前纯 `core/` 不添加未使用的依赖。

## A → B 的触发条件

A-first **传输/加密** spike（5 人日）必须证明：

1. relay-only 传输，两个独立 client JVM；100 条消息 + 重连；1,000 条 <5s；
2. 安全门：邀请/应答签名、端到端加密、重放/篡改拒绝、密钥分离；
3. 无 Rust 依赖。

以下**不是** A/B 触发项，各有自己的 spike / 设计 gate：

- 冷启动 p50/p95 与空闲内存：含 Electron 壳的 shell spike；
- SQLCipher/重启/应用锁：storage/state spike；
- Pekko、ScalaJS/Laminar、Electron 集成：M3/M4；
- 最终两方棘轮/前向保密：最终方案设计 gate。spike 的对称 hash chain 只是 harness，不是最终协议。

只有 A-first 的三项 named gate 失败才写 ADR 记录证据并切 B。最终方案 gate 失败时，重新设计或按新 ADR 再评估 B；不得把「ratchet 做不出来」记成「JVM/relay 路线不可行」。1,000 条 <5s 与 ≤800MB 是硬门，不校准；只有冷启动按 ADR-0004 校准一次。不得以「Rust 库更成熟」为理由在没有 spike 证据时直接切 B，也不得在 A 明显失败后继续加人日硬扛。

## 备选与否决

- **Rust + Tauri**：用户明确不再用 Rust；旧复盘显示协议层是主要复杂度来源。
- **JavaFX WebView + jpackage**：全 JVM，但 webview 与原生通知/钥匙串集成弱，UI 生态不如 Laminar。
- **保留 Svelte UI**：可复用旧界面，但 UI 变成第二语言，且不是 CFA 栈。
- **Tauri shell + JVM sidecar**：shell 仍是 Rust，违反产品代码无 Rust 的边界。
- **直接 P2P（libp2p JVM / 自写打洞）**：v1 不承诺；打洞与 NAT 穿透是独立的深水区，先用中继把产品路径跑通。

## 后果

- v1 不是「完全 p2p」：没有中心账号服务器，但有一个中继。产品文案必须如实描述。
- JVM + Electron 的包体与空闲内存高于 Tauri；冷启动由含壳的 spike 校准一次，内存是硬门。
- SQLCipher/JDBC、两方 ratchet/FS、Electron↔JVM 生命周期、Laminar 长列表都是 spike；没有通过 spike 之前不写实现。
- B 是受控回退，不是第二条并行路径；同一时间只有一条网络实现。
