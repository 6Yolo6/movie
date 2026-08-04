# 数据库迁移

## 新环境

新环境直接导入：

```bash
mysql -uroot -p gying < backend/src/main/resources/db/schema.sql
```

## 旧环境

旧环境按需执行 `backend/src/main/resources/db/` 下的迁移文件：

1. `migration_sys_config.sql`
2. `migration_governance.sql`
3. `migration_favorites.sql`
4. `migration_notifications.sql`
5. `migration_resource_reports.sql`
6. `migration_resource_hub.sql`
7. `migration_qq_automation.sql`
8. `migration_qq_channel_daily_post_config.sql`
9. `migration_qq_channel_template_year_type.sql`

`migration_resource_reports.sql` 会增加资源质量字段、拒绝原因字段和 `resource_report` 举报表。
`migration_resource_hub.sql` 会增加 Resource Hub 所需的 TMDB 标识、资源追踪字段和任务表。
`migration_qq_channel_template_year_type.sql` 只把仍使用旧默认值的 QQ 频道模板升级为包含年份和类型的版本，不覆盖管理员自定义模板。

如果你已经手动执行过某些字段，请先用 `DESC resource_link;` 和 `SHOW TABLES LIKE 'resource_report';` 确认，避免重复 `ALTER TABLE`。
