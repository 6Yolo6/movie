# 全项目文档整理（2026-10-22）

[现行导航](../README.md) · [当前状态](../current-project-status.md) · [历史索引](README.md)

本轮续接已完成的推荐/自然搜索交付，重新核对并总结全部59份维护文档；项目/后端AGENTS及运维SKILL入口另作指令核对，不归入文档字数。迁移数据内的外部工作区、依赖README和临时日志不是现行项目手册。

## 结果

| 范围 | 整理前 | 整理后 |
| --- | ---: | ---: |
| 原59份现行/历史文档字符数（含Markdown语法） | 302,166 | 119,175 |
| 文档审阅 | 59 | 54改写/校正，5保留简洁契约 |
| 整理前原文快照 | 0 | 59 |
| 当前Spring路由 | 旧清单 | 24控制器/172路由 |

原59份维护文档正文减少约60.6%；此指标不包含新增索引、JSON清单和原文归档，总仓库存储没有减少。

## 阅读与维护

入口README→现行docs导航；状态只保有日期的能力/架构/版本/待办/验收，流程由专项手册维护，长验收从历史索引和快照溯源。补原始响应格式、元数据/视频隔离、来源身份/MCP默认值及失效调度日期；安全G01–G11/真实账号/冷索引/独立评测等待办保持。

当前API静态路由和源码hash存[JSON](../security/api-inventory.json)，直接guard调用只供审查，不证明动态授权。合成搜索/推荐和真实库/页面证据边界保留，五份前序评测JSON未修改。

## 保存方式

每份原文增加历史说明并重定位相对链接，正文证据/原有未提交文档文字保留，不把旧“当前”当线上事实。原始字节和清单另存外部 `E:/gying-tools/releases/documentation-refresh-20261022/before`；归档只在链接修正时改变Markdown链接目标。旧命令仅追溯，不能直接复用历史授权或执行。原求职分析中的15处旧机器绝对路径仅保留历史引用，不作为可移植仓库链接或本轮可用性验收。

短小且仍准确的流程保留，避免为了字数删回退/停止条件：

- [MIGRATION.md](../../MIGRATION.md)
- [.agents/skills/gying-project-ops/references/docker-operations.md](../../.agents/skills/gying-project-ops/references/docker-operations.md)
- [.agents/skills/gying-project-ops/references/deployment-recovery.md](../../.agents/skills/gying-project-ops/references/deployment-recovery.md)
- [.agents/skills/gying-project-ops/references/social-publishing-operations.md](../../.agents/skills/gying-project-ops/references/social-publishing-operations.md)
- [docs/deployment.md](../deployment.md)


## 验证与范围

122份Markdown、829处仓库链接/锚点无错误；59快照正文除链接目标外一致，全部24增量迁移/23个schema表、172路由行号/hash通过，工作区密钥扫描0 findings，Git diff格式通过。运维就绪11通过/1既有schema覆盖警告/0失败，缺两QQ表不因文档清单完整而关闭。项目代码/五份评测JSON不变，业务功能此前已部署；本轮只改文档/后端AGENTS路径及静态清单，不执行SQL/部署/发消息/网盘写入。原有未提交安全工具保留且不纳入提交。

## 原文索引

