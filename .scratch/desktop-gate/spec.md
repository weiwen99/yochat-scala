# desktop-gate: 桌面门禁在 GUI 会话中运行

- Status: implemented, verified on m1
- Plan: 0014（durable 记录 `docs/plans/2026/11/0014-desktop-gate-session/`）
- Branch: `fix/s-desktop-gui-session`，base `c415a07`

## 规模

| 维度 | 用户要的 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 需求条数 | 5（4 处缺陷 + 1 条可复现运行路径） | 7（4 处代码修复 + 1 个 runner + 1 个新门禁 + 1 节规程） | 1.4× |
| 表数 | 0 | 0 | — |
| 组件数 | 0 新抽象 | 2 个新文件（`desktop-gate.sh` 300 行、`shell/passphrase-smoke.js` 186 行） | 需解释 |
| 配置项 | 2（超时、注入模式） | 3（另加 `YOCHAT_KEYCHAIN_UNAVAILABLE`） | 1.5× |
| 工程师日 | 0.5（估算） | 1.5（估算，含三轮评审与复跑） | 3× |
| 发布波次 | 1 | 1 | 1× |

- 组件解释：`desktop-gate.sh` 不进 `check.sh`，是因为 `check.sh` 是「跑哪些门」的
  清单，而 runner 表达的是「以什么上下文跑、跑多久算超时、环境事实记在哪」。第二个
  新文件是仓库规则要求的失败用例（AGENTS.md §6），不是新抽象。
- 工程师日解释：初估 0.5 只含实现；三轮两轴评审共提出 40 条发现（21 + 14 + 5），
  修复与复跑使实际约为初估 3 倍。表中数字是估算，不是实测。

## 观察到的缺陷（2026-11-27，m1，commit c415a07）

1. `shell/main.js` 的 `keychainAvailable()` 返回 `Promise`。`if (!keychainAvailable())`
   恒为假；钥匙串不可用时 `passphrase-save` 调用同步 `encryptString` 抛错，而不是返回
   `false`。
2. `shell/keychain-smoke.js` 在钥匙串不可用时因 unhandled rejection 挂死：实测 180s
   未退出，不是红色失败。
3. 经 ssh 启动的 Electron 在 Background 会话里创建钥匙串条目失败
   （`errSecInteractionNotAllowed`）；`launchctl asuser 501` 注入后通过。
4. Electron 45.4.1 已弃用 `safeStorage` 的同步 API 与 `isEncryptionAvailable`
   （`shell/node_modules/electron/electron.d.ts`）。
5. 被中断的门禁会把 Electron 与其 JVM 留在后台：实测让下一次性能门报
   「idle process tree does not contain the owned JVM」（`main.js` 的 `serverIsUp()`
   发现端口已有服务时不再拉起 JVM）。另实测看门狗子 shell 的 `sleep` 会继承调用方
   管道，使 `| tail` 永不结束。

## 目标

- 钥匙串不可用时优雅降级：`available=false`、`save=false`、`load=null`，不抛错。
- 所有门禁有界结束：`keychain-smoke` 显式退出码 + 30s 看门狗；`passphrase-smoke`
  180s 看门狗；runner 统一超时并按进程组收尾。
- `desktop-gate.sh`：Aqua 直跑 / `launchctl asuser` 注入 / degraded 拒跑；
  `caffeinate -dims`；`YOCHAT_*` 白名单透传；回执可锚定（head + dirty_files）。
- `passphrase-smoke` 覆盖 Aqua、Background、强制不可用三条路径，并对旧实现可报红。
- `check.sh` 的桌面门经 runner；`docs/agents/verification.md` 记录运行规程。

## 非目标与已知约束

- 真正锁屏分支未验证：解锁需要用户口令。回执记录锁屏状态，锁定态行为标记未核实。
- `passphrase-load` 不区分「文件不存在」与「存在但不可读」，都返回 `null`。这是改动
  前就有的 IPC 契约（`git show c415a07:shell/main.js`），改成可区分需要 UI 设计与接口
  变更，单列。
- `passphrase-save` 把所有失败（钥匙串不可用、写盘失败）并成 `false`，错误消息只进
  shell 日志。
