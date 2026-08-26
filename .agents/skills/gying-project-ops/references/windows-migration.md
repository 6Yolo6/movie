# Windows 与 Linux 迁移

处理 Windows 到 Windows、Windows 到 Linux 或 Linux 到 Linux 的项目迁移、灾难恢复和新机上线时，先完整读取仓库根目录 `MIGRATION.md`，并把它作为迁移步骤和命令的权威来源。本参考规定运维 Skill 的判断边界和执行顺序。

## 必须先确认

1. 源机和目标机的真实部署目录、Git 提交和 Compose 项目名。
2. MySQL、Redis、MinIO、PanSou、quark-auto-save 和 OpenClaw 是外部容器、Compose profile 还是托管服务。
3. 所有容器的实际挂载、命名卷、bind mount、所有者和备份位置。
4. Resource Hub、Worker、机器人、频道和多平台发布是否已停写。
5. 迁移期间是否存在 `RUNNING`、`SUBMITTED`、`WAITING_SHARE` 或 `PENDING` 的外部任务。
6. 目标机是否需要保留原域名、证书、端口、QQ 账号、微博会话和发布计划。

## 迁移判断

- Git 仓库和 Skill 不是完整备份。没有 MySQL、MinIO、关键卷、外部配置和凭据，不能恢复现有系统。
- MySQL 优先恢复完整逻辑 dump；已有 dump 时不要先执行含 `DROP TABLE` 的 `schema.sql`。
- MinIO/S3 按对象层镜像并保持数据库中的对象键不变。
- Redis 默认作为缓存重建；只有存在不可重建状态时才单独评审 RDB/AOF。
- 必须迁移 quark-auto-save 配置和任务、OpenClaw 配置/认证/插件、`social-publisher-qq-accounts` 和 `social-publisher-weibo` 的实际持久化数据。
- NapCat 已停用，`napcat-data` 不迁移，目标机不启动 NapCat，也不把它纳入健康检查和验收。
- `.env`、Cookie、Token、Authorization、QQ/微博会话和 MCP 密码只能通过受保护介质迁移，不进入 Git、状态文档或日志。

## 执行顺序

1. 运行 `collect-ops-snapshot.ps1` 和 `test-ops-readiness.ps1`，记录提交、镜像、容器、卷、挂载和配置键。
2. 关闭 Worker、机器人自动转存、频道和多平台发布调度。
3. 备份 MySQL、MinIO/S3、关键 Docker 卷、外部配置、OpenClaw、MCP 和计划任务。
4. 生成校验和，并至少完成一次数据库恢复测试。
5. 初始化目标机并检出确定提交，保持所有自动化关闭。
6. 按数据库、Redis、对象存储、搜索/转存依赖、`gying-source`、`social-publisher`、backend、frontend、nginx、OpenClaw 的顺序恢复。
7. 比较表集合、关键计数、状态分布、对象和中文文本。
8. 运行一条受控真实链路，再逐项启用自动化。
9. 保留旧机回滚窗口并更新 `docs/current-project-status.md`。

## Windows 注意事项

- 使用 Docker Desktop/Engine、Compose v2、WSL2、MySQL CLI 和稳定的工作目录。
- 通过 `docker inspect` 获取真实卷名和 bind mount，不凭 YAML 短名称猜测。
- 使用 `Export-ScheduledTask` 迁移 Windows 计划任务，重新确认执行用户、工作目录、路径和权限。
- PowerShell 读取 Markdown、SQL、JSON 和日志时显式使用 UTF-8。

## Linux 注意事项

- 使用受支持的发行版、Docker Engine 和 Compose v2；代码与备份放在仓库之外的稳定路径。
- 处理 UID/GID、目录权限、SELinux/AppArmor、Docker daemon、日志轮转、NTP 和磁盘监控。
- 防火墙默认只开放 `80/443` 及经过评审的管理端口，不将 MySQL、Redis、MinIO 管理端口直接暴露公网。
- 使用 systemd 或 cron 承载宿主机桥接和 OpenClaw 时，明确 `User`、`WorkingDirectory`、`EnvironmentFile`、重启策略和日志位置。
- 迁移域名时同步 DNS、TLS 证书、反向代理和公网 MinIO/CDN 地址。

## 禁止事项

- 不执行 `docker compose down -v`。
- 不用复制仓库代替数据库和卷备份。
- 不把运行中的 MinIO/Redis 数据目录当作一致性备份直接复制。
- 不盲目重放 `backend/src/main/resources/db/` 的全部 SQL。
- 不直接重试迁移前处于中间状态的外部任务。
- 不恢复或重新启用 NapCat。
- 不在输出中显示敏感值。
