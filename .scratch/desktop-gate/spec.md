# desktop-gate: 桌面门禁在 GUI 会话中运行

Status: implementing. Branch: `fix/s-desktop-gui-session`. Base: `c415a07`.

## 规模

| 维度 | 用户要的 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 需求条数 | 3 处缺陷 + 1 条可复现运行路径 | 3 处修复 + 1 个 runner + 1 节运行规程 | 1.4× |
| 表数 | 0 | 0 | — |
| 组件数 | 0 新抽象 | 1 个脚本（`desktop-gate.sh`，约 100 行） | 需解释 |
| 配置项 | 1 | 1（`YOCHAT_GATE_TIMEOUT`） | 1× |
| 工程师日 | 0.5 | 0.5 | 1× |
| 发布波次 | 1 | 1 | 1× |

组件解释：不把这段逻辑塞进 `check.sh`，是因为 `check.sh` 是线性的「跑哪些门」清单，而这里要表达的是「以什么上下文跑、跑多久算超时、环境事实记进哪份回执」。塞进去会让 `check.sh` 变成一个框架；独立 runner 后 `check.sh` 仍然唯一决定跑什么。

## 观察到的缺陷（2026-11-27，m1，commit c415a07）

1. `shell/main.js` 的 `keychainAvailable()` 返回 `Promise`。`if (!keychainAvailable())` 恒为假，钥匙串不可用时 `passphrase-save` 调用已弃用的 `encryptString` 抛错，而不是返回 `false`。
2. `shell/keychain-smoke.js` 在钥匙串不可用时因 unhandled rejection 挂死；实测 180s 未退出（不是红色失败）。
3. 经 ssh 直接启动的 Electron 在 `LaunchAgents`/Background 会话中拿不到登录钥匙串：`safeStorage` 报 `Encryption is not available`；`sudo launchctl asuser 501` 注入后 `KEYCHAIN_SMOKE ok`。
4. Electron 45.4.1 已弃用 `safeStorage.encryptString/decryptString/isEncryptionAvailable`，替代为 async 版本（`shell/node_modules/electron/electron.d.ts` 实测）。

## 目标

- keychain 不可用时优雅降级：`passphrase-available` 返回 `false`，`passphrase-save` 返回 `false`，`passphrase-load` 返回 `null`，不抛错。
- `keychain-smoke` 任何分支都以明确退出码结束；30s 看门狗兜底。
- 新增 `desktop-gate.sh`：Aqua 会话内直接跑；ssh 下用 `launchctl asuser` 注入；`caffeinate -dims`；per-command 超时；超时判 `INCOMPLETE` 且非零退出；回执记录显示器与锁屏状态。
- `check.sh` 的 5 个桌面门全部经 runner 执行。
- `docs/agents/verification.md` 记录运行规程与未验证项。

## 非目标与已知约束

- 不验证「真正锁屏」分支：锁屏需要用户密码才能恢复，测试期间无法自解。回执记录锁屏状态，锁定态下的行为标记未核实。
- 不改 UI（`UnlockView.scala` 已经把 `keychainAvailable()` 当 Promise 用，契约不变）。
- 不引入 CI 配置；当前仓库没有 CI。
- 不改变任何消息、存储、协议行为。
- `decryptStringAsync` 的 `shouldReEncrypt` 标记不处理：忽略只影响密钥轮换后的重新加密时机，不影响正确性。记为已知约束。
