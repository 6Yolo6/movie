# 数据库与迁移

[文档导航](README.md) · [部署](deployment.md) · [迁移与恢复](../MIGRATION.md) · [数据库安全](security/database-security.md)

## 1. 先区分数据库类型

| 场景 | 正确路径 | 禁止做法 |
| --- | --- | --- |
| 已有生产数据 | 备份并核对实际表、列、索引、默认值、注释，再按差异审查增量 SQL | 导入 `schema.sql`、按文件名盲目重放全部迁移 |
| 异机迁移/灾难恢复 | 在独立目标恢复经验证的完整 dump，再按目标版本核对差异 | 为“补表”先覆盖原库，或自动用旧备份回滚在线数据 |
| 确认为空的隔离新库 | 审查 `schema.sql` 后初始化，再补齐缺失结构/配置 | 把新库初始化当成存量库升级命令 |

**[schema.sql](../backend/src/main/resources/db/schema.sql) 含 DROP TABLE。** 执行前必须确认目标地址、库名和零历史数据；本文不提供可直接指向生产的初始化命令。项目没有 Flyway/Liquibase，也没有可靠的已应用迁移台账，文件存在不代表已经执行。

仓库建表脚本没有插入默认管理员账号。新环境管理员初始化须单独审查；不要尝试旧文档中的通用初始密码或批量改角色。

## 2. 仓库结构与生产证据

本次文档核对的 `schema.sql` 声明 **23 张表**：

| 领域 | 表 |
| --- | --- |
| 片库与资源 | `movie_metadata`、`resource_link`、`movie_source_identity`、`resource_report` |
| 用户与设置 | `sys_user`、`sys_config`、`login_device`、`user_invitation_code`、`user_invitation_use` |
| 互动与通知 | `comment`、`comment_vote`、`user_favorite`、`user_notification` |
| 资源任务 | `resource_hub_task`、`resource_discovery_result`、`quark_transfer_task`、`xunlei_transfer_task`、`qq_transfer_cleanup_job` |
| 多平台发布 | `social_publish_target`、`social_post_log` |
| 监控 | `site_access_log`、`site_search_log`、`resource_operation_log` |

**新库覆盖缺口仍存在：** `qq_bot_search_log`、`qq_channel_post_log` 由 `migration_qq_automation.sql` 创建，尚未合入 schema。新库不能只导入 schema 就宣称完整；还需按功能核对其他迁移中的配置默认值。生产 25 表的最近备份验证见[2026-10-11 上线记录](code-optimization-deployment-20261011.md)，不是本次文档整理重新查询的结论。

## 3. 增量文件清单

以下 **23 个文件按名称排序，仅作库存，不是执行顺序或执行授权**。即使脚本含幂等保护，也必须审查数据更新、依赖表和自定义配置影响。

