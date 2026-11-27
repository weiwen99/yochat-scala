# Linux 支持（桌面门禁）

- Status: implemented, verified on m1 and w7
- Branch: `feat/s-linux-support`，base `3430a1d`
- 来源：`TODO.md` 的 Linux 条目（已完成项移出，见"验收"）

## 规模

| 维度 | 用户要的 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 需求条数 | 4 条待做 + 3 类未跑门 | 4 条待做实现 + 3 类门在 w7 实跑 | 1× |
| 表数 | 0 | 0 | — |
| 组件数 | 0 新抽象 | 2 个新文件（`shell/electron-runtime.js`、`shell/browser-runtime.js`） | 需解释 |
| 配置项 | 2 | 3（`YOCHAT_ELECTRON`、`YOCHAT_CHROME_PATH`、`YOCHAT_GATE_DISPLAY`） | 1.5× |
| 工程师日 | 1（估算） | 1.5（估算，含评审与两机验证） | 1.5× |
| 发布波次 | 1 | 1 | 1× |

- 组件解释：两个新文件都是"把已有的一段逻辑抽出来复用"，不是新抽象。
  `electron-runtime.js` 把"用哪个 Electron 二进制、带什么参数"从
  `electron-smoke.js` / `passphrase-smoke.js` / 未来的 keychain 调用里收拢；
  `browser-runtime.js` 把 `cross-host-smoke.js:104` 已有的 `command -v google-chrome`
  解析抽成共用函数。D2 要求复用现有模式，这两处正是把散落的重复收成一处。
- 配置项解释：3 个都是"覆盖自动探测"的逃生口，默认不设；多出的 1 个是 Linux 的
  显示地址（macOS 上 ssh 会话有 Aqua 可注入，Linux 没有等价物）。

## 状态与前序

- 依赖 `docs/plans/2026/11/0014-desktop-gate-session/`（桌面门禁 runner）。
- 取代：无。本计划只扩展现有 runner 的平台分支，不新建门禁体系。

## 背景与缺口

`desktop-gate.sh` 目前硬编码 macOS：`launchctl managername`、`launchctl asuser`、
`caffeinate -dims`、`pmset -g log`、`ioreg`、`/usr/bin/stat -f`，并且在非 Darwin
上直接 `exit 2`。`shell/ui-smoke.js` 硬编码 Playwright 的 `channel: "chrome"`
（找 `/opt/google/chrome/chrome`）。npm 分发的 Electron 在 NixOS 上缺整条 FHS 库链。

2026-11-27 在 w7 的实测（一次性观测，无回执）：`core.test` 通过；`store.test`
8816ms 对门限 5000ms；`ui-smoke` 改指系统 chrome 后通过；`electron-smoke` 换
nixpkgs Electron 45.3.4 后通过但 224s。

## 目标

1. `desktop-gate.sh` 有 Linux 分支：会话/显示发现、`systemd-inhibit` 替代
   `caffeinate`、`dpms`/`LockedHint` 替代 `pmset`/`ioreg`、无注入。
2. Electron 二进制可解析：`YOCHAT_ELECTRON` > 平台默认；Linux 上自动探测
   `PATH` 与 `nix build nixpkgs#electron`；需要时加 `--no-sandbox`。
3. 浏览器解析共用一处：`browser-runtime.js` 提供 `chromeLaunchOptions()`，
   `ui-smoke` 与 `cross-host-smoke` 的本地端都用它。
4. keychain 门在 Linux 上可运行；后端不可用时给出显式 SKIP，不冒充 PASS。
5. 在 w7 实跑：`keychain-smoke`、`passphrase-smoke`、`ui-smoke`、`electron-smoke`、
   `server.e2e`（1k/10k 硬门），把数值记进回执或文档。
6. macOS 行为零回归：全部现有门禁在 m1 上复跑通过。

## 非目标与已知约束

- **不改 Electron 版本**：仓库仍钉 45.4.1；Linux 上若走 nixpkgs 则是 45.3.4。
  版本统一需要 owner 决定（跟随 nixpkgs 或自建包），本计划只把差异记进回执。
