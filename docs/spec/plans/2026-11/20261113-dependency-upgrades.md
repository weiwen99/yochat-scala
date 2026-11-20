---
schema: tao.project.plan/v0.1
id: "DOC_20261113_CZ2RPGW7DMBW6FET"
title: "稳定依赖、安全修复与验证工具链升级"
locale: "zh-Hans"
status: draft
spec_docs: [DOC_20260918_8RNAEGQ7629XHTVM, DOC_20260918_P2NQVR3XQ1BXBDRA, DOC_20260918_TQMGKKQ6Y50JHKDC, DOC_20260918_K0ENVBAX9Y6YAXT4, DOC_20260918_G2PHPKZ09H2A0ECQ]
design_docs: [DOC_20260918_94F65AR6A6WAQ9QM, DOC_20260918_T09EDKCAGT4Q75JT, DOC_20260918_0TGB43S944N20PKH, DOC_20260918_65J9C775MF8PYAQJ, DOC_20260918_9810WZW7EA7H8FC5]
change: "CHG_20261113_WA9Y31DT1ASC5ZN8"
---

# 稳定依赖、安全修复与验证工具链升级

<!-- tao:section scope -->
## 目标与边界

用户在2026-11-13的全量依赖审计后授权按建议处理。输入为提交`42fc77e896e3b6c6c23fc00e45d24c51b159e6a8`，在`chore/dependency-upgrades`中按业务分别提交，不推送。不改原产品承诺、资源上限、超时、密码套件或旧格式拒绝策略；原交付计划的205项历史任务不因本次维护重新勾选。

