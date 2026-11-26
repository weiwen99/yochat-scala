# 直接连接传输的可行性（2026-11-26）

- 对象：`main`（`8931c35`）的传输实现、ADR-0002 / ADR-0003 的 relay-only 约束，以及 JVM 生态可用的直接连接组件。
- 用途：为「是否以及如何把直接连接纳入后续版本」留一份可复核的证据与条件式选项。本文不改协议、不改门禁，也不把任何路线升格为决定。
- 关联：ADR-0002（技术栈与 A-first）、ADR-0003（v1 范围）、`DESIGN-DISCIPLINE.md` Y2（一条协议路径）、`GLOSSARY.md` 的「中转」「直接连接」（即通常说的 P2P）、[2026-11-22 微信反馈对照](2026-11-22-wechat-yochat-feedback.md)。

## 规模

本文不是设计，不适用 D1 的发布规模表。按 D1 的最小口径记：代码 0 行，新表 0，新组件 0，新配置 0；新增文档 1 份，另改索引 1 处。第 5 节的人日是估算。

## 1. 问题与判定标准

Q1 只使用 JVM 上可运行的代码（JDK 加纯 Java/Scala 库，不加载本地库、不启非 JVM 进程），能否让两台设备建立不经过中转的数据连接，并交换现有帧？

- 成立：存在可复用的纯 JVM ICE 客户端与可靠 UDP 传输，能接到现有帧格式，并有集成证据。
- 不成立：关键环节只能自写协议，或只能使用非 JVM 本地库。
- 不足：组件存在但没有集成证据，或维护与安全状态无法判断。

Q2 纯 JVM 路线在真实网络里能覆盖多少直接连接场景，失败时能否回落到中转？

- 成立：在明确的网络矩阵上有成功率与回落时延的实测。
- 不成立：对称 NAT / CGNAT 或 UDP 被封时无法连接，或回落会丢消息、破坏顺序。
- 不足：只有库文档，没有目标网络环境下的实测。

Q3 把直接连接接进 YoChat，需要新增哪些服务、协议状态与运维面？

- 成立：能列出组件、服务、状态机与失效路径，并给出可验证的 spike 门。
- 不成立：只能给出「换一个传输库」的说法。
- 不足：关键接口（ICE 选中的 socket 交给 QUIC、路径切换后的重复与乱序）没有验证。

## 2. 与当前实现的对照

### 2.1 传输

- 中转是 TCP：`new ServerSocket()` 后绑定端口（`transport/src/yochat/transport/RelayServer.scala:55-57`）。ADR-0002:25 写「通过 WebSocket/TCP 连接中继」，实现只有裸 TCP 与自定义帧，没有 WebSocket。
- 只有已注册的目标连接才会收到转发；目标不在线时帧被静默丢弃，没有离线队列（`RelayServer.scala:113`）。
- 帧类型有五种：Wire / Reply / RekeyRequest / RekeyAccept / DeviceSync（`transport/src/yochat/transport/RelayWire.scala:10-16`，kind 常量 `:20-24`；DeviceSync 由 `5fcc796` 引入）。载荷上限 1,048,774 字节，另有 5 字节帧头（`RelayWire.scala:25`）。
- 客户端接收是阻塞 `take` 的有界队列（`transport/src/yochat/transport/RelayClient.scala:19,37`；容量来自 `Queue.bounded`，`:86-88`）。

### 2.2 内容加密与送达

- 端到端加密、邀请/应答、两方棘轮、序号、ACK/Gap 与有界窗口都在应用层（ADR-0006、ADR-0008）。直接连接只替换数据路径，不改变内容加密。
- 送达语义也不变：中转只表示「已交给中继」，对端确认另由 ACK 表示（`DESIGN.md:182`、`PRODUCT.md:49`、ADR-0008）。GLOSSARY 用「中转」，界面文案用「中继」，指同一设施，用词未统一。

### 2.3 协议约束

- `AGENTS.md` §4、`DESIGN-DISCIPLINE.md` Y2、ADR-0002:25 与 ADR-0003:30 都把路径钉在 relay-only；ADR-0002:71 原文：「若后续要改，另写 ADR，不在实现里静默改变拓扑。」
- 所以直接连接不能作为实现细节顺手加进来；它需要新的 ADR，本文只能作为那份 ADR 的输入。

## 3. 组件证据

版本列写发布版本，无法取得版本的写「观测日期」，两者不混用。

