# 影窝 · GYing Movie

电影、剧集和动漫片库，提供检索、个性化推荐、资源治理、GYING 采集与夸克/迅雷自有分享。英文品牌为 FilmNest。

| 需要做什么 | 从这里开始 |
| --- | --- |
| 了解系统、在线版本和待办 | [项目状态](docs/current-project-status.md) |
| 查全部文档 | [文档导航](docs/README.md) |
| 开发、测试 | [开发手册](docs/development.md) · [前端](frontend/README.md) |
| 对接接口、修改数据库 | [API](docs/api.md) · [数据库](docs/database.md) |
| 发布、回滚、迁移 | [部署](docs/deployment.md) · [迁移恢复](MIGRATION.md) |
| 安全与故障 | [安全导航](docs/security/README.md) · [排障](.agents/skills/gying-project-ops/references/troubleshooting.md) |
| AI 项目成果与原计划 | [本轮成果](docs/catalog-features-search-20261021.md) · [求职路线](docs/ai-career-project-analysis-20261016.md) |

`backend/` 为 Java 17 / Spring Boot API；`frontend/` 为 Next.js；`crawler/` 为 Python 数据源；`social-publisher/` 为独立发布器；`tools/` 为测试及运维工具。

使用独立检出与测试依赖开发，禁止复制生产凭据。`schema.sql` 含 DROP，只能用于确认空的新库，且缺两张 QQ 日志表；没有默认管理员。生产配置和历史报告不能代替在线证据。发布仅替换受影响服务，保留加密检查点；真实转存、发帖、凭据变更及破坏性操作按各专项流程执行。
