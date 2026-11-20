# YoChat 旧实现阅读笔记

这组文档是对旧实现 `~/projects/yochat-reference` 的**外部阅读**，用于回答三个问题：

1. 旧项目现在是什么状态？
2. 118 条需求里，哪些真的要在 MVP 做？哪些可以分批？哪些互相拉扯或很难实现？
3. 用一组图和术语表，先把项目读明白，再进入重写。

产出不是旧项目的规格，也不是本仓 S1 的设计；它是一份可维护的阅读材料。旧项目的权威仍是 `docs/spec/`、`docs/engineering/` 和主计划。

## 文件

| 文件 | 内容 |
| --- | --- |
| [`survey.md`](survey.md) | 旧实现现状：规模、架构、需求与计划、验证到了哪、为什么重写 |
| [`requirements.md`](requirements.md) | 118 条需求的分批（S1 40 / S2 20 / S3 35 / 延后 23）、12 处矛盾/张力、9 个难点 |
| [`terms.md`](terms.md) | 术语表与旧→重写对照；最容易混的八组词 |
| [`diagrams/07-scope-batches.svg`](diagrams/07-scope-batches.svg) | 需求从旧规格分流到 S1 / S2 / S3 / 延后的概念图 |
| [`index.html`](index.html) | 离线阅读页：设计图 + Markdown 弹窗；点击看大图，`Esc` 退出 |
| [`build.py`](build.py) | 重建 `index.html` 的脚本；扫描本目录的 Markdown / SVG，并引用 flw 的六张图 |

外部材料（原样保存，不在本目录重复）：

- [`../2026-11-20-flw-yochat-diagrams/notes/`](../2026-11-20-flw-yochat-diagrams/notes/)：flw 的六张图、术语表、原始 `index.html`、`build.py`。
- [`../2026-11-20-flw-yochat-diagrams/prompts.md`](../2026-11-20-flw-yochat-diagrams/prompts.md)：产生这组材料的提示词与复用模板。

## 图回答什么问题

| 图 | 回答的问题 |
| --- | --- |
| 01 模块概念图 | 代码由哪几块组成，依赖往哪个方向走 |
| 02 架构框图 | 纯状态机、执行器、适配器如何分层；崩溃安全顺序是什么 |
| 03 发送数据流 | 一条消息从界面到对方、再回到确认的完整路径 |
| 04 接收与补齐 | 乱序、缺失、等待、重发、通道重建如何串起来 |
| 05 好友交换 | 没有中心服务时，邀请/应答、渠道绑定、安全码如何建立关系 |
| 06 设备与吊销 | 多设备、设备列表、吊销与历史同步的边界 |
| 07 需求分批 | 118 条如何分流到 S1 / S2 / S3 / 延后 |

## 推荐阅读顺序

1. **先看现状**：`survey.md`。先知道旧项目做到哪、没做到哪，再看需求。
2. **再看图**：打开 `index.html`，按 flw 的 01 → 06 顺序看模块、架构、发送、接收、好友交换、设备与吊销。
3. **再看分批**：`requirements.md` 第 2 节。重点不是条数，而是「为什么 S1 只有一条路径」。
4. **再看矛盾与难点**：`requirements.md` 第 4、5 节。读旧规格时先带着这些边界。
5. **随手查术语**：`terms.md`。遇到「等待/暂不可得」「日志位置/消息标识」先查这里，不要凭语感。

## 打开与维护

```bash
# 在仓库根目录
python3 docs/references/2026-11-20-yochat-reference-study/build.py

# 直接打开
open docs/references/2026-11-20-yochat-reference-study/index.html
```

阅读页行为：

- 点击设计图 → 弹窗看大图；`←` / `→` 切换，`Esc` 关闭，双击图片切换原始尺寸。
- 点击 Markdown 卡片 → 弹窗渲染；正文里的 `.md` / `.svg` 链接也在弹窗中打开。
- 页面离线可用：Markdown 在构建时嵌入，不依赖 CDN 或本地服务器。
- flw 的六张图以相对路径引用；移动本目录时要一起移动 `../2026-11-20-flw-yochat-diagrams/`，或改 `build.py` 的 `FLW_DIAGRAMS`。

维护约定：

1. 改 `*.md` 或 `diagrams/*.svg`；
2. 跑 `python3 build.py`；
3. 刷新 `index.html`。

新增 SVG 时，把标题与说明写进 `<title>` / `<desc>`，卡片会自动出现；新增 Markdown 时，用第一个 `#` 标题作为卡片标题、第一段作为摘要。

## 来源与口径

- 旧项目：`~/projects/yochat-reference`（2026-11-20 的只读副本；没有 `.git`，不能核对提交历史）。
- 需求快照：本仓 `docs/spec/`。
- 旧项目状态：主计划 `docs/plans/2026-09/20260918-yochat-f2f-messenger.md` 的验证摘要；复盘 `docs/evaluations/2026-11-20-yochat-postmortem/`。
- 重写范围：`docs/adr/0003-v1-scope.md`、`docs/adr/0004-test-parity-and-performance-gates.md`。
- flw 的材料：`../2026-11-20-flw-yochat-diagrams/`；外部模型生成，未经旧项目作者校对。

## 限制

- 本文的分批是**建议**，不是旧项目的声明，也不是已经拍板的 S3 清单。S3 必须逐条重新论证。
- 旧产品文档全部 `status: draft`（全仓 34 draft + 1 accepted），118 条需求全部 `status: proposed`；数值、时限、平台行为仍有大量「待确认事项」。
- 任务 205/205 完成不等于产品验收完成；Windows/Linux、万条原生、常在线、真实通知点击等仍有未验项。
- flw 的六张图和术语表是阅读辅助，不是验收证据；引用前对照 `docs/spec/` 和代码。
- 本文只覆盖旧实现，不替代本仓 S1 的设计与计划。重写的 S1 以 ADR-0003 和 Plan 0001 为准。