- `YOCHAT_KEYCHAIN_UNAVAILABLE` 在打包版被忽略（`!app.isPackaged`），避免变量残留
  让真实用户失去「记住口令」。
- 门禁自行 setsid 出去的后代不在组杀范围内；当前没有门禁这样做。
- 性能门要求 8791–8797 端口空闲。
- `DEGRADED`（非 Aqua 且无免密 sudo）不运行门禁，写 INCOMPLETE 回执并 exit 2：
  会话配置不属于代码缺陷。
- Background 分支只能人工验证：`check.sh` 恒注入 Aqua，永不进入该分支。
- 回执只记录透传变量的名字，不记录值；取值保真由一次带空格与冒号路径的实测证明。
- `contrast-smoke` 是静态 CSS 检查，不启动窗口，故直跑、无 runner 回执与超时。
- 超时后先 TERM 再等 30s 才 KILL，真实上界是 `YOCHAT_GATE_TIMEOUT + 30s`。

## 三轮评审裁决

对象：`b500bbf`（第 1 轮）、`b500bbf..HEAD`（第 2、3 轮）。记录位置：本文件。

| 编号 | 来源 | 发现 | 结论 |
| --- | --- | --- | --- |
| S1/P8 | 标准/规格 | 无 GNU timeout 时超时判 FAIL、只杀直接子进程 | fix：改为 bash 看门狗 + 进程组，124/129/130/137/143 全判 INCOMPLETE |
| S2 | 标准 | `passphrase-load` 把读/解失败并成 `null` | defer：改动前同契约，改它需 UI 设计，已入已知约束 |
| S3 | 标准 | `contrast-smoke` 不依赖 GUI 却绑会话前置 | fix：改回直跑，并在文档写明它无回执、无超时的理由 |
| S4/P2 | 标准/规格 | asuser 注入丢弃全部 `YOCHAT_*` | fix：白名单透传 + 回执记名字 + 空格/冒号取值实测 |
| S5/P1 | 标准/规格 | 降级分支无覆盖 | fix：`YOCHAT_KEYCHAIN_UNAVAILABLE` 钩子，`check.sh` 跑两条；补对照组验证红因 |
| S6/P5 | 标准/规格 | 回执 commit 锚在 c415a07 | fix：回执记 head + dirty_files；HEAD 复跑 |
| S7 | 标准 | 回执缺工具版本/日志/执行会话 | fix：补 node、electron、log、session、caller_manager |
| S8/S9 | 标准 | 规模表与门禁计数不实 | fix：本表按实测重写（300/186 行，7 条 runner 调用） |
| S10/P4 | 标准/规格 | 声称熄屏验证但无回执 | fix：补 `display_before=off` 回执；HEAD 复跑时显示器被系统立即唤醒，如实记录 |
| S11 | 标准 | `screencapture` 半句自设靶子 | fix：删除，改为截图路径入回执（并限定「窗口已建立时」） |
| S12 | 标准 | `npm run` 绕过 runner；无看门狗 | fix：文档限定适用面；`passphrase-smoke` 加看门狗 |
| P3 | 规格 | 差分结论与回执矛盾 | fix：在 c415a07 的独立工作树里做控制变量对照，红回执锚 `head=c415a07`，复跑步骤写入 0014 `impl.md` |
| P6 | 规格 | UI remember 路径未覆盖 | fix：Aqua 先断言 UI 写文件，再断言 bridge |
| P7 | 规格 | DEGRADED 无回执、判红 | fix：degraded 不运行、写 INCOMPLETE、加 `YOCHAT_GATE_INJECT=degraded` 可测 |
| P9 | 规格 | 无 plan 号与 durable 记录 | fix：建 0014，并在 0009/0010 impl 追加执行方式变更 |
| R2-F1 | 标准 | Ctrl-C（130）/HUP（129）判成 FAIL | fix：纳入 INCOMPLETE；用 perl fork 构造真实 Ctrl-C 上下文实测 |
| R2-F2 | 标准 | Background 通过无可锚定回执 | fix：HEAD 上从 Background 会话直跑，回执 `dirtyFiles=0` |
| R2-F3 | 标准 | HEAD 无 124/143 回执 | fix：HEAD 复跑 124/129/130/2 |
| R2-F4 | 标准 | 裁决表「只杀直接子进程」与代码矛盾 | fix：本文件与提交正文同步 |
| R2-F5 | 规格 | 三条路径缺干净 Background 回执 | fix：同 R2-F2 |
| R2-F6 | 规格 | P7 延后理由不成立 | fix：见 P7 行 |
| R2-F7 | 规格 | 编号理由不成立 | fix：建 0014 |
| R2-F8 | 规格 | 行数与计数仍错 | fix：本表按 `wc -l` 与 `grep -c` 实测 |
| R2-F9 | 规格 | contrast 直跑无回执且与「每条门超时」冲突 | fix：文档改写为「每个 Electron 门禁」 |
| R2-F10 | 规格 | 钩子进产品路径；save 吞异常无记录 | fix：`!app.isPackaged` 守卫 + `console.error` |
| R2-F11 | 规格 | 环境透传只证名字不证值 | fix：空格与冒号路径实测，值原样可见（该实测锚 `ed11f6e`，转发循环此后未改） |
| R3-F1 | 标准/规格 | verification.md 把熄屏通过挂在 b696e37 | fix：改为锚 `8ad1d44`/`6a57864`，并写明 b696e37 重试被系统唤醒 |
| R3-F2 | 标准/规格 | degraded 回执缺锚定字段且 command 未转义、不是合法 JSON | fix：复用同一模板（head/dirty_files/session/log/耗时），`json.load` 通过 |
| R3-F3 | 规格 | 失败用例对照无锚定、无可复跑路径 | fix：在仓库内独立工作树复跑，回执 `head=c415a07`，步骤入 0014 |
| R4-1 | 规格 | impl 的 Background 时长与锚不符 | fix：改为回执实测 2.7s 并标回执名 |
| R4-2 | 规格 | impl 对红因的描述与回执相反（写成加密失败） | fix：改为「加密成功、返回 true、在 false 断言处变红」，并注明需 Aqua 会话 |
| R4-3 | 规格 | 行数 284 在 HEAD 已过期 | fix：改为 300（`wc -l` 实测） |
| R2 备注 3 | 标准 | 门禁自行 setsid 的后代不在组杀范围 | defer：入已知约束，当前无门禁如此 |

