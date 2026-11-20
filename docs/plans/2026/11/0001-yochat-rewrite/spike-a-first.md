# Plan 0001 — A-first spike 记录

- 状态: completed (2026-11-20)
- 时间盒: 5 人日
- ADR: `docs/adr/0005-a-first-crypto-and-transport.md`
- 代码: `spike/`
- 结论: A 通过密码/传输可行性门；B 不触发。最终方案仍需设计评审。

## 目的

回答 ADR-0002 的 A/B 问题：JVM-only 能否在 S1 的两台设备路径上完成邀请/应答、端到端加密、单聊、重连与 1000 条吞吐，且不引入 Rust。

不回答：SQLCipher、进程重启、应用锁、Pekko、ScalaJS/Laminar、Electron、冷启动、内存、真实网络/NAT、群/多设备。

## 实现

- `spike/src/main/scala/yochat/spike/Crypto.scala`：JDK 25 JCE 原语 + RFC 5869 HKDF。
- `Wire.scala`：邀请、应答、消息、设备帧的有界二进制格式。
- `Protocol.scala`：邀请/应答、会话根、两方 hash chain、乱序窗口、重放拒绝。
- `Relay.scala`：长度前缀 TCP 中继 + 客户端；注册确认避免启动竞态。
- `SpikeMain.scala`：`crypto`、`relay`、`peer`、`demo` 命令；demo 起中继、跑两个独立 client JVM。
- `build.mill`：Scala 3.10.1，无第三方依赖；`-Werror`。

## 验证

环境：macOS arm64（m1），JDK 25.0.3，Mill 1.3.1，Scala 3.10.1，2026-11-20。

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

`crypto` 断言：邀请/应答往返、会话根一致、安全码对称、双向消息、密文篡改、邀请签名篡改、应答密文篡改、重放、乱序补齐、错误密钥、过期邀请。

## 判断

- 1000 条 107 ms < 5 s；100 条 + 重连 51 ms + 29 ms。**可行性门通过**。
- 这些数字是回环、harness 中继、无 UI、无持久化的结果；产品性能验收未完成。
- 当前两方 hash chain 是对称的，没有 DH ratchet；不能把它当最终协议。
- 进程重启、SQLCipher、应用锁、Pekko、Electron、冷启动/内存均未测。

## 未关闭项

| 项 | 归属 |
| --- | --- |
| 正式两方/群棘轮方案与安全评审 | 设计 + ADR |
| 进程重启不丢、SQLCipher/应用层 AEAD、应用锁 | storage/state spike |
| Electron ↔ JVM、冷启动 p50/p95、空闲内存 | shell spike |
| Pekko HTTP 本地服务与 Electron IPC | M3/M4 |
| 跨作者最终全序 `REQ_20261103_GBNVF8ZY42F1DZX4` | 设计裁决：实现或写已知约束 |
| 中继认证、配额、滥用限制 | 生产设计 |

## 下一步

1. 设计评审返回后处理 findings；把 `GBNV` 的裁决写进 ADR-0003。
2. storage/state spike：SQLCipher 或应用层 AEAD；重启不丢；应用锁边界。
3. shell spike：Electron/JVM 生命周期、冷启动、空闲内存。
4. 正式协议设计：两方棘轮 + 乱序/重建/重放的安全论证，冻结 Quint 模型。
