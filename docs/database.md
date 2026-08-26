# 数据库迁移

## 新环境

新环境直接导入：

```bash
mysql -uroot -p gying < backend/src/main/resources/db/schema.sql
```

`schema.sql` 包含 `DROP TABLE`，只用于没有历史数据的空库。迁移已有生产数据时应恢复完整 `mysqldump`，不要先导入 `schema.sql`。当前 schema 声明 16 张表，包含 `xunlei_transfer_task`、`movie_source_identity`、`social_publish_target` 和 `social_post_log`；QQ 自动化日志表仍需要对应增量迁移。

## 旧环境

旧环境按需执行 `backend/src/main/resources/db/` 下的迁移文件：

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

`migration_resource_reports.sql` 会增加资源质量字段、拒绝原因字段和 `resource_report` 举报表。
`migration_resource_hub.sql` 会增加 Resource Hub 所需的 TMDB 标识、资源追踪字段和任务表。
`migration_movie_source_identity.sql` 会增加 GYING/TMDB 外部身份与本地 canonical 影片的映射表。
`migration_resource_link_updated_at.sql` 和 `migration_soft_delete_columns.sql` 补齐资源更新时间与核心软删除字段。
`migration_qq_channel_post_pending_status.sql`、`migration_fix_qq_channel_template_mojibake.sql` 用于频道待发送状态与旧模板乱码修复。
`migration_qq_channel_template_year_type.sql` 只把仍使用旧默认值的 QQ 频道模板升级为包含年份和类型的版本，不覆盖管理员自定义模板。
`migration_gying_owned_share_source.sql` 是数据校准脚本，只标记同时具备保存目录和自有分享证据的活动资源；执行前后必须核对影响数量。
`migration_social_publishing.sql` 会创建多平台发布目标和审计日志，并预置 1 个新浪微博目标；QQ 账号扫码授权后再由管理员添加频道目标，所有自动发布默认关闭。

项目没有 Flyway/Liquibase 和可靠迁移历史表。已有数据库必须先比较表、字段、索引、默认值和中文注释，再按需执行增量 SQL；不能按文件名盲目重放。`migration_resource_hub.sql` 含存储过程和 `DELIMITER`，应通过 MySQL CLI 整文件执行。
