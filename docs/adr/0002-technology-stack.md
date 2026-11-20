# 技术栈：Scala 3 + Pekko + ScalaJS/Laminar + Electron，A-first 无 Rust

**状态**: accepted (2026-11-20)

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
- **存储**：SQLite；JDBC 驱动与加密方式由 spike 决定（SQLCipher native 或应用层 AEAD），不假设。
- **密码学**：BouncyCastle 提供 Ed25519、X25519、ChaCha20-Poly1305、HKDF、Argon2id；不手写原语。邀请/应答与设备组所需的棘手状态机在 `core/` 中以纯函数实现，并有 Quint 模型与测试向量。
- **网络（v1）**：relay-only。客户端通过 WebSocket/TCP 连接中继，中继转发密文；不做 NAT 打洞、不做直连。中继可见端点、时间、字节数，内容端到端加密。
- **A-first**：JVM-only。若在时间盒内无法通过 S1 验收、安全门与性能门，切 B：iroh/p2panda 作为外部 sidecar，经窄 IPC 调用；产品代码仍不写 Rust。

## A → B 的触发条件

A 必须在计划 0001 的 spike 中证明：

1. S1 端到端验收（两台设备、100 条消息、重启、断网重连）；
2. 安全门（密钥分离、E2E、签名验证、重放拒绝、篡改拒绝）；
3. 性能门（1,000 条 <5s、冷启动 p50/p95、空闲内存）。

任一项在时间盒内无证据，写 ADR 记录证据并切 B。不得以「Rust 库更成熟」为理由在没有 spike 证据时直接切 B，也不得在 A 明显失败后继续加人日硬扛。

## 备选与否决

- **Rust + Tauri**：用户明确不再用 Rust；旧复盘显示协议层是主要复杂度来源。
- **JavaFX WebView + jpackage**：全 JVM，但 webview 与原生通知/钥匙串集成弱，UI 生态不如 Laminar。
- **保留 Svelte UI**：可复用旧界面，但 UI 变成第二语言，且不是 CFA 栈。
- **Tauri shell + JVM sidecar**：shell 仍是 Rust，违反产品代码无 Rust 的边界。
- **直接 P2P（libp2p JVM / 自写打洞）**：v1 不承诺；打洞与 NAT 穿透是独立的深水区，先用中继把产品路径跑通。

## 后果

- v1 不是「完全 p2p」：没有中心账号服务器，但有一个中继。产品文案必须如实描述。
- JVM + Electron 的包体与空闲内存高于 Tauri；性能门按这个形态校准。
- SQLCipher/JDBC、JVM 群棘轮、Electron↔JVM 生命周期、Laminar 长列表都是 spike；没有通过 spike 之前不写实现。
- B 是受控回退，不是第二条并行路径；同一时间只有一条网络实现。
