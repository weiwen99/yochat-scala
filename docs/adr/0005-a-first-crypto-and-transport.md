# A-first 加密与传输：JVM JCE + 中继，5 人日 spike 通过

**状态**: accepted (2026-11-20)

## 背景

ADR-0002 规定 A-first：产品代码不写 Rust，先用 JVM 证明 S1 的加密与传输；只有 A 在时间盒内无法通过 S1 验收、安全门与性能门时才切 B（iroh/p2panda sidecar）。用户接受 relay-only v1，并给出 5 人日时间盒。

旧实现的加密与传输压在 iroh 1.4 + p2panda-encryption 0.8 上。JVM 侧没有同等成熟、同等验证过的群棘轮与 NAT 穿透库。spike 的目的不是把旧协议原样搬过来，而是回答一个更窄的问题：**JVM 自带的密码原语 + 一个最小中继，能不能撑起 S1 的两台设备路径？**

## 决策

A 通过可行性门。S1 继续走 JVM-only：

- 密码原语用 JDK 25 JCE：Ed25519、X25519、ChaCha20-Poly1305，HKDF-SHA256 用本地 RFC 5869 实现。
- 邀请/应答：邀请由用户 Ed25519 私钥签名；应答由应答方签名后，用邀请的一次性 X25519 公钥派生的密钥加密。
- 会话：两方各一条对称 hash chain（`messageKey = HKDF(chain, "message")`，`chain = HKDF(chain, "chain")`）；接收方保留 64 个乱序槽，拒绝重放。
- 传输：长度前缀 TCP 中继；设备公钥注册，中继按目标设备公钥转发密文帧。中继不解析消息密钥或正文。
- 验证拓扑：中继在 harness JVM，Alice/Bob 各一个独立 client JVM；不是同一线程模拟。

spike 代码在 `spike/`，是可行性证据，不是生产实现。生产实现要回到 `core/` 的纯状态机 + 执行器，并重新做协议设计与评审。

## 证据

环境：macOS arm64（m1），JDK 25.0.3，Mill 1.3.1，Scala 3.10.1，2026-11-20。命令与输出：

```text
$ mill spike.run crypto
CRYPTO_CHECKS PASS

$ mill spike.run demo --messages 100 --reconnect
SPIKE messages=100 total=101 reconnect=true aliceSent=101 aliceAcked=101 elapsedMs=51 reconnectMs=29 bobReceived=101 ok=true

$ mill spike.run demo --messages 1000
SPIKE messages=1000 total=1000 reconnect=false aliceSent=1000 aliceAcked=1000 elapsedMs=107 reconnectMs=0 bobReceived=1000 ok=true

$ mill spike.run demo --messages 1000 --reconnect
SPIKE messages=1000 total=1001 reconnect=true aliceSent=1001 aliceAcked=1001 elapsedMs=89 reconnectMs=29 bobReceived=1001 ok=true
```

`crypto` 覆盖：邀请/应答往返、会话根一致、安全码对称、双向消息、密文篡改拒绝、邀请签名篡改拒绝、应答密文篡改拒绝、重放拒绝、乱序补齐、错误密钥拒绝、过期邀请拒绝。

1000 条端到端 107 ms，低于 ADR-0004 的 5 s 门一个数量级以上。但这是本机回环、harness 中继、无 UI、无持久化的数字；不能当产品性能验收。

## 没有证明的事

- **没有 DH ratchet / 双棘轮**：当前是对称 hash chain，前向保密有限，也没有群成员语义。S1 只有两台设备，但方案不能假装已经满足 S2 的群/多设备要求。
- **没有进程重启与持久化**：消息链在内存里；重启不丢、SQLCipher、应用锁、状态运行时都是 storage/state spike。
- **没有 Pekko、ScalaJS/Laminar、Electron**：spike 故意只用 JDK，以隔离 A/B 的密码/传输问题；这些是壳/服务 spike。
- **没有冷启动、空闲内存、真实网络、打洞、NAT**：relay-only 的已知暴露面与依赖仍在。
- **中继没有认证、配额、滥用限制**：它只是路由；生产需要单独的准入与资源上限设计。
- **没有跨作者最终全序**：S1 只承诺按作者顺序；`REQ_20261103_GBNVF8ZY42F1DZX4` 仍是待裁决项，不能静默忽略。
- **没有密码学外部评审**：JCE 原语是成熟的，但拼装方式（邀请/应答/链）必须走设计评审；本 ADR 只接受「A 可行」，不接受「方案已定稿」。

## 后果

- B 不触发；不引入 iroh/p2panda sidecar。若最终方案评审无法在 S1 预算内满足安全门，再回到 ADR-0002 的 B 或重新设计。
- 下一步：storage/state spike（SQLCipher 或应用层 AEAD、重启不丢、应用锁），shell spike（Electron/JVM/冷启动/内存），以及正式的两方棘轮设计 + 评审。
- `spike/` 保留为可复现的可行性证据；生产代码不复用它的可变链实现，只复用已验证的原语选择与测试口径。
