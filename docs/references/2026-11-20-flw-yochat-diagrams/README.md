# flw 的 YoChat 阅读材料：完整 notes 包、六张图与提示词

2026-11-20，群友 flw 在微信群「AI 体验营」用 DeepSeek V4.1 Flash 1017 对旧 YoChat 做了一次外部阅读：先提取「项目现状调查」会话里的用户发言，再按五条提示词整理术语表、生成六张 SVG 设计图和一份可离线打开的 `index.html`。

`notes/` 是 flw 分享的原始目录，原样保存；本文件是入口、来源与提示词证据。这些材料帮助理解**旧实现**；它们不是本仓新架构的设计，也不是产品规格。

## 完整阅读包

| 路径 | 内容 |
| --- | --- |
| `notes/README.md` | flw 的阅读说明、推荐顺序、图与旧项目文档的对应表 |
| `notes/glossary.md` | 按主题整理的术语表 |
| `notes/index.html` | 离线阅读页：设计图 + Markdown 弹窗 |
| `notes/build.py` | 生成 `index.html` 的脚本；`ROOT` 取脚本自身目录 |
| `notes/diagrams/` | 六张 SVG |

`notes/README.md` 里的 `notes/...` 路径按本目录的 `notes/` 解释。`build.py` 用自身目录定位，直接 `python3 notes/build.py` 即可重建阅读页；重建会更新页脚时间戳。

## 六张图

| 文件 | 标题 | 说明 |
| --- | --- | --- |
| `notes/diagrams/01-modules.svg` | YoChat 模块概念图 | 五个 Rust crate 与 Svelte UI 的职责和依赖方向。 |
| `notes/diagrams/02-architecture.svg` | YoChat 三层架构与崩溃安全执行顺序 | 纯状态机、执行器与适配器的分层，以及状态与效果同事务、提交后执行、再标记完成的顺序。 |
| `notes/diagrams/03-send-message.svg` | 发送一条消息的数据流 | 从界面撰写到核心状态推进、同事务落盘、加密发送、对方接收与确认的完整路径。 |
| `notes/diagrams/04-receive-order.svg` | 接收、严格按序与补齐路径 | 入站数据经过准入、解密、去重与定序；缺口进入等待，并通过请求重发、兜底重发、通道重建或可验证声明放行。 |
| `notes/diagrams/05-contact-exchange.svg` | 好友邀请与应答的控制流 | A 生成邀请，经外部渠道传给 B；B 生成加密应答回传；A 在签发设备确认并发出首条认证操作；双方再做安全码带外比对。 |
| `notes/diagrams/06-device-revocation.svg` | 设备组、吊销与多设备同步 | 用户私钥只存在于指定设备；设备列表由用户私钥签名并分发；吊销声明只影响设备公钥，不影响用户身份与安全码。 |

点开 SVG 查看原图；浏览器可直接缩放。图的 `<title>` 与 `<desc>` 与上表一致。推荐阅读顺序与每张图对应的旧项目文档见 `notes/README.md`。

## 来源

- 群：AI 体验营（`57253483893@chatroom`）
- 作者：flw
- 日期：2026-11-20（北京时间 13:46–14:11）
- 原始分享：`notes.zip`（14:04:20）；提示词截图（14:11:26，消息 `wm_018111c3e485568d33dece868ef9b02546e1d270f7519cf7d277ff892da6b618`）
- 用户下载的笔记：`/Users/weiwen/Downloads/notes`（本目录的 `notes/` 即其完整副本）
- 旧项目：`~/projects/yochat-reference`；需求快照：`docs/spec/`
- 证据：
  - `prompt-screenshot.png` — 从微信媒体 `.dat` 解出的原始截图（3304×1280）
  - `prompt-ocr.txt` — 截图的原始 OCR
  - `prompts.md` — 逐字提示词与复用模板

## 口径与限制

- 图描述旧实现（Rust + Tauri + Svelte + SQLite/SQLCipher + iroh/p2panda），**不是**本仓的 Scala 3 + Pekko + ScalaJS/Laminar + Electron 架构。
- 图中标「后续阶段」的文件发送与群聊不在旧项目首个交付增量内。
- 图刻意省略数值（超时、配额、算法参数）；以旧项目文档和代码为准。
- 图由外部模型生成，未经旧项目作者校对；它们没有逐条对照需求编号，也没有测试，不能当验收证据。
- 本仓的规范仍是 `docs/spec/`、`docs/adr/` 与 `docs/plans/`；这组图只作阅读辅助。

## 提示词

见 [prompts.md](prompts.md)。逐字提示词、复用模板和方法边界都在那里。
