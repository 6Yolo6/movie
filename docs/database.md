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

`migration_resource_reports.sql` 会增加资源质量字段、拒绝原因字段和 `resource_report` 举报表。

如果你已经手动执行过某些字段，请先用 `DESC resource_link;` 和 `SHOW TABLES LIKE 'resource_report';` 确认，避免重复 `ALTER TABLE`。