| 组件 | 形态 | 版本或观测日期 | 能做什么 | 已知限制 | 来源 |
| --- | --- | --- | --- | --- | --- |
| JDK 25 | JDK 自带 | 项目钉在 25 | TCP、UDP、TLS 1.3、Ed25519/X25519/ChaCha20-Poly1305；JEP 510 已把 KDF（含 HKDF）转正 | 没有 ICE/STUN/TURN；`HttpClient` 只到 HTTP/1.1 与 HTTP/2 | JDK 25 API 文档；JEP 510 |
| JDK 26 | JDK 自带 | JEP 517 已交付（Release 26）；项目未升级 | HTTP/3 客户端 | HTTP/3 是 HTTP 语义，不是任意协议的 QUIC 传输 | JEP 517 |
| ice4j | 纯 Java | `org.jitsi:ice4j`；Maven lastUpdated 2026-11-17 | ICE/STUN/TURN 客户端；`Component` 暴露 `MultiplexingDatagramSocket` | 仓库 topics 标 `rfc5245`，README 未声明任何 RFC 编号，RFC 8445 的符合性没有声明可依；不含 TURN 服务端 | <https://github.com/jitsi/ice4j>；Maven 元数据 |
| kwik | 100% Java | `tech.kwik:kwik` 0.12.3；Maven lastUpdated 2026-11-15 | QUIC v1/v2 客户端与服务端；客户端可注入 `DatagramSocketFactory`，服务端可 `withSocket` | 作者自述：服务端不支持连接迁移、TLS 自研且未做安全评审、性能未优化；单一维护者；许可证见第 7 节 | <https://github.com/ptrd/kwik>；Maven 元数据 |
| coturn | C | 观测 2026-11-25 仍在推送 | TURN 服务端 | 非 JVM，需要单独部署与鉴权 | <https://github.com/coturn/coturn> |
| jitsi/turnserver | Java | 已归档，最后推送 2015-11-06 | 历史上的 Java TURN 服务端 | 已归档，无维护者 | 仓库元数据 |
| jvm-libp2p | JVM（Kotlin） | 1.4.0（2026-10-22） | libp2p 组件 | 组件表：QUIC、relay、autonat 为 beta；hole-punching 没有实现状态 | <https://github.com/libp2p/jvm-libp2p> |
| webrtc-java | JVM API + native（JNI） | 1.0.1（2026-11-14） | WebRTC 数据通道，ICE/DTLS/SCTP 一起解决；Apache-2.0 | 预编译平台只有 Windows、macOS、Linux；没有 Android/iOS | <https://github.com/devopvoid/webrtc-java> |
| Electron | 浏览器运行时 | 45.4.1（`shell/package.json`） | 自带 Chromium 的 WebRTC 数据通道 | 传输不在 JVM；未在 YoChat 帧与两台设备上跑过 | `shell/package.json` |
| Netty 4.2 QUIC | JVM API + BoringSSL | 4.2.21（2026-11-16） | QUIC 编解码与流 | 依赖本地 BoringSSL，不是纯 JVM | Netty 4.2 API |
| libwebrtc 手机 SDK | native | 上游镜像观测 2026-11-26 有推送 | `sdk/android`（Java API + `.so`）、`sdk/objc`（framework） | iOS 没有 JVM | <https://github.com/webrtc-sdk/webrtc> |
| io.github.webrtc-sdk:android | 预编译 AAR | 150.7871.09（2026-11-26） | Android 用的 libwebrtc 预编译包 | 与上游版本的对应关系未核实；未在真机跑通 | Maven 元数据 |
| libsignal | Rust 核心 + Java/Swift/TS 包装 | 观测 2026-11-26 仓库有推送 | Signal 协议（含双棘轮） | Java 侧是 JNI；AGPL-3.0；官方声明 Signal 之外使用不受支持 | <https://github.com/signalapp/libsignal> |

## 4. 判定

### Q1：纯 JVM 能做到，但靠拼装

- `ice4j + kwik + coturn + 自写胶水` 在接口上成立：ICE 负责候选与打洞，kwik 负责可靠加密传输，TURN 负责对称 NAT 兜底，中转继续做信令与回落。
- 没有「引入一个库就完成」的纯 JVM 方案。kwik 的 TLS 由作者自写且未做安全评审；选 C 就要接受这一项由自研 TLS 承担。
- 判定：**不足**。能力成立，成熟度不够；需要 spike 才能升级。

### Q2：覆盖率没有实测，回落路径可以复用现有中转

