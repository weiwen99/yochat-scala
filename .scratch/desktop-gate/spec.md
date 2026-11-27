# desktop-gate: 桌面门禁在 GUI 会话中运行

Status: implemented. Unplanned fix: no plan number, branch `fix/s-desktop-gui-session`
per `docs/agents/git-workflow.md` (`s-` 前缀用于小型非计划修复). 本 spec 是 live
状态；durable 记录是两条提交正文（`fix(shell): 桌面门禁在 GUI 会话中运行…` 与
`fix(shell): 关闭桌面门禁评审发现`）。Base: `c415a07`.

## 规模

| 维度 | 用户要的 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 需求条数 | 4 处缺陷 + 1 条可复现运行路径 = 5 | 3 处代码修复 + 1 个 runner + 1 个门禁 + 2 处文档 = 7 | 1.4× |
| 表数 | 0 | 0 | — |
| 组件数 | 0 新抽象 | 2 个新文件（`desktop-gate.sh` 248 行、`shell/passphrase-smoke.js` 186 行） | 需解释 |
| 配置项 | 2（超时、注入模式） | 3（另加 `YOCHAT_KEYCHAIN_UNAVAILABLE` 门禁钩子） | 1.5× |
| 工程师日 | 0.5（估算） | 1.0（估算，含两轴评审与修复） | 2× |
| 发布波次 | 1 | 1 | 1× |

组件解释：不把这段逻辑塞进 `check.sh`，是因为 `check.sh` 是线性的「跑哪些门」清单，
而这里要表达「以什么上下文跑、跑多久算超时、环境事实记进哪份回执」。塞进去会让
`check.sh` 变成框架；独立 runner 后 `check.sh` 仍然唯一决定跑什么。第二个新文件是
仓库规则要求的失败用例（AGENTS.md §6「每个 bug 修复先写失败用例」），不是新抽象。

工程师日解释：初估 0.5 只含实现；两轴评审提出 21 条发现，修复与复跑使实际翻倍。
数字是估算，不是实测。

## 观察到的缺陷（2026-11-27，m1，commit c415a07）

1. `shell/main.js` 的 `keychainAvailable()` 返回 `Promise`。`if (!keychainAvailable())`
   恒为假，钥匙串不可用时 `passphrase-save` 调用同步 `encryptString` 抛错，而不是返回
   `false`。差分实测：旧写法 `rejected`，新写法 `returned false`。
2. `shell/keychain-smoke.js` 在钥匙串不可用时因 unhandled rejection 挂死；实测 180s
   未退出（不是红色失败）。
3. 经 ssh 直接启动的 Electron 在 Background 会话里创建钥匙串条目失败：
   `errSecInteractionNotAllowed`，`keychain-smoke` 报错；`launchctl asuser 501`
   注入后 `KEYCHAIN_SMOKE ok`。
4. Electron 45.4.1 已弃用 `safeStorage.encryptString/decryptString/isEncryptionAvailable`，
   替代为 async 版本（`shell/node_modules/electron/electron.d.ts` 实测）。

## 目标

- keychain 不可用时优雅降级：`passphrase-available` 返回 `false`，`passphrase-save`
  返回 `false`，`passphrase-load` 返回 `null`，不抛错。
- `keychain-smoke` 任何分支都以明确退出码结束；30s 看门狗兜底。
- `desktop-gate.sh`：Aqua 内直跑，否则 `launchctl asuser` 注入；`caffeinate -dims`；
  每条门禁超时；超时判 `INCOMPLETE` 且非零退出；回执记录提交、工具版本、注入模式、
  实际会话、透传环境名、显示器与锁屏状态。
- `check.sh` 的桌面门经 runner 执行；`passphrase-smoke` 覆盖 Aqua、Background 与
  强制不可用三条路径。
- `docs/agents/verification.md` 记录运行规程与未验证项。

## 非目标与已知约束

- 不验证「真正锁屏」分支：锁屏需要用户口令才能恢复，测试期间无法自解。回执记录
  锁屏状态，锁定态下的行为标记未核实。
- 不改 UI（`UnlockView.scala` 已把 `keychainAvailable()` 当 Promise 用，契约不变）。
- 不引入 CI 配置；当前仓库没有 CI。
- 不改变任何消息、存储、协议行为。
- `passphrase-load` 不区分「文件不存在」与「存在但不可读」，两者都返回 `null`。
  这是既有 IPC 契约（改动前同样返回 `null`），改动需要 UI 设计与接口变更，单列。
- `decryptStringAsync` 的 `shouldReEncrypt` 标记不处理：忽略只影响密钥轮换后的重新
  加密时机，不影响正确性。
- 看门狗统一由 bash 实现（不再依赖 GNU `timeout`）。门禁与看门狗各自独立进程组：
  超时、Ctrl-C 或 ssh 断连都会连带杀掉整棵树。看门狗的输出重定向到 `/dev/null`，
  否则其 `sleep` 会继承调用方管道、让 `\| tail` 永远等不到 EOF（实测过）。
- `DEGRADED` 模式（非 Aqua 且无免密 sudo）仍会运行门禁，keychain 类门会失败；该分支
  没有回执。`mode=direct` 路径有回执覆盖。
