# S1 Quint 模型映射（170 个旧模型，F6）

- 状态：模型级映射完成；逐行签字待补。
- 旧基线：`/Users/weiwen/projects/yochat-reference/models/checks.json`（170 个模型、334 条 check 配置）。
- 方法：只读 `checks.json` 的模型/配置/性质登记与模型文件名；未运行 Quint，不声称任何模型通过。
- 判定：`KEEP` = 旧性质原样移植；`REWRITE` = 保留旧性质但按 S1 新状态机重写；`DROP` = S1 范围外，不移植。
- 计数：170 个模型；KEEP 5、REWRITE 22、DROP 143。
- 签字：用户 2026-11-20 同意三项范围裁决（#12 OMIT、11 条不变量、GBNV S2/T1 不等价）；本表逐行签字待补。未签字不得把 DROP 当作已完成。

| 旧模型 | 配置数 | 判定 | 新落点 | 理由 | 签字 |
| --- | ---: | --- | --- | --- | --- |
| `acked_remove.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `address_budget.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `approval_store.qnt` | 3 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `authenticity.qnt` | 1 | `KEEP` | `Q1` | 签名/真实性门；一对一性质保留 | 待签 |
| `bootstrap_global_protocol_cas.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_historical_origin.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_lower_candidate.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_peer_opening.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_provenance.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_ready_receive.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_retired_capacity.qnt` | 4 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_reverse_bundle.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_scope_retention.qnt` | 5 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_wait_retirement.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `bootstrap_waiting.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `buffered_binding.qnt` | 3 | `DROP` | `—` | group/welcome 缓冲；S2 | 待签 |
| `buffered_decryption.qnt` | 3 | `REWRITE` | `SM-crypto` | 失败解密不消耗棘轮/候选原子保留；旧 DCGKA 替换 | 待签 |
| `buffered_receive.qnt` | 1 | `DROP` | `—` | group/welcome 缓冲；S2 | 待签 |
| `completion_service.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `concurrent_welcome_forward.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `contact_bootstrap.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_attempt.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_authorization.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_bilateral.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_commit.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_convergence.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_cross_attempt.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_cross_open.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_disclosure.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_reentry.qnt` | 3 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_replication.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_revocation_barrier.qnt` | 5 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_bootstrap_store.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_confirmation.qnt` | 1 | `KEEP` | `Q1` | 双方确认/待确认状态性质保留 | 待签 |
| `contact_discovery_driver.qnt` | 6 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_discovery_lookup.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_discovery_receiver.qnt` | 7 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_established_migration.qnt` | 3 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_hello_return_route.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_helper_selection.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_helper_sweep.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_profile_view.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_reconsent.qnt` | 2 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_removal_variants.qnt` | 1 | `DROP` | `—` | S2/S3 联系人发现/接管/删除；S1 只有定向邀请+应答 | 待签 |
| `contact_reply.qnt` | 1 | `KEEP` | `Q1` | 应答验签与一次性密钥性质保留 | 待签 |
| `control_budget.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `control_prefix_fence.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `covered_native_payload.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `current_format.qnt` | 1 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `cursor.qnt` | 4 | `KEEP` | `Q2` | 游标与缺口检测保留 | 待签 |
| `deletion.qnt` | 4 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `delivery_scope_reconcile.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `device_alert_reads.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `device_alias.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `device_announcement_retention.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `device_join_announcement_merge.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `device_list_delivery.qnt` | 2 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `devices.qnt` | 3 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `discovery_locator.qnt` | 2 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `e2e_restart_generation.qnt` | 1 | `REWRITE` | `Q4` | 进程代次/重启保留；旧 runner 实现替换 | 待签 |
| `early_gone_effects.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `exchange.qnt` | 2 | `REWRITE` | `Q1` | 只保留一对一邀请/应答；group/welcome 配置 DROP | 待签 |
| `exemption.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `footprint.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `handshake_reconnect.qnt` | 1 | `REWRITE` | `Q4` | 重连/有界重放保留；旧 p2p 握手实现替换 | 待签 |
| `handshake_source_rate.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `history_abandon_drain.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_handoff.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_intent_batch.qnt` | 4 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_native_overlap.qnt` | 3 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_queue.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_quota_owner.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_retry.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `history_time_consent.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `home_relay_status.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `identity_construction.qnt` | 1 | `REWRITE` | `SM` | 规范身份构造/锁定失败保留；旧文件/SQL 路径重写 | 待签 |
| `identity_creation.qnt` | 2 | `REWRITE` | `SM` | 身份创建保留；24 词恢复 DROP | 待签 |
| `idle_autolock.qnt` | 1 | `REWRITE` | `SM` | 空闲超时锁定保留；旧 UI 实现替换 | 待签 |
| `inbound_backpressure.qnt` | 4 | `REWRITE` | `transport` | 有界队列/容量保留；旧 QUIC/native 字段替换 | 待签 |
| `inbound_frame_deadline.qnt` | 1 | `REWRITE` | `transport` | 固定帧期限/准入保留；旧传输实现替换 | 待签 |
| `incoming_budget.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `independent_membership.qnt` | 9 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `instance_switch.qnt` | 1 | `REWRITE` | `SM` | 锁定/实例切换保留；旧 UI 实现替换 | 待签 |
| `ip_receive_fairness.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `join_attempt_store.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `join_retry_authorization.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `late_native_original.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `liveness.qnt` | 1 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `local_order_promotion.qnt` | 1 | `DROP` | `—` | GBNV/跨作者全序；S2 | 待签 |
| `longterm_registry.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `mapped_address.qnt` | 2 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `membership.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `membership_keys.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `membership_views.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `message_attention.qnt` | 5 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `message_order.qnt` | 2 | `KEEP` | `Q2` | 同作者顺序/去重/缺失检测保留 | 待签 |
| `message_visibility.qnt` | 1 | `REWRITE` | `SM-order` | 等待/冲突/可见性投影保留；旧 Svelte UI 实现替换 | 待签 |
| `migration_release_handoff.qnt` | 2 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `multidevice.qnt` | 7 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `native_application.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_dependency_recovery.qnt` | 21 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_forward_owner.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_forward_purpose.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_key_ancestry.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_resend_inference.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `native_timer_priority.qnt` | 3 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `net_poll_fairness.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `network_deadline.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `order_bootstrap_helper.qnt` | 2 | `DROP` | `—` | GBNV/跨作者全序；S2 | 待签 |
| `order_content.qnt` | 11 | `DROP` | `—` | GBNV/跨作者全序；S2 | 待签 |
| `order_frontier.qnt` | 2 | `DROP` | `—` | GBNV/跨作者全序；S2 | 待签 |
| `order_proof_job.qnt` | 11 | `DROP` | `—` | GBNV/跨作者全序；S2 | 待签 |
| `outbound_class_admission.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `own_bootstrap.qnt` | 2 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_device_admission.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_capability.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_handshake.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_locator.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_promotion.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_rendezvous.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_discovery_work.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_locator_retirement.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_roster_sync.qnt` | 2 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `own_roster_tick_gate.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `owned_process.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `packet_journal.qnt` | 1 | `REWRITE` | `Q4` | journal/重启语义保留；旧 native 实现替换 | 待签 |
| `peer_event_principal.qnt` | 6 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `pkarr_budget.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `prepared_join.qnt` | 4 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `private_discovery_provider.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `profile_advert.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `profile_conflict.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `profile_request.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `profile_route.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `profile_sender.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `profile_transfer.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `protocol_context.qnt` | 1 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `protocol_dependency_wakeup.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `quic_credit.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `quota.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `rebuild.qnt` | 5 | `REWRITE` | `Q5` | 解密失败→重建→重传未确认；群 epoch DROP | 待签 |
| `recovery_admission.qnt` | 2 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `recovery_send_ownership.qnt` | 3 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `recovery_storage_budget.qnt` | 1 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `relay_idle.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `relay_receive.qnt` | 1 | `REWRITE` | `transport` | S1 relay 只转发密文；旧 p2p/relay 回退替换 | 待签 |
| `resend.qnt` | 2 | `REWRITE` | `Q3` | 等待/重发有界性质保留；history/native 批准队列 DROP | 待签 |
| `resend_scope.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `retained_reconsent_prune.qnt` | 1 | `DROP` | `—` | 非 S1（未命中 S1 协议面）；不移植 | 待签 |
| `retransmit_dispatch.qnt` | 1 | `REWRITE` | `Q3` | 重传调度/有界窗口保留；old native dispatch DROP | 待签 |
| `revocation_delivery.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `revocation_scope.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `search_window.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `secret_reveal_owner.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `send_identity.qnt` | 1 | `REWRITE` | `ST` | 发送代次/序号持久化保留；旧实现重写 | 待签 |
| `send_retry.qnt` | 3 | `REWRITE` | `Q3` | 固定截止/有界重试保留；old native queue DROP | 待签 |
| `send_scope.qnt` | 5 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `shared_prekeys.qnt` | 1 | `REWRITE` | `Q1` | 一次性预密钥/不可双用保留；组预密钥 DROP | 待签 |
| `store_writer_ownership.qnt` | 2 | `REWRITE` | `ST` | 单写者/重启/别名拒绝保留；SQLite 重写 | 待签 |
| `stream_open_service.qnt` | 3 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `timer_commit.qnt` | 3 | `REWRITE` | `SM` | 持久计时器/原子提交保留；执行器重写 | 待签 |
| `transport_budget.qnt` | 5 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `transport_send_retirement.qnt` | 1 | `DROP` | `—` | 旧 p2p/QUIC/relay 细节；S1 relay-only 不移植 | 待签 |
| `typed_pending_create.qnt` | 1 | `DROP` | `—` | 旧 native/传输服务实现；S1 relay-only 不移植 | 待签 |
| `typed_state_cache.qnt` | 1 | `REWRITE` | `SM` | 状态缓存/发布/恢复保留；纯状态机重写 | 待签 |
| `ui_maintenance.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `user_key_sync.qnt` | 3 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `verified_root_settlement.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
| `view_ownership.qnt` | 1 | `DROP` | `—` | S3 发布级/UI/配额/删除/搜索；S1 不实现 | 待签 |
| `welcome_merge.qnt` | 1 | `DROP` | `—` | S2 多设备/历史/吊销/发现；S1 不实现 | 待签 |