- **发行形态已实现为默认，待 owner 追认**：`electron-runtime.js` 把「PATH 上找不到就用
  `nix build nixpkgs#electron`」写成 Linux 默认，等于选了 nixpkgs 包装形态。这是可回退的
  实现选择，不是已批准的决定；owner 若选「补齐 FHS 环境」或「自建 45.4.1 包」，改这一个文件。
- **不动硬门阈值**：w7 上 `store.test` 与两进程 E2E 的绝对阈值照旧判决。
  要分档必须先立 ADR 并由人签字，本计划只记录观测。
- **不做 Windows**。
- Linux 上的通过不替代 macOS 桌面证据，反之亦然；回执的 `platform` 字段区分。
- Linux 只看 X（Xwayland）；纯 Wayland 下的 Electron 启动不在本计划验证。
- `--no-sandbox` 只在 Linux 且使用非 npm 二进制时加，属测试夹具的让步，不是产品默认。
- w7 的负载不可控，时间类数值只记为观测。

## 设计

### 平台分支（`desktop-gate.sh`）

```
platform = macos | linux
```
- **显示**：macOS 无此概念（Aqua 常驻）。Linux 按 `YOCHAT_GATE_DISPLAY` →
  `DISPLAY` → 扫描 `/tmp/.X11-unix/X*` 并用 `xdpyinfo` 探活。都没有 → `degraded`
  写 INCOMPLETE 回执，`reason=degraded-no-display`。
- **会话**：macOS 用 `launchctl managername`；Linux 用 `loginctl` 的 `Type`。
- **注入**：macOS 保持 asuser/direct/degraded 三态；Linux 默认 `direct`（没有等价的
  跨会话注入），`YOCHAT_GATE_INJECT=asuser` 在 Linux 上报错退出 2；`degraded` 仍需显式
  指定，用于在无显示的主机上验证降级路径。
- **保活**：macOS `caffeinate -dims`；Linux `systemd-inhibit --what=idle:sleep`。
- **显示器状态**：macOS 读 `pmset -g log`；Linux 读 `/sys/class/drm/card*-*/dpms`，
  任一 `Off` 即 `off`，全 `On` 即 `on`，否则 `unknown`。
- **锁屏状态**：macOS 读 `ioreg ... IOConsoleLocked`；Linux 读
  `loginctl show-session <id> -p LockedHint`。
- 回执新增 `platform`、`session_type`、`display_env`、`electron_binary`。

### Electron 解析（`shell/electron-runtime.js`）

```
resolveElectron() -> { executablePath | null, args: string[], source, version }
```
顺序：`YOCHAT_ELECTRON` → 平台默认。macOS 默认 `shell/node_modules/electron`（npm，
现状）。Linux 默认依次试 `command -v electron`、`nix build --no-link
--print-out-paths nixpkgs#electron`（结果缓存到 `tmp/checks/electron-path`，避免
每次 eval）。解析不到 → 抛出带操作指引的错误。

`args` 在 Linux 且 `source !== "npm"` 时含 `--no-sandbox`：nix store 里的
`chrome-sandbox` 无法带 setuid，Electron 会拒绝启动。

文件同时作为 CLI：`node shell/electron-runtime.js <script> [args...]`，用解析结果
spawn 并转发退出码。`check.sh` 的 keychain 门改走它。

### 浏览器解析（`shell/browser-runtime.js`）

```
chromeLaunchOptions({ headless, probe }) -> Playwright launch options
```
- `YOCHAT_CHROME_PATH` 优先。
- macOS 且未设：`{ channel: "chrome" }`（现状不变）。
- 否则用 `probe(cmd)` 解析 `google-chrome`/`chromium`/`chromium-browser`；
  Linux 上附加 `--no-sandbox --disable-dev-shm-usage`。
- `probe` 默认是本地 `command -v`；`cross-host-smoke` 传 ssh 版探测，保持远端解析。

### keychain 与 passphrase 的 Linux 语义

