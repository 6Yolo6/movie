# 数据库运维

## 基本规则

- DDL、批量更新、清理和迁移前必须备份。
- 使用专用应用或运维账号，避免日常使用 root。
- 不在命令参数和日志中暴露凭据。
- 使用 `utf8mb4` 并验证中文往返。
- 表和字段的 `COMMENT` 默认使用中文。
- 通过状态和 `deleted_at` 保留历史，不物理删除维护记录。
- 清理前运行 dry-run，并人工复核歧义标题。
- 在 `docs/current-project-status.md` 记录前后数量和回滚材料。

## 迁移模型

项目没有 Flyway、Liquibase 或迁移历史表，SQL 需要手工执行。文件名顺序不会自动生效，
部分早期脚本包含没有保护的 `ALTER TABLE`。

不要把 `crawler/add_name_column.py`、`crawler/db_migration.py`、
`crawler/run_migration.py`、`crawler/reset_password.py` 或
`crawler/clean_duplicates.sql` 当作当前迁移系统。这些是历史一次性工具，
部分包含硬编码 localhost/root 凭据或直接 DDL。优先使用
`backend/src/main/resources/db/` 中经过审查的 SQL。

`backend/src/main/resources/db/schema.sql` 会删除表，只能用于空的新数据库，
禁止在已有环境执行。

### 全新数据库

当前 `schema.sql` 创建 15 张核心表，并包含 `movie_source_identity`，但仍未包含所有后续运维能力。导入后，根据当前 Git
重新核对并预检以下增量文件：

1. `migration_resource_link_updated_at.sql`
2. `migration_soft_delete_columns.sql`
3. `migration_qq_automation.sql`
4. `migration_qq_channel_daily_post_config.sql`
5. `migration_qq_channel_post_pending_status.sql`
6. `migration_fix_qq_channel_template_mojibake.sql`
7. `migration_qq_channel_template_year_type.sql`

就绪脚本会报告 `docs/database.md` 没有列出的迁移，但不能仅凭仓库文件判断在线数据库已执行哪些 SQL。

### 历史数据库

必须根据在线架构预检，不能盲目重放。历史依赖顺序为：

1. `migration_sys_config.sql`
2. `migration_governance.sql`
3. `migration_favorites.sql`
4. `migration_notifications.sql`
5. `migration_resource_reports.sql`
6. `migration_resource_hub.sql`
7. `migration_movie_source_identity.sql`
8. `migration_resource_link_updated_at.sql`
9. `migration_soft_delete_columns.sql`
10. `migration_qq_automation.sql`
11. `migration_qq_channel_daily_post_config.sql`
12. `migration_qq_channel_post_pending_status.sql`
13. `migration_fix_qq_channel_template_mojibake.sql`
14. `migration_qq_channel_template_year_type.sql`
15. `migration_gying_owned_share_source.sql`
16. `migration_social_publishing.sql`

`migration_governance.sql`、`migration_favorites.sql` 和
`migration_resource_reports.sql` 并非完全幂等，执行前检查字段、索引和表。
`migration_gying_owned_share_source.sql` 是数据校准脚本，只把同时具备已保存目录和自有分享证据的活动资源标记为 `GYING_PUBLISHED`，执行前后必须核对影响数量。
`migration_social_publishing.sql` 创建 `social_publish_target` 和 `social_post_log`，并预置自动发布关闭的微博目标。
`migration_resource_hub.sql` 包含存储过程和 `DELIMITER`，应通过 MySQL CLI 整文件执行，
不能拆给 MCP。

## 预检查询

```sql
SELECT VERSION(), @@session.time_zone, @@global.time_zone, NOW(), UTC_TIMESTAMP();
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME, ORDINAL_POSITION;
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
GROUP BY TABLE_NAME, INDEX_NAME;
```

将在线结构与实体、`schema.sql` 以及上次部署后新增的迁移逐项比较，不能只凭表是否存在判断迁移状态。

## 备份与恢复

```bash
mysqldump --single-transaction --routines --triggers \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  -h HOST -u USER -p gying > gying_YYYYMMDD_HHMMSS.sql

mysql --default-character-set=utf8mb4 -h HOST -u USER -p gying \
  < gying_YYYYMMDD_HHMMSS.sql
```

备份后记录校验和、大小、表清单和恢复测试结果。恢复后比较影视、资源、任务和审计表的关键数量，
并抽查中文文本。

## MCP 与 CLI 选择

MySQL MCP 适合只读检查和小型参数化语句。当前路径每次只接受一个 SQL 语句，
目标变更必须逐条执行。

以下操作使用 MySQL CLI：

- 完整迁移文件；
- 含 `DELIMITER`、存储过程或多条语句的脚本；
- dump 和 restore；
- 需要流式输入的操作。

不能把整个迁移文件作为一个字符串传给 `execute_sql`。

## 资源数据维护

合并重复影片时，保留既有片库记录作为 canonical，将资源、发现、转存、收藏、评论等关联记录迁移，
再软删除重复项。执行前先调用：

```text
POST /api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true
```

清理标题不匹配资源时，先运行 dry-run，复核标题、别名和季信息，再用状态和 `deleted_at`
执行软清理，禁止改成 `DELETE FROM`。

维护 `movie_source_identity` 时，保持 `(source, source_type, external_id, season)` 唯一。
电影季号规范为 `0`；冲突身份保留审计并标记为 `REJECTED`，不能通过物理删除掩盖歧义。

## 变更后验证

- 预期表、字段、索引和中文注释存在；
- 没有意外的 null 或默认值转换；
- 应用启动时没有 mapper 或架构错误；
- MySQL 与 JVM 的 Asia/Shanghai 语义一致；
- 核心读取链路正常；
- Resource Hub 数量和待处理状态一致；
- `movie_source_identity` 的来源、类型、外部 ID、季号和 canonical 影片关系一致；
- 多平台发布表、唯一键、中文注释、默认关闭状态和历史日志均正确；
- 备份和回滚步骤仍可执行；
- 状态文档已记录验证结果。