## 干净态回执（`b696e37`，`dirty_files=0`；`0bed255` 只改 degraded 分支与文档）

| 门禁 | 结果 |
| --- | --- |
| `keychain-smoke` 经 runner | PASS，2.0s，`session=Aqua` |
| `passphrase-smoke` Aqua | PASS，6.6s |
| `passphrase-smoke` 不可用钩子 | PASS，4.7s |
| `passphrase-smoke` Background（直跑，session=Background） | PASS，2.7s（`passphrase-1795764223531`） |
| `ui-smoke` | PASS，双向 100 条，25.7s |
| `electron-smoke` | PASS，双向 100 条 + B/A 重启，25.0s |
| `perf-start` | PASS，p50 1269ms / p95 1317ms / idle 741.7MB |
| 超时 / SIGINT / SIGHUP / degraded | INCOMPLETE，exit 124 / 130 / 129 / 2，无残留 |
| 失败用例对照（旧实现 c415a07 + 钩子） | FAIL，`head=c415a07`、`dirty_files=3`，红在 save 断言；步骤与回执见 0014 `impl.md` |

熄屏回执锚在 `6a57864`（`display_before/after=off`，electron-smoke PASS）。之后产品
代码只改了钥匙串钩子的 `isPackaged` 守卫与 save 的错误日志，不触及消息路径。在
`b696e37` 上重试熄屏时，显示器被系统在 1 秒内唤醒（同一分钟内两次），未再取得熄屏
回执；回执如实记为 `display_before=on`，不写成熄屏。

未跑：`check.sh` 的 Scala、模型与 spike 段。本次 diff 只改 `shell/`、`check.sh` 桌面
段落与文档；`c415a07` 的 closure full-gate 回执对这些段落仍然有效。

## 验收

`git worktree list` 只剩主检出；`git branch --list 'fix/*'` 为空；两个远端 `main` 与
本地一致（推送由用户指令触发）。
