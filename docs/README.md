# 文档导航

先读[当前状态](current-project-status.md)了解现行版本、配置和待办，再按任务进入手册。

| 主题 | 必要入口 |
| --- | --- |
| 项目/开发 | [知识导读](project-guide.md) · [开发与测试](development.md) · [前端](../frontend/README.md) |
| API/数据 | [接口](api.md) · [数据库/迁移](database.md) · [安全API清单](security/api-inventory.md) |
| 部署/恢复 | [部署与回退](deployment.md) · [跨主机迁移](../MIGRATION.md) · [加密备份](security/backup-plan.md) |
| 元数据/资源 | [Resource Hub](resource-hub.md) · [采集与周榜](metadata-weekly-transfer.md) · [来源研究](evaluation/metadata-update-watch-20261115/sources/metadata-sources-research.md) |
| 网盘/P2P | [网盘工作流](cloud-drive-workflows.md) · [来源采集](gying-p2p-collection.md) · [归档](p2p-cloud-archive.md) · [重复合并](duplicate-movie-merge.md) |
| QQ/发布 | [发布状态机](social-publishing.md) · [频道多盘正文](qq-channel-multi-drive.md) |
| 主机/安全 | [迅雷会话](xunlei-token-sync.md) · [优雅重启](weekly-graceful-restart.md) · [安全](security/README.md) · [排障](../.agents/skills/gying-project-ops/references/troubleshooting.md) |
| AI | [架构](ai-architecture.md) · [评测](ai-evaluation.md) · [回答质量](ai-answer-quality-guide.md) · [求职路线](ai-career-roadmap.md) |
| 历史 | [发布台账](history/releases.md) · [问题台账](history/issues.md) · [Git原文](history/README.md#git-history) · [评测/验收索引](evaluation/README.md) |

## 阅读规则

现行手册写机制，状态手册写有日期的配置/部署事实，台账写交付结论和未决事项。HTTP、业务完成、模型质量、真实登录/出站与恢复各有证据范围；凭据和敏感原日志不进入docs。

## 维护规则

- 小功能/已解决bug默认只更新对应手册和一条台账：日期、变化/根因、最终验证、必要回退/待办；通常3–5行，不新建长篇日期报告。
- 已通过检查复用；变更功能、真实入口和启动日志做一次最终验收，只有新改动、失败或具体未决问题才补验。
- 当前状态只留现行值，旧版本和已完成流水从状态手册移到台账；完整过程从Git历史读取。
- 一次性验收压成结论，AI冻结题集、原始评分/回答、数据快照与必要恢复证据按原字节保留。安全、许可、迁移和未决限制不能因精简而关闭。
- 纯文档只检查编码、链接、事实和diff；整理日期不替代运行观测日期。