- `keychain-smoke` 使用 `electron <script>` 身份（`Electron Safe Storage`），不是产品
  的钥匙串条目；产品路径由 `passphrase-smoke` 覆盖。
- 性能门要求 8791–8797 端口空闲。`main.js` 的 `serverIsUp()` 在端口已有服务时不再拉起
  自己的 JVM，而 idle 阶段要求进程树里必须有 JVM；因此上一次被中断的运行留下的实例会
  让下一次性能门报「idle process tree does not contain the owned JVM」。runner 现在
  会清理进程组，但手工中断 runner 之外的运行仍可能残留。

## 评审裁决（对象 `b500bbf`，两轴共 21 条）

| 编号 | 来源 | 发现 | 结论 |
| --- | --- | --- | --- |
| S1/P8 | Standards/Spec | 无 GNU timeout 时超时判 FAIL、只杀直接子进程 | fix：看门狗先置标记，任何超时归一到 124/INCOMPLETE；still 只杀直接子进程，列入已知约束 |
| S2 | Standards | `passphrase-load` 把读/解失败并成 `null` | defer：改动前同样返回 `null`，不是本次引入；改契约需 UI 设计 |
| S3 | Standards | `contrast-smoke` 不依赖 GUI 却被绑上会话前置 | fix：改回直跑 |
| S4/P2 | Standards/Spec | asuser 注入丢弃全部 `YOCHAT_*` | fix：白名单透传并在回执记录变量名 |
| S5/P1 | Standards/Spec | 降级分支无覆盖，用例对旧实现全绿 | fix：加 `YOCHAT_KEYCHAIN_UNAVAILABLE` 钩子，`check.sh` 跑 Aqua 与不可用两条；差分证据留档 |
| S6/P5 | Standards/Spec | 回执 `commit` 锚在 c415a07，无法对应被测代码 | fix：回执记录 `head` 与 `dirty_files` |
| S7 | Standards | 回执缺工具版本/输入/日志；`manager` 语义不清 | fix：补 node/electron/head/log/`session`（执行上下文），`caller_manager` 单列 |
| S8 | Standards | 规模表数字自相矛盾、行数与组件数不实 | fix：本文件重算 |
| S9 | Standards | 提交正文「5 个桌面门」口径不明 | fix：本轮提交正文更正为「4 个原有 + 2 个新增 + 1 个条件」 |
| S10/P4 | Standards/Spec | 文档称熄屏验证但无回执 | fix：补 `display_before=off` 的 electron-smoke 回执 |
| S11 | Standards | `screencapture` 半句是自设靶子 | fix：删掉，改为正面事实（截图路径入回执） |
| S12 | Standards | `npm run` 绕过 runner；`passphrase-smoke` 无看门狗 | fix：文档限定适用面；加 180s 看门狗 |
| P3 | Spec | 提交正文的差分结论与回执矛盾 | fix：正文改为可复现口径，差分输出留档 |
| P6 | Spec | UI「记住口令」路径未覆盖，注释夸大 | fix：Aqua 先断言 UI 写入文件，再断言 bridge 契约 |
| P7 | Spec | DEGRADED 前提未写入约束、无回执 | defer：写入已知约束；direct 路径留回执 |
| P9 | Spec | spec 无 Plan 号与 impl 记录 | 部分 fix：标注 unplanned fix 并补验证/缺口；拒绝编造 plan 号（0008 已被占用） |

## 验证

在 m1（Electron 45.4.1、node v24.22.2）实际运行；回执在 `tmp/checks/`，每份记录
`head` 与 `dirty_files`。

- 差分：Background 下旧写法 `rejected: … not available`，新写法 `returned false`。
- `keychain-smoke` 经 runner：PASS，`session=Aqua`；直连 ssh：exit 1 且秒级结束。
- `passphrase-smoke`：Aqua PASS（UI remember 写文件、bridge save/load、重启预填）；
  Background PASS（布尔契约）；`YOCHAT_KEYCHAIN_UNAVAILABLE=1` PASS（false/null/无文件）。
- 超时：`YOCHAT_GATE_TIMEOUT=3` 与无 GNU timeout 的 PATH 下均为 `verdict=INCOMPLETE`、
  `exit=124`，无残留进程。
- 环境透传：`YOCHAT_TEST_VAR=hello` 在注入会话内可见，回执 `forwarded_env` 记录。
- `electron-smoke`：显示器开启与熄灭（`display_before=off`）两次 PASS，各 100 条双向、
  B/A 重启。
- `perf-start` 经 runner 两次 PASS（p50 1284/1222ms，p95 1338/1239ms，idle 747.2/763.5MB）。
  中间一次 FAIL 归因于上一次被中断运行留下的实例占住 8797，清理后复跑通过。
- 中断语义：SIGTERM 到 runner 时判 `INCOMPLETE`（exit 143）并写回执；超时与管道输出两条
  路径都不留孤儿，`\| tail` 能正常收尾。

## 验收

`./check.sh` 全绿；`git worktree list` 只剩主检出；`git branch --list 'fix/*'` 为空；
两个远端 `main` 与本地一致（推送由用户指令触发）。