优先处理HPKE/p2panda依赖链的RUSTSEC-2026-0387更新及根锁中已yanked的async-executor 1.14.0、yoke-derive 0.8.3。旧crypto-bigint 0.5.5位于公告影响范围，但Core实际使用DhKem25519，不能把锁命中宣称为产品路径已可利用。安全依据见[RustSec](https://rustsec.org/advisories/RUSTSEC-2026-0387.html)。

| 范围 | 目标稳定组合及约束 |
| --- | --- |
| 密码协议 | HPKE三件套0.8.0；p2panda-core/encryption 0.8.1，保留本地七项源码修复，重做来源、补丁及测试锁 |
| 网络 | iroh/dns/relay 1.4.1；重基六项主题，仅移除已上游覆盖的重复分支 |
| 数据库 | rusqlite 0.42.0及其libsqlite3-sys/SQLCipher配套，验证真实加密库与事务，不强拉sys版本 |
| 外壳与界面 | Tauri Rust 2.12.5/build 2.7.4、CLI/API 2.12.4；Svelte5、Vite8、Tailwind4的稳定更新 |
| 原生验收 | JS/Rust Tauri桥接1.5.1；WDIO9.33.1配套expect7，不升级WDIO10或桥接预发布 |
| JS大版本迁移 | pnpm12.18.0、cn0.5.2、tailwind-variants4.0.2；TypeScript保持6.0.3以满足svelte-check支持范围 |
| 执行环境 | Rust1.100.0、Node26.13.1 LTS、Python3.14.10；CI与本机统一，MSRV不因编译器升级擅自提高 |
| 模型 | Quint0.33.1及其默认Apalache0.62.4、Java21+；不独立追Apalache0.63或TLC预发布 |

所有模型保持原论域、步数、安全与公平时序性质及负控；任何语法迁移须说明等价性，不能以类型检查或轨迹采样冒充性质验证。补丁维护遵循[既有管理约定](../../../patches/README.md)，不直接维护生成的vendor源码。宿主工具只升级本次明确涉及的运行时，不覆盖系统curl或清理用户现有环境。

<!-- tao:section design -->
## 实施与验证原则

业务机制沿用[架构](../../engineering/architecture.md)、[本地存储](../../engineering/local-store.md)、[消息](../../engineering/messaging.md)及[验证策略](../../engineering/decisions/verification-strategy.md)。p2panda新Signer/CBOR接口不能放宽签名、完整输入和规范编码边界；组状态镜像、线格式与落盘布局继续由真实字节及重启回归约束。

普通依赖、补丁重基、数据库、JS大版本与模型后端分别可追溯。根Cargo.lock与pnpm锁各由单一集成所有者维护；共享依赖直接指向主工作区稳定位置，各worktree使用独立Cargo target。只有相应代码变更、失败或未解决风险才扩大或重复检查，已通过且输入未变的结果复用。

<!-- tao:section tasks -->
## 唯一任务集合

- [x] `TASK_20261113_CZHJMR0J64PC611E` 迁移HPKE与p2panda安全依赖及主题补丁
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: 固定0.8.1发布包可由补丁序列重建；HPKE三件套0.8.0及必要调用方完整迁移；原签名/编码、成员、并发Welcome、缓冲恢复、私料及镜像回归通过，失败、取消与重启不半提交；根锁和独立密码库测试锁不残留公告所涉及的旧依赖链。
- [x] `TASK_20261113_84K2BG4V11M1EA6W` 升级普通Rust与数据库依赖并退出撤回版本
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: 各直接依赖约束及根锁一致，退出已yanked版本；rusqlite/SQLCipher真实加密存储、重开、事务失败回滚与查询回归通过；保留原特性及平台边界，不以强制覆盖传递版本消除合法多版本。
- [x] `TASK_20261113_0YBZGF8S508QMZRM` 重基iroh升级并保留网络安全预算
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: 固定1.4.1与六主题补丁可重建，已上游修复不重复实现；映射、控制/物理预算、接收前缀、home所有者、PKARR固定截止和双栈公平的原模型/真实网络对拍及负控通过，不扩大容量或续期。
- [x] `TASK_20261113_A37ZMSKHM4SSJ8DN` 迁移JS包管理器、界面及稳定原生桥接组合
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: pnpm正式锁解析及冻结安装成功，无被忽略的必需peer/engine冲突；生产UI构建、Svelte检查、UI回归、E2E类型与运行器契约通过；原生驱动实际观察真实窗口和业务表面，未启用E2E能力的正式包保持隔离。
- [x] `TASK_20261113_NBTQTHQCQGDKPFCR` 统一受支持运行时与CI Actions
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: 本机实际版本输出及CI配置一致，宿主旧环境不被无关清理；Rust格式/Clippy、工具回归和实际构建使用目标工具链；区分本机证据与尚未执行的三平台远端CI，不把Actions配置当成运行通过。
- [x] `TASK_20261113_MTVM7H6PPG8QQTEK` 迁移Quint支持链并验证原全部性质
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - verify: Quint0.33.1、默认Apalache0.62.4与当前JVM实际可运行；原checks.json全部配置、原安全/公平时序及反向对照得到明确结论，不降低论域或步数；保留exit0、有ok且无violation的守卫及共享资源互斥，语法/导出变化与原实现回放保持对应。
- [x] `TASK_20261113_66GRKWCFCSTJ8BZG` 完成可集成依赖批次与实际产品表面验证
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - depends_on: ["TASK_20261113_CZHJMR0J64PC611E", "TASK_20261113_84K2BG4V11M1EA6W", "TASK_20261113_0YBZGF8S508QMZRM", "TASK_20261113_A37ZMSKHM4SSJ8DN", "TASK_20261113_NBTQTHQCQGDKPFCR", "TASK_20261113_MTVM7H6PPG8QQTEK"]
  - verify: 项目必需检查逐项完成，超时或失败如实处理；版本升级的真实CLI及原生窗口、输入和业务链路可观察；复用仍有效结果，不用构建成功替代GUI或性质求解，不声称未经执行的跨平台/真实通知点击已通过。
- [x] `TASK_20261113_DQZF2NJ1XK1RT3MH` 保存长期升级约束及实际交付结果
  - relates: ["CHG_20261113_WA9Y31DT1ASC5ZN8"]
  - depends_on: ["TASK_20261113_66GRKWCFCSTJ8BZG"]
  - verify: 长期版本和补丁退出边界归入既有权威文档；本计划记录受检输入、实际日期、结果、失败及未覆盖限制；tao verify通过，按独立业务提交，不提交常规日志、可再生输出或第二份源码。

<!-- tao:section verification -->
## 实际结果与限制

升级前的只读审计覆盖45个外部直接crate、24个npm直接包及pnpm，根Cargo锁807条、两个上游测试锁665条及pnpm锁653个name@version；这不是新版兼容性验证。实施输入为`42fc77e896e3b6c6c23fc00e45d24c51b159e6a8`；以下为2026-11-13至14日本机macOS arm64的实际结果，未执行远端CI、Kani或Windows/Linux验收。

目标依赖、运行时及CI声明已迁移，两个上游包可由固定来源与主题补丁重建。HPKE/p2panda上游检查53项通过；iroh完整性及映射、接收、空闲、入站和握手等分项对拍通过。pnpm冻结安装、Svelte检查、生产UI构建、31个文件共360项UI测试、E2E工具检查及模型运行器检查通过。154个模型类型检查通过，不据此宣称性质验证完成。

格式、Clippy、E2E特性Rust测试、生产配置检查及捕获日志检查通过；正式依赖图未启用Core的`test-utils`。工作区运行中App414项单测、Core195项单测及全部Core集成目标、Net57项单测及全部Net集成目标通过，含真实SQLCipher存储、嵌套查询截止、重开/回滚及双进程QUIC。此前夹具旧指纹已用真实Quint导出修正。本轮在Node依赖隔离目标停止：测试内直接调用Cargo，绕过验证包装器的官方索引覆盖，隔离缓存中的旧镜像缺少p2panda-core0.8.1；不是产品链接了UI。同步官方索引元数据与不可变包到本次隔离缓存后，Node与Protocol全部单元/集成目标通过（54.69秒），全工作区文档测试通过（14.60秒），未改用户Cargo配置或削弱检查。工作区覆盖由本轮已通过目标与补跑合并取得，不把首次`just rust-test`的退出码101改写为单次成功。

Quint0.33.1/Apalache0.62.4的完整正向门槛已取得明确结论：308项配置产生313次调用，先前有序匹配的191次成功与补跑的122次成功合计覆盖全部安全及公平时序义务，0个violation；补跑退出0，耗时1645.13秒。模型运行器的退出码、明确判词、反例优先及互斥反向守卫检查通过。原论域、步数、性质及模型语义不变；既有历史对照结论不改写为本次逐项重跑。首次运行确曾因下载超时失败，第二次在用户取消QuintUpgrade时中断，不将这两次写为完整成功。最终后端来自用户提供的`apalache-0.62.4.tgz`，SHA256为`b781b53bba32fac14ac2e02b0332e1dc2edc64cb7b850c0608d8142cc7aa2ef4`，已部署到Quint预期缓存且实际版本输出0.62.4；与先前缓存相同的JAR SHA256为`e5223915507b65cc5f1638bd037ede60f8a4fc1644aff46355779f9b05df9262`，复用结果未跨后端字节变化。JAR运行时仍输出上游protobuf生成代码的安全告警；未屏蔽告警或以未经Quint支持的后端替换。

本机实际使用Rust1.100.0、Node26.13.1、Python3.14.10、pnpm12.18.0及just1.59.0；Node和Python通过项目本地运行环境选择，未改用户默认shell。Homebrew运行时更新同时触发e2b由2.20升级至2.23；未清理旧keg。CI Actions及Java配置仅已更新声明，不代表远端执行成功。tao verify通过431定义、1738引用及29份文档；前端升级提交为`e386ef28`，其余集成实现提交为`663fc765`，Rust分项实际结果已由`7109673`回写。

`663fc765`已在独立worktree中以63.27秒完成原生E2E程序构建并冻结。首轮GUI测试因用户报告可能有手动干扰而取消；重跑明确发现旧自应答夹具与联系人引导能力隔离冲突，以及WebKit把折叠details的隐藏后代计入offsetParent的测试误判。改为产品创建的两个独立身份、原生多窗口焦点管理及真实可见性后，关系建立和安全码12项、当前控件聚焦、锁定清空及联系人键盘激活已通过；Return须复用受信原生输入，不能用驱动能写入字符推定按钮默认激活有效。

最终发送场景暴露的真实前提已修正并实际验收：两个独立身份通过真实端点、正常自动接收和两侧认证通道就绪观察完成准备，不用本人会话或合成密码状态绕过。消息输入改为textarea，Enter提交、Shift+Enter原生换行，代码保留输入法组合期间不提前提交的守卫；既有键盘用例核对完整换行和表情正文，不改成单行文本。WDIO的`keys`拒绝代理对字符，字符录入改用标准元素文本命令，仍不以指针点击替代键盘。更新后的Svelte检查、360项UI测试、生产UI构建、13项Python与37项Node原生工具检查、E2E类型检查及162条Tauri命令参数检查通过。

`7b16d91d`在独立worktree中以58.51秒完成新版原生程序构建，冻结程序SHA256为`59335f5344ebba6cd34a19e3987ab3a74cce9c98fa1efd9a0cd78bba9a36eb78`。2026-11-14运行原`e2e/wdio.conf.ts`全部6个spec、41项用例通过，退出0，38.14秒；实际覆盖身份创建/解锁、真实双身份交换与安全码、键盘焦点/消息发送、锁定清空及原生外壳，含受信Shift+Enter后完整换行与表情正文提交。此前GUI失败保留上述原因，不改写为用户干扰或通过。临时命令日志和可再生产物留在忽略目录；临时验证包装器、诊断脚本及MSRV探针已移除，长期边界归入既有技术栈、补丁管理、消息设计及原生验收文档。

结果按本机有效分项合并，不声称一次完整`just checks`退出0。模型两段运行墙钟合计约73分钟，包含取消时的未完成配置；尚未验证全套串行模型检查能在CI既有3600秒门槛内完成，不能据本机性质结论宣称远端门槛已绿。远端三平台CI/Kani、真实系统通知点击及实际输入法组合操作未执行；TS7、WDIO10及独立最新版Apalache仍不在本次受支持稳定组合中。所有提交留在`chore/dependency-upgrades`，未推送。
