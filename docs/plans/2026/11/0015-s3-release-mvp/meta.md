# Plan 0015 metadata

- Status: frozen — 八条裁决由 owner 于 2026-11-27 委派决定；S3-A 实现中
- Title: S3 发布级 MVP 的范围
- Live spec: `.scratch/s3-release-mvp/spec.md`
- 来源：`docs/references/2026-11-20-yochat-reference-study/requirements.md` 的 35 条 S3 候选
- 采纳 15（含 9 条部分）、阻塞 2、延后 18
- 三波：S3-A 关系结束 / S3-B 发布级可见性 / S3-C 历史可用性（阻塞于 ADR）
- 预算：生产 19,614（只计五个模块 src；含 spike/shell 则 23,990）→ A+B 估算 23,100–25,100；
  上限 30,000。测试 9,845 → 估算 10,800–11,600；上限 15,000
- 第 1 轮 P1 12 / P2 14；第 2 轮 P1 8，全部处置，见 `design-review-findings.md`
- 存储扩展：`docs/adr/0012-s3-storage-blocked-peers-and-contact-tombstone.md`
- Next: S3-A 实现（store → core → server → ui → e2e），再做 S3-B；S3-C 等自己的 ADR
