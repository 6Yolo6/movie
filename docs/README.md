# 文档导航

建议按“当前事实 → 系统知识 → AI架构 → 求职路线 → 证据”阅读。每份手册维护一个主题；旧修复与候选流水已提炼，全文从Git历史核查，不与当前机制并排混读。

## 理解项目与AI

| 阅读顺序 | 文档 | 可以回答的问题 |
| --- | --- | --- |
| 1 | [当前项目状态](current-project-status.md) | 现在部署了什么、证据是哪天、哪些还没完成？ |
| 2 | [项目知识导读](project-guide.md) | 请求、权限、SQL、缓存、任务、网盘、发布、前端与运维如何配合，在哪看源码？ |
| 3 | [AI整套架构](ai-architecture.md) | 从网页问题到queryPlan、BM25/BGE-M3、联网、依据与回执恢复怎样工作？ |
| 4 | [Java与AI求职补强路线](ai-career-roadmap.md) | 已有成果能怎么表达、下一阶段怎样学习和验收？ |
| 5 | [AI评测与人工复核](ai-evaluation.md) | 为什么请求完成不等于答对，怎样做同数据比较、冻结题集与人工评分？ |

## 开发和运维手册

| 主题 | 入口 |
| --- | --- |
| API/权限/数据 | [接口](api.md) · [数据库与迁移库存](database.md) · [安全API清单](security/api-inventory.md) |
| 本地开发/测试 | [开发手册](development.md) · [前端](../frontend/README.md) |
| 部署/回退/迁移 | [最小部署](deployment.md) · [迁移恢复](../MIGRATION.md) · [加密备份](security/backup-plan.md) |
| 采集/资源治理 | [Resource Hub](resource-hub.md) · [元数据与周榜](metadata-weekly-transfer.md) |
| P2P/双盘 | [来源采集](gying-p2p-collection.md) · [网盘归档](p2p-cloud-archive.md) |
| QQ/外部发布 | [发布状态机](social-publishing.md) · [频道多盘正文](qq-channel-multi-drive.md) |
| 主机维护 | [迅雷会话同步](xunlei-token-sync.md) · [优雅重启](weekly-graceful-restart.md) |
| 安全/故障 | [安全导航](security/README.md) · [运维排障](../.agents/skills/gying-project-ops/references/troubleshooting.md) · [运维技能](../.agents/skills/gying-project-ops/SKILL.md) |

## 核查原始证据

[发布与恢复台账](history/releases.md)集中保留范围/版本/检查点；[问题台账](history/issues.md)集中保留排查结论和未决项；[Git全文来源](history/README.md)映射删去的旧文档。[evaluation](ai-evaluation.md#2-本轮文件与默认行为)下的题集、freeze、原始报告和评分口径保持原样。

## 阅读规则

机制说明、代码、配置、在线观测、程序测试、真实登录、外部出站与人工质量各有适用范围。新小修复优先更新现行手册/状态/台账，不再复制一份整套文档或重复“本轮”流水。真实凭据与原始敏感日志不进入docs。
