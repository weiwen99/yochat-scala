# Design review findings — Plan 0002

- 对象: `design.md` 初稿 + 基线 `0ce9937` + Plan 0001 `impl.md` §M3 review round 1
- Review 预算: 最多 7 轮；第 3 轮问题数不下降即停（R9）
- 当前状态: round 0（基线），尚无正式 review 轮

## Round 0 基线

M3 review round 1 的九项原文、位置与完整五字段见
`docs/plans/2026/11/0001-yochat-rewrite/impl.md`。下表只登记本计划的关闭对象与证据，
不复制历史正文；实现记录必须逐项引用原文位置。

| ID | 原句位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- |
| R1 | 0001 impl §M3 review round 1，未关闭 (1) | D7、R8 | Reply/Rekey 的 contact/session/conversation/invitation 是多个事务；崩溃可留下半套关系 | 故障注入在任一写入后失败，重启后 store 无半套关系；一次握手一个提交区间 | 1–1.5d |
| R2 | 同上 (2) | 工程规范 state runtime、R8 | runtime 先发布内存状态再落盘；失败靠 reload+fatal | store 失败时旧状态不替换、WS 不发布 next；恢复后无“内存领先”窗口 | 1–1.5d |
| R3 | 同上 (3) | AGENTS §4 先落盘再发、R8 | 接受邀请后 reply 未持久化；首发送失败直接 fatal | 断网接受不 fatal；重启/重连可重发；成功后删除 invitation | 1d |
| R4 | 同上 (4) | 所有等待/重试有界 | rekey 控制帧丢失可永久停在 old ReadOnly + new Pending | 四个控制阶段丢帧注入最终 active 或按 cap abort | 1.5–2d |
| R5 | 同上 (5) | 查询计划、性能门、R8 | snapshot 多事务、100k 前缀、`List :+` 累积 | 10k 恢复一次读事务 + 有界查询；无 100k blob 扫描 | 1.5–2d |
| R6 | 同上 (6) | 所有重试有界、restore 契约 | 重启不重发 outstanding Gap；attempts 归零 | 重启补拉 Gap；attempts 持久化并在 cap 停止 | 0.75–1d |
| R7 | 同上 (7) | 入站准入、有界工作 | Reply 无邀请 id，runtime 扫描全部邀请 | wire 带 invitation id；未知 id 直接丢弃；O(1) 或上限可见 | 0.5d |
| R8 | 同上 (8) | 有界内存/句柄 | timer fiber 触发后仍留 registry | 触发/取消后删除；重复 start/cancel 幂等；有界测试 | 0.2d |
| R9 | 同上 (9) | ADR-0003 锁代数、ADR-0004 | idle timeout 未调度；Lock generation 未持久化 | 空闲到期锁定；重启 generation 前进；旧代数结果不落地 | 1d |

## 轮次记录

- Round 0: 基线九项，全部 `open`。未进行 review；不得把本表当作通过。
- Round 1–7: 待实现前按 `design.md` 的轮次表追加；每轮记录对象、两轴覆盖、发现、关闭/撤回、升级。
- 规则: review 前冻结 diff（R8）；发现必须带原文、规则、影响、关闭证据、代价（R5/R6）；第 3 轮问题数不下降即停（R9）。