Electron 在 Linux 上的 `safeStorage` 走 libsecret/kwallet（或 `--password-store=basic`
的固定密钥），不是 macOS 的 per-app keychain item。因此：

- `keychain-smoke`：后端不可用 → 退出 78 并打印 `KEYCHAIN_SMOKE skip reason=no-backend`。
  runner 把 78 映射为 `verdict=SKIP`（exit 0，不阻断 check.sh），回执里可见。
  SKIP 不是 PASS。
- `passphrase-smoke`：判定不再只看会话，改为看**可用性**：
  `forcedUnavailable` → 断言 false/false/null/不落盘；`available === false` →
  同一组断言（任何平台）；`available === true` 且 macOS 非 Aqua → 只断言布尔契约；
  其余 → 完整流程（remember 写盘、save true、load 回值、重启预填）。

## 测试规格等价

无行为契约变化：15 条行为契约、性能门阈值、门禁清单都不动。新增的是同一批门在
第二个平台上的执行能力。测试等价性体现为"m1 上逐条复跑通过"（见验收）。

## 性能门

不新增门。`server.e2e` 的 1k <5s 与 10k <60s 在 w7 上首次运行，数值记入回执；
若红，按 `docs/agents/verification.md` 分类（固定落点 = 缺陷，漂移 = 负载型 flake），
不放宽阈值。

## 实测结果（2026-11-27）

### w7（NixOS 26.11 x86_64，commit `e717a93`）

| 门 | 结果 |
| --- | --- |
| `keychain-smoke` | PASS，`backend=basic_text` |
| `passphrase-smoke` | PASS，完整流程（remember 写盘、save、load、重启预填） |
| `ui-smoke` | PASS，双向 100 条，37.6s（runner 回执 `duration_ms=37632`） |
| `electron-smoke` | PASS，双向 100 条 + B/A 重启，226s（m1 为 25s） |
| `server.e2e` 行为契约 | #1–#7、#9–#15、#17 PASS；#8（1k <5s，即 `PERF#1K`）FAIL 25385ms |
| `server.e2e` `PERF#1K` | FAIL，25385ms 对门限 5000ms（超阈 5.1 倍；即契约 #8） |
| `server.e2e` `PERF#10K` | FAIL，225458ms 对门限 60000ms（超阈 3.8 倍） |
| `server.e2e` `PERF#RATE` | PASS（观测），45.0 msg/s；m1 为 330–340 |
| `perf-start` | FAIL，`p50_ms=1643`、`p95_ms=1702` 均在门限内，`idle_mb=995.7` 超 800 门限 24% |

吞吐相对 m1 慢 3.8–7.4 倍（超阈倍数 3.8–5.1 倍，两个口径不同）；空闲内存超门限 24%，且
不随负载变化，单独列为未知项。功能契约除 #8 外全绿。

未做隔离重跑，按仓库口径这些都是**未分类的红**，不是已排除的代码缺陷，也不构成本计划对
w7 的豁免。本计划只确立两件事：功能契约能在 Linux 上跑通；该机的性能数字与 m1 不可比。

### m1（macOS，无回归）

`keychain-smoke`、`passphrase-smoke`×2、`ui-smoke`、`electron-smoke` 全部 PASS，
耗时与改动前一致（25.9s / 25.5s）。

## 验收

1. w7：`./desktop-gate.sh` 跑通 `keychain-smoke`(SKIP 或 PASS)、`passphrase-smoke`、
   `ui-smoke`、`electron-smoke`，每份回执 `platform=linux`、`head`/`dirty_files` 可锚定。
2. w7：`./mill server.e2e` 的 1k/10k 数值记录，PASS 或如实记 FAIL。
3. m1：`keychain-smoke`、`passphrase-smoke`×2、`ui-smoke`、`electron-smoke` 全部仍 PASS。
4. 两轴评审 P1=0；本地合入 `main`；工作树与分支清理；`TODO.md` 的 Linux 条目移入本
   effort 并删除（两条需 owner 决策的除外，保留在 `TODO.md`）。
