# Plan 0014 implementation

## 状态

已实现并验证（m1，macOS arm64，Electron 45.4.1，node 24.22.2）。产品行为未变：
消息、存储、协议、UI 与 IPC 返回形状都不动。

## 交付范围

- `shell/main.js`：`keychainAvailable()` 改为 await 异步探针；`passphrase-save/load`
  走 `encryptStringAsync/decryptStringAsync`；不可用或失败时返回 `false`/`null`。
  `YOCHAT_KEYCHAIN_UNAVAILABLE` 仅在 `!app.isPackaged` 时生效。
- `shell/keychain-smoke.js`：所有路径显式退出（0/1/2/3），30s 看门狗，`fs.writeSync`
  保证判决行不被 `app.exit` 截断。
- `shell/passphrase-smoke.js`（新）：经真实 preload/IPC 断言三条路径。
- `desktop-gate.sh`（新）：Aqua 直跑 / `launchctl asuser` 注入 / degraded 拒跑；
  `caffeinate -dims`；bash 看门狗 + 进程组收尾；`YOCHAT_*` 白名单透传；回执记录
  head、dirty_files、工具版本、注入模式、执行会话、透传变量名、显示器与锁屏状态、日志路径。
- `check.sh`：桌面门经 runner；新增 keychain 与两条 passphrase 门；contrast 直跑。
- `docs/agents/verification.md`：新增 Desktop gates 一节。

## 文件变更

| 文件 | 变化 |
| --- | --- |
| `shell/main.js` | 钥匙串 IPC 异步化与降级 |
| `shell/keychain-smoke.js` | 有界结束 |
| `shell/passphrase-smoke.js` | 新增，186 行 |
| `desktop-gate.sh` | 新增，300 行 |
| `check.sh` | 门禁入口与清单 |
| `shell/package.json` | `passphrase:smoke` 脚本 |
| `docs/agents/verification.md` | 运行规程 |

## 关键行为

- 退出码：门禁 0 成功；`124/129/130/137/143` → `INCOMPLETE`；其他 → `FAIL`；
  `degraded` → 不运行、`INCOMPLETE`、exit 2。
- 回执：`tmp/checks/desktop-gate-<epoch>.{json,log}`（忽略目录）。
- 门禁与看门狗各自独立进程组，超时/中断按组 TERM→KILL。

## 验证

全部在 m1 实跑，回执锚定 `b696e37`、`dirty_files=0`（除非另注）。

| 项 | 结果 |
| --- | --- |
| `keychain-smoke` 经 runner | PASS，2.0s，session=Aqua |
| `passphrase-smoke` Aqua | PASS，6.6s（remember 写文件、save/load、重启预填） |
| `passphrase-smoke` 不可用钩子 | PASS，4.7s（false/null/不落盘） |
| `passphrase-smoke` Background（不经 runner） | PASS，session=Background，2.7s（`passphrase-1795764223531`） |
| `ui-smoke` | PASS，双向 100 条，25.7s |
| `electron-smoke` | PASS，双向 100 条 + B/A 重启，25.0s；另有一份熄屏回执（`6a57864`） |
| `perf-start` | PASS，p50 1269ms / p95 1317ms / idle 741.7MB |
| 超时 | INCOMPLETE，exit 124 |
| SIGINT | INCOMPLETE，exit 130，无残留 |
| SIGHUP | INCOMPLETE，exit 129，无残留 |
| degraded | INCOMPLETE，exit 2，回执 `mode=degraded` |
| 管道 `\| tail` | 成功与超时两条路径都立即收尾 |
| 环境值透传 | `/tmp/a b/c.jar` 与 `127.0.0.1:7654` 在注入会话内原样可见 |
| 失败用例对照 | 钩子 + 旧 Promise 写法 → 红在「savePassphrase resolved true, expected false」 |

未跑：`check.sh` 的 Scala、模型与 spike 段。本次 diff 只改 shell、`check.sh` 桌面段落
与文档；`c415a07` 的 closure full-gate 回执对这些段落仍然有效。

## 已知缺口

见 `meta.md` 的已知约束；另有两处记录层面的取舍：

- Background 分支只能人工验证，`check.sh` 因恒注入 Aqua 而永不进入它。
- 回执只记录透传变量的名字，不记录值（值可能含路径）；取值保真由上面那次
  「空格与冒号」实测证明。
