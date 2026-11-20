---
schema: tao.project.decision/v0.1
id: "DOC_20260918_Z2M1AN8FXV4J071T"
title: "技术栈选型"
locale: "zh-Hans"
status: draft
---

# 技术栈选型

<!-- tao:section decisions -->
## 设计决策

```{adr} 技术栈选型
:id: ADR_20260918_1S8RW7QWGCDMH60S
:status: proposed

<!-- tao:field context -->
**背景与备选：** 规格要求桌面三端优先、移动端架构预留，要求常在线设备能以无人值守方式长期运行，
要求界面参照主流即时通讯软件且做得好看，并要求代码精简可控。

界面侧的候选按是否自带观感分两类：自带设计系统的（Skeleton、Flowbite、Carbon）
与无样式原语（bits-ui、Melt）。自带观感的省事但要做出即时通讯软件的手感就得与它
的既有风格对抗，且形成锁定。需要注意的是，本应用真正决定手感的几个部件——十万量级
消息的虚拟列表、按设备分层的共享目录树、传输日志表——任何 Svelte 组件库都不提供，
无论选谁都要另行接入或自写。因此组件库的选择实际只决定叠层与表单这一类原语，
而这类原语恰恰是可访问性与焦点管理最容易做错的地方。

<!-- tao:field decision -->
**选择：** - 核心为一个不依赖 Tauri、不含界面假设的 Rust crate，使常在线设备可以复用它而
  无需带上界面。
- 桌面外壳采用 Tauri 2稳定线（当前2.12）；不采用3.0预发布。
- 界面采用 Svelte 5 + Vite，**不引入 SvelteKit**：其服务端渲染、load 与 form
  actions 在本地包里没有用处，而本应用只有主界面、设置与首次引导等少数视图，
  视图切换用一个显式状态机表达，与本项目其余部分的原则一致。
- 组件以 shadcn-svelte 为起点，它不是运行时依赖，而是把构建在 bits-ui 之上的
  带样式组件源码复制进仓库；只复制用得到的部分，其余不进仓库。样式用 Tailwind 4。
- 大数据量部件采用 TanStack 的无头实现：虚拟滚动用 `@tanstack/svelte-virtual`，
  传输日志表用 `@tanstack/svelte-table`。
- 本地存储用rusqlite 0.42搭配配套SQLCipher 4.20/SQLite 3.54（`bundled-sqlcipher`特性），不独立强拉底层sys版本。
- 数据库密钥的封装用 **Argon2id** 拉伸（`argon2` crate），参数取 RFC 9106 的**第二推荐档**：
  64 MiB 内存、3 轮、并行度 4。第一推荐档（2 GiB）在桌面上的交互延迟与内存占用都不合适，
  而这一档仍把离线穷举的成本抬到有意义的量级。**参数属于安全属性的一部分，调低要有依据**。
- 封装用的 AEAD 取 **ChaCha20-Poly1305**（`chacha20poly1305` crate）。选它而不是 AES-GCM：
  不依赖硬件 AES 指令，在没有该指令的机器上性能不塌；且它对随机数复用的失效模式不比
  AES-GCM 更差。本项目每份封装各带自己的随机数，不复用。
- 恢复短语用 **BIP39**（`bip39` crate），**24 词**。
- 签名用 **ed25519**（`ed25519-dalek` crate）。算法由[密钥与设备](../keys-and-devices.md)点名；取 dalek 实现是因为 iroh 用的也是它，全仓因此只有一份 ed25519 代码，不会出现两套实现对同一把密钥有不同看法。
- 网络与同步用p2panda-core/encryption 0.8.1搭配iroh 1.4.1；本地修复继续由[主题补丁](../../../patches/README.md)维护，不把升级等同于退出补丁。
- JavaScript包管理器用pnpm 12；锁文件包含引导与工作区两个YAML文档，读取工具不能假设只有一个文档。

<!-- tao:field consequences -->
**影响与后果：** 收益：观感完全由本项目决定；组件源码在仓库内，可改可删，不存在黑箱依赖；核心 crate
与界面解耦，常在线设备与将来的移动端都复用同一套逻辑。

集成边界：shadcn-svelte在纯Vite下使用显式`components.json`；路径别名同时登记在
TypeScript的`paths`及Vite的`resolve.alias`，一侧声明不能代替另一侧。虚拟/窗口列表
的双向加载、逻辑身份锚定与像素偏移恢复仍由应用负责，无头库只提供测量与定位
（{need}`TASK_20260919_TE9DN97R1P2SBQ18`、{need}`TASK_20260919_0R84ESC1SZ2Y0FEM`）。

依赖具体版本以清单和锁文件为准。当前运行矩阵统一Rust1.100.0、Node26.13.1 LTS及
Python3.14.10；Rust最低版本声明仍与实际依赖要求分开，不随编译器升级自动提高。
TypeScript维持6.0.3以满足svelte-check的稳定peer范围，不启用TS7或重复编译器的兼容绕行。
Quint0.33.1固定其受支持的Apalache0.62.4，使用Java21+，不单独追最新求解器或预发布TLC；
无论版本多新，进程退出0仍不能替代明确的安全及时序判词。
pnpm依赖构建脚本按包放行；严格检查engine和peer，第三方服务缺失的真实依赖只以精确版本
packageExtensions补齐，不放宽peer范围或强行覆盖服务内部WDIO版本
（{need}`TASK_20261113_A37ZMSKHM4SSJ8DN`、{need}`TASK_20261113_MTVM7H6PPG8QQTEK`）。

rusqlite 0.42要求独占连接才能注册SQL钩子。正式截止回调在打开连接时安装，后续SQL预算只
更新连接自持的原子截止；嵌套取更早期限并在退出时恢复，不通过unsafe别名或每次查询重新
注册回调绕过借用要求。需要可变Store的整段工作使用`with_sql_deadline`，不让内部读取清掉
外层期限。崩溃注入和查询观察的可变连接访问仅由开发依赖的`test-utils`提供；临时进度
观察结束必须恢复正式截止钩子，正式依赖不得启用该特性。

打包不等于发布信任：macOS无自动化授权时Finder排版步骤可失败，`CI=true`跳过排版
而非跳过包完整性；Developer ID签名、公证及首次系统授权与本机ad-hoc签名是不同
边界。三端矩阵的静态配置不证明Windows/Linux安装启动，也不自动启用仓库分支保护；
实际受检平台和产物范围见[主计划验证摘要](../../plans/2026-09/20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)
（{need}`TASK_20260919_CMJBZJRPNQ5FD7QE`）。

图标维护保留原始`crates/yochat-app/icon-source.png`、默认运行时使用的
`icons/icon.png`与`icons/icon.ico`，以及用户决定保留的未来macOS自定义图标`icons/icon.icns`。
当前`tauri.conf.json`未配置`bundle.icon`，保留ICNS不表示发行包已使用它；其余未被消费的
派生尺寸不在源码库维护，需要时从原图生成并显式接入打包配置。

被排除的选项及理由：Skeleton、Flowbite与Carbon自带观感并形成锁定；Melt和
`svelte-headless-table`在选型时分别存在维护节奏及Svelte 5支持缺口，不能把当时观察
写成永久的上游版本事实。移动端组件体系另行评估，不由桌面栈决定。
```