- ICE 的候选交换本来就需要信令通道；现有中转已经能按设备公钥转发任意帧，可以顺带承担候选交换，不必新建账号服务。
- 对称 NAT / CGNAT 与 UDP 封锁的现实比例没有本项目的实测数据。对称 NAT 的分类按 RFC 4787，不按实现的自述。
- 回落本身不新：直接连接失败就走现有中转，送达语义与重试已经是现成的（ADR-0008）。路径切换的重复与乱序有一部分已被应用层吸收：序号单调、重放拒绝、乱序槽（`core/src/yochat/core/S1Limits.scala:6-7`，单次跳 64、累计 256）与 Gap 请求都与传输路径无关。没有验证的是同一帧经两条路径到达时的在途窗口与重发预算。
- 判定：**不足**，缺目标网络矩阵的实测。

### Q3：新增的是服务与状态，不是换库

- 服务：信令（可复用中转）、STUN、TURN；TURN 需要鉴权、配额与带宽预算。
- 协议状态：路径选择（direct / relayed）、切换时的重复窗口、回落原因的可观测性。
- 失效路径：TURN 不可达、信令丢失、路径切换后回落到中转时的重复投递。
- spike 门（取自调研包的 H1/H3）：kwik 在 ICE 选中的 socket 上完成握手并收发 100 条 Wire 帧；换 pair 后能继续，或能按策略重连；1,000 / 10,000 条消息的 p50/p95 与 30 分钟空闲 keepalive 没有不可定位的握手或流错误。任一项失败，路线 C 不成立。
- 判定：**不足**，失效路径与门已列出，但没有一项被执行。

## 5. 路线与量级（估算）

| 路线 | 组成 | 纯 JVM | 直接连接能力 | 人日（估算） | 主要风险 |
| --- | --- | --- | --- | --- | --- |
| A 维持 relay-only | 现有传输 | 是 | 无 | 0 | 依赖中转；产品上没有「节点」叙事 |
| B 条件直连 | 现有帧 + IPv6 / LAN / 端口映射，失败回中转 | 是 | 部分 | 5–10 | 对称 NAT 无效，覆盖率低 |
| C ICE + QUIC | ice4j + kwik + coturn + 中转信令 | 是（TURN 除外） | 完整打洞 | 20–40 | kwik 成熟度与许可、socket 交接、路径切换 |
| D WebRTC 桌面 | webrtc-java + 中转信令 + coturn | 否（JNI） | 完整 | 10–20 | native 打包；桌面 only |
| D′ 全平台 WebRTC | 桌面 webrtc-java + Android `org.webrtc` + iOS framework | 否 | 完整 | 25–50 | 三份绑定；iOS 无 JVM；后台与推送另算 |
| E iroh sidecar | Rust 二进制 + 窄 IPC | 否 | 完整 | 5–15 | 即 ADR-0002 的 B：只在 A-first 三项命名 gate 失败并另写 ADR 后才成立；是受控回退，同一时间只有一条网络实现；产品代码仍不写 Rust |
| F Electron WebRTC | Chromium DataChannel + IPC | 否（浏览器） | 完整 | 5–15 | 传输离开 JVM；桌面与浏览器 only |
| G jvm-libp2p | Kotlin libp2p | 是 | 不完整 | 不建议 | hole-punching 未实现；QUIC 与 relay 为 beta |

## 6. 手机端

- D 路线不能直接用于手机：`webrtc-java` 的预编译平台只有 Windows、macOS、Linux（README 的平台表），没有 Android 与 iOS。
- D 的架构在手机上成立（第 5 节的 D′），但绑定不同：Android 用上游 `sdk/android` 或预编译 AAR，iOS 用 `sdk/objc` framework。协议层可能可以共用，代码不能共用；跨端共用同一套帧与协议没有验证。
- 「纯 JVM」在移动端不是可满足的约束：Android 运行时是 ART，iOS 没有 JVM。若产品必须覆盖 iOS，传输方案要先放弃 JVM。
- 现有代码在移动端的可用面：`core/` 是纯 Scala，可编进 Android；`store/` 依赖 doobie（含 doobie-hikari 带入的 HikariCP）与 `sqlite-jdbc`（`build.mill:46-53`），据此推断不能在 Android 运行，未在 ART 上验证；`server/` 是绑定回环的本地服务、Electron 壳是桌面进程模型，两者都没有为手机常驻设计（推断，未评估）。移动端是第二个壳，不是配置开关。
- 手机端的硬约束在后台与网络：蜂窝网多为对称 NAT，直接连接率低于桌面、TURN 占比更高，具体比例需实测；Android 的 Doze 与厂商省电策略会断开后台 socket；中国大陆没有 FCM，需要厂商推送或自建长连；iOS 挂起后连接失效。这几条机制性判断未取一手来源，列为待核实。离线消息仍要落盘与推送，中转不能省。