| 整理前文档 | 快照 |
| --- | --- |
| .agents/skills/gying-project-ops/references/database-operations.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/database-operations.md) |
| .agents/skills/gying-project-ops/references/deployment-recovery.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/deployment-recovery.md) |
| .agents/skills/gying-project-ops/references/docker-operations.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/docker-operations.md) |
| .agents/skills/gying-project-ops/references/mcp-operations.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/mcp-operations.md) |
| .agents/skills/gying-project-ops/references/project-map.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/project-map.md) |
| .agents/skills/gying-project-ops/references/resource-hub-operations.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/resource-hub-operations.md) |
| .agents/skills/gying-project-ops/references/security-hardening.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/security-hardening.md) |
| .agents/skills/gying-project-ops/references/social-publishing-operations.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/social-publishing-operations.md) |
| .agents/skills/gying-project-ops/references/status-maintenance.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/status-maintenance.md) |
| .agents/skills/gying-project-ops/references/troubleshooting.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/troubleshooting.md) |
| .agents/skills/gying-project-ops/references/windows-migration.md | [原文](snapshots/20261022/.agents/skills/gying-project-ops/references/windows-migration.md) |
| MIGRATION.md | [原文](snapshots/20261022/MIGRATION.md) |
| README.md | [原文](snapshots/20261022/README.md) |
| docker-security-report.md | [原文](snapshots/20261022/docker-security-report.md) |
| docs/README.md | [原文](snapshots/20261022/docs/README.md) |
| docs/access-log-client-identity-20261014.md | [原文](snapshots/20261022/docs/access-log-client-identity-20261014.md) |
| docs/ai-assistant-20261019.md | [原文](snapshots/20261022/docs/ai-assistant-20261019.md) |
| docs/ai-career-project-analysis-20261016.md | [原文](snapshots/20261022/docs/ai-career-project-analysis-20261016.md) |
| docs/ai-recommendations-20261020.md | [原文](snapshots/20261022/docs/ai-recommendations-20261020.md) |
| docs/api-4xx-investigation-20261018.md | [原文](snapshots/20261022/docs/api-4xx-investigation-20261018.md) |
| docs/api.md | [原文](snapshots/20261022/docs/api.md) |
| docs/catalog-features-search-20261021.md | [原文](snapshots/20261022/docs/catalog-features-search-20261021.md) |
| docs/code-optimization-20261009-round2.md | [原文](snapshots/20261022/docs/code-optimization-20261009-round2.md) |
| docs/code-optimization-20261009.md | [原文](snapshots/20261022/docs/code-optimization-20261009.md) |
| docs/code-optimization-20261012.md | [原文](snapshots/20261022/docs/code-optimization-20261012.md) |
| docs/code-optimization-deployment-20261011.md | [原文](snapshots/20261022/docs/code-optimization-deployment-20261011.md) |
| docs/current-project-status.md | [原文](snapshots/20261022/docs/current-project-status.md) |
| docs/database.md | [原文](snapshots/20261022/docs/database.md) |
| docs/deployment.md | [原文](snapshots/20261022/docs/deployment.md) |
| docs/development.md | [原文](snapshots/20261022/docs/development.md) |
| docs/gying-p2p-collection.md | [原文](snapshots/20261022/docs/gying-p2p-collection.md) |
| docs/history/README.md | [原文](snapshots/20261022/docs/history/README.md) |
| docs/history/migration-guide-before-20261011.md | [原文](snapshots/20261022/docs/history/migration-guide-before-20261011.md) |
| docs/history/operations-through-20261008.md | [原文](snapshots/20261022/docs/history/operations-through-20261008.md) |
| docs/metadata-weekly-transfer.md | [原文](snapshots/20261022/docs/metadata-weekly-transfer.md) |
| docs/monitoring-resource-counts-20261011.md | [原文](snapshots/20261022/docs/monitoring-resource-counts-20261011.md) |
| docs/movie-detail-enhancements-20261021.md | [原文](snapshots/20261022/docs/movie-detail-enhancements-20261021.md) |
| docs/p2p-cloud-archive.md | [原文](snapshots/20261022/docs/p2p-cloud-archive.md) |
| docs/qq-channel-multi-drive.md | [原文](snapshots/20261022/docs/qq-channel-multi-drive.md) |
| docs/resource-hub.md | [原文](snapshots/20261022/docs/resource-hub.md) |
| docs/resource-search-p2p-deployment-20261019.md | [原文](snapshots/20261022/docs/resource-search-p2p-deployment-20261019.md) |
| docs/security/README.md | [原文](snapshots/20261022/docs/security/README.md) |
| docs/security/api-inventory.md | [原文](snapshots/20261022/docs/security/api-inventory.md) |
| docs/security/backup-plan.md | [原文](snapshots/20261022/docs/security/backup-plan.md) |
| docs/security/cloudflare-access-activation-20261013.md | [原文](snapshots/20261022/docs/security/cloudflare-access-activation-20261013.md) |
| docs/security/cloudflare-access-setup.md | [原文](snapshots/20261022/docs/security/cloudflare-access-setup.md) |
| docs/security/cloudflare-security.md | [原文](snapshots/20261022/docs/security/cloudflare-security.md) |
| docs/security/database-security.md | [原文](snapshots/20261022/docs/security/database-security.md) |
| docs/security/deployment-checklist.md | [原文](snapshots/20261022/docs/security/deployment-checklist.md) |
| docs/security/docker-security.md | [原文](snapshots/20261022/docs/security/docker-security.md) |
| docs/security/hardening-gates.md | [原文](snapshots/20261022/docs/security/hardening-gates.md) |
| docs/security/incident-response.md | [原文](snapshots/20261022/docs/security/incident-response.md) |
| docs/security/risk-register.md | [原文](snapshots/20261022/docs/security/risk-register.md) |
| docs/security/secret-management.md | [原文](snapshots/20261022/docs/security/secret-management.md) |
| docs/security/security-architecture.md | [原文](snapshots/20261022/docs/security/security-architecture.md) |
| docs/social-publishing.md | [原文](snapshots/20261022/docs/social-publishing.md) |
| docs/weekly-graceful-restart.md | [原文](snapshots/20261022/docs/weekly-graceful-restart.md) |
| docs/xunlei-token-sync.md | [原文](snapshots/20261022/docs/xunlei-token-sync.md) |
| frontend/README.md | [原文](snapshots/20261022/frontend/README.md) |
