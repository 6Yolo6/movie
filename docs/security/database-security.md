# MySQL 安全方案

[状态](../current-project-status.md) · [数据库契约](../database.md) · [备份](backup-plan.md) · [完整旧证据](../history/snapshots/20261022/docs/security/database-security.md)

## 1. 在线观测（区分当前配置与历史查询）

2026-09-28：MySQL8.0.28/25 InnoDB表，bind/mysqlx_bind=*、TLS强制OFF、local_infile=OFF、secure_file_priv=NULL；三应用共用 gying_app@% 的库级CRUD/EXECUTE，已非root但未分服务。物理接口六端口防火墙拒绝及同网客户端历史验证通过，公网/IPv6待验收。专用 gying_backup@localhost 已验证完整逻辑备份权限，未扩应用grants。此为原日期证据，本轮不改账号/数据库。

## 2. 目标账号矩阵

| 用途 | 目标身份/权限/来源 |
| --- | --- |
| backend | gying_app，按业务CRUD，明确Docker IP/netmask |
| source/social | gying_source/gying_social，按实际SQL收紧，独立值/来源 |
| MCP审计 | gying_readonly，SELECT/SHOW VIEW，维护来源 |
| 备份 | gying_backup，SELECT/SHOW VIEW/TRIGGER/EVENT，必要SHOW_ROUTINE，备份主机 |
| DBA | root仅本机break-glass，不给应用 |

`tools/security/provision_db_accounts.py` 默认dry-run，各账号独立≥24字节凭据，拒Host=%，不删root/修改旧用户。实际来源验证后选IP/netmask，不凭Docker配置猜。

## 3. 安全切换流程

备份→记录账号/grants摘要→从受保护环境注入管理员/新账号值→dry-run核对→按当前授权apply→各容器新身份只读及受控最小写验收→逐服务切外部secret/重建→真实链路/日志→保留旧账号回退窗口。不能从本手册推导已获凭据轮换/DDL授权；root至少跨完整备份/恢复周期后另行处理。

## 4. 传输和服务器配置

防火墙Public/Private默认拒绝，来源收窄，MySQL loopback/受控网关；X Protocol不用则关或同限。TLS先隔离验证CA/JDBC，再独立启 require_secure_transport，避免所有连接器同时断连。保持local_infile=OFF、secure_file_priv不可空串；密码策略/审计/锁定按实际8.0.28插件验收。

## 5. SQL 注入和数据边界

参数绑定、排序白名单、LIMIT/分页/body上限、ADMIN写鉴权/审计、sys_config敏感键脱敏且禁运行时写。MCP默认只读，不输出Cookie/Authorization/用户URL。这些静态契约不是完整DAST；SQL/鉴权用[隔离fixture](../development.md#真实-sql-的隔离回归)按影响验证。

## 6. 备份和恢复

backup.py 以 mysqldump single-transaction/quick/no-tablespaces 流式age加密。9月25检查点 `G:/gying-backups/20260925T030328.719395Z` 25表行数/CHECKSUM及对象数量比较通过，不含全主机/系统账号/应用恢复闭环。最新发布7产物与完整恢复范围不同，详见[备份方案](backup-plan.md)。
