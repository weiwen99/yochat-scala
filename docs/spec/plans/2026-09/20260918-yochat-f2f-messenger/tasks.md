---
schema: tao.project.tasks/v0.1
id: "DOC_20260919_JB5KF5E5T9MC27EY"
title: "YoChat 首个交付增量的任务集合"
locale: "zh-Hans"
status: draft
change: "CHG_20260918_NAHYZQ1MM6ZYT4S1"
---

# YoChat 首个交付增量的任务集合

<!-- tao:section scope -->
## 目标与边界

本附件是[实施计划](../20260918-yochat-f2f-messenger.md)的完整任务集合，计划正文不再另存任务。
按能力域及后续修正分组，每项直接关联其交付需求；使能任务关联本CHG，并按实际依赖被下游任务指向。

两条贯穿全局的排法写在这里，不在每项任务里重复：

第一，**落地检查策略是一切要跑检查的任务的前置**，这一点用 `depends_on` 表达而不是靠它排
在最前面——顺序只是阅读次序，依赖才是约束。第二，**七处未定的定义由建模任务逼出**，凡是
实现协议行为的任务都指向那项修订任务（{need}`TASK_20260919_YGJ521XV59FBBJ1R`），在它完成之前
不开始写对应的协议代码。只有静态加密那一项完全不受这条约束；身份创建、应用锁、引用与搜索经会话落盘任务传递依赖它，因为第 2 处定义（配额计数器是否进入 `State`）会改变 `State` 的形状，而落盘任务要测状态序列化往返。

当前205项，205项已完成、0项待办；唯一状态为下方TASK复选框，实际结果/历史失败及独立审查限制见[实施计划验证](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)。原93条需求（用户与设备24、好友与通讯录24、聊天会话与消息33、界面与外壳4、隐私与防护8）及已批准排序/阅读增补由此唯一任务集承接；群聊/文件传输23条按计划明确分期。evidence指向当前契约、逐项义务或计划实际结果，不宣称被删除原始回执仍在现行目录；清理前原件统一按固定2d79b29c追溯。verify保留判据，不是本轮执行流水或通过声明。

<!-- tao:section tasks -->
## 任务

### 底座与检查策略

- [x] `TASK_20260919_4VA6XYGKJWWM56D2` 落地检查策略：统一必需检查清单并跑通首次基线
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - verify: 按架构篇的项目检查入口建立Cargo工作区、根pnpm workspace、固定工具链与CI工作流；必需检查各有单项截止和明确输入，首次空骨架七项本地基线无漏跑，模型空集合的初始结果不冒充性质已验。项目唯一检查清单由justfile及tools/run_checks.py承载，`just checks`与CI调用同一runner；文档结构/关系由`tao verify`单独检查，不在.tao/config.toml保留已取消的verification配置。首次基线按当时已批准的CI暂缓边界不要求远端执行；现行CI触发与未核实平台结果见主计划。
  - evidence: [检查入口、隔离与验证边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--verification)
- [x] `TASK_20260919_MN1FTNNX11BEA7GQ` Rust 工作区分层：纯状态机、执行器与适配三层的骨架
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: crates/yochat-core、crates/yochat-app、crates/yochat-node 各自编译通过；`step(state, event) -> (State, Vec<Effect>)` 与五类效果的类型落地，`cargo tree -p yochat-core` 中不出现 tauri、rusqlite 与 iroh；项目必需门槛通过
  - evidence: [检查入口、隔离与验证边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--verification)
- [x] `TASK_20260919_TE9DN97R1P2SBQ18` Tauri 2 与 Svelte 5 外壳跑通，含 shadcn-svelte 在纯 Vite 下的接入
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: `pnpm --dir ui run build` 与 tauri dev 能起窗口；按技术栈决定的要求实际跑通一次 shadcn-svelte 在非 SvelteKit 项目下的组件添加，把实测得到的路径别名配置写进仓库而不是照抄官方说明；ui-check 与 ui-test 两项检查通过
  - evidence: [检查入口、隔离与验证边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--verification)
