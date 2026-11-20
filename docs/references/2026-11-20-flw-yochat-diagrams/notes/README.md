# YoChat 阅读材料

这是对 `YoChat-20261120` 项目的外部阅读整理，**不属于项目源码，也不修改项目**。目的是先建立整体理解，再进入具体需求与实现。

## 文件

| 文件 | 内容 |
|---|---|
| [`glossary.md`](glossary.md) | 按主题整理的术语表，含“最容易混淆的几组词” |
| [`index.html`](index.html) | 生成的阅读页：设计图 + Markdown 弹窗（推荐从这里开始） |
| [`build.py`](build.py) | 构建脚本：扫描 Markdown 与 SVG，生成 `index.html` |
| `diagrams/01-modules.svg` | 模块概念图：5 个 Rust crate + Svelte UI 的职责与依赖方向 |
| `diagrams/02-architecture.svg` | 三层架构与崩溃安全执行顺序 |
| `diagrams/03-send-message.svg` | 发送一条消息的数据流（本机 → 对方 → ACK 回本机） |
| `diagrams/04-receive-order.svg` | 接收、严格按序、等待与补齐路径 |
| `diagrams/05-contact-exchange.svg` | 好友邀请与应答的控制流 |
| `diagrams/06-device-revocation.svg` | 设备组、吊销与多设备同步 |

## 推荐阅读顺序

1. **01 模块概念图**：先知道代码由哪几块组成、依赖往哪个方向走。
2. **02 架构框图**：理解“纯状态机 + 执行器 + 适配器”和崩溃安全顺序。
3. **03 发送流程**：看一条消息从界面到对方的完整路径。
4. **04 接收与排序**：理解为什么“缺失检测、等待、重发、通道重建”是一套东西。
5. **05 好友交换**：理解没有中心服务时，关系如何靠邀请/应答 + 渠道绑定 + 安全码建立。
6. **06 设备与吊销**：理解多设备、设备列表、吊销和历史同步的边界。

术语表可以配合图随时查；最容易混的几组词放在最后一节。

## 图与项目文档的对应

| 图 | 主要依据 |
|---|---|
| 01 模块 | `README.md`、`Cargo.toml`、`docs/engineering/architecture.md`、`docs/engineering/decisions/technology-stack.md` |
| 02 架构 | `docs/engineering/architecture.md`、`docs/engineering/state-runtime.md`、`docs/engineering/decisions/pure-state-machines.md` |
| 03 发送 | `docs/product/messaging.md`、`docs/engineering/messaging.md`、`docs/engineering/local-store.md` |
| 04 接收/排序 | `docs/product/messaging.md`、`docs/engineering/messaging.md`、`models/` 中的排序与重发模型 |
| 05 好友交换 | `docs/product/contacts.md`、`docs/engineering/contact-exchange.md` |
| 06 设备/吊销 | `docs/product/users-and-devices.md`、`docs/engineering/keys-and-devices.md` |

## 阅读时注意

- 项目文档当前几乎全部是 `status: draft`；需求是 `proposed`，不是最终冻结版。
- 图中标“后续阶段”的文件发送与群聊**不在首个交付增量**内。
- 首个交付增量在平台上先验收 macOS；Windows/Linux 的打包与通知验收按计划留在后续 CI 阶段。
- 图中刻意省略了大量数值（超时、配额、上限、算法参数），这些以项目文档和代码为准。

## 打开与维护

```bash
# 生成/更新阅读页
python3 notes/build.py

# 打开阅读页
xdg-open notes/index.html
```

阅读页的行为：

- 点击设计图 → 弹窗查看大图；`←` / `→` 切换，`Esc` 关闭，双击图片切换原始尺寸。
- 点击 Markdown 卡片 → 弹窗渲染；正文里的 `.md` / `.svg` 链接也会在弹窗中打开。
- 页面完全离线：Markdown 在构建时嵌入，不依赖 CDN 或本地服务器。

维护约定：

1. 修改 `notes/*.md` 或 `notes/diagrams/*.svg`；
2. 运行 `python3 notes/build.py`；
3. 刷新 `notes/index.html`。

新增 SVG 时，把标题和说明写进 SVG 的 `<title>` 与 `<desc>`，卡片会自动出现；
新增 Markdown 时，用第一个 `#` 标题作为卡片标题、第一段作为摘要。

SVG 文字使用系统中文字体回退（Noto Sans SC / PingFang SC / Microsoft YaHei / sans-serif）；如果本机缺少中文字体，浏览器会显示方框，但不影响结构。