## 7. 未核实与待办

- 本文没有运行任何 spike。`ice4j` 选中的 socket 交给 `kwik` 后能否跨路径切换继续工作，未知。
- 没有目标网络矩阵的实测：同 LAN、双 IPv6、普通家用 NAT、对称 NAT / CGNAT、仅 TCP 出网，各 20 次建连的成功率与时延。
- `webrtc-java`、Electron 数据通道与 Android AAR 都没有在 YoChat 的帧与两台设备上跑过；AAR 与上游 libwebrtc 的版本对应关系未核实。
- 手机端后台保活与推送通道的到达率、耗电、成本没有数据；Doze、FCM 可达性、iOS 挂起失效这几条机制性判断未取一手来源。
- 许可：kwik 的 README 称 LGPL，仓库同时含 GPL-3.0 全文 `LICENSE.txt`，GitHub 元数据判为 GPL-3.0，冲突未解；libsignal 为 AGPL-3.0；Android AAR 未逐项核对；没有法律意见。
- Maven 的 `lastUpdated` 与仓库推送时间只表示当时有活动，不等同于发布质量。
- 移动端的 `core/` 复用需要验证：`core/` 无 JVM-only 依赖，但没有在 Android 上编译过。
- 跨端共用同一套帧与协议（桌面与手机共用一个对端）没有验证。
- 人日为估算，不含完整网络矩阵、安全评审与推送接入。

## 8. 需要人拍板的输入

目标平台（桌面 only / Android / Android+iOS）、「纯 JVM」的硬度（是否排除 JNI 与 sidecar）、TURN 运维预算、隐私优先级（直接连接会把本机公网地址暴露给对端，见 GLOSSARY「直接连接」）、移动端推送通道、是否仍要求离线不丢、可接受的时间与质量门。这些输入不同，第 5 节的路线会收敛到不同答案；在此之前只能给条件式建议。

## 9. 条件式建议

1. 只做桌面、想尽快减少对自有中转的依赖：先用 Electron/Chromium 的 WebRTC 数据通道（F），中转只做信令与回落。代价是传输离开 JVM，并把本机地址暴露给对端。
2. 传输必须留在 JVM 服务内、允许 JNI：选 webrtc-java（D），它把 ICE/DTLS/SCTP 一起解决；代价是 native 打包与桌面 only。
3. 零 native 是硬约束：选 C，但先确认 kwik 的 GPL-3.0 / LGPL 冲突对发布方式是否可接受，再做 spike，把 TLS 与性能当风险项；TURN 用 coturn。不要选 G。
4. 必须覆盖手机（含 iOS）：收敛到 D′，或走 ADR-0002 的 B（iroh/p2panda sidecar，产品代码仍不写 Rust）——B 只在 A-first 三项命名 gate 失败并另写 ADR 后才成立，同一时间只有一条网络实现。纯 JVM 路线在 iOS 上不存在，同时要单独设计后台保活与推送。
5. 无论哪条，中转都要保留为回落与离线重试的通道：信令在 ICE 之外，TURN 本身就是中转设施，离线重试与推送也仍要服务器。这与 2026-11-22 记录的产品表述一致：没有账号服务器，但有一个中继（ADR-0002:71）。

## 10. 来源

- 仓库：`transport/src/yochat/transport/RelayServer.scala`、`RelayWire.scala`、`RelayClient.scala`、`core/src/yochat/core/S1Limits.scala`、`build.mill`、`shell/package.json`、`AGENTS.md`、`DESIGN-DISCIPLINE.md`、`GLOSSARY.md`、`DESIGN.md`、`PRODUCT.md`、ADR-0002、ADR-0003、ADR-0006、ADR-0008。
- 库与规范：<https://github.com/jitsi/ice4j>、<https://github.com/ptrd/kwik>、<https://github.com/libp2p/jvm-libp2p>、<https://github.com/devopvoid/webrtc-java>、<https://github.com/webrtc-sdk/webrtc>、<https://github.com/signalapp/libsignal>、Maven Central 元数据、JDK 25 `HttpClient` 文档、JEP 510、JEP 517、RFC 8445、RFC 8656、RFC 9000、RFC 4787。
- 版本与日期均在 2026-11-26 读取；Maven 元数据的 `lastUpdated` 与仓库推送时间不等同于发布质量。
