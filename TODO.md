# TODO

活的待办清单，只放**还没有 effort 目录**的条目。开工即移入 `.scratch/<effort>/spec.md`，并从这里删除。

单一来源分工：已开工项的状态在 `.scratch/`；冻结记录在 `docs/plans/`；发布状态在 `README.md` 的
Status 与 `AGENTS.md` §10。本文件不与它们重复，也不记录已完成项。

## 平台

### Linux 支持

**状态：部分验过，未开工。**

Linux 上已有通过记录：`shell/cross-host-smoke.js` 以 w7 作 B 端跑 server + 真实 Chrome，覆盖
100+100 条 UI 消息、relay 断连重连、B/A 重启；回执见
`docs/plans/2026/11/0010-s1-ui-automation/impl.md`（`CROSS_HOST_RATE` 1k/5k 样本，`ca33ec3`，m1/w7）。
未验的是 Electron 壳、`desktop-gate.sh` runner，以及全部两进程交付门。

#### 2026-11-27 在 w7 的一次性观测（无回执，不能当门禁证据）

w7：NixOS 26.11 x86_64，commit `99600e5`。

| 层 | 观测 | 对照 |
| --- | --- | --- |
| `core.test` | `SUCCESS`；10k 回放 `elapsed_ms=18960` | 门限 50s，通过（不引用 `145/145`：那是 Mill 的任务序号，不是测试数，见 `docs/reviews/2026-11-24-s1-crypto/`） |
| `store.test` | 失败：`insert_ms=8549 read_ms=266 total_ms=8816` | 门限 5000ms；m1 全库回执 `total_ms` 1546–2553ms |
| `ui-smoke` | 原样失败（`channel: "chrome"` 找 `/opt/google/chrome/chrome`）；改指系统 chrome 后通过，31.8s | m1 记录 25.7s |
| `electron-smoke` | 原样不可启动（npm 的 Electron 缺 `libglib-2.0.so.0`）；换 nixpkgs Electron 45.3.4 + `--no-sandbox` 后通过，224s | m1 记录 25.0s |

口径说明：

- 这些耗时没有回执，w7 上的检出也已被后续操作覆盖，因此是「一次性观测」，不是可复核的门禁结果。
- 机器状态在这台机器上不稳定：观测时 iowait 38–61%、btrfs 已用 94%、load 11–16；复核时
  iowait 仍 38–61%，load 已回落到约 1。`bees` 进程一直在跑（pid 997，`--loadavg-target 5.0`
  自限速），不能靠 `command -v bees`（不在 PATH）或 `systemctl is-active/enabled`（inactive /
  not-found，因为它是从 store 绝对路径手工拉起的）判断它是否在跑。时间类数值因此不可重复。
- `store.test` 失败未做隔离重跑，按仓库口径只是**未分类的红**，不是已排除代码缺陷。
- 慢的幅度按现有记录是：store 3.5–5.7 倍（8816ms 对 m1 全库 `total_ms` 1546–2553ms，端点分别见
  0001/0004/0006 的 impl.md），electron 约 9 倍（224s 对 25.0s）。

#### 未跑、且决定 Linux 能否交付的门

- 两进程硬门：`server.e2e` 的 1,000 条 <5s（`S1E2E.scala:385`）与 10,000 条 <60s（`:811`）。
  w7 上未跑；这才是交付门，比冷启动门重要。
- `keychain-smoke` 与 `passphrase-smoke`：Linux 的 `safeStorage` 走 libsecret/kwallet，不是 macOS
  的 per-app keychain item；`launchctl asuser`、`caffeinate -dims`、`Aqua/Background` 判定在 Linux
  上都不存在，`desktop-gate.sh` 的降级判据要重新定义，不能照搬 macOS 的 INCOMPLETE 语义。

#### 待做

- [ ] `desktop-gate.sh` 的 Linux 分支：会话与显示、Electron 可执行覆盖、`--no-sandbox`，并给出
      macOS 专属判据（asuser / caffeinate / keychain）在 Linux 上的等价物或明确的「不适用」
- [ ] 浏览器定位复用 `shell/cross-host-smoke.js:104` 已有的 `command -v google-chrome` 解析（该
      路径在 w7 上已跑通），抽成共用函数供 `ui-smoke` 使用；不新增第二套定位方式
- [ ] Electron 版本对齐：仓库钉 45.4.1，nixpkgs 提供 45.3.4。**未定**，需 owner 选择「跟随
      nixpkgs」或「自建 45.4.1 的 Linux 包」
- [ ] 时间类门禁在 w7 的口径：先按 `PERF#RATE` 的既有模式「只报数值、无阈值」记录观测；若要给
      硬门加机器分档，按 `docs/agents/verification.md` 必须另立 ADR 并由人签字，不能作为普通待办推进

#### 已知约束

- v1 的交付判据不因 Linux 支持而放宽；Linux 上的通过不替代 macOS 的桌面证据，反之亦然。
- Windows 未排期；是否纳入由 owner 决定，本条目不作范围承诺。
- w7 同时承担构建与其他服务，负载不可控；时间类结果只记为观测，不作判决。
