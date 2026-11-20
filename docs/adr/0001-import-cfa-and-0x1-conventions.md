# 继承 CFA 与 0x1 的护栏，并吸收 YoChat 复盘的教训

**状态**: accepted (2026-11-20)

本仓从 CFA（`~/projects/comp-fert-ai`）与 0x1（`~/projects/project-0x1`）继承项目约束与开发习惯。继承的是**护栏**，不是流程体量：两份 2026-11-20 的评估显示，CFA 与 0x1 的计划文档/代码比都是 0.64，7 轮评审上限被触顶甚至绕过，性能 SLO 不进 `check.sh`，文件大小上限只是建议。照抄体量会把同样的问题搬进来。

## 来源

- `~/projects/comp-fert-ai/DESIGN-DISCIPLINE.md`（D1–D10 / R1–R7）
- `~/projects/comp-fert-ai/AGENTS.md`（构建、门禁、Scala 规范）
- `~/projects/project-0x1/AGENTS.md`（评审闭环、工作树、定例、证据纪律）
- `~/projects/project-0x1/docs/agents/{git-workflow,review-quality,plan-docs,domain}.md`
- `docs/evaluations/2026-11-20-yochat-postmortem/`（复盘原文与摘要）
- `docs/evaluations/2026-11-20-cfa-session/`（CFA 会话原文、转录与摘要）

## 采纳

- `AGENTS.md` 作为单一注入正文；程序细节放 `docs/agents/`；同一规则只写一份，其他位置放指针。
- CFA 的设计与评审纪律，实例化为 `DESIGN-DISCIPLINE.md` 的 D1–D10 / R1–R10，并追加 Y1–Y8。
- 两轴评审（Standards / Spec）、五字段发现、append-only 记录、7 轮上限；追加「第 3 轮问题数不降就停」与「评审前冻结 diff」。
- 一变更一工作树：`worktrees/<slug>/`、分支 `<type>/plan-NNNN-<slug>`、rebase + ff-only、同轮清理。
- 两层计划：`.scratch/` 活状态，`docs/plans/` 冻结记录；计划文档用 markdown，不用 org。
- 单上下文领域文档：根 `GLOSSARY.md` + `docs/adr/`。
- 「定例」：采用上游做法时，同一次改动写规则 + ADR 记来源与差异 + `AGENTS.md` 留指针。
- 证据纪律：一手来源、对象/版本/日期/口径、冲突并列、估算不升级为实测。
- 门禁：一个入口、自动修复、警告即错误、定向检查与合入前全量、不完整回执不算通过。

## 拒绝 / 不适用

- Pekko Cluster / Singleton / Sharding：v1 是单机本地服务 + 中继，不引入集群。
- 多租户、IDOR、管理后台、交付执行器、应用代次：没有服务端多用户产品面。
- Fleet SSH、0x1 个人助理首轮提醒、微信/归档/研究域、技能格式：与本项目无关。
- CFA `./it.sh` 容器门禁：本仓没有 Testcontainers 套件；模型/真实 SQLite/E2E 的入口在 `docs/agents/verification.md`。
- 计划/发现/ADR 的 append-only 体量：保留 append-only 的记录语义，但每个计划必须声明取代了哪份旧文档，发现必须关闭或撤回。

## 与来源的差异

- **文件上限 2,000 行**，不是复盘建议的 800 行。用户在 2026-11-20 明确裁决；模块上限 8,000 行、v1 生产 ≤30,000 行、测试 ≤15,000 行仍保留。
- **计划文档用 markdown**，不用 0x1 的 org。原因：本仓规格是 markdown，第二套语法没有收益；org 行内标记规则只在写 org 时适用。
- **没有远程镜像**。当前未配置 remote；将来配置时另立 ADR，不默认套用 0x1 的双推送。
- **本次是最后一次整体重写**。之后只做模块替换（strangler），每次替换有行为契约与性能门兜底。

## 后果

- 先写完约束与计划 0001 的设计，再写产品代码；第一版会比「直接开写」慢，但范围、测试与性能的出口在开始时就有。
- 规则变多，但每条都能指向一次真实事故；不再新增没有案例的流程。
- 2,000 行文件上限仍会产生大文件；模块与总量上限、删代码预算和评审风险分级承担剩余约束。