- [x] `TASK_20260919_P2HD03NJRM84P241` 网络适配的窄接口与 p2panda／iroh 接入
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_MN1FTNNX11BEA7GQ"]
  - verify: 网络适配只暴露收发与可达性观察的窄接口，状态机侧看不到 p2panda 与 iroh 的类型（用 core 的公开签名核对）；接口有内存替身实现，另有一条用真实 iroh 在两个本机进程间完成收发的集成测试
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260919_CMJBZJRPNQ5FD7QE` macOS、Windows、Linux 三端打包与 CI 矩阵
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: **本期**（用户 2026-09-24 定：持续集成暂缓期间先只在 macOS 上做）：macOS 产出可安装包并能启动到主界面；三端的持续集成矩阵配置写好并在本地核对，任一端失败即阻断合入的规则写进配置。Windows 与 Linux 的打包与矩阵实际运行**分期延后**到持续集成启用之后，见计划的待确认事项
  - evidence: [打包、平台与实际验证限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20260919_SEMPP73D13V55EJ7` 纯函数层的 Kani 有界证明与它的独立持续集成任务
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_5NM8Z6W23J25315B", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_35DXYYM7SVWXGTXR"]
  - verify: 为 TLV 与信封解析、序号运算、令牌解码、设备列表与吊销声明的解析各写 proof harness，并覆盖有效期与设备公钥个数的边界；日志位置那一段由 {need}`TASK_20260923_9RA0DAP61VAW01XD` 移交：`yochat_protocol::wire::log_nearly_full` 与 `author` 里 `OpId.seq` 到 `u32` 的换算，证明对任意输入不回绕、不溢出，并在 `u32::MAX - LOG_WARN_HEADROOM` 的前后、`u32::MAX` 本身与越过它的第一个值上各自给出与设计一致的结论；`cargo kani -p yochat-core -p yochat-protocol` 全部通过（日志位置那两个函数在后者里，原先只写了前者）；它按架构篇的安排留在门槛之外、跑在独立的 CI 任务里，该任务失败同样阻断合入
  - evidence: [纯函数证明及分层验证边界](../../../engineering/decisions/verification-strategy.md#DOC_20260918_59RGEGWQ21WQXBF5--decisions)

### 模型与实验

- [x] `TASK_20261030_GR1G47QADRQWBZK8` 用户私钥材料在双端确认后的传输模型
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_WSG175RHG9NHXPD0"]
  - verify: Quint区分接收端实际获知私钥材料与签发接口启用，含两个尝试、确认重放、目标冒用、替换/取消/过期、临时解密材料与重启。先检查无两端确认不传输材料、不同尝试和设备不能借用确认、接收端作废后不能通过迟到密文新获知私钥，以及在合法确认和公平执行前提下最终完成；撤去各必要守卫有相应反例。导出固定见证并定义实现的事件/观测映射；新协议实现的真实密码学与逐步对拍在用户私钥同步任务中完成。本任务不凭API布尔值判定材料保密。
  - evidence: [私钥双端确认与材料知识边界](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)

- [x] `TASK_20261030_1JBHWEVA74PZKKSQ` 独立成员、重新接入与缓冲接收事务的前置模型
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: quint verify 检查成员事实、单操作许可与跨帮助者恢复尝试三部分有限状态：加入事实的多个来源及显式移除合并，不因B退出而移除仍有效的C/D，不因旧欢迎或迟到加入而复活明确退出的C；重新接入只按C绑定会话、指定帮助者与控制位置的许可执行，当前授权、依赖就绪、去重和密钥状态与处理标识原子保存共同承重。覆盖重复投递、乱序、准备后授权变化及崩溃恢复；同次尝试多个帮助者只重设一次，最高尝试号持久保存，旧尝试不回退且尝试号由目标设备签名，分别撤去守卫得到对应反例；给出公平执行和有效授权前提下的最终完成性质。追加 buffered_receive.qnt 检验欢迎到达后立即放行已就绪正文、密钥转交与接收状态原子保存、重复原件与重启不重建已拒绝的转交；导出固定见证与乱序/故障轨迹，逐步对拍真实签名、组协议、执行器及存储，并以撤去欢迎放行或事务守卫产生反例作为对照。模型不证明密码学或字节兼容，实际事务接线继续由原实现任务验证
  - evidence: [独立成员、许可重入与收敛边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20260919_WSG175RHG9NHXPD0` 建立 Quint 工程骨架与对拍回放器的接口
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: models/下Quint工程可运行；本地`just quint-typecheck`及`just quint-verify`经过项目统一runner和独立单项命令结果一致，非空模型有明确性质结论，初始空集合通过不算性质验证；回放器事件/观察接口定义明确，逐步比较真实step的可观察分量并报告第一处分叉。首次基线不以当时暂缓的远端CI为判据，后续真实字节/SQL接线由对应任务承接。
  - evidence: [检查入口、隔离与验证边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--verification)
- [x] `TASK_20260919_CMQ5HX5V77QHC7KH` 为好友交换写 Quint 模型，逼出第 5 处定义
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_WSG175RHG9NHXPD0"]
  - verify: 模型含邀请、应答、确认、两侧到期与攻击者（可读取、丢弃、重放、改造渠道上的文本，可生成自己的密钥），并覆盖[好友交换](../../../engineering/contact-exchange.md)验证节列出的全部性质（含带前提的公钥真身性质、未经比对不得呈现为已认证、四条超时安全性质、活性及其安全对偶）；把「已建立 ∧ 对方无关系 ⟹ 对方持有该次交换记录」写成 `State` 上的安全性质并检验，若为假则反例即第 5 处定义的答案；另建模多设备下对端直投与通讯录私有同步的两种到达顺序，检验「有顺序保证」与「无顺序保证」两种取法下关系与通讯录是否仍然收敛，由结论定下第 6 处定义；另建模设备加入与吊销在并发下的收敛，以及吊销声明传播期间各联系人视图不一致的窗口
  - evidence: [好友交换模型的安全与条件活性](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--verification)
- [x] `TASK_20260919_PFGXDHF5JWV01TRJ` 为消息按序应用与三条补齐路径写 Quint 模型，逼出第 1、2、3、4 处定义
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_WSG175RHG9NHXPD0"]
  - verify: 模型含应用游标、乱序缓冲、三条补齐路径、通道重建、成员变化与删除指令的逐设备确认，并覆盖[消息](../../../engineering/messaging.md)验证节列出的安全性质与活性前提；分别以「每纪元有界」与「每时间窗有界」两种口径各跑一次，记录哪一种能同时满足有界性与离线送达的活性（第 1 处）；把配额计数器一次放进 `State`、一次放在模型外各跑一次，记录哪一种下上限可被检验（第 2 处）；`connected` 的两种语义各跑一次，记录哪一种下「连通期内一次投递能完成」成立（第 3 处）；事件字母表含与不含「对端转为可达」各跑一次，记录可达性事件是否为检验有界性所必需（第 4 处）；「超限豁免集合」与「送达粒度为任一台设备已落盘」两种取法各跑一次，记录哪一种不与另一条冲突（第 7 处）；以上全部运行都检验 {need}`UC_20260918_3F320TE06E95J4TX`——安全性质在包含任意超时序列的执行上无反例
  - evidence: [消息模型的安全与条件活性](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--verification)
- [x] `TASK_20260919_YGJ521XV59FBBJ1R` 依据模型结论修订 design 与 spec（走 refine），把七处定义写死
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_CMQ5HX5V77QHC7KH", "TASK_20260919_PFGXDHF5JWV01TRJ"]
  - verify: 七处定义在文档中各有唯一答案且附模型依据；通道重建的两类告知条件分别写明：配额用尽进入暂停，持续不可用跨时间窗产生长期告知，附rebuild.qnt反例且不推翻原暂停需求；检索任务附件的「模型给出的第」「按模型结论核对」「模型给出结论后」，除本条引用外无残留间接判据；`tao verify`通过，另一次定向复核只核对模型结论与文档是否一致，实际发现及未独立确认范围记在主计划。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)
- [x] `TASK_20260919_Q84FGP203EPHG687` 实测中转与发现：中转实际能观察到什么、发现信息是否发布直连地址
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 用可查日志的自建中转与独立的发现查询客户端取得实测结论，记录中转侧实际可见的字段与发现记录的实际内容；本任务只产出事实，不判定任何需求的验收——据实测判定两条隐私承诺是改需求还是改实现，若要改文档按 refine 处理；验收由隐私组的实现任务承担
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260919_2AQRGA1K60DY1E6E` 用小原型验证设备组方案在并发增删、离线成员加入与通道重建下的实际行为
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 原型覆盖三种组合并各自记录实际行为——并发增删下的成员视图是否收敛、离线成员加入后是否读不到加入之前的消息、通道重建后的成员与密钥状态；本任务只产出事实，不判定任何需求的验收；结论与[聊天会话即设备组](../../../engineering/decisions/conversation-as-device-group.md)不一致时按 refine 修订该决定
  - evidence: [参考实现原型的历史结果与当前设备组边界](../../../engineering/decisions/conversation-as-device-group.md#DOC_20260918_JEAWMGFQWK0761MM--decisions)

### 用户与设备

- [x] `TASK_20260919_YNE0A885R3XZ7TV2` 身份创建与恢复短语的生成、确认与使用
  - relates: ["REQ_20260918_4DYMP7NVJDKNPTKR", "REQ_20260918_T1H63J7F3QD0WBBT", "REQ_20260918_G2S3MXJ3J0HXYXYP", "REQ_20260918_VMJVPZMK63123DKH", "REQ_20260918_E6NXMN0ABVQD7NSN"]
  - depends_on: ["TASK_20260919_MN1FTNNX11BEA7GQ", "TASK_20260919_M09VDQV6DHRV5633", "TASK_20260919_CNDAGMZJP5S3CJ6R", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 断开网络做一次全新安装，确认不产生任何对外网络请求且本机生成密钥对；恢复短语只在创建时展示并要求回填确认，重启后主界面不出现短语、设置中的查看入口要求先输入应用锁口令；恢复三种情形各走一次——用短语打开本机既有数据、输错短语时报告无法解开并停下且原目录不变、放弃后新设备位于另一目录而原目录仍在；生成时如实说明后果；{need}`UC_20260918_1RRB8TM1KYDJHQ7B` 与 {need}`UC_20260918_KW9DXS3704B5MYB2` 各执行一次，后者另确认恢复不要求钥匙串提供口令封装所绑定的随机值
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_M09VDQV6DHRV5633` 本地静态加密与同机多实例隔离
  - relates: ["REQ_20260918_ZTSEXMZQ2VHS21R6", "REQ_20260918_RCMJA190DC6RY6DW"]
  - depends_on: ["TASK_20260919_MN1FTNNX11BEA7GQ", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: SQLCipher 加密库落地；注入已知标记值后在未解锁状态下检索**整个应用数据目录**，消息正文、联系人名称与密钥材料一处都读不到；同机并存两个用户与同一用户的两个设备实例，各用不同的数据库密钥，互相解不出对方任何数据。「解锁其中一个不使其他实例变为已解锁」「切换实例时要求重新通过应用锁」与 {need}`UC_20260918_VX22BC4VKGYQ6MN2` 归应用锁任务执行
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_CNDAGMZJP5S3CJ6R` 应用锁与空闲超时锁定
  - relates: ["REQ_20260918_B9Y4E73VMQ8RS99X", "REQ_20260918_YQW27DXZJSHAR0Z0", "REQ_20260918_RCMJA190DC6RY6DW", "REQ_20260918_8F32S19RPRC25471"]
  - depends_on: ["TASK_20260919_M09VDQV6DHRV5633", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 锁定后内存中的明文密钥被清除、再次进入需重新解锁；空闲超时按配置触发且不被后台活动重置；{need}`UC_20260918_HE49NBGG4GRM5V6J` 的核心侧在此确认——锁定期间返回的在途结果被拒且拿不到库句柄，因而写不进任何状态；三条件（实例相同、代数相同、当前已解锁）缺一不可，各造一个不满足的情形验一次；该用例里界面可观察的那一半归主界面任务执行（其逐项内容列在那里），本任务无从观察；{need}`UC_20260918_N69VYMQJ1M8WTA3B` 以 proptest 检验任意请求与锁定、实例切换交错下旧代数的结果永不落地；{need}`UC_20260918_VX22BC4VKGYQ6MN2` 确认解锁一个实例不使其他实例解锁、切换实例要求重新通过应用锁、切换后不出现上一个实例的内容；未解锁状态下无法执行搜索
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_35DXYYM7SVWXGTXR` 设备条目签发、设备列表与公开资料
  - relates: ["REQ_20260918_5A7WDB3CTV03FJTW", "REQ_20260918_H0XN0536A5P1WZVG", "REQ_20260918_VWTKG0AZG2084W8K", "REQ_20260918_Z1P2TGCMD0K4W1XP"]
  - depends_on: ["TASK_20260919_YGJ521XV59FBBJ1R", "TASK_20260919_YNE0A885R3XZ7TV2", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 设备条目由用户私钥签发，新设备加入后本人其他设备在下次连通时显示该加入记录，且使用一个刻意不发布加入事件的客户端加入时仍因设备列表版本变化而告警；设备别名由持有者设置：改名必须重签设备列表，接口上没有「改别人的别名」这回事（界面不提供入口那一半归通讯录同步任务执行，与联系人侧可见性同处）；用户别名与头像只有本人能改，超过体积上限的头像被拒绝并给出上限值，两者在本人全部设备间一致（联系人界面不提供修改对方别名或头像的入口那一半同归通讯录同步任务）；性质测试覆盖签名主体错误、设备不在列表、代数回退三类伪造条目均被拒，另覆盖两条封装路径的往返与设备列表版本的单调性。联系人侧的可见性（设备列表、设备别名、用户别名与头像）归通讯录同步任务执行
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_5VV5HJ3Y1E8W1X4M` 用户私钥的持有范围与同步
  - relates: ["REQ_20260918_R1J6B5CH21YFFP32", "REQ_20260918_ZVSZ6JT217MBK53K", "REQ_20260918_P2MVJR9GAY96T95W"]
  - depends_on: ["TASK_20260919_YGJ521XV59FBBJ1R", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_5NM8Z6W23J25315B", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_WPP64DJDH6659YMQ", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20261030_GR1G47QADRQWBZK8"]
  - verify: 针对原缺陷修复：旧UserKeyOffer曾在接收端确认前传输并落盘种子，holds_user_key为false不能证明尚未取得材料。改为指定目标与尝试标识的双端确认；确认前仅交换元数据，种子只在两端确认后封装给该次尝试的目标设备；拒绝/过期/替换清除接收端临时解密材料，旧确认不批准新尝试。按Quint轨迹逐步对拍真实协议、实际密钥可解性、事务与重启，而不只对拍API开关。原验收仍保留：只有用户指定的设备持有用户私钥，同步只能在已持有它的设备上发起，两端确认经加密通道完成；{need}`UC_20260918_5HZ2CN981MYF8K32` 确认未持有用户私钥的设备签不出任何条目；「添加联系人入口不可用且界面说明应去哪台设备」由邀请与应答任务的同一用例执行，此处只核对结果；{need}`UC_20260918_16MT9NTZHN35GKP3` 确认未持钥设备不能签发吊销；私钥全部丢失时按规格给出的处理路径在界面上走得通并如实说明后果
  - evidence: [私钥同步、封装与恢复契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_ZAW3PVG8Y2WQTQ4T` 历史同步：加入基线、发起方与断点续传
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20260919_YGJ521XV59FBBJ1R", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_5X1SXR1ZNC2XQAWC", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260925_MYXS26S7V4AZWBES"]
  - verify: {need}`UC_20260918_TKY1NS0PJ4HTM0D5` 确认新设备默认只读到加入基线之后的消息；新设备上不存在任何单方面拉取历史的入口，只能在持有历史的设备上发起并由用户选择聊天会话范围；{need}`UC_20260918_1GN7QPDQ3PZ3D842` 按验收规模执行——同步一万条消息、中途断网并重启双方，恢复后目标设备该范围的消息集合与源设备逐条一致，无重复、无缺失；同步内容与本机游标和墓碑合并时墓碑优先，已决区间的连续性在合并前后都成立
  - evidence: [历史推送许可、恢复与合并契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20260919_WPP64DJDH6659YMQ` 设备吊销及其边界
  - relates: ["REQ_20260918_9YNQV83FP0G2FV42", "REQ_20260918_PYQVKZPN5ZS9HWSA", "REQ_20260918_214C96FXNAE7W0G6", "REQ_20260918_DCNS38550F4AY1K5"]
  - depends_on: ["TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_5NM8Z6W23J25315B", "TASK_20260919_20WC4ZWNSZY85QWY", "TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: {need}`UC_20260918_RKTCTTNNZ6CJV5N5` 确认被吊销设备无法收发、其消息被拒绝，且它无法解出吊销之后产生的消息；吊销声明的签名可用用户公钥验证，篡改其中的设备公钥或时间戳后验证失败，安全码不变；用被吊销设备构造自称创建时间早于吊销的消息并在接收方已收到声明后投递，该消息被拒绝，而声明之前已接受的该设备消息仍可读但在界面上带标记；吊销确认界面区分被吊销设备是否持有用户私钥两种情形、不提供任何声称能远程擦除的入口；本人其余设备把该设备签发的未过期邀请显示为不再受理并提示重新生成与撤下张贴；{need}`UC_20260918_9QFKPPPJVQKK9DRE` 一并执行
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_80NB4PK0KR53XJVS` 常在线设备与密钥材料的日志边界
  - relates: ["REQ_20260918_GBC9KZAACYZ1RHYZ", "REQ_20260918_865PCSHV46Q4X2XF"]
  - depends_on: ["TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: **本期由长期运行的桌面进程承担常在线职责**（用户 2026-09-24 定）：桌面应用在本人其他设备全部离线期间接收并保存消息，其他设备上线后经本人设备间同步取得这些消息；yochat-node 不链接任何界面依赖在构建期成立，无界面形态承担该职责**分期延后**，见计划的待确认事项；部署说明与设置界面都包含「该设备持有可解密的密钥材料」这一说明；注入已知标记值后以最高日志级别完成一次完整收发并构造一次崩溃，在各级日志与崩溃输出中检索本地数据库密钥、设备私钥、用户私钥、恢复短语与会话密钥材料，一处都不命中
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

### 好友与通讯录

- [x] `TASK_20260919_5NM8Z6W23J25315B` 邀请的生成、有效期与作废
  - relates: ["REQ_20260918_2276A5JCK1KGNS1A", "REQ_20260918_37F049FH3B007X48", "REQ_20260918_6J1EMT8Y9HZ68STG", "REQ_20260918_F53PQ4FB8VT4SCV9"]
  - depends_on: ["TASK_20260919_YGJ521XV59FBBJ1R", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 在不持有用户私钥的设备上生成邀请的入口不可用且界面说明应去哪台设备；邀请由用户私钥签名，改动载荷任何一个字段后验证失败；每份邀请带专属一次性公钥，邀请记录的本机数据模型中不含任何可同步字段承载该私钥，作废后覆盖该私钥的存储且另一份邀请不受影响（「该私钥不出现在本人其他设备的任何数据中」须在真有设备间同步的地方验，归通讯录同步任务执行）；邀请各自独立、有有效期、可随时作废；备注超上限时提示而不截断；公开张贴与定向邀请的代价文案按规格逐字核对；载荷按定长两字节 TLV 与 base64url 编码，解码器对截断、超长与未知 tag 有性质测试。应答侧的处理（不符账号进待确认并带标记、长期公钥加密的应答不被接受、过期或已作废邀请的应答被拒）归验证顺序任务执行
  - evidence: [邀请签名、私料与编码契约](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--architecture)
- [x] `TASK_20260919_QDJR74RRME6FVXED` 渠道绑定与本方渠道身份的保存复用
  - relates: ["REQ_20260918_YP7HYFDC5BSKK1BP", "REQ_20260919_757FNZE6B8FN15YV"]
  - depends_on: ["TASK_20260919_5NM8Z6W23J25315B", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 除渠道为「当面」外，不填写本方渠道与账号时无法生成邀请或应答；选「当面」时账号可空、界面不提供定向邀请选项，也不把「无法比对」显示为相符；应答界面与确认界面都展示对方的用户公钥与其声明的渠道与账号，并含「回到该渠道比对发件账号」的提示；生成界面始终显示本次将写入的渠道与账号；在设置中新增、修改、删除渠道身份后生成界面可直接选取、无需重新输入；解码本机生成的邀请与应答 TLV，确认其中不含已保存的渠道身份列表与本次对应记录（这两类载荷由人复制粘贴、不经网络，抓包核对无效），关系建立后的同步流量另行抓包核对；{need}`UC_20260919_S6AZ7VM94X9QFR3K` 与 {need}`UC_20260918_3XJNK8EN35CX2NZ0` 各执行一次；渠道标签按双向可比的规则归一化，用例覆盖大小写与别名形式
  - evidence: [渠道绑定与隐私范围](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--architecture)
- [x] `TASK_20260920_F5MDP1BS4S20XGC4` 应答的验证顺序与否定用例
  - relates: ["REQ_20260918_GXF8WYYMWT40CFSM", "REQ_20260918_37F049FH3B007X48", "REQ_20260918_6J1EMT8Y9HZ68STG"]
  - depends_on: ["TASK_20260919_5NM8Z6W23J25315B", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: {need}`UC_20260918_EQC87D19YBE70P1J` 确认应答既不能改换作者也不能换到另一份邀请上，且**拒绝发生在验签步骤**而不是靠事后比对；{need}`UC_20260918_BCNK4Q16YB2HVH8Y` 确认改换作者的应答被拒绝；除发起方外任何人都无法从应答中读出应答方的用户公钥、设备公钥与渠道账号；用长期用户公钥加密的应答不被接受（{need}`UC_20260918_09843YX1S2DDR8P6`）；过期或已作废邀请的应答被拒；第 7 步账号不符时不丢弃而是进入待确认状态并带不符标记；以 proptest 检验验证顺序在任意字段被破坏时的行为，以及关系状态机在任意动作序列下不进入未定义状态
  - evidence: [应答认证与验证顺序](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--contracts)
- [x] `TASK_20260919_GWD9N0YMH757CHCV` 应答、双方确认与关系建立
  - relates: ["REQ_20260918_A1MZYZ7JV43G7EJ3", "REQ_20260918_1VXJQ68HXHM0JR0Q", "REQ_20260918_KWW858WDBR49C9KS", "REQ_20260918_2276A5JCK1KGNS1A"]
  - depends_on: ["TASK_20260919_5NM8Z6W23J25315B", "TASK_20260920_F5MDP1BS4S20XGC4", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 在不持有用户私钥的设备上生成应答的入口不可用且界面说明应去哪台设备；{need}`UC_20260918_RC0CZ2W71EG8BJ0H` 确认同一份邀请被多人分别应答时各自独立成关系；对方尚未应答时通讯录不出现该联系人；在非签发设备上粘贴应答时界面说明应去哪一台；账号不符的待确认项在列表中出现且不符标记在确认按钮旁可见；应答方自发出即完成本方确认，B 在收到设备组加入操作时转为已建立、收到前不发消息；有效期加宽限之后 B 侧降级且 A 侧确认入口关闭、两侧文案都是「已过期」而不是对方行为的断言，并提示重新交换且提供重新交换入口；A 在有效期末确认而加入操作迟到时 B 识别为过期交换并提示重新交换，而不是当作陌生对端静默丢弃；A 在阈值内收不到 B 的**任何**操作时提示这次交换可能未完成并提供重新交换入口；拒绝一份应答时抓包确认不发出任何 YoChat 层面的信号，包括不发拒绝回执
  - evidence: [邀请、应答与确认契约](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--contracts)
- [x] `TASK_20260919_5AB9BSEC7J2FBZP2` 入站丢弃与关系建立后的设备授权
  - relates: ["REQ_20260918_HYREM42TB44CYAHF", "REQ_20260918_2PEVDK1HG1EK3KSW", "REQ_20260918_WEXAYYYH18M9XH5Y"]
  - depends_on: ["TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_WPP64DJDH6659YMQ", "TASK_20260919_20WC4ZWNSZY85QWY", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 「在不持有用户私钥的设备上既不能生成邀请也不能生成应答、界面各自说明应去哪台设备」由邀请与应答任务的同一用例执行，此处只核对结果；无匹配记录的入站一律丢弃且不产生响应，被丢弃的数量在诊断信息中可见；对「从网络接受入站邀请或应答」写一条否定测试，确认这条路径不存在；来自邀请所载公钥之外的消息一概不接受；设备列表验签的三条否定用例各执行一次——验证不通过即整份丢弃、替换其中任一设备公钥后接收方拒绝、合法签名搬到另一份邀请或另一个用户或另一台设备上复用同样拒绝；联系人增加设备、吊销设备、更改别名或头像后本方验签通过即直接接受，界面不要求重新比对安全码，伪造声明被拒；首次收到对端设备列表而其中不含交换期间那台设备时，把它移出并告警
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_20WC4ZWNSZY85QWY` 安全码、校验状态与联系人刷新
  - relates: ["REQ_20260918_FXAWEFJ6Q0S22E5S", "REQ_20260918_XS6GKW2ZZ4R1CS6Y", "REQ_20260918_GG1H42XAJXA0M4ZT"]
  - depends_on: ["TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 安全码按 HKDF-SHA256 导出 128 位、39 位数字分两段，同一对用户在各自设备上看到相同的安全码；测试向量由**独立实现**（另一语言或参考 HKDF 库）按[好友交换](../../../engineering/contact-exchange.md)的安全码字节级参数与独立向量计算并按 refine 写回设计，本实现与之逐位相符；对方增删设备后本方安全码与已校验状态不变；把本地记录的对方用户公钥替换掉之后，本人任一设备都能发现已校验状态不再对应当前公钥；界面在引导比对时提示换一条渠道，两人各自被指示念出不同部分且两部分合起来覆盖完整安全码，任一部分核对不上都不得记为已比对；{need}`UC_20260918_W1HT3Y7AX0SR0KSD` 与 {need}`UC_20260918_Z54B0RETFN8ZTP8Z` 各执行一次；已建立关系的联系人经另一份邀请再次应答时作为新的待确认项出现并标明来自另一份邀请，不自动改写既有记录；删除后重新添加不继承校验状态，界面明确提示这是新建立的关系
  - evidence: [安全码参数及独立测试向量](../../../engineering/contact-exchange.md#DOC_20260918_T09EDKCAGT4Q75JT--architecture)
- [x] `TASK_20260919_FMT2MTRTWPK5HMG5` 拉黑、解除与其可推断性的如实说明
  - relates: ["REQ_20260918_ZWMFKB381M0AKCYN", "REQ_20260918_TW7PP68XQAKBAVYH", "REQ_20260918_J22VY0VTZVRBY1P6"]
  - depends_on: ["TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 拉黑后对方消息既不接收也不落盘，本方不向其发起连接、不对其任何数据产生 YoChat 应用层出站响应，向被拉黑者发送在本方被拒绝并给出明确原因；{need}`UC_20260918_115YW7002JKNKA7C` 以持续投递核对拉黑期间本机存储增量为零；解除拉黑后三条分支各核对一次——上限之内自动按原顺序出现、超出重发上限时界面说明需对方批准或改走历史同步且会话不停留等待、对方已删除的部分收到可验证的不再提供声明并在界面标明原因；拉黑确认界面包含可推断性与「绑定当前公钥、对方可换密钥重新应答」两条说明，且不提供任何声称能完全隐蔽拉黑或永久阻止某人的选项
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260919_PCRNAZQF6Z5JPMDY` 删除联系人及其与拉黑的关系
  - relates: ["REQ_20260918_F7ZBKSBAC1N4KM2K", "REQ_20260918_SD1XKGXS4DBG5ZTE"]
  - depends_on: ["TASK_20260919_FMT2MTRTWPK5HMG5", "TASK_20260919_5V9YG7MWWE8TYWAW", "TASK_20260919_3MVCZ37H78S789VG", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 删除联系人时询问是否一并删除历史：选保留时该会话消息仍可被搜索到，选删除时这些消息在本设备上不再可读；本人其他设备上该联系人同样消失，但其历史在各自确认该删除指令之前仍然完整；删除确认界面包含关于被删除方可观察现象的说明；{need}`UC_20260918_X4V8TN0GEQC3EVDV` 确认拉黑并删除后仍挡住新的应答，该条目在拉黑名单中可见并可移除
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)

- [x] `TASK_20261101_ZAT00B2DANEYHYM4` 多设备送达确认不提前撤销其他设备的有界兜底补发
  - relates: ["REQ_20260918_1F7X3ZHJS4N07TRG"]
  - depends_on: ["TASK_20260919_1BBHDZ61GTE9MGAG", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 在主要multidevice模型细化两个收件设备的确认和待补发集合，任一确认不能清掉其他设备义务；每连通纪元固定补发预算耗尽即停止且保留未完成项，重连后重新核对当前授权。旧任一确认撤销全部义务须有反例；完成前置模型后，以真实SQL/重启/故障回滚对拍，再接入实际签名/加密收发，覆盖A先确认而D关系记录后到、D随后由发送方补回且去重。界面仍在任一对端设备确认后显示已收到，不能把补发清单当作界面未送达计数；固定SQL期限、删除及吊销后的清理另验。
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)

- [x] `TASK_20260919_5V9YG7MWWE8TYWAW` 通讯录在本人设备之间的同步与私有字段
  - relates: ["REQ_20260918_1F7X3ZHJS4N07TRG", "REQ_20260918_0P17VCWKG2FZ26SZ", "REQ_20260918_CE3T62ZJNE2FKYKE", "REQ_20260918_5A7WDB3CTV03FJTW", "REQ_20260918_Z1P2TGCMD0K4W1XP", "REQ_20260918_VWTKG0AZG2084W8K", "REQ_20260918_37F049FH3B007X48"]
  - depends_on: ["TASK_20261101_ZAT00B2DANEYHYM4", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_FMT2MTRTWPK5HMG5", "TASK_20260919_20WC4ZWNSZY85QWY", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 先在主要multidevice Quint模型检查保护性合并、旧解除/删除重加/旧校验乱序与公平有限批次收敛，并以真实SQL/重启/故障回滚逐状态对拍；之后经本人私有通道验收实际收发，不以存储原语或抽象批次替代产品同步。在设备 A 上添加、拉黑、完成校验或修改备注后设备 B 连通后显示相同状态；合并规则按两条冲突用例核对——不含拉黑的旧记录不覆盖已拉黑状态、删除后重新添加的联系人不继承删除前的校验记录；本方渠道身份表一并同步；在本人两台设备都完成一轮通讯录同步之后，检索另一台设备的全部数据，确认邀请的一次性私钥一处都不出现；好友别名、备注与分组只在本人设备间可见，对端抓包确认这些字段不出现，联系人无法得知自己被分在哪个组、也无法得知存在哪些组；按好友别名、好友备注、对方用户别名三者任一均可检索到该联系人；通讯录与聊天界面以人为单位组织、不出现按设备拆分的条目，联系人详情中可查看对方的设备公钥与设备别名，本方增删设备后对方无需任何操作即可继续正确收发；本人更改用户别名、头像或设备别名后联系人在下次连通时看到新的；公开资料同版本不同有效签名按相反到达顺序均提示冲突，取消/重启保留，确认绑定当前冲突集合，本人选用并签发严格更高版本后收敛，迟到旧原件不复活冲突，联系人界面不提供修改对方别名或头像的入口，也不提供修改他人设备别名的入口；对端直投与通讯录私有同步之间**不实现任何顺序保证**：关系记录后到时那台设备按无匹配关系丢弃直投，由发送方兜底重发补回；测试覆盖两种到达顺序，并确认记录后到的那一路最终收到该消息

  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)

### 聊天会话与消息

- [x] `TASK_20260919_5X1SXR1ZNC2XQAWC` 会话与消息的落盘：事务边界与崩溃安全
  - relates: ["REQ_20260918_R5JVF7M78YZQKN8H", "REQ_20260918_C2TDVVB9MGCY30KM"]
  - depends_on: ["TASK_20260919_YGJ521XV59FBBJ1R", "TASK_20260919_MN1FTNNX11BEA7GQ", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 状态推进与待执行效果同事务落盘、效果提交后才执行，在每个提交边界杀进程重启后结果与无崩溃执行一致；定时器按（实例，等待种类，等待对象，授权代数，尝试序号）派生的稳定标识幂等、到期事件按同一标识去重、尝试序号随状态同事务落盘，崩溃重放后同一次等待不会起出两个定时器；{need}`UC_20260918_8MYKR8N04GFEQAW3` 确认效果重放不产生第二次可观察结果；{need}`UC_20260918_NNAV2ED8GE3QW75V` 与 {need}`UC_20260918_YXA72YMB87ZEXWKN` 分别以注入写失败与读失败确认故障不被翻译成语义答案、读不到不被伪装成不存在；存储层用 proptest-state-machine 与内存参考模型对拍；{need}`UC_20260918_Q84RX0RQ8JQP6NVF` 确认状态序列化往返一致；消息默认永久保存，设置中不存在保留期或自动删除开关
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_N0BEHW57ZGWS5B4A` 端到端加密、棘轮串行与对端准入
  - relates: ["REQ_20260918_DCNS38550F4AY1K5", "REQ_20260918_RYGFPP529TDA9R6X", "REQ_20260918_T6HDW9X9ZSAMECDT"]
  - depends_on: ["TASK_20260919_5X1SXR1ZNC2XQAWC", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 在传输路径上抓包，捕获的数据不含明文正文（「设备被吊销后无法解出此后产生的消息」归吊销任务执行）；逐字段核对信封只暴露[消息接口契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--contracts)的明文清单所列字段；棘轮与加密组用 p2panda-encryption（原型只说明参考实现行为，实际协议另由真实密码回归验证）；强制并发的性质测试确认同一条棘轮上的效果串行、提交时校验版本，不出现同一代密钥被用两次或一次推进覆盖另一次；{need}`UC_20260918_98Z0HQG3F9YRDASM` 以注入提交失败确认棘轮状态与操作不会分离；由陌生用户发起消息时设备不存储任何内容也不产生 YoChat 应用层出站数据；应答方在等待期内接受来自被应答邀请发起方的消息、等待期过后一概不接受，两种情形各投递一次；构造自称创建时间在未来与远早于实际的消息各一条，确认不改变任何安全判定、不被排到与序号不符的位置，且界面在自称时间与本机接收时间明显矛盾时给出提示
  - evidence: [真实密码与消息接收契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--contracts)
- [x] `TASK_20260919_5FAQYYAJQXERDQHA` 发送与接收的基础路径与送达状态
  - relates: ["REQ_20260918_JW13382752N6T1XR", "REQ_20260918_Y1C61561WBX7BTNE", "REQ_20260918_N2K56BGH73GX0YCB"]
  - depends_on: ["TASK_20260919_N0BEHW57ZGWS5B4A", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 发送一条含换行与表情字符的消息，对方会话中按序出现且内容与原文逐字符一致；对方全部设备离线时该消息停留在已写入本地的状态，界面不报错也不丢失它；发送后立即崩溃并重启，该消息仍在且状态不变；对方离线七天后上线，期间发送的全部消息按原顺序到达且内容完整；对方收到并落盘之后转为已收到；「任一台设备即可、不等其余设备」与另一设备合法补齐不计超限两项同时成立，豁免按设备记（已定）；跨设备观察归多设备同步任务执行；界面上不存在任何已读或已查看一类的状态；效果出站前重新检查授权代数——目标仍是联系人、未被拉黑、目标设备仍在设备列表、所引用的消息未被删除——任一不成立即丢弃该效果并记录；**p2panda 在此接入**——消息以 p2panda 的操作与追加日志形式产生与编码，由网络接缝按字节承载（接缝本身归 {need}`TASK_20260919_P2HD03NJRM84P241`，它之上的格式归本任务）；核对出站字节确为该格式、且接收方能据日志序还原发送序
  - evidence: [真实密码与消息接收契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--contracts)
- [x] `TASK_20261030_DP7DTJ5BANC8NBG7` 显示顺序与稳定分页键的Quint前置模型
  - relates: ["REQ_20260918_537BK4VME23Y2GTW"]
  - depends_on: ["TASK_20260919_PFGXDHF5JWV01TRJ"]
  - verify: quint verify 两作者各三条消息、任意接收时间/到达顺序、重复和重启下，同作者显示序按编号递增、真实接收时间保留、已有分页键稳定且游标前后分区无漏无重；正常安全与公平投递后最终完整性质通过；仅接收时间排序、缺前邻居或缺后邻居守卫分别产生预期反例，导出固定轨迹供真实SQL与Rust逐步对拍
  - evidence: [现行统一排序与分页契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)
- [x] `TASK_20260919_S8VPFC18M5CTXY2Z` 按序应用、缺失检测、去重与乱序等待
  - relates: ["REQ_20260918_537BK4VME23Y2GTW", "REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_S02F3SXHZY2DHA87", "REQ_20260918_BXC7RNH3YCQZ9BQY"]
  - depends_on: ["TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20261030_DP7DTJ5BANC8NBG7"]
  - verify: {need}`UC_20260918_QNQ99NQRGB0AFCX2` 与 {need}`UC_20260918_RXST1YTA61GFAP9V` 作为 proptest 性质执行，序列含任意到达顺序、任意重复投递与任意次中断重启，并构造超出乱序与缺失上限的序列确认触发通道重建而不是跳过；{need}`UC_20260918_PDT6X1THCWW6BQDX` 的协议部分在此执行——缺失到达后等待自行结束；其界面部分归主界面任务；Quint 模拟器导出的轨迹由回放器逐步喂入真实 `step`，逐步比对应用游标、已应用集合、待发送集合、墓碑集合与待确认删除指令队列；{need}`UC_20260918_NYXWNN6DAMM0G4KD` 在此执行——含「操作已到达而内容尚未解开」的那一类，确认它不算已决而是使本机进入等待
  - evidence: [现行统一排序与分页契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)
- [x] `TASK_20260919_1K6Y3YJ2HC3RNNBK` 通道重建及其频率上限
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_DEM5TY7V4XDCPWGJ", "REQ_20260918_Y7C40JC6A0EWEDT5"]
  - depends_on: ["TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260923_Y2FCJZZXAPNAADXV"]
  - verify: {need}`UC_20260918_6XHT4H4D84PPNSW0` 确认无法解开的消息经重建恢复且一条不少，另对「超出容忍上限的缺失」这一触发情形单独跑一次；换代之后对端在旧一代上加密、收方解不开的那些位置，由对端在新一代重传之后经恢复通道发出签名的作废声明，收方按「已在新一代重发」放行（用户 2026-09-25 定，见[消息](../../../engineering/messaging.md)的「旧一代上那些位置怎么了结」）：两种触发各一条端到端，确认内容一条不少、这些位置不显示为缺口、不再发出重发请求、不再触发重建，并确认伪造的同类声明被忽略；`models/cursor.qnt` 的放行原因补上这一种，门槛所列不变量仍然成立；重建次数上限**按滑动时间窗**计（规格已定，非待定项），{need}`UC_20260918_HF5AQ53HZHT6ZK0N` 以内存、CPU 与存储增量为观察量确认持续注入不拖垮应用；达到上限后界面给出「对方发来的数据持续无法解开」的提示，并提供立即重试与暂停该对端两种当场可执行的动作；停止注入并等到下一个时间窗后系统自动重新尝试并在成功后撤销提示；暂停期间显示带原因的等待状态而不是「对方没有说话」，已落盘消息一条不少；对方离线导致无法完成重建时才告知用户，上线后自动补齐；{need}`UC_20260923_T3FEK0Y3RZ0P4KAY` 在此执行——让一台设备保留设备私钥而清空本地库，使其出站游标从零重来，确认对端判为分叉、如实告知并转入重建，既不静默覆盖也不当作重复丢弃
  - evidence: [重建窗口、提示及恢复边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20260919_RG3BWW88MEQ85YW6` 重发请求与被请求方的应答
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_Q15AKFP42V04BVM3", "REQ_20260918_9MKG1QMBXQAG6C12", "REQ_20260918_XMX889MKAJ4QK8JX"]
  - depends_on: ["TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_3MVCZ37H78S789VG", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260923_PSKJ7VBW8CVAH8N5"]
  - verify: 「对端两台设备时，任一台收到并落盘即转为已收到」与「另一台从未收到过该消息的设备来补齐时**不被计入超限**」**两条一起观察**——按用户记豁免会使它们互相抵消，而豁免集合与重发请求都在本任务里才存在（前一条的送达粒度已由多设备同步那条任务单独验过）；制造一次持续的乱序缺失，接收方在其设定时限之后自动发出重发请求并在收到后按顺序应用，界面不弹出提示；缓冲区达到上限时接收方请求重发而不是丢弃已缓存的消息；{need}`UC_20260918_YX0YE0QZHB8PXZC6` 在被请求方两台设备对同一批删除指令作出不同决定的情形下执行，确认重发按全部设备的并集提供、无法提供的有可验证的明确答复；另核对三种情形——收到更高版本设备列表且有未答复新设备时退回暂不可得、已标记不再提供的位置由本人另一台设备同步到达时仍被接受、声明在消息本身未到达时投递仍能处理；伪造的「无法提供」答复通不过验签；由对方请求重发时，已确认的消息仍能被重新送出，已删除的位置给出可验证的不再提供声明
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260919_1BBHDZ61GTE9MGAG` 兜底重发、入组基线与大范围请求的批准
  - relates: ["REQ_20260918_W5CABH0H2PAJ2TAG", "REQ_20260918_MEK3HV5ENZDAE9SS"]
  - depends_on: ["TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: {need}`UC_20260918_63BZAJW187MFPWVK` 确认接收方完全未收到时由发送方在其时限之后自动重发且不产生重复；兜底重发按模型定下的有界口径受限且与离线七天补齐的承诺不冲突；新设备请求基线之前的范围被拒绝且未取得任何内容、请求基线之后的范围正常取得，拒绝不使该设备卡在基线处、它能继续接收并应用基线之后的消息
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20261030_PAPNKFDC82ZMKS73` 独立成员的确认者视角与多来源Quint模型及Rust对拍
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_1JBHWEVA74PZKKSQ"]
  - verify: quint verify 正常模型检验加入/显式移除事实保留、每位确认者的成员视角、仅继承加入来源当时已知历史、重复原件不扩张历史、旧欢迎不复活显式移除及不同建组来源拒绝；公平供应下全部事实到达并使有效视角收敛；删除来源/错继承本机历史/迟到加入复活/重复扩大历史/错误建组来源五个对照有对应反例。固定轨迹驱动实际AckedGroupMembership接口和序列化重启逐步比对全部观察者；此项不代替实际密钥转交和线上切换
  - evidence: [成员事实与历史知识边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20261030_B32Y29KZGWY52EJV` 独立成员的密钥转交与确认棘轮Quint前置
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_PAPNKFDC82ZMKS73"]
  - verify: quint verify 在明确移除C和另一路迟到Add并发下检查不向C转交当前链、实际确认被记录、有效成员按确认因果顺序更新棘轮；公平供应下有效成员最终跟上。撤掉转交守卫、丢移除确认、打乱确认顺序三项对照各有反例。真实DCGKA回归与固定模型轨迹逐步比较转交目标、确认者视角和实际密钥解密，不能仅比较模型计数
  - evidence: [成员密钥、恢复原件与实际协议边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20261030_8AFPXVWJPSERSDAG` 共享一次性预密钥与旧组快照Quint前置及Rust对拍
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_B32Y29KZGWY52EJV"]
  - verify: quint verify 检查设备级一次性私料至多使用一次、组快照之后发布的新密钥可用、旧快照只在其生成上界内补记消耗且不恢复旧私钥、密钥与组状态/原件同事务提交、崩溃重试和公平处理下合法请求最终完成；过时快照、遗漏旧消耗、错删未知新密钥、恢复旧私钥、保留组内私料副本和分开提交六个对照各有反例；升级时全局消耗与全部组副本清理同事务完成，之后落盘不再复制一次性私料。固定轨迹驱动实际KeyManager与存储事务，真实X3DH验证初始化后新密钥可用和跨组重复私钥拒绝
  - evidence: [设备私料唯一归属与可靠公告](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20261030_WZ83SVEFVPEDDKPJ` 吊销声明按用户归属隔离的Quint前置模型
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_WSG175RHG9NHXPD0", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 两用户同设备，设备主动签给两用户也不假定唯一归属；用户负面声明不能替代设备归属证明，不抑制其他用户的有效授权。模型含归属证明与吊销乱序、重复、准备/提交/崩溃，归属证据在吊销后保留，原件首次接收时间不被重放更新；安全和公平提交下最终完成检验，五项撤去守卫对照必须产生反例。导出正常轨迹及移除、跨用户声明、双重主动绑定的可达见证，给出真实签名对象、SQL事务和重启的映射；实际Rust对拍与欢迎历史错用户签名移除回归归属依赖本结论的GSW6实现任务。历史无可验证归属证据时拒绝仅靠任意用户负面声明移除，不能编造归属。
  - evidence: [用户局部吊销及历史正向绑定](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--contracts)

- [x] `TASK_20261030_HJ35GF3WY0Z6CF3X` 历史吊销补证与确认的Quint前置模型
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_WZ83SVEFVPEDDKPJ"]
  - verify: 既有裸吊销补充设备归属原件时重新等待确认；确认绑定补证摘要，旧裸原件确认不能停止补证；接收端证明/吊销同事务保存后才确认。模型含重放、丢包、崩溃、旧确认迟到与公平执行下最终完成；三项撤去守卫对照必须分别产生反例。导出正常及完成/旧确认可达轨迹，定义实际SQL、签名载荷和应用可靠投递的事件映射；依赖本结论的GSW6实现继续负责真实对拍及桌面新设备欢迎验收。
  - evidence: [用户局部吊销及历史正向绑定](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--contracts)

- [x] `TASK_20261030_M7M0CME5QMG60C3S` 丢失组状态后的二方通道与作者链重新接入Quint前置
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_1JBHWEVA74PZKKSQ", "TASK_20261030_B32Y29KZGWY52EJV", "TASK_20261030_8AFPXVWJPSERSDAG"]
  - verify: 在已有重新接入模型中细化两位观察者与丢失状态目标的二方通道；确认旧通道不可直接续用、每次恢复至多重建一次、通道、目标作者链起点与尝试标识同事务持久保存，可靠重试下双方最终可解新欢迎/转交。故障注入清空目标组内状态、丢包和崩溃，并重放控制，撤去更新二方/作者链重设/逐次去重/原子保存分别产生反例。导出固定轨迹和事件映射；真实DCGKA先复现已提供新公钥包仍报UnknownSecretUsed，再由依赖本结论的GSW6实现真实换钥及对拍。只更新二方后真实AEAD仍不能解目标新正文，细化模型再检作者链起点；该结论不证明旧控制前缀、完整消息棘轮或产品恢复。
  - evidence: [成员密钥、恢复原件与实际协议边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261031_PDPV2Y8HTTCGGY31` 首次欢迎之前的成员协议版本选择Quint前置
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_M7M0CME5QMG60C3S", "TASK_20261030_8AFPXVWJPSERSDAG"]
  - verify: 在主要独立成员模型中细化首次欢迎之前的版本选择；正文和非入组控制不能确定版本，只有当前授权且真实密码处理成功的欢迎可确定，已建立状态不能被另一版本欢迎切换。版本、一次性密钥消耗、缓冲分路与原件同事务提交，失败与重启不丢待处理原件；另一版本控制须明确保留为不能处理，不得当成已处理。quint verify 注入欢迎延迟、准备后崩溃和重放，正常安全性质与公平重试下完成性通过，提前选择、重复改版、分开提交、重启丢缓冲、静默丢异版控制和接受密码处理失败的欢迎六项对照有反例；导出固定轨迹及映射。签名/授权/密码结果作为输入前提，真实Rust运行态对拍与桌面接线由依赖本结论的GSW6承接。
  - evidence: [成员密钥、恢复原件与实际协议边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261031_E3GTXDN2C3TSW1YC` 重新接入期间旧控制前缀退役的Quint前置
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261030_M7M0CME5QMG60C3S", "TASK_20261031_PDPV2Y8HTTCGGY31"]
  - verify: 在主要独立成员模型内建模作用域和当前授权绑定的新链检查点、实际旧控制原件及成员事实保留、前缀/新链/尝试标记原子安装；quint verify 正常安全性质与公平供应下完成性通过，旧密文必须先可解、忽略旧成员事实、错误作用域、缺原件扩大前缀、接受失败新链、分开提交、漏存尝试标记、迟到旧控制重设新链和授权失效后安装九项对照均有反例。导出固定轨迹及事件映射；真实session先注入B换钥控制延迟再让C丢状态重入，复现UnknownSecretUsed及失败Protocol回滚；依赖本结论的GSW6继续负责实际前缀/成员事实/密码边界实现与Rust逐步对拍，不将模型检查当作恢复产品验收。
  - evidence: [成员密钥、恢复原件与实际协议边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261031_B8PHGQ7N23VJ9SKM` 一次性包按需补发与可靠序号的Quint前置
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 细化exchange.qnt既有bundleExchange为两个包方向、两组和每方向最多两个公告版本，第二组可由同一帮助者或另一端发起；quint verify 完整遍历安全性质与公平补包/欢迎供应下两组均实际安装的活性。真实Rust先复现第二组欢迎MissingPreKeys，不能把反向包可用视作常量；目标缺少确认所需反向包时持久保留已验签欢迎，补包后重试，重启不丢原件或复活已消费包。注入耗尽、丢包、乱序、重复请求/公告、旧确认、事务失败及等待后重启。相同观察序号至多生成一次，公告与私料、接收与高水位各自原子保存；关闭补包证伪活性，撤去请求去重、接收去重、确认匹配、私料原子保存、持久后确认、保留缺包欢迎、等待重启持久性及反向包消费八项守卫各有反例。导出正常、两端分别发起、等待后恢复及迟到确认见证，声明准入/密码/节流/历史私料总量和控制原件最终供应的输入边界；真实Rust回放及产品接线由GSW6承接。
  - evidence: [成员密钥、恢复原件与实际协议边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261101_FP9B6QTAH3ZV238V` 审批存取有界执行Quint及SQL对拍
  - relates: ["REQ_20260918_XBRY5Q81Z4ZPP6ER"]
  - depends_on: ["TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 先以Quint细化现有本地/本人副本审批队列；不可变请求标识和精确范围、按请求设备容量、重复不延长原到期、逐次决定、关闭/过期、重启、时钟前进/回退和事务中断均覆盖，导出轨迹与实际SQL逐状态对拍。读取/去重/编码/逐行解码与SQL共享五秒单调工作截止，故障不返回部分列表或部分提交；输入重复不得造成整份额外复制，合法范围和既有容量不改变。不新增联系人总量上限，不以随合法请求数增长的结果集合冒称常量RSS。去掉固定期限、范围绑定、失败回滚或工作截止分别有对应反例；真实SQL阻塞/大记录和中断后可继续回归通过后，再交由原GSW6验收。
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260920_GSW6Y996D2E1E681` 大范围重发请求的批准流程
  - relates: ["REQ_20260918_XBRY5Q81Z4ZPP6ER"]
  - depends_on: ["TASK_20261101_FP9B6QTAH3ZV238V", "TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260919_80NB4PK0KR53XJVS", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20261030_1JBHWEVA74PZKKSQ", "TASK_20261030_PAPNKFDC82ZMKS73", "TASK_20261030_B32Y29KZGWY52EJV", "TASK_20261030_8AFPXVWJPSERSDAG", "TASK_20261030_WZ83SVEFVPEDDKPJ", "TASK_20261030_HJ35GF3WY0Z6CF3X", "TASK_20261030_M7M0CME5QMG60C3S", "TASK_20261031_PDPV2Y8HTTCGGY31", "TASK_20261031_E3GTXDN2C3TSW1YC", "TASK_20261031_B8PHGQ7N23VJ9SKM"]
  - verify: 在上限之内的重发自动完成、用户不被打扰；超出上限的请求出现在被请求设备界面上并列出规格要求的信息，未获批准时不提供任何内容；同一请求方对同一范围在批准前反复请求只显示一条待批准项，批准之后再发起新的超限请求需要再次批准；无界面设备回应一条可验签的「需人工批准」声明，请求方界面显示为等待对方批准而不是未答复，且该位置照常放行、不阻塞其后的消息；待批准队列的上限与过期按设计执行；超限豁免**按请求方设备记**——提供方对某请求方设备的重发请求，凡落在它自己「已向该设备发出但未获该设备送达确认」的集合内的位置不计入超限，另一台设备是否已确认不影响本台
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_73T31Y3GSPKQPM9Z` 多设备同步与和自己的聊天会话
  - relates: ["REQ_20260918_WRGB8QE5N650VWDK", "REQ_20260918_Y1KSJ34NKQH17Z08", "REQ_20260918_N2K56BGH73GX0YCB"]
  - depends_on: ["TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 用 multiremote 同时驱动多个应用实例（架构篇验证节所定）：在设备 A 上收发的消息在设备 B 上出现且顺序一致，三台设备下任意两台先收到都收敛到同一结果；{need}`UC_20260918_PSGHZ6HXEYJRTGES` 确认 B 全部设备离线七天后上线时 A 的 N 条按原顺序到达、B 与自己的聊天会话在其全部设备上收敛到相同内容与顺序；与自己的聊天会话同样具备缺失检测与顺序保证，并在界面上与其他聊天会话使用相同的交互；对端有两台设备时，任一台收到并落盘即转为已收到、不等其余设备
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)
- [x] `TASK_20260919_EHW3KX9RB30XHQQP` 删除消息：本地效果与原文保留的边界
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV", "REQ_20260918_F9J932YWK907YPY7", "REQ_20260918_XMX889MKAJ4QK8JX"]
  - depends_on: ["TASK_20260919_5X1SXR1ZNC2XQAWC", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 单条、按会话、按时间范围三种删除方式各执行一次；批量删除执行前给出的条数与时间范围由**与执行同一个取值**算出，因而与真的删掉的条数一致（界面呈现那一半归主界面任务执行）；**用正确的密钥打开本地数据库之后**在消息表、全文索引与全部引用摘要中检索该消息原文均不命中，并以一个只写删除标记而保留正文的对照实现跑同一检查、要求它失败；该消息的标识仍在，不会在后续同步或重建重传中重新出现，也不会被当作缺失而使会话进入等待（{need}`UC_20260918_SS1JB5J70PQ8GS00`）。「由对方请求重发时已确认的消息仍能被重新送出」归重发请求任务执行，否则删除与重发会互为前置
  - evidence: [删除擦除、墓碑与原子失败契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_3MVCZ37H78S789VG` 删除的传播、逐设备确认与未送达消息
  - relates: ["REQ_20260918_Y4W1WTZE4K8TNEYD", "REQ_20260918_K54NXXBZW1R6415F", "REQ_20260918_SR68DSXCX4S5TZ3R"]
  - depends_on: ["TASK_20260919_EHW3KX9RB30XHQQP", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 默认删除后本人其他设备收到对应的删除指令；关闭传播时其他设备不收到任何指令，且被删除的消息不会被重新同步回本设备；在设备 B 上出现待确认的删除指令且对应消息仍可读，同意后按本地删除规则被覆盖，拒绝后消息保持完整且该指令不再重复出现；逐条与整批两种确认方式各走一次；伪造的删除指令被拒；{need}`UC_20260918_5CGPPXBXY0AY93K1` 覆盖另一台设备尚未决定时删除既不复活也不卡死，该性质由 Quint 检验并经回放器对拍；{need}`UC_20260918_DME4XYQWCTPPVP3B` 确认本人其余设备均已同意时对方上线后不再收到该消息，存在尚未同意的设备时删除确认界面已说明这一点并给出其条数
  - evidence: [删除擦除、墓碑与原子失败契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260919_VDH8GAR8M13PK346` 引用回复与本地全文搜索
  - relates: ["REQ_20260918_8F32S19RPRC25471", "REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20260919_5X1SXR1ZNC2XQAWC", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 搜索词命中多个聊天会话时全部返回并分别标明归属；中文按词可搜——两字词、三字词与中英混排各验一次，并附所选分词器及其下限的依据；对搜索结果执行删除时效果出现在该消息所属的会话上，且该归属由类型保证（操作只接受携带归属的消息引用，取不到「当前打开的会话」）；索引在删除与覆盖后同步更新，查不到已删除内容，并以一个只写删除标记而保留正文的对照实现跑同一检查、要求它失败。「未解锁状态下无法执行搜索」归应用锁任务执行；{need}`UC_20260918_V7DK0XN0XPWG3FZ8` 里界面可观察的那半句（当前会话的消息、草稿与滚动位置均不变）归主界面任务执行——界面在依赖图上位于本任务下游，本任务内无从观察
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260920_NVVX1PC80N53SGRZ` 引用回复的跨设备呈现
  - relates: ["REQ_20260918_WW7ZXW92D1B6NVH9"]
  - depends_on: ["TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 引用一条消息发送后，对方界面显示引用摘要；对方持有原消息时可跳转，不持有时摘要仍然显示且跳转入口标为不可用，而不是空白或错误
  - evidence: [界面行为与可见验收判据](../../../product/app-shell.md#DOC_20260918_K0ENVBAX9Y6YAXT4--requirements)
- [x] `TASK_20260919_WJJ4H5F7WSPXKERS` 接收、落盘与重发的速率与体积上限
  - relates: ["REQ_20260918_0FAYS8R0Z7A9WF3Q"]
  - depends_on: ["TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260919_1K6Y3YJ2HC3RNNBK", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 以正常人聊天的最快节奏持续收发不触发任何上限；单条体积、单位时间接收条数、单位时间落盘量、**重发与通道重建的频率**、单个对端可占用的本地存储五类上限各核对一次；另核对对端诱导动作的配额——入站重发请求与对方发起的重建按对端、按滑动时间窗有上限，超限即丢弃并计数，纪元切换超限后不再重置预算；以远超正常水平的速率与体积持续投递时应用保持可用、其他会话不受影响，被拒绝的数量在诊断信息中可见；让一个正常联系人的占用达到存储上限，界面告知对端与具体项目、会话暂停并提供至少一种恢复动作，执行后本机主动重新请求这段范围、被拒绝的消息按序补齐
  - evidence: [接收、重发及资源边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

### 界面与外壳

- [x] `TASK_20260919_0R2EC1M6896HVHT7` 主界面布局与可达性基线
  - relates: ["REQ_20260918_3AEMNVEGME49ZT2R", "REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_BXC7RNH3YCQZ9BQY"]
  - depends_on: ["TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260919_35DXYYM7SVWXGTXR", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 端到端用例按架构篇定的工具执行（WebdriverIO 加 @wdio/tauri-service，macOS 上 driverProvider 为 embedded）；逐项核对：从启动到发出一条消息、切换聊天会话、打开联系人详情、发起搜索，全程不使用指针设备即可完成，焦点可见、Tab 序正确；切换系统外观后应用随之变化，深浅两套外观各核对一遍；对比度按[界面与外壳](../../../product/app-shell.md#DOC_20260918_K0ENVBAX9Y6YAXT4--requirements)的既定基线实际测量并判定；{need}`UC_20260918_HE49NBGG4GRM5V6J` 的界面一半在此执行——解锁状态下发出一次跨全部会话的搜索，在结果返回之前锁定应用并解锁同机另一个用户，确认该搜索结果不出现在任何界面上，且新的应用会话中不存在上一个用户的会话内容、草稿、搜索词与联系人；{need}`UC_20260918_V7DK0XN0XPWG3FZ8` 的界面一半与按时间范围批量删除的界面一半移交 {need}`TASK_20260924_MRTBTT1GGHG7TETK`——本项只验「整个会话」那一种批量删除执行前显示条数与时间范围、确认后删掉的正是这些；缺失等待的界面部分在此端到端执行——等待超时后会话列表与会话内出现非打扰的行内标识、不弹模态、不提供任何会丢弃消息的入口；错误码集合手工登记，未登记的码无法通过构建，任一语言缺文案无法通过构建
  - evidence: [界面行为与可见验收判据](../../../product/app-shell.md#DOC_20260918_K0ENVBAX9Y6YAXT4--requirements)
- [x] `TASK_20260919_0R84ESC1SZ2Y0FEM` 十万条量级历史下的可用性测量
  - relates: ["REQ_20260918_3TK81N30WS2S6JWB"]
  - depends_on: ["TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260925_MYXS26S7V4AZWBES"]
  - verify: 以十万条消息的本地库测量打开会话到首屏可读、连续滚动、一次跨全部会话的搜索，各测不少于三次并记录机型、数据规模与统计口径，同时观察内存占用不随滚动持续增长；「可接受时延」的阈值须在 refine 时**独立于本次实测值**给出（依据为主流客户端同规模的公开基线或明确的体验目标），不得用本次实测值反推；阈值写回规格后在本任务内重跑同一测量并按该阈值判定通过与否
  - evidence: [十万条界面实测与版本范围](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20260919_X3YXF0F4M0KS683P` 未读计数与会话列表排序
  - relates: ["REQ_20260918_2AD55GM4Z0PTAJXX"]
  - depends_on: ["TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260923_QK7N2XVDR9MHB4TC"]
  - verify: 未读计数在多设备同步、消息删除与乱序补齐三种情形下与实际未读一致，其中补齐那一种以**真的丢掉一条上线的操作**制造，不靠构造空洞，并确认在另一台设备上读过之后两台设备的未读状态一致；会话列表排序按规格核对，并发到达下不出现抖动
  - evidence: [界面行为与可见验收判据](../../../product/app-shell.md#DOC_20260918_K0ENVBAX9Y6YAXT4--requirements)
- [x] `TASK_20260923_QK7N2XVDR9MHB4TC` 本人设备之间的恢复通道：补齐请求发得出去
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_WRGB8QE5N650VWDK"]
  - depends_on: ["TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260919_73T31Y3GSPKQPM9Z"]
  - verify: 本人两台设备之间真的丢掉一条上线操作后，chat_tick到期自动发出设备到设备恢复请求，所需长期预密钥经可靠公告可用；公告发送失败不假记完成，重试及重开不丢义务。unread.e2e.ts确认补齐无需手工泵请求、后继正文可读且补回内容一条不少地计入未读；联系人既有恢复路径保持。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260919_84943YC4AF86ZET8` 桌面通知及其内容边界
  - relates: ["REQ_20260918_G3JQAH0SXEXVDATA"]
  - depends_on: ["TASK_20260919_0R2EC1M6896HVHT7"]
  - verify: 应用最小化时收到消息，系统通知出现并可点击跳转到对应聊天会话；**默认设置下**通知文本不含消息正文；关闭某个聊天会话的通知后不再为它发出通知；{need}`UC_20260918_KZACP3YFJW0WRWBK` 确认从通知进入的是消息所属的聊天会话，并定位到那条消息、X 的草稿与滚动位置不变；通知直接调用平台的系统通知接口（官方插件在桌面端不交出点击事件）。**本期只在 macOS 上实现与核对**（用户 2026-09-24 定），Windows 与 Linux 分期延后到持续集成启用之后
  - evidence: [系统通知的实际专项与未覆盖项](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

### 隐私与防护

- [x] `TASK_20260919_W1QMH1F4CEXS7VD1` 中转与发现的实际暴露面与如实告知
  - relates: ["REQ_20260918_P7X1QGZCYA41GAQT", "REQ_20260918_QZGW3CN8BHBB0F6N", "REQ_20260918_Q0MQBNAVS1TB1PBW", "REQ_20260918_386RMXBPWJYEXT9P"]
  - depends_on: ["TASK_20260919_Q84FGP203EPHG687", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260924_823S0AQ3VBRQC778"]
  - verify: 按 REQ 验收原文核对：中转协议解封后仅见目的端点标识与时间、不含内容与参与者身份，可被观察到的目的端点标识范围与告知给用户的内容一致且不多不少；发现记录不含直接地址；若实测已促成 refine，则按修订后的验收原文核对，不以「不成立就改文档」代替判定；界面在使用前如实告知实际暴露的信息；在线状态只对持有设备公钥的人可见，用不持有该公钥的第三方视角确认取不到，且不提供任何声称能隐藏在线状态的开关
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)
- [x] `TASK_20261101_9ZM9FK8YG0K5F2HY` iroh 地址映射容量与回收竞态模型
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 在最小 vendor 补丁前用 Quint 明确全局容量、使用中映射不可回收、固定空闲期限、重复查询不续期、代数耗尽明确拒绝及旧地址不得指向新身份；容量耗尽立即失败，不建等待队列。正常安全性质与公平时钟/回收下的空闲结束性质通过，去掉容量、使用者保护、旧地址身份或固定期限各有反例；导出固定轨迹与实际 Rust 池逐状态对拍。模型只说明池的有限边界，实际 iroh 路径/actor/QUIC 连接持有、入站限额和联系人可用性由 S5A7 实现验收。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261101_M6R5V4X2J9Q7C8NP` 网络控制队列与查询取消/截止竞态模型
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20261101_9ZM9FK8YG0K5F2HY"]
  - verify: Quint 检查有限请求/对端下立即入队或拒绝、固定截止、取消/过期释放、旧 actor 清理不删除新 actor；公平时钟下所有请求终止。去掉入队、截止、容量或取消守卫各给反例，并导出轨迹与实际 Rust 等待队列对拍。模型完成后才细化下游实现。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261101_H8R6C2M9V5Q7X4NP` 映射池陌生配额与首次保留连接模型
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20261101_9ZM9FK8YG0K5F2HY", "TASK_20261101_M6R5V4X2J9Q7C8NP"]
  - verify: 在现有mapped_address主要模型中细化两槽/一陌生槽、三身份、可变已知事实与显式持有者；检查总容量、陌生分配上限、保留占用未满时新已知对象有空间、提升原子返还名额但不续期、在用引用保护及固定期限回收。去掉陌生配额、提升时续期和使用者保护分别有反例，公平时钟/维护下空闲退出；导出固定轨迹与实际Rust池逐状态对拍。模型不授予通信授权，不证明默认4096条下全进程RSS或任意调度的实时延迟。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261101_9V2DSHS7EAVG63CM` 重连批次事实投影Quint及真实SQL对拍
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 扩展现有send_scope主要模型，核对精确用户/设备重连资格与原完整快照等价，保留本人正面归属、用户局部吊销和当前关系；删除引用不参与纯连接授权，未知批次目标关闭。把他用户负面声明当全局吊销、把引用删除套到纯连接分别有反例。逐状态驱动实际SQL限定事实读取，覆盖重启、无本人名单本机规则、32条上限、共享五秒截止和无关大量记录/大别名；完成后才接net.resume_contacts。本模型不替代全局保留名单或整个S5A7压力验收。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261101_A9VDMFEFDSEEV5KB` 全局资源资格投影Quint及SQL对拍
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20261101_9V2DSHS7EAVG63CM", "TASK_20260919_WPP64DJDH6659YMQ", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 在现有send_scope事实模型细化资源投影，与旧完整事实/逐用户枚举等价；本人当前名单、各用户正面设备绑定/局部吊销、已建立关系、已确认应答的精确设备邀请宽限、本机排除及删除联系人后保留的拉黑记录均覆盖。未知期限不保留，边界关闭，引用删除不参与资源资格；负面声明不能凭空产生设备。全局吊销误伤、未知期限扩权、丢失保留拉黑、套用引用删除分别有反例。真实SQL逐状态核对保留/禁止集合及原结果；读取共用五秒工作截止，中间只逐行/流式名单，不复制公开资料、别名、备注或历史引用。输出按已验证设备集合大小计，不冒称任意合法联系人规模下常量RSS。模型完成后接Net同步/发布和实际回归。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261101_QP2ZY2TVRTEEH4VT` 单设备入站准入事实的有界查询模型与真实SQL对拍
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4", "REQ_20260918_MXHYRG96W0VKB031"]
  - depends_on: ["TASK_20260919_5AB9BSEC7J2FBZP2", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 先在send_scope主要事实模型细化精确入站设备的本人归属、当前吊销、关系优先与应答宽限；无记录、两侧各状态、同设备多用户绑定、建立时间相同与不同、已知/未知期限及临界时钟、读取失败关闭均核对。旧忽略期限、选择另一关系、读取失败放行应有反例；独立固定截止控制在公平时钟前提下结束，进度不能续期。模型完成后逐状态对拍真实SQL与旧全表判定，读取不物化无关渠道/账号/验证字段；真实首行前中断、重启、并发快照及索引大表测量通过后才接App与Core入站入口。陌生身份RSS/任务/延迟及三类请求抓包仍由S5A7完整验收。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20261102_W4HPKP6130N384N4` 映射actor、连接读者与QUIC排空的总任务预算模型及实现核对
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20261101_9ZM9FK8YG0K5F2HY", "TASK_20261101_M6R5V4X2J9Q7C8NP", "TASK_20261101_H8R6C2M9V5Q7X4NP"]
  - verify: 扩展transport_budget主要模型，分开映射actor、应用读者和已关闭但未完成的QUIC驱动；包含物理计数（含排空）、创建前预约、调用固定截止、取消及迟到物化。仅统计活跃读者、取消后仍物化和进度续期应有反例；在公平时钟前提下检查调用有界结束，物理排空持续计入预算，不假设Noq存在外部固定PTO或强制结束钩子。先采样预约再采样物理数，跨采样并发物化/退款不能双漏；反序应有反例。核对真实Rust/iroh/Noq所有权和观测计数，明确现有实现可用的实际期限与预算，不把模型假设当成库保证；需要产品逻辑修改时先完成模型与真实实现对拍，再做当前App压力/回收验收。512映射额度不能直接当总运行时任务上限，也不能仅提高试验阈值判通过。
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260919_S5A75YBBYQ6HGGRA` 陌生对端的准入预算与对未授权请求不响应
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4", "REQ_20260918_MXHYRG96W0VKB031"]
  - depends_on: ["TASK_20261102_W4HPKP6130N384N4", "TASK_20261101_QP2ZY2TVRTEEH4VT", "TASK_20260919_5FAQYYAJQXERDQHA", "TASK_20260919_FMT2MTRTWPK5HMG5", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20261101_9ZM9FK8YG0K5F2HY", "TASK_20261101_M6R5V4X2J9Q7C8NP", "TASK_20261101_H8R6C2M9V5Q7X4NP", "TASK_20261101_9V2DSHS7EAVG63CM", "TASK_20261101_A9VDMFEFDSEEV5KB"]
  - verify: {need}`UC_20260918_G6816V452C2ZEFE8` 以高并发、不断更换身份的陌生对端持续发起连接握手并投递畸形应答，应用保持可用、总内存与待处理任务数受限于所设预算、已建立关系的联系人收发延迟不显著变化；{need}`UC_20260918_21V7YHFYJQNNR090` 结合应用层发送记录与抓包确认三种未获授权的请求都不产生YoChat应用层出站数据（含拒绝、送达确认和错误回执），其中含已拉黑发起方；另记录iroh/QUIC握手、ACK、关闭、连接结果及时序差异并核对产品明确告知，不承诺传输层不可区分；遍历全部失败路径，展示给用户的文案不含密钥材料，也不含其他联系人的标识
  - evidence: [网络准入、资源、静默及传输边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260919_DAY5XXYDM3XKNHEH` 诊断的记录边界与当前连接方式可见
  - relates: ["REQ_20260918_DVFN7QEAWP32QD9G", "REQ_20260918_377PNHQWJ8PGCN80"]
  - depends_on: ["TASK_20260919_W1QMH1F4CEXS7VD1", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260924_823S0AQ3VBRQC778"]
  - verify: {need}`UC_20260918_CVCBC3MVS9F6DYBV` 以默认级别完成一次完整流程，按会话正文与联系人标识检索日志均不命中，且提高日志级别的入口带有相应告知；在强制经中转与允许直连两种条件下界面分别显示对应的连接方式，并在连接方式变化后的可接受时延内更新
  - evidence: [设备授权与联系人同步契约](../../../engineering/keys-and-devices.md#DOC_20260918_0TGB43S944N20PKH--architecture)

### 补齐坐标与验证机制（2026-09-23 定案）

本组由端到端逼出：一台设备漏掉一条操作之后，其后的密文被加密定序层扣住，状态机收到的
是「一条控制消息」，缺失检测因此不触发；而恢复通道的声明今天与正文同在一条日志上，
「不再提供」那条答复恰好排在它要解除的缺口之后，回不来。定案是**补齐机制改用日志位置**，
内容序号保留为消息标识。文档在前、门槛机制次之、建模再次、编码最后——机制先立，后面
几组的缺口才会自己变红，而不是靠人记得。

- [x] `TASK_20260923_PFJQFTZD12D3Z53N` 设计改写：两种位置各自的职责、恢复通道的独立性、明文头的实情
  - relates: ["REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_537BK4VME23Y2GTW", "REQ_20260918_P7X1QGZCYA41GAQT"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: [消息](../../../engineering/messaging.md)的「日志与排序」「传输信封的明文字段」「恢复通道」三节改写后同时满足四条，逐条核对：日志位置与消息标识各有定义、各有职责，写明补齐机制按前者、产品语义按后者；恢复通道写明不占日志位置、独立认证、不走聊天会话的组加密，并给出它排在缺口之后就永远轮不到的理由；明文头一节按 iroh 直连的实测（[架构](../../../engineering/architecture.md)的中转可见性）改写——日志位置不对中转公开，其可见范围等于内容的可见范围，并写明接入 gossip 之后这条结论须重新评估；四处改动各自指向承接它的 UC 或任务，没有一处是无人认领的断言。`tao verify` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_E1QN0CRNT9G6KN3H` 澄清逻辑消息标识的构成，使文档与实现一致
  - relates: ["REQ_20260918_S02F3SXHZY2DHA87", "REQ_20260918_F9J932YWK907YPY7"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 文档与实现统一以（会话、作者设备、MessageId）标识逻辑消息，明文内容摘要只作完整性校验、不进身份；标识在重新加密后不变，并有对应消费者用例承接；`tao verify`通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_9BWW4TCFGFSA5F4W` 新增用例：设备私钥留存而本地日志丢失时，分叉可被发现
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_S02F3SXHZY2DHA87"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 在[消息](../../../engineering/messaging.md)的不变量一节新增一条 UC：一台设备保留设备私钥而本地库丢失（重装，或以恢复短语在新机上打开旧身份），其出站游标从零重来，占用了收方已持有的那些日志位置时，收方按（会话、作者、日志位置）配上不同哈希判定为分叉、如实告知，既不静默覆盖也不当作重复丢弃；该 UC 的执行由 {need}`TASK_20260919_1K6Y3YJ2HC3RNNBK` 承接，并在其 verify 里点名；核对 `StoreError::InboundFork` 这条已经存在的代码路径此前没有任何用例承接，此后有。`tao verify` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_GM1JJD0Y3GVV6240` 新增用例：恢复通道的声明不得排在它要解除的缺口之后
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_9MKG1QMBXQAG6C12"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 恢复通道独立性的规范条款有真实UC：请求、不再提供、需由用户决定及重建声明在任意缺口存在时均可处理，不被聊天日志缺口扣住；执行归{need}`TASK_20260923_CZHCJ5P6JAT3TEWC`与{need}`TASK_20260923_Y2FCJZZXAPNAADXV`，`tao verify`通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_7HJ2XXQS3GBXGDYP` 设计写明：删除按内容锚定，范围在执行前解析为标识集合
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV", "REQ_20260918_Y4W1WTZE4K8TNEYD"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_E1QN0CRNT9G6KN3H"]
  - verify: 在「删除与墓碑」一节写明三条并各自给出理由：用户只按内容选择对象（单条，或在历史上圈出的一段）；范围在执行之前解析成一个消息标识的集合，此后覆盖、墓碑、本人设备之间的那条指令与确认前的回显一律以该集合为准，不以区间为准；呈现层不出示任何序号，也不接受以序号输入的范围。理由须点明区间会纳入本机尚无内容的位置与另一位作者交错其中的位置，而墓碑一旦落下不复活。`tao verify` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_2P74SP699V21QJ7V` 架构约束：跨层的同名量必须各有独立类型
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 在[架构](../../../engineering/architecture.md)写明：跨层传递且语义不同的量必须各有独立类型，彼此之间不提供转换与算术，由编译器而不是命名约定承重；给出本次的实例（日志位置与消息标识）与它造成的后果作为依据。`tao verify` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_5PXJE9P4FZFAJV67` 术语表与计划记录：两个位置的术语、本次三处发现与定案
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 术语表的日志位置与消息标识各写明是什么、不是什么；计划保留缺失检测被密码定序层扣住、恢复声明骑同一日志无法解除缺口、历史13模型中6个不检时序三处发现及定案理由；新增任务覆盖全部在本计划范围内，`tao verify`通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_SD4EB1KDTTRM7F51` 设计写明日志位置的宽度上限及其触顶行为
  - relates: ["REQ_20260918_JW13382752N6T1XR", "REQ_20260918_R5JVF7M78YZQKN8H"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N"]
  - verify: 写明日志位置是 u32（`p2panda_core::SeqNum`），每条（设备，日志）各数各的，按每条消息约占两到三个位置折算约十七亿条；写明触顶不回绕而是拒绝签发，以及触顶前须先告知、触顶后须给出可分辨的失败而不是笼统的重试；该行为的执行归 {need}`TASK_20260923_9RA0DAP61VAW01XD`。`tao verify` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_VNN0E85FK4H6YNZ1` 门槛新增：设计里的规范性断言必须有标识且被用例承接
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 检查扫描受管理设计文档的规范性断言，附近无真实{need}标识或未被UC/TASK承接即失败；移除恢复通道不占位、确认也占位置两处真实断言的标识时必须报警，恢复后通过；例外逐条有理由，空例外显式声明。检查接入项目必需门槛并在`just checks`完整执行中通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_J8GMK9NEYJY5JR8A` 门槛新增：模型比它声明覆盖的设计文档旧即失败
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_WSG175RHG9NHXPD0"]
  - verify: 每个.qnt声明所覆盖设计文档；按项目现行时效/指纹规则拒绝过期输入并列出全部受影响模型。历史12个过期模型的回归保持检出；改变文档或源码输入会使登记失效，逐项依据语义复核或重验后才能重新绑定，不只改哈希隐藏变化。检查接入项目必需门槛。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_KSK2P344VRPHW1HR` 任务图新增：补齐类任务必须含故障注入的端到端
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: `tools/check_task_graph.py` 新增一条不变量——verify 里出现补齐、重建、重试或缺失字样的任务，必须同时含一条注入故障的端到端观察（丢弃一条操作、删掉一份原件、或断开一台设备），否则失败；以本次那项被撤下端到端的任务构造回归：把注入那半句删掉，检查必须报出；例外逐条写理由
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_NHDBWTQA9DXT5RBV` 对拍入口从事件层下移到字节层
  - relates: ["REQ_20260918_537BK4VME23Y2GTW", "REQ_20260918_YACX40YKDV0GV3G6"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_WSG175RHG9NHXPD0", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260923_3EQ4NFHJ41WYE7MA", "TASK_20260923_ARB87T35J8J0W4AG", "TASK_20260923_HM6FAAG9FAXM666R"]
  - verify: 回放器不再直接构造状态机事件，而是把模型轨迹的每一步映射成一串真实上线字节，经解码与执行器喂进状态机，再比对状态分量；以本次这个缺陷构造回归——在旧入口下模型与实现一致，在新入口下必须不一致，从而证明新入口确有区分力；封装与解封的随机性固定种子，同一条轨迹两次运行逐字节相同
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_2FFZEH36Z2C9FCKP` 门槛新增：每个模型必须检验时序性质，或显式声明无此义务
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_WSG175RHG9NHXPD0"]
  - verify: models/checks.json中每模型给出至少一项temporal，或明确无活性义务及理由；状态含waiting/pending/awaiting/unanswered等等待字段而无结束性质即失败。历史13模型中6个无temporal的回归全部检出，修正后通过；检查接入项目必需门槛，不因豁免省略真实等待义务。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_3EQ4NFHJ41WYE7MA` cursor 模型补第二坐标与「到了却读不出」这一类事件
  - relates: ["REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_BXC7RNH3YCQZ9BQY"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_PFGXDHF5JWV01TRJ", "TASK_20260923_2FFZEH36Z2C9FCKP"]
  - verify: 模型里同时存在两种位置，并含一个「操作已到达而其内容对本层不可见」的动作；断言本层必须据此起等待——去掉这条转移，检验必须给出反例；补一条时序性质：缺的那一条永远不到时，等待最终结束（放行并给出原因），不得永久停滞；`quint verify` 在门槛里通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_M6TDV15S1DEDJJ86` liveness 模型拆开「送到」与「可被应用」
  - relates: ["REQ_20260918_537BK4VME23Y2GTW", "REQ_20260918_Y1C61561WBX7BTNE"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_PFGXDHF5JWV01TRJ", "TASK_20260923_2FFZEH36Z2C9FCKP"]
  - verify: 现有模型把两者焊成一个动作，本次拆开，使「已送到但尚不可应用」成为一个可停留的状态；重新检验招牌活性，确认它在拆开之后仍然成立，若不成立则给出反例并把前提补足；补一条时序性质覆盖「前置永远不到」的执行；`quint verify` 在门槛里通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_CZHCJ5P6JAT3TEWC` 恢复通道自身的可达性进入模型
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_9MKG1QMBXQAG6C12"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_PFGXDHF5JWV01TRJ", "TASK_20260923_2FFZEH36Z2C9FCKP", "TASK_20260923_GM1JJD0Y3GVV6240"]
  - verify: quint verify在models/membership.qnt检验恢复声明自身会受缺口影响的情形，并断言独立恢复通道不被扣住；与正文同路的错误变体产生反例。有设备始终不答复时，公平时钟/调度下最终落到暂不可得而非永久等待；承接{need}`UC_20260923_9SJ5B0MZWXQ8TZAD`。resend.qnt只管兜底预算/纪元，其复核由其余模型任务承担。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_N4KFPCB48X64Z50V` rebuild 模型补消息标识与日志丢失那条执行
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_S02F3SXHZY2DHA87"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_PFGXDHF5JWV01TRJ", "TASK_20260923_2FFZEH36Z2C9FCKP", "TASK_20260923_9BWW4TCFGFSA5F4W"]
  - verify: 模型里出现消息标识这个概念，断言重传前后标识不变、已被覆盖的内容不因一次重建而回来——把标识改成随重新加密变化的那种，检验必须给出反例；新增一条「私钥留存而本地日志丢失」的执行，断言分叉被判定出来而不是静默接受；补时序性质：重建始终不成功时最终如实告知，不永久停滞；`quint verify` 在门槛里通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_GTYPCD6TV315KXBR` exchange 模型补「已建立但组未建」与一次性包耗尽
  - relates: ["REQ_20260918_1VXJQ68HXHM0JR0Q", "REQ_20260918_DCNS38550F4AY1K5"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_CMQ5HX5V77QHC7KH", "TASK_20260923_2FFZEH36Z2C9FCKP"]
  - verify: 关系状态增加设计所定的那一段（已建立而加密组尚未建起），模型覆盖双方各自公告、两边都拿到之后才建组这条次序；断言这一段里不产生任何正文出站；建一次性包用一次即耗尽的语义，断言同一份包不被用于两次握手；补时序性质：对方长时间不公告时，这一段最终有个着落（继续等待并如实告知，且对方上线后自动继续），不永久停滞；`quint verify` 在门槛里通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_MBRZJH9R5WT66HQS` 其余模型按两条新门槛逐个复核
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_J8GMK9NEYJY5JR8A", "TASK_20260923_2FFZEH36Z2C9FCKP"]
  - verify: 逐项复核authenticity、devices、exemption、footprint、multidevice、quota、deletion、exchange和resend模型：覆盖声明与现行设计一致，各有时序性质或明确无义务理由；deletion与footprint含对方始终不决定/不响应执行，并在声明的公平前提下不永久等待；模型时效和等待义务两道门槛全部通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_PN0FFV6WZZY4PAT5` 消息标识与日志位置各给独立类型
  - relates: ["REQ_20260918_537BK4VME23Y2GTW"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_PFJQFTZD12D3Z53N", "TASK_20260923_2P74SP699V21QJ7V"]
  - verify: `MessageId` 与 `LogPos` 两个类型落地，彼此之间不提供转换与算术；载荷字段改名为消息标识、解码结果字段改名为日志位置，全仓无残留的含混命名；构造一处故意的张冠李戴，确认编译不通过；`cargo clippy` 与全部用例通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_ARB87T35J8J0W4AG` 游标、缓冲、等待、请求与放行改用日志位置
  - relates: ["REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_537BK4VME23Y2GTW", "REQ_20260918_BXC7RNH3YCQZ9BQY"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_S8VPFC18M5CTXY2Z", "TASK_20260923_PN0FFV6WZZY4PAT5", "TASK_20260923_3EQ4NFHJ41WYE7MA"]
  - verify: 状态机新增一种到达事件，其取值是日志位置，且在密文未能解开时同样产生；接收进度的三个集合改以日志位置键控；单元用例覆盖「密文解不开也能起等待」这一条，去掉新事件则用例必须红；模型对拍在新入口下通过；`just checks`全部通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_HM6FAAG9FAXM666R` 定序层新增放弃依赖并释放其后代的入口
  - relates: ["REQ_20260918_BXC7RNH3YCQZ9BQY", "REQ_20260918_9MKG1QMBXQAG6C12"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_N0BEHW57ZGWS5B4A", "TASK_20260923_ARB87T35J8J0W4AG"]
  - verify: 定序层现在只能等依赖到齐，本次新增放弃某个依赖并把它的后代放行的入口；单元用例：放弃之后其后代依次可读，且被放弃的那一条此后到达时仍被接受而不是当作重复丢弃；放行判定只有一处，不新增第二套就绪规则；`cargo test` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_Y2FCJZZXAPNAADXV` 恢复通道迁出会话日志，改走设备到设备加密
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_9MKG1QMBXQAG6C12"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_N0BEHW57ZGWS5B4A", "TASK_20260923_ARB87T35J8J0W4AG", "TASK_20260923_GM1JJD0Y3GVV6240"]
  - verify: 恢复通道承载的那几种声明不再占用聊天会话的日志位置，各自独立认证与去重，载荷含会话标识、作者、范围与一个单调计数；端到端注入故障：让一台漏掉一条操作、并让持有方已经覆盖掉该位置的内容，确认那条签名答复能被处理并使该位置落到不再提供，而不是被扣住；改回与正文同路时该端到端必须红；`just checks`全部通过；{need}`UC_20260923_9SJ5B0MZWXQ8TZAD` 在此执行
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_S1705ZB1TQYQBJ51` 墓碑的执行点下沉到库，原件两张表一并对齐
  - relates: ["REQ_20260918_F9J932YWK907YPY7", "REQ_20260918_XMX889MKAJ4QK8JX"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_EHW3KX9RB30XHQQP", "TASK_20260919_3MVCZ37H78S789VG", "TASK_20260923_ARB87T35J8J0W4AG"]
  - verify: 正文落库之前按（会话、作者、消息标识）核对墓碑，命中即不落地——构造「先收到删除的指令、后收到内容」的顺序，确认内容不复活；入站原件表随删除一并清理，确认删除之后库里不再留有该位置的任何原件字节；按位置取原件的那条路径改以日志位置索引，且只从随删除清理的那张表取；`cargo test` 与 `just checks` 通过
  - evidence: [删除擦除、墓碑与原子失败契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260923_QYR9HWEVFT1MKJQV` 删除的范围在执行前解析为消息标识集合
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV", "REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_EHW3KX9RB30XHQQP", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260923_7HJ2XXQS3GBXGDYP"]
  - verify: 范围选择在执行前解析成标识集合，覆盖、墓碑与本人设备之间那条指令携带的都是该集合；构造一段时间范围，其中夹着一个只有占位而无内容的位置与另一位作者的消息，确认解析结果只含用户实际选中的那些；呈现层不出示序号，也没有以序号输入范围的入口；内容锚定的圈段选择由{need}`TASK_20260924_MRTBTT1GGHG7TETK`验收，不把它算作本项交付。执行前的回显给出条数与首末时间，确认后删掉的正是那些；`cargo test`与界面用例通过。
  - evidence: [删除擦除、墓碑与原子失败契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)
- [x] `TASK_20260923_9RA0DAP61VAW01XD` 日志位置触顶：可分辨的失败、提前告知与有界证明
  - relates: ["REQ_20260918_JW13382752N6T1XR", "REQ_20260918_R5JVF7M78YZQKN8H"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_SEMPP73D13V55EJ7", "TASK_20260923_SD4EB1KDTTRM7F51"]
  - verify: 日志位置触顶给出可分辨错误码与文案，接近设计余量时提前告知；恰在上限仍可封装、越过即拒绝不回绕、进入余量产生告知。函数与边界值明确，有界证明由{need}`TASK_20260919_SEMPP73D13V55EJ7`承担，不以普通测试冒充证明。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_93MAD0N3VCC6QRBT` 一次性预密钥包耗尽之后的再公告
  - relates: ["REQ_20260918_DCNS38550F4AY1K5", "REQ_20260918_37F049FH3B007X48"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_N0BEHW57ZGWS5B4A", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260923_GTYPCD6TV315KXBR"]
  - verify: 构造设备一次性包耗尽，确认可靠再公告有明确触发者，发送失败/重启仍可继续；同一台设备先后与两个联系人各建立实际加密组，第二次不因已用包而失败；真正缺包返回可分辨原因而非笼统握手失败，端到端覆盖该路径。
  - evidence: [真实密码与消息接收契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--contracts)
- [x] `TASK_20260923_PSKJ7VBW8CVAH8N5` 重发的端到端按故障注入重写并加回套件
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_Q15AKFP42V04BVM3"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260923_ARB87T35J8J0W4AG", "TASK_20260923_HM6FAAG9FAXM666R", "TASK_20260923_Y2FCJZZXAPNAADXV", "TASK_20260923_S1705ZB1TQYQBJ51"]
  - verify: 端到端不再靠构造序号空洞，而是真的丢掉一条上线的操作，据此覆盖三条路：持有方仍有原件时该位置补齐并使其后的消息依次可读；持有方已覆盖内容时该位置落到不再提供并给出原因与所依据的列表版本；有设备始终不答复时落到暂不可得并记下是哪几台；每条都先确认在改动之前是红的；`pnpm --dir e2e run test:multi` 全过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

### 故障注入端到端逼出的两笔（2026-09-23 收尾）

故障注入暴露两类独立接缝：一次原件可放行多条操作，各条须按其真实作者/位置记账；认证原生放行结论须有产品执行者。下列任务保留这两项消费者验收及效果接线门槛。

- [x] `TASK_20260923_3FNCX4RK1W5643QN` 位置有了结论，就把定序层那一边也放开
  - relates: ["REQ_20260918_BXC7RNH3YCQZ9BQY", "REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_9MKG1QMBXQAG6C12"]
  - depends_on: ["TASK_20260923_HM6FAAG9FAXM666R", "TASK_20260923_PSKJ7VBW8CVAH8N5"]
  - verify: 状态机的认证原生放行结论经产品效果接入ordering::abandon，由外壳执行、不在纯状态机操作协议；端到端故障注入真的丢掉一条上线操作且源已删除其原件，收到可验证不再提供后后继正文可读。无人答复导致的业务暂不可得不能伪造密码跳过许可：保持等待，真实迟到原件到达后后继正文才可读；撤去接线或越权放行的错误变体必须失败。项目必需门槛全部通过。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_T0N28909BYZQ254E` 门槛新增：状态机说出的每一种效果都必须有人执行
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260923_3FNCX4RK1W5643QN"]
  - verify: 源码检查要求enum Effect中状态机实际会产出的每个变体在产品代码crates/*/src（排除machine.rs本身）有执行匹配，只在tests出现不算；声明但从不产出的变体单列用途。存量基线只减不增，已接线却保留例外因过期失败；故意改名已接线匹配时报警，恢复后通过，接入`just checks`。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260923_G96PXYP1PHYBA4ZJ` 一次放行里的每条操作各按自己的作者与位置记账
  - relates: ["REQ_20260918_YACX40YKDV0GV3G6", "REQ_20260918_BXC7RNH3YCQZ9BQY"]
  - depends_on: ["TASK_20260923_HM6FAAG9FAXM666R", "TASK_20260923_ARB87T35J8J0W4AG"]
  - verify: 协议层交回的不再是「这一次解出的那一条载荷」而是**放行了哪些操作**，每条带它自己的作者与日志位置；游标按每条的作者各推一次，正文按每条各落一行，送达确认按每条各回一份、各记一笔；原件那串字节按**该条自己那个作者那个位置**上已记下的取，不照搬本次投递这一条；「不再提供」的验签绑到写它的那台，不绑递字节的那台；单元用例：一次投递里放行两条同号不同作者的正文，两条各落一行、两个作者各记一笔位置，去掉作者这一维则用例必须红（后一条会被当成重复而落不进去）；端到端注入故障——真的丢掉一条上线的操作，等它被要回来时一次放行两条，确认两条正文都在、都可读；改回只取最后一条则该端到端必须红；`cargo test` 与 `pnpm --dir e2e run test:multi` 全过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

### 接续核对补上的缺口（2026-09-24）

{need}`TASK_20260924_MRTBTT1GGHG7TETK`统一承担单条、圈段、时间范围及搜索结果上的界面删除/引用观察；与存储范围解析和主界面布局的验证职责分开，不能只以库接口存在认定界面路径完成。

- [x] `TASK_20260924_MRTBTT1GGHG7TETK` 界面上的单条删除、按范围删除与从搜索结果执行操作
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV", "REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20260919_EHW3KX9RB30XHQQP", "TASK_20260919_VDH8GAR8M13PK346", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260923_QYR9HWEVFT1MKJQV"]
  - verify: 验证三种按内容选择的入口——在一条消息上删它，在历史上以两条看得见的消息为两端圈出一段，以及一个时间范围（可限定当前会话，也可跨全部会话）——三者都先经预览、在执行前解析为标识集合，回显条数与首末时间，确认后删掉的正是那些，呈现层不出示序号也不接受以数字输入的范围；搜索结果上补齐引用与删除，{need}`UC_20260918_V7DK0XN0XPWG3FZ8` 在此以端到端执行：打开着 X 时对属于 Y 的结果执行跳转、引用、删除三种操作，效果都落在 Y 上，X 的消息、草稿与滚动位置不变；搜索结果携带消息所属会话、作者及逻辑身份，精确定位所属会话中的那条消息，不依赖当前选择会话；{need}`REQ_20260918_PE8XM7D0MPZ2XWTV` 的验收三种方式各在界面上执行一次，被删的在聊天界面与搜索结果中都不出现、未选中的不受影响；每条新用例先确认在改动之前是红的；`npm --prefix ui run test`、`cd e2e && npm run test:multi` 与 `just checks` 通过
  - evidence: [界面行为与可见验收判据](../../../product/app-shell.md#DOC_20260918_K0ENVBAX9Y6YAXT4--requirements)
- [x] `TASK_20260924_51X6CYWRCDYB1394` 问过之后才被吊销的设备不再算作未答复
  - relates: ["REQ_20260918_VBRZ8N5AC8QR0N3B", "REQ_20260918_Q15AKFP42V04BVM3"]
  - depends_on: ["TASK_20260919_RG3BWW88MEQ85YW6", "TASK_20260923_QK7N2XVDR9MHB4TC", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 由 {need}`TASK_20260923_QK7N2XVDR9MHB4TC` 排查时发现：一个缺口在旧版列表下问过的设备后来被吊销，仍留在 `ResendAsk.asked` 里，`all_answered()` 因此永远凑不齐，位置只能等超时落到「暂不可得」，界面还会写一台已被吊销的设备「未答复」。设计补上「更高版本列表里少了一台已问过的设备」这一条；状态机在换版时把新版里没有的设备从问过与答过里去掉，去掉之后问过的都答了即放行为相对新版本的不再提供，已按暂不可得放行的一并改判（「早于入组基线」不改）；三条单元用例各自先确认在改动之前是红的——其余都答了当场得出不再提供、被吊销的那台不进未答复名单且它此后的声明不被记下、已判暂不可得的位置改判；`models/membership.qnt` 补上版本 3 吊销设备 2 这条边与 `unansweredAreStillListed`、`unavailableNamesSomeone` 两条不变量，对照模块 `askedKeepsRevoked`（不去掉被吊销设备）上两条各给出反例；`just checks` 通过
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)
- [x] `TASK_20260924_823S0AQ3VBRQC778` 把中转与发现接进应用：默认 n0，可改为自建
  - relates: ["REQ_20260918_Y1C61561WBX7BTNE", "REQ_20260918_P7X1QGZCYA41GAQT", "REQ_20260918_QZGW3CN8BHBB0F6N", "REQ_20260918_Q0MQBNAVS1TB1PBW"]
  - depends_on: ["TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_Q84FGP203EPHG687", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_73T31Y3GSPKQPM9Z"]
  - verify: 用户 2026-09-24 定：默认用 n0 的公共中转与发现，用户可在设置里改为自建的地址。本项让应用默认接上二者、设置里可改并持久保存；发布器那道「只发布中转入口」的过滤器两种情形都守住，以本机自建的中转与发现服务核对发布记录不含直连地址；两台只知道对方设备公钥、不交换直连地址的实例经中转收发成功（测试用本机自建的中转与发现，不碰外部设施）；告知清单写明默认情形下依赖 n0、改为自建后换成用户自己的地址——告知的内容本身由 {need}`TASK_20260919_W1QMH1F4CEXS7VD1` 核对
  - evidence: [中转、发现与发布过滤边界](../../../engineering/architecture.md#DOC_20260918_94F65AR6A6WAQ9QM--contracts)
- [x] `TASK_20260925_MYXS26S7V4AZWBES` 状态不随历史增长：已应用按区间记，等待与未确认移出整份状态
  - relates: ["REQ_20260918_3TK81N30WS2S6JWB", "REQ_20260918_R5JVF7M78YZQKN8H"]
  - depends_on: ["TASK_20260919_5X1SXR1ZNC2XQAWC", "TASK_20260923_PFJQFTZD12D3Z53N", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 已应用位置按区间保存、等待归定时器表、未确认归库表，State只留判定计数；参考模型、真实字节/SQL对拍及崩溃重放保持全部原语义。原版本的旧状态迁移前后逐项相等按固定历史输入追溯，现行只读当前格式并安全拒旧；同一对端离线、真实栈连续发送场景下每批耗时不随条数增长，报告机型与规模，旧二十多秒尖峰有查明原因，不以新输入改写旧失败。随后{need}`TASK_20260919_ZAW3PVG8Y2WQTQ4T`一万条原验收执行，不缩成默认千条。
  - evidence: [区间表示、状态边界与当前编码](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)

### 2026-11-02 实现审查修复（用户已批准）

- [x] `TASK_20261102_TKJWV0BW29RFYSX6` 删除预览集合归属模型
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: deletion.fixedDeletePreview检验确认不扩大集合，取消/重启丢弃同意；旧选择扩大有反例。真实SQL固定5秒预算。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_SDC8EBR3BCEG24TQ` 会话结果与草稿版本归属模型
  - relates: ["REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7"]
  - verify: view_ownership.ownedViewResults检验ABA、迟到、编辑、失败/取消下的归属；无读守卫/草稿守卫各有反例，公平失败/取消前提下回调等待终止。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_SK19NC5VCZMA6GXC` 并发设备操作集合的签名与收敛模型
  - relates: ["REQ_20260918_9YNQV83FP0G2FV42"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 扩展devices主要模型，核对两签发者同版本加入/改名、乱序/重复、旧快照、永久吊销及授权投影；模型先过，真实Rust签名/SQL轨迹对拍后才接协议。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_WY3W2TT6QV4TJAXG` 持钥升级与恢复封装持久化模型
  - relates: ["REQ_20260918_G2S3MXJ3J0HXYXYP"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 扩展user_key_sync主要模型，区分取得私钥、恢复封装写入、完成回执；显式封装写入故障注入/重启及固定重试边界，不能在恢复封装缺失时报告成功。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_71Z6GDDBTMXGBCR1` 创建确认的重启与失败模型
  - relates: ["REQ_20260918_T1H63J7F3QD0WBBT"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 模型核对持久未确认标记、锁定/解锁、确认落盘失败及重启；未确认不能进入main，已确认重启不主动展示短语。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_53G5AJRZK4PQJQ2J` R1-1 确认删除不得超过预览集合
  - relates: ["REQ_20260918_PE8XM7D0MPZ2XWTV"]
  - depends_on: ["TASK_20261102_TKJWV0BW29RFYSX6", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 真实Rust/SQL核对新消息、范围内迟到、已有删除变化，以及删除后重复确认（含重启）拒绝；UI提示重新预览；e2e调用携带预览指纹。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_1Q0D2K8F3BX0RZZJ` R1-2 异步读取只更新所属会话与世代
  - relates: ["REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20261102_SDC8EBR3BCEG24TQ", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7"]
  - verify: A→B、A→B→A、分页/刷新/跳转的迟到结果不覆盖当前窗口；取消预览不复活。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_FT2TF38R7QK3Q0MG` R1-3 发送完成只清理提交时的草稿及引用版本
  - relates: ["REQ_20260918_DK8P5E0K5GES1T0C"]
  - depends_on: ["TASK_20261102_SDC8EBR3BCEG24TQ", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7"]
  - verify: 发送期间切换、继续编辑、取消后重选同一引用、失败及成功路径，保留其他会话/新版本草稿。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_NXV0BBQ0N83QZGT1` R2-1 实现并发设备授权与改名操作合并
  - relates: ["REQ_20260918_9YNQV83FP0G2FV42"]
  - depends_on: ["TASK_20261102_SK19NC5VCZMA6GXC", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_WPP64DJDH6659YMQ", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 两个持钥设备断连各加不同设备，相反顺序/重复/重启后所有观察者授权集合收敛，吊销不复活；后续实际收发验证。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_02GQSJGE51Z1NGTF` R2-2 持钥升级补建可恢复封装
  - relates: ["REQ_20260918_G2S3MXJ3J0HXYXYP"]
  - depends_on: ["TASK_20261102_WY3W2TT6QV4TJAXG", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 普通设备加入、收消息、双方确认私钥同步、重启后短语打开旧历史；封装写入失败不得报告完成，既有文件原子保留。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_9C3WTTYBREVSEQR3` R2-3 持钥状态未知时保留风险告知
  - relates: ["REQ_20260918_214C96FXNAE7W0G6"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 未知为null，只有已认证持有为true；未知/短语加入/重启不省略条件式吊销风险，已知不持有保留既有文案。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_Q38T3MD9JEWXKVJF` R2-4 创建确认持久化
  - relates: ["REQ_20260918_T1H63J7F3QD0WBBT"]
  - depends_on: ["TASK_20261102_71Z6GDDBTMXGBCR1", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 创建展示/回填期间退出和重启后先解锁再恢复确认，确认写入失败不进入main；已确认正常重启不展示短语。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

- [x] `TASK_20261102_VM8JSB4QQMR5EXRG` C1 超时清理整个拥有的进程组
  - relates: ["REQ_20260918_FBGYS6FJ6X3GHEQ4"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 真实隔离进程中leader先退出、后代忽略TERM仍在固定宽限后被KILL；超时/取消返回前拥有的进程组全部结束，正常退出不误报、不误杀无关进程；全部对应工具回归通过，旧失败只在计划保留。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

### N1 组合原生回归调查与修正（2026-11-02 用户已批准）

- [x] `TASK_20261102_4GQPWWEER01K4MQ1` 固定N1混合流水线与重建窗口差异
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_DEM5TY7V4XDCPWGJ"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_1K6Y3YJ2HC3RNNBK"]
  - verify: 注入乱序和丢包故障；保留旧/新冻结产物相同失败、独立双设备成功及SQLCipher快照；用真实Rust反例核对本地重建成功后的频率计数与窗口期限，区分模型/协议实现与夹具前提。
  - evidence: [重建窗口、提示及恢复边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261102_SC6ATYG01XCN2A0N` 扩展重建主要Quint模型的预算、固定窗口期限与会话计时器归属及真实解密证据
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_DEM5TY7V4XDCPWGJ"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_1K6Y3YJ2HC3RNNBK", "TASK_20261102_4GQPWWEER01K4MQ1"]
  - verify: 注入乱序和丢包故障；在rebuild主要模型中检验成功/失败/暂停/重启/迟到完成不补回自动重建预算、不延长窗口；公平时钟前提下窗口结束；复位计数/延长截止的旧算法有反例；会话共享窗口及旧重复计时器一次性消费经TLC安全/活性检查，按发起设备分别起窗的对照有反例；补日志位置不能证明新密钥跟上的模型，明确应用内容释放或固定到期才结束抑制，正向TLC安全/活性通过、位置即跟上对照失败。
  - evidence: [重建窗口、提示及恢复边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261102_N9QG3CHPKPF5WQVQ` 修复N1重建次数与窗口生命周期并完成原生组合复验
  - relates: ["REQ_20260918_2MTCJFK2TX0E9ACY", "REQ_20260918_DEM5TY7V4XDCPWGJ"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_1K6Y3YJ2HC3RNNBK", "TASK_20261102_4GQPWWEER01K4MQ1", "TASK_20261102_SC6ATYG01XCN2A0N", "TASK_20261102_HG0948Q7GA70QBQ5"]
  - verify: 注入乱序和丢包故障；模型先过再修改Rust；保留自动频率上限、用户手动重试与暂停语义，真实SQL/密码协议轨迹核对并重跑组合原生流水线；不提高产品阈值、不删除断言。
  - evidence: [重建窗口、提示及恢复边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)


### N2 历史恢复乱序与积压

- [x] `TASK_20261102_EK15QYVZFJM625YY` 区分历史同步的创建、积压、恢复接受与业务落盘
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20260919_ZAW3PVG8Y2WQTQ4T", "TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 注入故障、丢包及乱序，区分诊断缩短期限与原项目期限；校正在线时钟后仍停滞的输入按固定版本追溯，离线核对真实原件、解密、Store水位拒绝及发送积压，分清已证事实和未确认瓶颈，不输出密钥，不把创建/诊断用时当同步完成性能。
  - evidence: [历史推送许可、恢复与合并契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261102_KZWBFBM1GD51HYTX` 用Quint检验恢复包共用水位及候选上下文隔离
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20261102_EK15QYVZFJM625YY", "TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 注入故障、丢包及乱序；models/recovery_admission.qnt检查历史块/ACK独立上下文、原子提交、所有接受包推进普通全局水位及非法计数边界，正向与六错误对照均取得实际结论。原批准时的新库/升级固定基线判据保留旧版本归属；当前格式拒旧，不恢复迁移兼容。本模型只检无等待原子账本，不作生产或网络性能证明。
  - evidence: [历史推送许可、恢复与合并契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261102_Y8WBASG1EM68WHH7` 确认N2技术设计修订并同步权威设计
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20261102_KZWBFBM1GD51HYTX"]
  - verify: 注入乱序和丢包故障的模型反例及设计修订有用户2026-11-03明确批准依据；messaging.md与正式Quint模型一致，明确历史任务隔离防重放、普通补齐答复严格水位、原子崩溃恢复。原升级基线仅属批准时版本，后续仅当前格式的决定不恢复旧库迁移；同步设计并验证后才实现，不把历史同意当新的授权。
  - evidence: [历史推送许可、恢复与合并契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261102_HG0948Q7GA70QBQ5` 实现已批准的历史恢复接受规则并完成组合与分段性能验证
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20261102_Y8WBASG1EM68WHH7", "TASK_20261102_KZWBFBM1GD51HYTX", "TASK_20260919_ZAW3PVG8Y2WQTQ4T", "TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20261103_K4R4BADMMQPN5CBG"]
  - verify: 注入故障、丢包及乱序；旧/新包重放、错设备/会话/任务、非法块、计数边界、取消、重启、落盘失败与迟到ACK真实Rust/SQL/密码路径对拍；迁移不放行旧重放；保留1000条、两轮断网、重载及全部原断言并完成15-spec组合；按改动重跑必需门槛，独立报告正常同步与混合恢复耗时；用户2026-11-03授权的三项性能工作包括分段计时、恢复路径优化及release/原生组合复验。本地元数据批处理先由history_intent_batch.qnt检查固定SQL截止、失败、取消、重启、迟到与日志参考方案，再以真实SQL轨迹对拍；保持密码处理顺序。ui_maintenance.qnt验证界面慢维护调用不重入、固定原生截止前提、卸载/失败/重启/迟到隔离，既定6条轨迹246状态与真实Promise调度逐状态对拍；修复后保留原15-spec负载和断言复验。
  - evidence: [历史推送许可、恢复与合并契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261103_K4R4BADMMQPN5CBG` 建模持久重封队列的有界领取与公平游标
  - relates: ["REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_9G1QW692AHHVT4JX", "REQ_20260918_KAN0STW3H8EWZB99"]
  - depends_on: ["TASK_20261102_Y8WBASG1EM68WHH7", "TASK_20261102_EK15QYVZFJM625YY", "TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 注入SQL失败、取消、重启及迟到网络结果；三块有限论域检查保留意图直到新原件及Send落盘、固定SQL截止、不丢游标及持续低编号工作不饿死高编号。正向安全和条件结束通过，丢领取、忘游标、无游标、迟到清空及续延截止五种错误变体给出反例；模型每批1元素，与Rust同一SQL算法的私有单项入口逐状态对拍；生产入口固定32项另用真实密码路径和部分失败回归验证，公平性按成功领取/实际尝试轮次说明，不承诺网络送达或硬实时。
  - evidence: [日志位置、补齐与独立恢复契约](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)


### N3 最终一致排序、补入阅读与未发布格式精简（2026-11-03用户已批准）

- [x] `TASK_20261103_HS5WPZ4V22HM4YGD` 同步最终排序、补入提醒与未发布格式契约
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681"]
  - verify: tao verify；覆盖跨设备共同消息相对顺序、已查看洞、视口锚点、并发含义、前置缺失、无旧格式兼容及先正确性后性能；用户本次批准为依据，不沿用旧问题答案。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_WXEC9DXWSZ6BNE77` 调查并列明全部项目自有协议与存储兼容分支
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_HS5WPZ4V22HM4YGD"]
  - verify: tao verify；扫描crates、ui、e2e、models、tools和现行设计；逐类列出旧schema回填、序列化回退、双成员协议、私料迁移、旧待办/水位/归属补登记、旧API适配、派生夹具；区分当前恢复或上游内部实现，将必要历史输入的归属与当前恢复边界区分，原件按固定提交追溯。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_330CYBATQ23EB561` Quint前置验证业务因果、统一全序及排序证据暂存
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_HS5WPZ4V22HM4YGD"]
  - verify: quint verify；多作者、两副本、部分历史、并发、引用、重复、重封、删除、错误证据、缺前置、固定截止、失败、取消、重启及迟到，正向安全/条件结束和错误变体；不给缺失前置或任意对端高度隐含信任。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_BYT580G357JHZ3JZ` Quint前置验证已查看覆盖洞、补入提醒与阅读所有者
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_HS5WPZ4V22HM4YGD", "TASK_20261103_330CYBATQ23EB561"]
  - verify: quint verify；精确已查看集合及范围压缩、本人同步合并、迟到旧消息、重复、导入汇总、锁定/切换/卸载、锚点和旧回调；注入写入失败、取消、重启和迟到；确认不会靠最高编号吞掉未查看消息。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_9V73P6BB1PPGXH0B` Quint前置验证单一当前格式及拒绝旧输入的原子边界
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_HS5WPZ4V22HM4YGD", "TASK_20261103_WXEC9DXWSZ6BNE77"]
  - verify: quint verify；当前输入成功/失败/重启恢复，旧/未知格式不写入、不自动清空；固定事务期限、取消及迟到不改变旧提交，保留私料与授权不变量。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_3JTCCPNA71SAQXZH` 实现独立业务排序类型、证据验证与消息创建规则
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_330CYBATQ23EB561"]
  - verify: 先模型结论再Rust；真实签名/SQL逐状态核对纯证据验证与创建规则，注入前置乱序/缺失/重复、冲突身份、非法高度、上界、失败和重开；统一键不依赖物理时钟，重封证据不变。实际异步取消/重启/迟到所有者及补证期限由{need}`TASK_20261103_TY9SPKDC1FJ0CQEX`对拍orderLifecycleChecks，本文不以稳定键投影冒充网络生命周期实现。
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)

- [x] `TASK_20261103_39ZQ3B1C5EYNKCB6` 删除旧库迁移与回填，使用当前schema原子初始化
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_9V73P6BB1PPGXH0B"]
  - verify: 实际SQL注入DDL/写入失败和固定五秒执行超时；新库初始化及当前库重开成功，旧库/未知版本拒绝且字节不被清空；移除启动ALTER旧列、旧索引迁移、绑定/水位/计量及Send旧待办补登记，保留当前重启与恢复封套对账。剩余业务API惰性旧字段/投递目标/本人起始记录适配由{need}`TASK_20261103_S513E8Z661W4G5RX`在验收前清理。
  - evidence: [本地存储事务、失败与恢复契约](../../../engineering/local-store.md#DOC_20260918_9810WZW7EA7H8FC5--architecture)

- [x] `TASK_20261103_HM558EKYKHMP2P20` 统一当前成员协议、快照及线上解析并删除旧私料迁移
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_9V73P6BB1PPGXH0B"]
  - verify: 真实密码路径注入丢包、乱序、失败/取消/重启及迟到；当前独立成员运行态的创建、欢迎、加入/移除、重建、许可重入和私料归属照常；旧控制和快照拒绝，删除Legacy路由及旧迁移模块，重新生成当前夹具；设备Protocol、成员、控制和快照入口统一，真实密码及应用回归通过。
  - evidence: [成员事实与历史知识边界](../../../engineering/messaging.md#DOC_20260918_65J9C775MF8PYAQJ--architecture)

- [x] `TASK_20261103_2V3MVXW59AT6NJNV` Quint前置验证正文绑定、元数据隐私与冲突隔离
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_330CYBATQ23EB561"]
  - verify: 有效同身份分叉的不同到达顺序、原子隔离、精确哈希祖先可验证、失败/重启/删除、秘密仅随授权正文、错误正文拒绝、引用摘要清除和旧原件不恢复；正向安全/条件收敛及错误变体后才实现Rust，wire/store逐状态对拍另验。
  - evidence: [正文/引用承诺、冲突与事务契约](order-content-and-conflicts.md#DOC_20261103_9C7EMXGVZB2RXQA8--contracts)

- [x] `TASK_20261103_NYBT3W5C273W3JVF` Quint前置验证补证所有者、分页权限和固定截止
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_330CYBATQ23EB561", "TASK_20261103_9V73P6BB1PPGXH0B", "TASK_20261103_2V3MVXW59AT6NJNV"]
  - verify: 先模型后Rust；两端独立截止、根签/用户范围、无正文补证、页原子提交、全局计数与页回执分离、重复/乱序、存储/发送预算、失败/取消/重启及迟到所有者；正向安全/条件结束与错误变体，导出真实中间状态用于下游逐步对拍。
  - evidence: [有界仅元数据补证及所有者契约](order-proof-recovery.md#DOC_20261103_XVTRZHW5CMK52BXN--contracts)

- [x] `TASK_20261103_TY9SPKDC1FJ0CQEX` 实现仅元数据的有界补证服务及实际协议/SQL对拍
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_NYBT3W5C273W3JVF", "TASK_20261103_3JTCCPNA71SAQXZH", "TASK_20261103_39ZQ3B1C5EYNKCB6", "TASK_20261103_HM558EKYKHMP2P20"]
  - verify: 注入丢包、页乱序、事务失败和迟到；当前二进制精确解析、最大合法页/证书/根签见证经过真实X3DH/1MiB帧；真实SQL逐状态核对作用域计数、游标、存储计量和固定期限；取消/重启/迟到不复活，缺证正文私有暂存，业务错误不重建密码组。作者须有双方用户下的根签历史证据，设备自签或第三方根不够；元数据不返回正文或秘密。补证数据、原件、当前名单与吊销分开，原资源上限保留；覆盖orderLifecycleChecks真实所有者。
  - evidence: [有界仅元数据补证及所有者契约](order-proof-recovery.md#DOC_20261103_XVTRZHW5CMK52BXN--contracts)

- [x] `TASK_20261103_0WMEGM30ANHCHYPK` 将原始业务排序证据接入密文、签名、正常接收及重封
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_3JTCCPNA71SAQXZH", "TASK_20261103_HM558EKYKHMP2P20", "TASK_20261103_2V3MVXW59AT6NJNV", "TASK_20261103_TY9SPKDC1FJ0CQEX"]
  - verify: 注入篡改/错作者/错会话、未知前置、重复和重封；排序证据作者认证，端到端保护不泄漏私有阅读状态；最大正文与最大前沿组合不静默截断或降低既定输入上限，完整当前编码边界另验；同逻辑消息多份操作原件得到同一排序；真实密码与SQL接线验证。 接线前将普通读取/未读统计/历史导出统一到已验证且无冲突的视图；正文与补证元数据的准入及持久计量不绕过既有资源限额。
  - evidence: [正文/引用承诺、冲突与事务契约](order-content-and-conflicts.md#DOC_20261103_9C7EMXGVZB2RXQA8--contracts)
- [x] `TASK_20261103_JWEZ4F37GZJ1765G` 以统一业务键保存、读取、定位及删除逻辑消息
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_3JTCCPNA71SAQXZH", "TASK_20261103_39ZQ3B1C5EYNKCB6", "TASK_20261103_2V3MVXW59AT6NJNV"]
  - verify: 全局搜索先检查search_window安全/条件结束及六错误变体，再实现会话分组、签名时间组序、业务键倒序、完整游标和修订/查询所有者。真实SQL与Quint轨迹对拍；注入事务失败、取消、重启、迟到、作者并发和不同接收时间；消息/证据/游标同事务，原件删除不丢无正文排序证据，全量/增量/定位/搜索及圈选删除同序。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_NKV98TSQMB3ZTZZX` 接入用户批准历史的原始排序证据并验收新设备组合
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_0WMEGM30ANHCHYPK", "TASK_20261103_JWEZ4F37GZJ1765G", "TASK_20261103_TY9SPKDC1FJ0CQEX"]
  - verify: 注入块乱序、重复、断网、取消/重启、缺前置及迟到ACK；源接收时间不重建排序，不要求目标持有未授权正文；当前设备新加入和已有部分历史都能验证共同顺序，源删除占位不制造墓碑。 实际补证所有者、固定请求期限、取消/重启及迟到逐步对拍orderLifecycleChecks；此义务不由纯排序类型投影代替。 元数据补证同时验证作者在本会话两方用户下的历史正向根签绑定，不只验设备签名；无授权历史正文及秘密不得随元数据返回。
  - evidence: [有界仅元数据补证及所有者契约](order-proof-recovery.md#DOC_20261103_XVTRZHW5CMK52BXN--contracts)

- [x] `TASK_20261103_0N14K5AT6VHJPJ4V` 持久化精确已查看覆盖和补入通知并接通本人私有同步
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_BYT580G357JHZ3JZ", "TASK_20261103_JWEZ4F37GZJ1765G", "TASK_20261103_0WMEGM30ANHCHYPK"]
  - verify: 真实SQL注入范围中的洞、重复/乱序本人同步、失败回滚、重启及迟到；仅记录确实查看过的消息，编号较小的补入仍未查看；联系人无已读响应，正文删除按既有通知清理规则处理。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)

- [x] `TASK_20261103_BB5VJXV4ERQQDVJX` 统一分页和窗口修订，按消息身份保留阅读锚点
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_JWEZ4F37GZJ1765G", "TASK_20261103_0N14K5AT6VHJPJ4V"]
  - verify: 注入跨作者补入与窗口修订，正反分页无重复/遗漏，过期页不拼接；滚动位置、草稿及引用定位保留；锚点被删有确定替代，真实SQL与界面状态对拍。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)
- [x] `TASK_20261103_KDN43B64WBWTFC1M` 实现补入/顺序更新提示、查看入口及有效可见确认
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_BB5VJXV4ERQQDVJX", "TASK_20261103_NKV98TSQMB3ZTZZX", "TASK_20261103_0N14K5AT6VHJPJ4V"]
  - verify: 注入窗口上方/下方迟到、历史批量导入、重复、锁定/切换/重启和旧回调；新增待查看可定位，未查看不自动消失；非打扰批量提示、键盘与辅助阅读可操作，用户当前阅读消息及草稿保持。
  - evidence: [统一排序、阅读及格式契约](ordering-and-attention.md#DOC_20261103_VE1ZM1QD1QP5NK51--architecture)
- [x] `TASK_20261103_S513E8Z661W4G5RX` 清理剩余旧API、模型、夹具、工具及现行文档兼容残留
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_HM558EKYKHMP2P20", "TASK_20261103_39ZQ3B1C5EYNKCB6", "TASK_20261103_0WMEGM30ANHCHYPK"]
  - verify: 扫描代表性同类路径；用静态门槛拦截旧格式回退/迁移重新引入，同时允许当前格式崩溃恢复和协议世代处理；旧业务测试改用当前夹具，移除只证明迁移的过时检查，必要历史决定与失败边界保留、原始输入按固定提交追溯。 包含业务API惰性旧字段补登记、原投递目标补建、本人通道旧起始记录；保留当前正面授权证据及旧密码纪元迟到保护。
  - evidence: [正文/引用承诺、冲突与事务契约](order-content-and-conflicts.md#DOC_20261103_9C7EMXGVZB2RXQA8--contracts)

- [x] `TASK_20261103_Q71SCNYQP46F9GEN` 完成排序、补入阅读及无兼容分支的业务验收
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_KDN43B64WBWTFC1M", "TASK_20261103_S513E8Z661W4G5RX", "TASK_20261103_NKV98TSQMB3ZTZZX"]
  - verify: 先保存业务正确性证据；Quint与真实Rust/SQL/密码/UI轨迹对拍，故障注入、当前格式重启/取消/迟到、相同集合与交集顺序、阅读洞、锚点全部通过；原15-spec保留1000条、两轮断网、重载、全部已批准业务断言及log-secrets，完整配置检查通过。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)
- [x] `TASK_20261103_CVES17CV0RNJMFWN` 业务验收后建立同一混合快照的性能基线
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_Q71SCNYQP46F9GEN"]
  - verify: 业务验收证据有效才运行；headless为主，debug/release同一混合负载注入普通重传/历史竞争及断网，分别记录种子、验证/密码/SQL/FTS、驱动、限速和总墙钟；多次报告分布，不用无组状态夹具替代混合恢复。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)
- [x] `TASK_20261103_5WJK6PTZ23BNHK25` 按已证瓶颈修复重复流量与调度并重新验收
  - relates: ["REQ_20261103_GBNVF8ZY42F1DZX4", "REQ_20261103_NS77FQDYNCMNF71P"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260920_GSW6Y996D2E1E681", "TASK_20261103_CVES17CV0RNJMFWN"]
  - verify: 先根据基线选择最小优化；并发/调度变化先quint verify再Rust；注入故障、取消/重启与迟到，同等负载复测，正常和混合分别报告；保持600帧/分钟、4块窗口及30秒/5次，原业务断言及完整配置检查通过。

  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)

### 全仓审查修复（2026-11-05）

本批由用户授权添加并修正全部审查问题；固定审查源为`a0d406a0e217201ad599a3bec3d7c1f78fb0376d`。29项确认缺陷、7项风险及13条原始清理建议全部承接，AU-C1与TM-C1合并，形成48个处置任务，另有3项模型及集成支撑任务。风险必须验证并修正实际缺口，只有可复核的非缺陷证明才可不改实现，不能以未复现或默认接受关闭。未发布格式不做向后兼容；当前恢复及旧格式安全拒绝保留。所有完成状态只读取本附件复选框，实际原生、性能及独立复核范围按主计划验证解释。


- [x] `TASK_20261105_6CDDFWKVPS6MEP09` 建立本批修复的Quint先行与实际映射
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20260919_4VA6XYGKJWWM56D2"]
  - verify: 逐项登记48个处置的模型义务、已有模型范围、需要新增或修订的性质、真实Rust/UI/SQL事件映射及定向命令；局部错误分支和纯清理注明不需新增模型的依据。此任务建立可执行清单，不要求一次建完全部模型；每个复杂修复仍必须先quint verify本项安全与条件活性、检查错误变体，再实现Rust/UI，明确时钟、公平性、固定截止、失败、取消、重启与迟到。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_Y730729MBTAQEJ8S` [AU-R1 P1] 钥匙串读取错误不得覆盖既有封装随机值
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 注入缺失条目、权限拒绝、临时不可用、损坏长度与写入失败；仅NoEntry允许生成新值，其余错误返回且绝不set_secret；既有keyfile在失败后恢复读取仍可解锁。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_R85820GQZ7HHHRNY` [AU-D1 P1] 使恢复短语结果服从取消及锁定所有权
  - relates: ["REQ_20260918_T1H63J7F3QD0WBBT", "REQ_20260918_B9Y4E73VMQ8RS99X"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；延后成功及错误结果，依次取消、重新打开、锁定；旧请求不得切回主界面或显示短语，新请求仍正常完成。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_1XXKK4HAPQF0AP3H` [CORE-D5 P1] 批准跨设备历史删除时擦除全部密码副本
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；构造历史正文与缓冲原件、引用、prekey_wait并存；真实批准删除后关闭重开，各副本均不可读；注入无codec、擦除失败、快照写入失败，审批决定与所有数据全部回滚。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_WYDG0K0792M84E7Z` [CORE-D1 P1] 将定时器消费与状态及效果原子提交
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；在消费后、状态及效果落盘前逐点注入失败和进程中断；关闭重开后原到期事件仍可执行，成功提交仅一次，旧代定时器不得驱动新状态。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_1Z7N1G88KKJ0DD8R` [CORE-D8 P1] 保留提前Gone全部到齐时的放行效果
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_73T31Y3GSPKQPM9Z"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；固定原件1和3、缺0和2，全部设备提前声明0不可提供；到期必须输出AbandonPosition(0)及下一缺口2的等待，密码与业务游标一致；注入重复、崩溃重开及迟到声明。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_3SXE6FJCW7SGXSKS` [TM-D1 P2] 统一检查及捕获运行器的有界进程组清理
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 隔离进程复现leader先退出、后代忽略TERM、超时和取消，运行器返回前所有拥有后代退出；不误杀其他进程，后续模型检查不遗留端口或锁。 Windows真实运行沿用既定CI延后边界，已加入必需检查；本机ABI/调用顺序回归不冒称Windows实机通过。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_15XXFKGF6EBXP2BC` [TM-D2 P2] 将冻结产物绑定完整构建期间的固定源码
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 在普通和优化debug及SDK分支注入构建中修改、切换HEAD和新增文件，拒绝冻结及通过回执；源码未变仍能构建，实际产物hash、source和配置一致。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_53VJV7EHG26CB8TW` [TM-R1 P2] 明确拒绝模型工具的矛盾结论
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 隔离工具退出0且输出violation、无结论、非0和0+ok四种情况，只有明确成功无violation通过；不宣称现行Quint实际曾假绿。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_CEVAADPNQFF65PP2` [TM-D3 P2] 补齐正向模型登记并防止漏接
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 明确注册14个正模块全部安全及需要的时序性质，实际quint verify给出结论；移除任一承担义务的注册可被守卫拒绝，负向变体不误当正向模型，类型检查不冒充性质验证。 全仓扫描另发现6个未单列的Checks候选：mappedPriorityTraceChecks、approvalStoreChecks、historyPlaceholderChecks、historyOriginalAvailabilityChecks、historyPendingReleaseChecks、viewedJournalChecks。逐一核对是否仅观测/分解证明别名或真遗漏；仅有可核实覆盖才免重复求解，不能直接当作已证明。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_K0XA2W2S9A1A36XW` [AU-D2 P1] 接通普通联系人首次建组与聊天
  - relates: ["REQ_20260918_1VXJQ68HXHM0JR0Q", "REQ_20260918_KWW858WDBR49C9KS", "REQ_20260918_A1MZYZ7JV43G7EJ3", "REQ_20260918_JW13382752N6T1XR", "REQ_20260918_N2K56BGH73GX0YCB"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_WYDG0K0792M84E7Z", "TASK_20261105_1Z7N1G88KKJ0DD8R", "TASK_20261105_70MWKJ214F6K3H3B", "TASK_20261106_A89B6DMN7WZFW48H"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；从两个干净产品实例完成交换及确认，不调用测试专用group_create或手动net_send；双方不打字也完成首条认证组操作，随后真实表单双向发送成功；注入断网和失败后重开可恢复。用户2026-11-06明确要求：初始化设备离线或丢失时，其它在线合法设备自动接替，不要求等待原设备或手动重新交换。须先用Quint验证并发创建、接替、冲突恢复及失败/重启/迟到结果；安全不依赖各端即时收到相同名单，完成保证注明网络/调度前提。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)




- [x] `TASK_20261106_KS6KE29SZHGZQMXX` [AU-D2.1 P1] 验证自动接替、私有发现及冲突切换契约
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_70MWKJ214F6K3H3B"]
  - verify: 核对[候选设计](../../../engineering/decisions/contact-bootstrap-takeover.md)，先Quint检查静默/授权/代次、固定尝试期限、并发候选、迟到结果、重启及消息/秘密保留；关键错误变体有实际反例。条件进展不得暗含原设备在线、相同完整名单或已知其它设备地址；明确密码与发现边界，模型不冒称实际API已支持。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261106_5QSVAWH365EDWNBW` [AU-D2.2 P1] 保存引导能力、候选和接收保留区的原子状态
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261106_KS6KE29SZHGZQMXX", "TASK_20260919_GWD9N0YMH757CHCV", "TASK_20260919_FMT2MTRTWPK5HMG5"]
  - verify: 当前联系人代次/设备授权/尝试作用域和版本在提交时重查；初始同步须先核验并交齐来源已知的本人与对端吊销事实，收到历史关系/能力不等同于当前设备授权完成。私有页仅在目标持久化后收到认证确认才放行，准备、提交和原密文重试均重查；证明通道覆盖未建立、拉黑及删除关系，签入阶段原件/确认和后台重试可恢复。完整模型与Rust/SQL轨迹对拍及headless产品组合验收通过。能力、最小授权/接收证书、定位代次/旧位置维护游标、候选密码快照、原件及可靠任务原子持久，失败/真实进程中断后可重试；定位旧结果不得推进新代次，缺记录不任意跳转，维护按固定工作预算分批；消息/日志标识不回退，删除/拉黑/过期/擦除及资源计量覆盖全部新副本，旧结果不恢复权限。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261106_KX7S78A8FQE811G5` [AU-D2.3 P1] 接通多候选密码身份、非零游标创建及安全切换
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261106_KS6KE29SZHGZQMXX", "TASK_20261106_5QSVAWH365EDWNBW"]
  - verify: 先用真实签名与密码回放验证候选设计可行，再接Rust；非零原生日志位置创建、跨初始组冷接入、退役组迟到接收及禁止退役出站正确；原受众保护和统一消息身份保留，错误候选/来源/签名/密文不消耗当前状态，旧格式拒绝。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261106_24PEVXETDE0NDVEJ` [AU-D2.4 P1] 驱动私有发现、首条认证操作和自动接替
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261106_KS6KE29SZHGZQMXX", "TASK_20261106_5QSVAWH365EDWNBW", "TASK_20261106_KX7S78A8FQE811G5", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 应答方静默直到验明发起方首条操作；原交换/初始化设备失联，其它已同步授权设备可经私有发现接替，不依赖预置完整对端名单或地址。维护可靠公告/补包/候选原件及公平帮助者选择；先建模并实际覆盖同一用户多台在线设备交替发布接收证书/提示的单槽竞争，验证有预算的选择/交接，不将单接收者公平窗口假设当作多设备实现。单次固定期限、断网/取消/重启及迟到结果有界，不放宽既有实时/历史预算。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261106_A89B6DMN7WZFW48H` [AU-D2.5 P1] 验收干净产品首次开聊和分区后的自动接替
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261106_24PEVXETDE0NDVEJ", "TASK_20261105_9DMRFXGW8T6DYNZT", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_73T31Y3GSPKQPM9Z"]
  - verify: 仅用真实产品身份/设备/邀请应答表单建立两个用户及多设备，不预建组、不直接net_send。覆盖无人打字首条操作、双向首条消息、初始化设备离线/丢失、原交换两设备失联但副本可恢复、分区中并发创建、迟到旧组消息、重启及故障；共同消息集合/统一顺序/不重复/原受众与草稿均验证，固定负载性能仍由整体验收承担。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_9DMRFXGW8T6DYNZT` [AU-D3 P1] 接通洁净本人设备及无用户私钥设备加入
  - relates: ["REQ_20260918_2PEVDK1HG1EK3KSW", "REQ_20260918_WRGB8QE5N650VWDK", "REQ_20260918_Y1KSJ34NKQH17Z08", "REQ_20260918_VMJVPZMK63123DKH", "REQ_20260918_5A7WDB3CTV03FJTW", "REQ_20260918_R1J6B5CH21YFFP32", "REQ_20260918_H0XN0536A5P1WZVG", "REQ_20260918_V6NGSKDD26FWGEZ4", "REQ_20260918_Z1P2TGCMD0K4W1XP"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_FPPHTG4KJM9PV037", "TASK_20261105_D3SMS2ZCBZFETVWX", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；仅使用产品入口完成恢复短语设备和无用户私钥设备加入；真实交换地址及根签授权、本人同步可用；最小设备授权/接收证书及来源已知吊销原件在设备可用之前交付并重查，不能先宣告可用再补证明。注入拒绝、过期、断网、重启及目标替换，不借助直接IPC预建名单。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_CTXDXSBVFZNMNBG3` [AU-D4 P1] 接通默认启用的空闲自动锁定
  - relates: ["REQ_20260918_YQW27DXZJSHAR0Z0"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_R85820GQZ7HHHRNY", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；配置默认与自定义空闲期限，只有真实用户活动续期，后台收包不得续期；截止后数据库、UI秘密和旧异步结果一起失效；注入锁定同时到达的旧结果，解锁后恢复正常。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_37JJH7NT87XJQXNS` [AU-D5 P2] 分开发送提交结果与视图刷新失败
  - relates: ["REQ_20260918_JW13382752N6T1XR", "REQ_20260918_R5JVF7M78YZQKN8H"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: chat_send成功后分别注入窗口、联系人和等待查询失败，成功提交不得显示发送失败，原草稿只清除一次；真实发送失败保留草稿，期间改写草稿不被旧结果清除。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_1A2PCP8532001PJ3` [AU-D6 P2] 删除历史时失效在途搜索结果
  - relates: ["REQ_20260918_F7ZBKSBAC1N4KM2K"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；延迟搜索及翻页结果，删除联系人并清历史后再放行；已删除片段不得复活，其他查询及新搜索正常，注入删除失败保持正确可见状态。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_9YSXEE2Z3TB0AQBE` [AU-D7 P2] 同步用户私钥后刷新邀请与应答能力
  - relates: ["REQ_20260918_ZVSZ6JT217MBK53K"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 在无用户私钥设备保持当前页面，完成双端授权同步后真实邀请和应答按钮可用；取消及失败不得提前获得能力，旧会话结果不得改变新身份。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_SHFGY5ZFTPM84P6Z` [AU-D8 P2] 在产品界面展示设备安全告警
  - relates: ["REQ_20260918_2PEVDK1HG1EK3KSW", "REQ_20260918_H0XN0536A5P1WZVG"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 真实接收缺加入公告的本人名单和不含交换设备的首份对端名单，经生产自动网络驱动展示准确告警；重复、重启及告警后名单变化不丢失或误指设备。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_FPPHTG4KJM9PV037` [AU-D9 P2] 加入原件重发前重查当前授权
  - relates: ["REQ_20260918_9YNQV83FP0G2FV42", "REQ_20260918_PYQVKZPN5ZS9HWSA"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_WPP64DJDH6659YMQ"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；已派发Add尚未收到安装确认时吊销必需设备，触发下一次重试，网络不得收到越权旧Add；合法未吊销对象仍可恢复；注入重启、授权变更和旧完成事件。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_NYFM119RV3RAHBX6` [CORE-D2 P2] 删除联系人历史时支持同身份冲突变体
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 两份合法同身份不同证书正文及普通正文并存，预览和批准不触UNIQUE失败，全部变体及引用清除、跨设备指令无重复身份；注入提交失败保持原集合。 保留既有10万条删除用例的原始数据量和五秒SQL预算，调查并修正2026-11-06完整及隔离运行均出现的ContactRemoval(Timeout)，不得放宽截止或删断言。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_70MWKJ214F6K3H3B` [CORE-D3 P2] 联系人确认与待确认项清理原子提交
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；逐点注入建立关系、设备映射和过期记录写入失败及进程中断；待确认项可重试或关系完整建立，不能出现两者皆无；重复确认保持幂等。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_1CJ5K9FYZEQ81SR1` [CORE-D4 P2] 为提前恢复事实及机器状态设有界计量
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；低于帧速率持续提交不同位置的合法提前Gone，极小存储上限内状态及资源有界并准确暂停；合法提前答复仍可参与补齐；注入重启、错误作者、删除退款与事务失败。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_J4SH80MPQS65DXP8` [CORE-D6 P2] 历史时间范围只披露被批准的正文
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；同作者日志0/1/2接收时间1000/9000/2000，批准[1000,2000]不得披露位置1正文或opening；保留恢复需要的原件连续性且不扩大内容许可；测试乱序时间、时钟回退、取消及迟到准备。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_A31603D5ZGRYXYQY` [CORE-D7 P2] 历史接收计量使用真实本人用户归属
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_73T31Y3GSPKQPM9Z"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；同一本人其他设备正文使用真实归属计费为0，不因较小对端限额拒绝；对端正文仍准确收费，注入提交失败回滚计量、回执和内容。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_AA698EPMTK2M92QY` [PN-D1 P2] 逐条隔离缓冲正文的内层绑定错误
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；真实签名与密码状态中先缓冲错误内层绑定正文，再保存重开并补真实依赖控制；控制及其他合法正文可提交，本次直接提交的坏帧仍拒绝，注入候选事务失败不推进密码。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_D3SMS2ZCBZFETVWX` [PN-D2 P2] 使重复长期公钥包幂等且登记有界
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；同一有效长期包以递增公告序号重复1000次并消耗一次性公钥，全设备状态不线性累积相同长期包；合法换代及迟到欢迎仍可解，注入保存失败不消耗私料。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_ME36G1H7MFHJVJWK` [PN-D3 P2] 使真实incoming.await遵守物理连接预算
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；真实端点以产品相同incoming.await路径验证limit=0及limit=1占满、draining、取消后预算，拒绝前不得新增越额driver；保留显式accept与出站路径。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_5EHNG5Q2N67NVP4R` [PN-D4 P2] 修正relay丢弃超大段后的批次输出前缀
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；混合超大段和有效报文、多槽及后续poll，返回的前n槽全部已写且payload、meta、source准确，无漏包或旧槽数据；关闭和空队列仍正确注册唤醒。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_JR8AMV930P0P27VC` [PN-D5 P2] 非home relay空闲期限不被空flush续期
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_P2HD03NJRM84P241"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；用可控时钟模拟连接失败及空队列flush，非home固定闲置期限到达即回收，无无限退避重拨；真实有用流量、home连接、取消和迟到结果各自保持契约。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_184YWJPY0NR51MDD` [PN-D6 P2] 原子更新home relay状态
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；确定性交错旧actor检查与新home设置或clear，旧更新不得写回旧URL；同URL当前actor正常更新，重启、取消及迟到状态不得复活旧所有者。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_N0RQFMSPH7XXMFBJ` [PN-D7 P3] 使上游测试辅助器重复移除固定点有界结束
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；真实AckedTestDgm序列create、remove、迟到add、重复remove在固定截止前结束并保留预期成员事实；不通过简单截断隐藏未收敛状态，产品DGM路径不回退。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_YPAH5T3KWHKKYV5B` [RQ-D1 P2] 提供发布构建多实例及显式放弃恢复入口
  - relates: ["REQ_20260918_G2S3MXJ3J0HXYXYP", "REQ_20260918_VMJVPZMK63123DKH", "REQ_20260918_B9Y4E73VMQ8RS99X", "REQ_20260918_RCMJA190DC6RY6DW"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_R85820GQZ7HHHRNY", "TASK_20261105_Y730729MBTAQEJ8S", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_CNDAGMZJP5S3CJ6R"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；非e2e配置真实创建两个用户及同用户两设备，切换要求重新解锁并清理旧异步所有权；恢复失败显式另起目录且原目录字节不变，不以测试专用实例参数代替产品入口。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_TSZKDF1W4GGW29BN` [RQ-D2 P2] 提供本机设备别名设置与同步入口
  - relates: ["REQ_20260918_VWTKG0AZG2084W8K"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_9DMRFXGW8T6DYNZT", "TASK_20260919_P2HD03NJRM84P241", "TASK_20260919_73T31Y3GSPKQPM9Z", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 真实表单修改本机别名并持久根签，经生产路径本人其他设备与联系人看到更新；不得修改他人设备，缺私钥、超长、保存失败有明确处理。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_W1MD8DCSW9J6H4HE` [AU-R2 P3] 修正锁定后键盘验收的静默跳过
  - relates: ["REQ_20260918_3AEMNVEGME49ZT2R", "REQ_20260918_B9Y4E73VMQ8RS99X"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_0R2EC1M6896HVHT7", "TASK_20260919_CNDAGMZJP5S3CJ6R", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 锁定再真实解锁后执行联系人及开聊键盘流程；缺必需DOM必须失败不能return成功；旧草稿和搜索清空，合法空联系人场景单独断言。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_N93G5377YV94JM5K` [CORE-R1 P2] 消除当前位置区间读回的逐位置展开成本
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先度量连续与稀疏区间在1000、10000和更长历史的恢复成本；保持区间操作而非逐位置扩张，成员、并集、缺口及JSON往返与独立算例一致，注入边界溢出与非法范围拒绝。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_BJY8WD26FKNYAVR7` [PN-R1 P2] 验证并修正握手饱和下联系人重连公平性
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；固定截止内占满陌生握手后发起真实联系人重连，核查预留、公平调度与关闭；已证缺口先建模后修正，不信任握手前自报身份或放宽总预算；不能复现时给出可检验的非缺陷依据，不以未测试关闭。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_7XSP10XHSJ63K5M9` [PN-R2 P2] 验证并消除IPv4持续就绪对IPv6的饥饿
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；确定性两IP lane及有界真实端点中让v4持续ready，v6已有数据仍在有界轮次被读；空队列唤醒及关闭不忙循环，不以custom lane测试代替IP路径。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_K0BAB8QKCJRY0EFN` [PN-R3 P2] 给地址发现响应及发布设置字节与时间边界
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；本地替身HTTP流发送超大、分块、挂起和迟到响应，格式最大包长前即拒绝超限，解析及发布共用固定截止；合法响应继续验签，旧发布不得覆盖新地址。 远端不回退依赖PKARR服务按签名时间戳保留较新记录；取消不能撤回服务器已收PUT，不向任意恶意重放服务器承诺新鲜性。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_SH7T2GYT25SPNME3` [AU-C1/TM-C1 P3] 移除IPC、UI及模型的废弃骨架证明面
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20260919_TE9DN97R1P2SBQ18"]
  - verify: 核对全部消费者后删除skeleton_step、appName专用平凡断言、skeleton.qnt及manifest注册；保留真实外壳和截图断言，当前身份及聊天入口测试仍有效；同时覆盖TM-C1。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_TAAQF10Z25XRYRMC` [AU-C2 P3] 收窄仅测试使用的未分页IPC命令
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_K0XA2W2S9A1A36XW"]
  - verify: 核对Rust及E2E消费者，把确认仅供测试的raw/full-history/旧search命令限制于e2e；生产保留有界window/search_page；发布注册不再暴露测试面且真实测试仍可运行。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_B8EGK05S2J3M60SJ` [CORE-C1 P3] 移除无生产消费者的公告标记
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 确认announced_to与devices_to_announce/mark_announced无生产调用后删除类型、schema及测试专用旧标记；当前BundleDelivery可靠公告与重启测试保持，不加入旧库迁移。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_CQBEGNSB64NTZVTC` [CORE-C2 P3] 移除PosSet及位置数值旧布局兼容读取
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_N93G5377YV94JM5K"]
  - verify: 仅接受当前区间和位置编码，旧逐位置数组及裸数字明确拒绝；改写夹具走当前布局，当前区间边界与重启保持，不新增格式猜测或迁移。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_GT1MKN5QYKTZQC65` [CORE-C3 P3] 移除测试独占的单公钥StoreAdmitter兼容接口
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 消费者转为当前设备绑定准入或明确测试支持，删除旧单公钥回退；合法设备准入与跨用户冒用拒绝回归保留，不削弱AdmissionFacts生产投影。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_366WYP4SM96N7XKA` [PN-C1 recommendation] 移除无序号KeyBundle旧载荷正向支持
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 删除旧正向编码、解码结果及分支，测试和压力夹具改为当前BundleDelivery收件人及serial；保留旧/未知格式拒绝负例和当前可靠公告，不加兼容回退。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_Q52EVWVDA1QDV8S0` [PN-C2 recommendation] 移除仅测试使用的重复公告解析入口
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 所有需要的测试改走统一open_message校验，移除open_announcement公开分支；目的标签、作者、签名、尾随数据及当前格式拒绝能力保持。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_SDYE9KF0JCXSK3FT` [PN-C3 recommendation] 精简唯一DGM的Family及泛型桥接
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_AA698EPMTK2M92QY"]
  - verify: 先完成本项Quint安全/条件结束与错误变体检查，再实现Rust/UI并与真实事件对拍；以当前具体消息/状态类型替代不存在的第二协议族分派；保留Waiting与Established及欢迎前缓冲，布局变化只更新当前tag并拒绝旧格式；真实密码、快照和失败回滚通过。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_34H3VM38CYQAKKYQ` [PN-C4 recommendation] 去重同一DGM的重复双实例测试
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_SDYE9KF0JCXSK3FT"]
  - verify: 按断言及事件轨迹核对重复，只合并重复部分；当前恢复、失败、拒绝旧格式和成员顺序的独立覆盖不减少，不以文件名相近判断重复。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_34YD7YQCRA3B7B3V` [PN-C5 P3] 接入或替换未编译的重入安全回归
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 逐项核对两份1075行孤立测试的独有义务；有价值者适配当前certificate/secrets并接入执行，重复者删除且注明替代测试；故意破坏相关守卫时实际失败，文件存在不算通过。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_PEEGYJ2F17G3YAVF` [TM-C2 P3] 精简历史复现源码的重复及失效副本
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09"]
  - verify: 按消费者与独有故障选取最小原件，删除可再生重复并整理引用/保存声明；保留固定历史提交与原始哈希含义，不将调查结论或不可替代原件当缓存删除。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_J1Z89VK4BQW49F77` [TM-C3 P3] 合并三组重复状态轨迹的存储
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_6CDDFWKVPS6MEP09", "TASK_20261105_CEVAADPNQFF65PP2"]
  - verify: 逐组核对消费者、生成器、固定文件名与数量断言，共用原件但保留两种性质及事件检查；真实导出和Rust/UI回放仍覆盖原场景，不以删除断言换通过。
  - evidence: [逐项模型义务及不可外推边界](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--model-obligations)


- [x] `TASK_20261105_NJDK86BZVP0DHH4M` 完成全批配置及真实产品路径验收
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_Y730729MBTAQEJ8S", "TASK_20261105_R85820GQZ7HHHRNY", "TASK_20261105_1XXKK4HAPQF0AP3H", "TASK_20261105_WYDG0K0792M84E7Z", "TASK_20261105_1Z7N1G88KKJ0DD8R", "TASK_20261105_3SXE6FJCW7SGXSKS", "TASK_20261105_15XXFKGF6EBXP2BC", "TASK_20261105_53VJV7EHG26CB8TW", "TASK_20261105_CEVAADPNQFF65PP2", "TASK_20261105_K0XA2W2S9A1A36XW", "TASK_20261105_9DMRFXGW8T6DYNZT", "TASK_20261105_CTXDXSBVFZNMNBG3", "TASK_20261105_37JJH7NT87XJQXNS", "TASK_20261105_1A2PCP8532001PJ3", "TASK_20261105_9YSXEE2Z3TB0AQBE", "TASK_20261105_SHFGY5ZFTPM84P6Z", "TASK_20261105_FPPHTG4KJM9PV037", "TASK_20261105_NYFM119RV3RAHBX6", "TASK_20261105_70MWKJ214F6K3H3B", "TASK_20261105_1CJ5K9FYZEQ81SR1", "TASK_20261105_J4SH80MPQS65DXP8", "TASK_20261105_A31603D5ZGRYXYQY", "TASK_20261105_AA698EPMTK2M92QY", "TASK_20261105_D3SMS2ZCBZFETVWX", "TASK_20261105_ME36G1H7MFHJVJWK", "TASK_20261105_5EHNG5Q2N67NVP4R", "TASK_20261105_JR8AMV930P0P27VC", "TASK_20261105_184YWJPY0NR51MDD", "TASK_20261105_N0RQFMSPH7XXMFBJ", "TASK_20261105_YPAH5T3KWHKKYV5B", "TASK_20261105_TSZKDF1W4GGW29BN", "TASK_20261105_W1MD8DCSW9J6H4HE", "TASK_20261105_N93G5377YV94JM5K", "TASK_20261105_BJY8WD26FKNYAVR7", "TASK_20261105_7XSP10XHSJ63K5M9", "TASK_20261105_K0BAB8QKCJRY0EFN", "TASK_20261105_SH7T2GYT25SPNME3", "TASK_20261105_TAAQF10Z25XRYRMC", "TASK_20261105_B8EGK05S2J3M60SJ", "TASK_20261105_CQBEGNSB64NTZVTC", "TASK_20261105_GT1MKN5QYKTZQC65", "TASK_20261105_366WYP4SM96N7XKA", "TASK_20261105_Q52EVWVDA1QDV8S0", "TASK_20261105_SDYE9KF0JCXSK3FT", "TASK_20261105_34H3VM38CYQAKKYQ", "TASK_20261105_34YD7YQCRA3B7B3V", "TASK_20261105_PEEGYJ2F17G3YAVF", "TASK_20261105_J1Z89VK4BQW49F77", "TASK_20260919_TE9DN97R1P2SBQ18", "TASK_20260919_CNDAGMZJP5S3CJ6R", "TASK_20260919_GWD9N0YMH757CHCV"]
  - verify: 48项处置都有实际观察与反例/非缺陷依据；完整配置检查、发布构建、真实原生联系人从零交换开聊、本人设备加入、锁定/多实例、别名/告警、删除/搜索、1000历史与一万条规模恢复均通过；注入断网/双方重启、失败/取消/迟到，性能按同快照正常及混合分别对照，不放宽600帧/分钟、4槽、30秒/5次，不以测试IPC补上缺失产品入口。
  - evidence: [本批实际验证、历史失败与剩余限制](../20260918-yochat-f2f-messenger.md#DOC_20260918_BFV4FCXTMM9MQRDD--verification)


- [x] `TASK_20261105_T7SDDPRAB87VH9A5` 按审查编号定向复核并集成清理
  - relates: ["CHG_20260918_NAHYZQ1MM6ZYT4S1"]
  - depends_on: ["TASK_20261105_NJDK86BZVP0DHH4M"]
  - verify: 逐项对29缺陷、7风险、13原建议（骨架合并）给出处置、实际执行及剩余限制；使用已授权本批第二轮定向复核，不重置轮次。无未解决阻断、范围无遗漏、工作区干净后按用户集成授权合入；不推送，不提交原始日志/数据库/可再生产物。