| 文件 | 主要用途 |
| --- | --- |
| [`migration_comments_login_devices.sql`](../backend/src/main/resources/db/migration_comments_login_devices.sql) | 评论类型、安全约束与登录设备授权表 |
| [`migration_favorites.sql`](../backend/src/main/resources/db/migration_favorites.sql) | 收藏表与影片热度字段 |
| [`migration_fix_qq_channel_template_mojibake.sql`](../backend/src/main/resources/db/migration_fix_qq_channel_template_mojibake.sql) | 修正历史 QQ 频道默认模板乱码 |
| [`migration_governance.sql`](../backend/src/main/resources/db/migration_governance.sql) | 资源/用户/影片治理字段与评论投票表 |
| [`migration_gying_owned_share_source.sql`](../backend/src/main/resources/db/migration_gying_owned_share_source.sql) | 依据保存目录和自有分享证据校准资源来源（数据更新） |
| [`migration_movie_source_identity.sql`](../backend/src/main/resources/db/migration_movie_source_identity.sql) | 外部来源与本地 canonical 影片身份表 |
| [`migration_notifications.sql`](../backend/src/main/resources/db/migration_notifications.sql) | 站内通知表 |
| [`migration_qq_automation.sql`](../backend/src/main/resources/db/migration_qq_automation.sql) | QQ 搜索、频道日志表及默认配置；补齐 schema 的两张缺表 |
| [`migration_qq_channel_daily_post_config.sql`](../backend/src/main/resources/db/migration_qq_channel_daily_post_config.sql) | QQ 频道每日自动发布配置 |
| [`migration_qq_channel_post_pending_status.sql`](../backend/src/main/resources/db/migration_qq_channel_post_pending_status.sql) | QQ 频道日志待发送状态注释 |
| [`migration_qq_channel_template_year_type.sql`](../backend/src/main/resources/db/migration_qq_channel_template_year_type.sql) | 升级仍使用旧默认值的频道模板，不覆盖自定义模板 |
| [`migration_qq_transfer_cleanup_and_settings_zh.sql`](../backend/src/main/resources/db/migration_qq_transfer_cleanup_and_settings_zh.sql) | QQ 临时转存清理任务与系统设置中文说明 |
| [`migration_registration_invitation_monitoring.sql`](../backend/src/main/resources/db/migration_registration_invitation_monitoring.sql) | 注册邮箱、邀请码、邀请使用及访问/搜索/操作日志 |
| [`migration_resource_form.sql`](../backend/src/main/resources/db/migration_resource_form.sql) | 资源表单快速参数配置 |
| [`migration_resource_hub.sql`](../backend/src/main/resources/db/migration_resource_hub.sql) | Resource Hub、发现及夸克/迅雷任务；含存储过程和 DELIMITER |
| [`migration_resource_link_updated_at.sql`](../backend/src/main/resources/db/migration_resource_link_updated_at.sql) | 资源更新时间字段 |
| [`migration_resource_reports.sql`](../backend/src/main/resources/db/migration_resource_reports.sql) | 资源质量、拒绝原因及举报表 |
| [`migration_social_publishing.sql`](../backend/src/main/resources/db/migration_social_publishing.sql) | 发布目标与审计表，预置自动发布关闭的微博目标 |
| [`migration_soft_delete_columns.sql`](../backend/src/main/resources/db/migration_soft_delete_columns.sql) | 核心表软删除字段 |
| [`migration_source_identity_case_sensitive.sql`](../backend/src/main/resources/db/migration_source_identity_case_sensitive.sql) | 外部来源 ID 使用 utf8mb4_bin，保留原值和影片主键 |
| [`migration_sys_config.sql`](../backend/src/main/resources/db/migration_sys_config.sql) | 系统设置表与默认配置 |
| [`migration_user_email_change.sql`](../backend/src/main/resources/db/migration_user_email_change.sql) | 邮箱变更时间与冷却期支持 |
| [`migration_user_nickname.sql`](../backend/src/main/resources/db/migration_user_nickname.sql) | 用户昵称字段与历史回填 |

### 执行前后检查

1. 使用授权维护身份，先确认加密备份、完整认证解密和恢复方案；不要给应用账号临时补 DDL 权限。
2. 比较线上表/字段/索引/排序规则、配置默认值与目标 SQL，明确变更集合及预计影响行数。
3. 协调相关写入方，保留原始审计、软删除关系、任务状态和配置；含数据校准的 SQL 要有前后数量对比。
4. 通过 MySQL CLI 正确处理整文件编码及 `DELIMITER`，不通过不支持该语法的通用 SQL 分割器执行。
5. 复核表集合、唯一约束、中文往返、关键行数和业务读取，再恢复原有调度。迁移完成不等于真实转存/发布已验收。
6. 记录实际执行日期、文件、源/目标版本与证据。回滚优先兼容的应用回退或经审查的前向修复，不自动撤销新增列或恢复旧 dump。

## 4. 外部来源 ID 大小写

`migration_source_identity_case_sensitive.sql` **已于 2026-10-08 在生产执行**，不再是“待部署”项；证据见[历史验收](history/operations-through-20261008.md)。2026-10-11 应用上线未再次执行 SQL 迁移。

GYING ID 是不透明、大小写敏感的标识，`dWXo` 与 `dwXo` 必须独立。`movie_source_identity.external_id` 使用 `utf8mb4_bin`；迁移不改写 ID、不修改 `movie_metadata.id` 或关联主键。

其他尚未迁移的环境应先备份并暂停相关元数据写入，核对原始行及唯一索引，再验证精确查询、不同大小写分别入库及同 ID 唯一约束。不能把 fixture 通过视作该环境已迁移。

**应用回滚保留二进制排序规则。** 自动恢复到不区分大小写的旧规则可能合并合法身份或触发唯一键冲突；恢复旧规则必须单独审查现有数据。

## 5. 其他长期约束

- 主表、资源和来源身份均保留历史；删除和合并先 dry-run，优先软删除/关联迁移。
- 发布状态不确定时不能批量改回 PENDING，见[发布状态机](social-publishing.md#2-状态机与重试边界)。
- 生产时区证据以[当前状态](current-project-status.md)为准；迁移时同时核对 MySQL session/global、JVM 与容器，不能只改一个配置文件。
- [运维就绪检查](../.agents/skills/gying-project-ops/scripts/test-ops-readiness.ps1)会检查迁移清单和 schema 覆盖；文档库存完整不能消除实际缺表 WARN。
