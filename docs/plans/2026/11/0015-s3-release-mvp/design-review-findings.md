# Plan 0015 design review findings

对象：`docs/plans/2026/11/0015-s3-release-mvp/design.md`，第 1 轮对象提交 `61aa957`（两轴并行）。
记录 append-only。第 1 轮共 26 条：Standards 5 P1 + 7 P2，Spec 7 P1 + 7 P2。

## 第 1 轮 · Standards 轴

| # | 级别 | 发现 | 处置 | 关闭证据 |
| --- | --- | --- | --- | --- |
| S-1 | P1 | 「解除拉黑借用 Gap 重传」与实现相反：棘轮 `MaxSkipGap=64` / `MaxSkipTotal=256`，`requestResend` 不含 Failed，重试 3 次 / 5s 后进 Failed，缺口超限静默失步 | fix | 设计「解除拉黑与补齐的真实窗口」整节重写，写明 64/256/32/约 19 秒与 `GapTooLarge` 静默失步；S3-A 补「GapTooLarge → 重建通道」为核心改动；裁决 2 给出两条路 |
| S-2 | P1 | 入站被当成一条链，实际有六条入口，Fanout 会在 A2 落正文，「不增长存储」不成立 | fix | 新增「入站入口表」六行（含代码位置与落盘点）；要求收敛到 store 层 `isBlocked`；验收加「六类帧落盘增量为 0」 |
| S-3 | P1 | `device_commands` 不服务任何采纳需求，且与 `device_sync` 构成第二套机制，且无 A2→A1 路径 | fix | 删除该表；D1 表数改 +1；跨设备「逐设备确认」随 `K54NXXBZW1R6415F` 延后；已知约束 3 写明 A1→A2 单向 |
| S-4 | P1 | 新密封域与 AAD 变更触碰 ADR-0007，设计却写「不动密钥管理」 | fix | 新增「存储与 ADR 边界」节：先写存储 ADR，域标签/派生/AAD/索引；`blocked_peers` 只存公钥与时间；高水位或已知约束二选一；裁决 7 |
| S-5 | P1 | 生产行数口径不可复现（19,614 排除了 spike/src），且「会撞穿 30,000」按原文数字不成立 | fix | 规模表补「口径」段，给出两种口径（19,614 / 23,990）与各自余量；估算方法改为「S1 密度 490 行/条 × 系数」；结论改为下界 31,300 > 30,000（按 19,614 口径） |
| S-6 | P2 | 8,300–12,300 行无分项依据 | fix | 同上：改为密度法，并标明是估算 |
| S-7 | P2 | 「16 条行为契约」无清单，且 verification.md 仍写 15 | fix（部分） | 设计内统一写 16；verification.md 等三处对齐另立任务，记入已知差异（本轮不顺手改规范文档） |
| S-8 | P2 | 把阻塞且声明不实现的 S3-C 计入产出 | fix | D1 需求条数拆为「采纳 15 / 阻塞 2 / 延后 18」，组件只 +1（搜索服务不计），工程师日注明只覆盖 A+B |
| S-9 | P2 | 入站顺序前移一级，触碰 ADR-0003 不可删清单未提出 | fix | 入口表写明先 `sessions.get(sessionId)` 取 `peer_user_pub` 再查；并在不变量 1 说明仍是「不落盘、不响应」 |
| S-10 | P2 | 「每加一个功能删等量死代码」无落点 | defer | 记入已知约束 7：若后续波次超限，先删等量死代码或 ADR 提上限；本波不指定删除清单（没有已识别的死代码，编一份清单等于造假） |
| S-11 | P2 | 验收只有场景没有命令 | fix | 验收补齐 `./mill server.e2e`、`./mill store.test`、`node shell/contrast-smoke.js`、`./desktop-gate.sh node shell/ui-smoke.js`、`./check.sh`、`./models/check.sh` 与回执落点 |
| S-12 | P2 | draft 状态就宣告取代来源文档 | fix | 改为「owner 裁决通过后取代」，并写明否决时本计划退为提案 |

## 第 1 轮 · Spec 轴

