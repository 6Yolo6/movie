# 历史来源与证据导航

[现行手册](../README.md) · [当前状态](../current-project-status.md) · [发布与恢复台账](releases.md) · [未决排查](issues.md)

旧日期修复、候选流水和整份文档快照已从当前目录提炼，必要实现进入稳定手册，版本/检查点/限制进入台账。冻结题集、原始报告、评分口径与元数据快照继续保留在 `docs/evaluation/`，没有改写为新模型成果。

<a id="git-history"></a>
## Git全文追溯

整理前完整内容固定在已推送提交 `49fe7fd7072ac9ef3c6d1bda7da4bce7d9cf5156`。此前10月22快照也在该提交的 `docs/history/snapshots/20261022/` 下；当前目录不再保存第二份相同手册。下面只读取文字，不执行其中历史操作：

```powershell
git show 49fe7fd7072ac9ef3c6d1bda7da4bce7d9cf5156:docs/ai-query-plan-v2-20261025.md
git show 49fe7fd7072ac9ef3c6d1bda7da4bce7d9cf5156:docs/ai-assistant-availability-20261025.md
git show 49fe7fd7072ac9ef3c6d1bda7da4bce7d9cf5156:docs/history/snapshots/20261022/docs/weekly-graceful-restart.md
git ls-tree -r --name-only 49fe7fd7072ac9ef3c6d1bda7da4bce7d9cf5156 -- docs
```

使用完整Git历史；浅检出若缺该对象，先从正常远端获取历史。读取旧文档不代表允许重放SQL、恢复旧状态或采用旧密码/授权。旧迁移原手册还可用 `git show fdf7276a4606b3957d2ee912232781e71503818a:MIGRATION.md` 核查。

## 旧文件对应的现行入口

| 旧文件（在上述提交中） | 已提炼到哪里 |
| --- | --- |
| ai-system-chain-20261023.md | [AI架构](../ai-architecture.md)，更新结构化研究/embedding/Bing/网页幂等现状 |
| ai-strengthening-roadmap-20261022.md、ai-career-project-analysis-20261016.md | [求职路线](../ai-career-roadmap.md)，区分已交付与下一阶段 |
| ai-comparable-evaluation-20261023.md | [评测手册](../ai-evaluation.md)，保留原判分与复现约束 |
| ai-assistant-20261019.md、ai-recommendations-20261020.md、catalog-features-search-20261021.md | AI架构的桥接/推荐/检索及[发布台账](releases.md#earlier-releases) |
| ai-execution-reliability-20261022.md、ai-reliability-deployment-20261023.md | AI架构的租约/回执契约及[可靠性启用证据](releases.md#ai-reliability-20261023) |
| ai-search-web-20261022.md、ai-query-plan-v2-20261025.md | AI架构的联网/结构化检索及[查询部署](releases.md#ai-query-20261025) |
| ai-assistant-availability-20261025.md | [服务恢复](releases.md#ai-availability-20261025)、[问题台账](issues.md) |
| access-log-client-identity-20261014.md、api-4xx-investigation-20261018.md | [数据库](../database.md)、[知识导读](../project-guide.md)、问题台账及发布来源 |
| code-optimization-20261009.md、code-optimization-20261009-round2.md、code-optimization-20261012.md、code-optimization-deployment-20261011.md | [发布状态机](../social-publishing.md)、知识导读/开发测试与历史来源 |
| monitoring-resource-counts-20261011.md、movie-detail-enhancements-20261021.md、resource-search-p2p-deployment-20261019.md | [API](../api.md)、知识导读、P2P与历史来源 |
| crawl-resource-links-20261022.md、gying-manual-completion-20261023.md | [Resource Hub](../resource-hub.md)与[补齐验收](releases.md#manual-completion-20261023) |
| security/cloudflare-access-setup.md、security/cloudflare-access-activation-20261013.md | [Cloudflare手册](../security/cloudflare-security.md)，保留精确回退与待验项 |
| history/operations-through-20261008.md、history/migration-guide-before-20261011.md、history/documentation-refresh-20261022.md、history/snapshots/ | 发布台账必要历史；全部原文按Git路径追溯 |

## 保留与维护规则

当前手册维护机制；状态文件维护有日期的运行事实；历史台账保留部署范围、检查点和未决限制；evaluation保留冻结证据。小修复优先更新这四处，不新增一份重复的日期流水文档。需要完整事故细节时，保留脱敏外部证据位置与对应Git提交。

镜像/hash正确、备份认证、隔离恢复、真实网站登录、外部出站与人工质量各自成立。历史记录没有写成功的项目仍待验，归并不清队列、重置失败或关闭安全风险。
