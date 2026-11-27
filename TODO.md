# TODO

活的待办清单，只放**本次工作还没有 effort 目录**的条目。开工即移入 `.scratch/<effort>/spec.md`，并从这里删除。
归属按「这项工作自己的 effort」判断，不按它要改的文件原先属于哪个 effort：改一个已结项模块的新增能力，仍算新条目。

单一来源分工：已开工项的状态在 `.scratch/`；冻结记录在 `docs/plans/`；发布状态在 `README.md` 的
Status 与 `AGENTS.md` §10。本文件不与它们重复，也不记录已完成项。

## 平台

Linux 门禁支持已交付，见 `.scratch/linux-support/spec.md`。结项时留下三个决定需要 owner 拍板，
开工前都要先写 ADR 或改文档：

- [ ] **Electron 版本口径**：仓库钉 45.4.1，nixpkgs 提供 45.3.4。当前 Linux 门禁经
      `shell/electron-runtime.js` 走 nixpkgs，回执同时记 `electron`（钉住的版本）、
      `electron_source` 与 `electron_resolved`（实际二进制），差异可见但未消除。二选一：跟随 nixpkgs 的版本，或自建 45.4.1 的 Linux 包。判据：Linux
      与 macOS 上的 Electron 主版本一致，且 `docs/agents/verification.md` 写明口径。
- [ ] **`basic_text` 后端**：w7 上 `safeStorage` 落到 `basic_text`——硬编码密钥，API 可用但
      口令未受保护。`keychain-smoke` 打印 `insecure=true` 并仍判 PASS（它证明的是 IPC 路径，
      不是密钥安全）。是否要在这种后端上把「记住口令」在 UI 里禁掉，需要产品决定。
- [ ] **时间类门禁的机器分档**：w7 上 `PERF#1K`、`PERF#10K`、`PERF#RATE` 与 `perf-start`
      的空闲内存全部判红，吞吐相对 m1 慢 3.8–7.4 倍（详见 `.scratch/linux-support/spec.md`）。
      没有放宽任何阈值，也没有做隔离重跑，所以这些是未分类的红。要判定「w7 只作功能验证」
      或给硬门加机器分档，按 `docs/agents/verification.md` 都必须另立 ADR 并由人签字。
      判据：ADR 记录分档依据、复算方式，以及一次隔离重跑的结果。

未排期：Windows。是否纳入由 owner 决定，本文件不作范围承诺。
