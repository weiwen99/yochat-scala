# flw 的 YoChat 阅读材料：六张图与提示词

2026-11-20，群友 flw 在微信群「AI 体验营」用 DeepSeek V4.1 Flash 1017 对旧 YoChat 做了一次外部阅读：先提取「项目现状调查」会话里的用户发言，再按五条提示词整理术语表、生成六张 SVG 设计图和一份可离线打开的 `index.html`。

这些材料帮助理解**旧实现**；它们不是本仓新架构的设计，也不是产品规格。

## 来源

- 群：AI 体验营（`57253483893@chatroom`）
- 作者：flw
- 日期：2026-11-20（北京时间 13:46–14:11）
- 原始分享：`notes.zip`（14:04:20）；提示词截图（14:11:26，消息 `wm_018111c3e485568d33dece868ef9b02546e1d270f7519cf7d277ff892da6b618`）
- 用户下载的笔记：`/Users/weiwen/Downloads/notes`
- 旧项目：`~/projects/yochat-reference`；需求快照：`docs/spec/`
- 证据：
  - `prompt-screenshot.png` — 从微信媒体 `.dat` 解出的原始截图（3304×1280）
  - `prompt-ocr.txt` — 截图的原始 OCR
  - `prompts.md` — 逐字提示词与复用模板

## 六张图

| 文件 | 标题 | 说明 |
| --- | --- | --- |
| `diagrams/01-modules.svg` | YoChat 模块概念图 | 五个 Rust crate 与 Svelte UI 的职责和依赖方向。 |
| `diagrams/02-architecture.svg` | YoChat 三层架构与崩溃安全执行顺序 | 纯状态机、执行器与适配器的分层，以及状态与效果同事务、提交后执行、再标记完成的顺序。 |
| `diagrams/03-send-message.svg` | 发送一条消息的数据流 | 从界面撰写到核心状态推进、同事务落盘、加密发送、对方接收与确认的完整路径。 |
| `diagrams/04-receive-order.svg` | 接收、严格按序与补齐路径 | 入站数据经过准入、解密、去重与定序；缺口进入等待，并通过请求重发、兜底重发、通道重建或可验证声明放行。 |
| `diagrams/05-contact-exchange.svg` | 好友邀请与应答的控制流 | A 生成邀请，经外部渠道传给 B；B 生成加密应答回传；A 在签发设备确认并发出首条认证操作；双方再做安全码带外比对。 |
| `diagrams/06-device-revocation.svg` | 设备组、吊销与多设备同步 | 用户私钥只存在于指定设备；设备列表由用户私钥签名并分发；吊销声明只影响设备公钥，不影响用户身份与安全码。 |

点开 SVG 查看原图；浏览器可直接缩放。图的 `<title>` 与 `<desc>` 与上表一致。

## 阅读顺序

1. **01 模块概念图**：先知道代码由哪几块组成、依赖往哪个方向走。
2. **02 架构框图**：理解「纯状态机 + 执行器 + 适配器」和崩溃安全顺序。
3. **03 发送流程**：看一条消息从界面到对方的完整路径。
4. **04 接收与排序**：理解为什么「缺失检测、等待、重发、通道重建」是一套东西。
5. **05 好友交换**：理解没有中心服务时，关系如何靠邀请/应答、渠道绑定与安全码建立。
6. **06 设备与吊销**：理解多设备、设备列表、吊销和历史同步的边界。

## 图的依据

| 图 | 旧项目文档 |
| --- | --- |
| 01 模块 | `README.md`、`Cargo.toml`、`docs/engineering/architecture.md`、`docs/engineering/decisions/technology-stack.md` |
| 02 架构 | `docs/engineering/architecture.md`、`docs/engineering/state-runtime.md`、`docs/engineering/decisions/pure-state-machines.md` |
| 03 发送 | `docs/product/messaging.md`、`docs/engineering/messaging.md`、`docs/engineering/local-store.md` |
| 04 接收/排序 | `docs/product/messaging.md`、`docs/engineering/messaging.md`、`models/` 中的排序与重发模型 |
| 05 好友交换 | `docs/product/contacts.md`、`docs/engineering/contact-exchange.md` |
| 06 设备/吊销 | `docs/product/users-and-devices.md`、`docs/engineering/keys-and-devices.md` |

这些路径在旧项目及 `docs/spec/` 快照中。

## 口径与限制

- 图描述旧实现（Rust + Tauri + Svelte + SQLite/SQLCipher + iroh/p2panda），**不是**本仓的 Scala 3 + Pekko + ScalaJS/Laminar + Electron 架构。
- 图中标「后续阶段」的文件发送与群聊不在旧项目首个交付增量内。
- 图刻意省略数值（超时、配额、算法参数）；以旧项目文档和代码为准。
- 图由外部模型生成，未经旧项目作者校对；它们没有逐条对照需求编号，也没有测试，不能当验收证据。
- 本仓的规范仍是 `docs/spec/`、`docs/adr/` 与 `docs/plans/`；这组图只作阅读辅助。

## 提示词

见 [prompts.md](prompts.md)。逐字提示词、复用模板和方法边界都在那里。