| # | 级别 | 发现 | 处置 | 关闭证据 |
| --- | --- | --- | --- | --- |
| P-1 | P1 | 别名/备注判「已满足」不成立：`note` 全仓无写入路径，联系人检索也没有 | fix | 改判部分采纳：私有性已满足，备注写入与联系人内检索本波补；条数从「确认 2」改为「部分 1」 |
| P-2 | P1 | 迟到补入判「已满足」不成立：GBNV 只保证顺序与不漏读，UI 无可察觉信号 | fix | 改判部分采纳，S3-B 补一个 notice code + E2E |
| P-3 | P1 | 可达性基线被缩到远小于来源验收（缺切会话/联系人详情/搜索、渲染级对比度与焦点） | fix | 采纳范围逐项列出：覆盖与不覆盖分开写，未覆盖项进已知约束 |
| P-4 | P1 | 通知需求被读错：来源要「逐会话关闭」+「正文默认不含」，设计自拟了联系人标签开关 | fix | 改为来源的两条开关；E2E 断言对应 |
| P-5 | P1 | 解除拉黑窗口外承诺「对方批准或历史同步」，两者在 S3 都不成立 | fix | 改为裁决 2 的两条显式路径；已知约束 2 写明窗口外取不回 |
| P-6 | P1 | 把在线状态**告知**项读成要建 presence 协议而延后 | fix | 移入 S3-B 部分采纳（只告知不做 presence）；延后数从 19 改 18 |
| P-7 | P1 | `device_sync` 载荷无拉黑位、无删除分支，却写「关系变更随通讯录同步」且「不改线格式」 | fix | 「关系变更的同步」节：载荷加 `state` 并升 version，接收端保护性合并；A2 反向见已知约束 3 |
| P-8 | P2 | 删除联系人「保留时消息仍可被搜索到」依赖 S3-C | fix | 验收写明该句随 S3-C 延后，本波只断言会话视图与本地库 |
| P-9 | P2 | 只写了「发送前查拉黑表」，八种出站 kind 没列 | fix | 设计列八种 kind 与统一判定；验收加「零帧」断言 |
| P-10 | P2 | 「仅含其公钥」与 sealed 存理由/时间冲突；Reply 判定点写错 | fix | `blocked_peers` 只存 `user_pub` + `created_at`；Reply 明确先开箱再丢弃 |
| P-11 | P2 | 重加/二次应答/删除确认页三处只有状态位、没有界面落点 | fix | 不变量 4、5 与验收各补一条可核对断言（新关系提示、version 不变、删除确认页文案） |
| P-12 | P2 | 告知页从「首次添加联系人」改成「首启」并漏了网络地址；配置项自造 | fix | 回到来源：首次添加联系人与邀请生成处；清单含本机中转网络地址；版本化重提示标为可选追加项并给成本 |
| P-13 | P2 | 待裁决缺「删除/搜索拆批」与「线格式新增批准权」；预填 DDL 与「不预填」纪律不一致 | fix | 待裁决扩到 7 条；DDL 改为文字描述并标「示意」 |
| P-14 | P2 | 分组延后理由「没有使用价值」与来源「有独立价值」冲突 | fix | 改为成本理由，并说明不否认来源判断 |

## 结论

第 1 轮 P1 12 条全部 fix；P2 13 条 fix、1 条 defer（S-10，理由见行）。第 2 轮只复审本轮 diff 与
上轮关闭项。

## 第 2 轮

对象：`238e19f` 之后的修订（本轮 diff 覆盖 design / spec / meta 与上一轮 findings）。
Standards 3 P1 + 5 备注，Spec 5 P1 + 4 备注。

### Standards

| # | 级别 | 发现 | 处置 | 关闭证据 |
| --- | --- | --- | --- | --- |
| S2-1 | P1 | 「约 19 秒」不可复现：`TimerId.Message` 直接 `sendNow`、不走退避，真实上限 3×5s ≈ 15 秒 | fix | 三处改为 15 秒并写明「投递超时路径无退避；wire 失败约 2 秒」 |
| S2-2 | P1 | 入站表 FanoutAck 行代码位置与落盘点都错，且漏第 7 类 DeviceSync | fix | FanoutAck 改为 `DeviceSyncScheduler.listenRequests` → `FanoutService.handleAck` → `delivery_receipts`；补说明：其发送方是对端 companion，`handleAck` 只校验 `authorizedCompanion`、不看联系人，故必须加判定；表头限定「对端可发起的帧」，DeviceSync 为自有设备帧不加 guard 并写明理由 |
| S2-3 | P1 | 已知约束 3 说「A2 没有反向通道」不实：A2→A1 已有 sync-status/ack/DeviceList/FanoutAck | fix | 改为「快照数据单向 A1→A2；缺的是关系变更数据的上行承载与 A1 应用」 |
| S2-4 | P2 | 「6 条部分」应为 7 条 | fix | 四处（design×2、spec、meta）改 7 |
| S2-5 | P2 | `Wire` 被称作 Data，而它含 DataAck/Gap/RekeyConfirm | fix | 表内改为「Wire（内含 Data / DataAck / Gap / RekeyConfirm）」 |

### Spec

| # | 级别 | 发现 | 处置 | 关闭证据 |
| --- | --- | --- | --- | --- |
| P2-1 | P1 | `0P17` 三句验收只交代第三句，且「用户别名」在 v1 不存在 | fix | 逐句改写：①别名为主显示已满足，①后半句随 `Z1P2` 延后；②按别名/备注检索本波做，按用户别名延后；③已满足；验收加一条可证伪断言 |
| P2-2 | P1 | `NS77` 的「可察觉」被缩成一个 notice code，装不下「定位逐条查看 / 锚点与草稿 / 重启仍在」 | fix | 采纳范围写明只做「可见提示 + 同一原件不重复提醒」，其余进已知约束 8；验收加断言 |
| P2-3 | P1 | 声明写进已知约束的对比度/焦点测量与搜索入口，已知约束里没有 → 静默删除 | fix | 新增已知约束 7 |
| P2-4 | P1 | `G3JQ` 标「全条」但漏「点击跳转到对应会话」 | fix | 改判部分：做逐会话关闭、正文默认关、点击跳转会话；不做「定位到具体消息与保留草稿滚动」，进已知约束 8；验收加深链断言 |
| P2-5 | P1 | `device_sync` 加 `state` + 「升 version」与协议结构不符；`deleted` 发不出去；`applyContact` 硬编码 `note=None` | fix | 重写该节：contacts 记录在 `records` 密文内，加 `state` 是向后兼容扩展、外层 `version` 保持 1；删除改为 contacts 墓碑行；`applyContact` 重写为保护性合并并保留 `note` |
| P2-6 | P2 | `Q0MQ` 标全条但清单少三项 | fix | 清单补「直连对端可见本机公网地址」「联系人可见全部设备公钥与别名」「中转不知道设备属于谁、看不到用户公钥」 |
| P2-7 | P2 | `386R` 少来源第三句 | fix | 补「用户公钥本身不能查询在线状态」 |
| P2-8 | P2 | A2 禁用关系操作，却又说「其他设备保留历史直到用户在该设备上操作」，闭环缺失 | fix | 关系变更节改为「A2 上的历史只能等 A2 恢复能力后自查（裁决 3）」 |
| P2-9 | P2 | 已知约束 1（扇出会把删过的消息带回）无可复核证据 | fix | 改写为条件句：「若 S3 做删除，扇出与重传需要墓碑才不复活」 |

## 结论

第 2 轮 P1 8 条全部 fix；P2 6 条 fix。第 3 轮只复审 diff 与关闭项；若 P1 仍不降，按
DESIGN-DISCIPLINE R9 停轮并交人工。

## 第 3 轮

对象：`af77189` 之后的修订；范围 `git diff af77189..HEAD`。Standards 3 P1 + 3 P2，Spec 轴 P1=0。

| # | 级别 | 发现 | 处置 | 关闭证据 |
| --- | --- | --- | --- | --- |
| S3-1 | P1 | FanoutAck 行的判据编造（`authorizedCompanion` 本来就按联系人匹配），且「行数增量 0」恒真（`markAcked` 只 UPDATE） | fix | 行内改为「不增长行数但能翻转回执状态」；断言改为「`state`/`updated_at` 不变」，并写明只数行数恒真、不能用 |
| S3-2 | P1 | A2→A1 帧清单多报两类：DeviceList 需 A1 用户私钥、FanoutAck 方向相反 | fix | 改为 sync-request / sync-status / ack 三类，并给出为什么另两类不在该方向 |
| S3-3 | P1 | 通知三条断言挂在 `server.e2e` 上，而它看不见窗口；点击跳转还缺 IPC 改动 | fix | 断言移到 `./desktop-gate.sh node shell/electron-smoke.js`；补 `notify` 带 `conversationId` 与 main→renderer 转发的改动；写明 `ui-smoke` 走 Chrome 不能承担 |
| S3-4 | P2 | 部分采纳计数应为 8（G3JQ 改判后），四处仍写 7，S3-B 标题仍写 3 | fix | design/spec/meta 全部改 8，S3-B 标题改 4 |
| S3-5 | P2 | Wire 列举漏 `RekeyConfirmAck` | fix | 改为五类并逐一列出 |
| S3-6 | P2 | contacts 墓碑写成「只保留 user_pub+state+version」，当前表没有 state 列 | fix | 改为「保留行 + 密封载荷只写 state=deleted + version 递增」，并写明这是 codec 变更、并入存储 ADR 与 store.test |
| S3-7 | P2 | findings 第 2 轮 P2-9 声称已改成条件句，实际未改 | fix | 已知约束 1 实际改为条件句，并加「消息墓碑 vs 联系人墓碑」的域限定 |
| S3-8 | P2 | 设计正文与目标表不同步（NS77、通知） | fix | 两处正文补齐不重复提醒与点击跳转 |
| S3-9 | P2 | 裁决 3 按 (a) 叙述，若选 (b) 有句子需作废 | fix | 裁决 3 补「若选 (b)，已知约束 3 与 A2 历史那句作废」 |

第 3 轮 P1 3 条全部 fix；第 4 轮只复审本轮 diff。
