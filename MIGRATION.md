# GYing Movie 完整迁移手册

本文用于 Windows 到 Windows、Windows 到 Linux 以及 Linux 到 Linux 的项目迁移、灾难恢复和新服务器上线。仅拉取 Git 仓库和项目 Skill 不能恢复完整系统，还必须迁移数据库、对象存储、关键 Docker 持久化数据、外部配置、OpenClaw、MCP、计划任务和受保护凭据。环境变量、外部服务和 QQ 配置详见 [部署文档](docs/deployment.md)，数据库增量详见 [数据库文档](docs/database.md)。

## 1. 迁移前

- 冻结写入窗口并记录当前提交、数据库版本和对象存储前缀。
- 备份 MySQL、MinIO/对象存储和 quark-auto-save 配置。
- 备份 `social-publisher-qq-accounts`、`social-publisher-weibo` 和 OpenClaw 实际挂载目录。
- 备份腾讯频道桥接配置、Windows 计划任务或 Linux systemd/cron 定义，以及 Codex MCP 配置。
- NapCat 已停用，不备份或恢复 `napcat-data`；Redis 默认只重建缓存，不迁移旧缓存。
- 使用专用数据库用户，不在命令或文档中写明文密码。
- 准备生产 `.env`，至少替换数据库密码、Redis 密码、JWT Secret 和内部令牌。

Windows 上优先使用 `--result-file`，避免 PowerShell 5.1 重定向改变编码：

```powershell
mysqldump `
  --single-transaction `
  --routines `
  --triggers `
  --events `
  --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 `
  --host=SOURCE_HOST `
  --user=SOURCE_USER `
  -p `
  --result-file="D:\gying-migration\mysql\gying.sql" `
  gying
```

Linux 上使用：

```bash
mysqldump --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  -h SOURCE_HOST -u SOURCE_USER -p gying \
  > /srv/gying-backup/mysql/gying.sql
```

## 2. 数据库

新环境导入当前 schema：

```bash
mysql -h <target-host> -u <user> -p \
  -e "CREATE DATABASE gying CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql -h <target-host> -u <user> -p gying \
  < backend/src/main/resources/db/schema.sql
```

迁移已有数据时先导入备份，再按 `backend/src/main/resources/db/` 中尚未执行的迁移文件补齐结构。执行前用 `DESC`、`SHOW TABLES` 或迁移记录确认状态，避免重复 `ALTER TABLE`。

Windows 恢复完整 dump：

```powershell
mysql --host=TARGET_HOST --user=TARGET_USER -p `
  --execute="CREATE DATABASE IF NOT EXISTS gying CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql --host=TARGET_HOST --user=TARGET_USER -p `
  --default-character-set=utf8mb4 gying `
  --execute="source D:/gying-migration/mysql/gying.sql;"
```

Linux 恢复完整 dump：

```bash
mysql -h TARGET_HOST -u TARGET_USER -p \
  -e "CREATE DATABASE IF NOT EXISTS gying CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql --default-character-set=utf8mb4 -h TARGET_HOST -u TARGET_USER -p gying \
  < /srv/gying-backup/mysql/gying.sql
```

迁移后检查：

- `movie_metadata`、`resource_link` 主记录数和 `deleted_at` 字段。
- Resource Hub 任务、发现、转存和 QQ 日志表。
- `sys_config` 中非敏感配置项及中文字段注释。
- MySQL 时区为 `Asia/Shanghai`。

源库和目标库至少比较以下数据：

```sql
SELECT COUNT(*) FROM movie_metadata;
SELECT COUNT(*) FROM resource_link;
SELECT COUNT(*) FROM resource_hub_task;
SELECT COUNT(*) FROM resource_discovery_result;
SELECT COUNT(*) FROM quark_transfer_task;
SELECT COUNT(*) FROM xunlei_transfer_task;
SELECT COUNT(*) FROM movie_source_identity;
SELECT COUNT(*) FROM qq_bot_search_log;
SELECT COUNT(*) FROM qq_channel_post_log;
SELECT COUNT(*) FROM social_publish_target;
SELECT COUNT(*) FROM social_post_log;
SELECT COUNT(*) FROM sys_config;
```

同时比较任务状态分布、活动资源与软删除数量，并抽查中文片名、简介、评论和模板，不能只比较总行数。

完整 dump 已包含结构和数据，已有数据迁移时不要先执行 `schema.sql`。该文件包含 `DROP TABLE`，只用于空的新数据库。当前 `schema.sql` 已包含 16 张表，包括 `xunlei_transfer_task`、`social_publish_target` 和 `social_post_log`；`qq_bot_search_log`、`qq_channel_post_log` 等增量结构仍需按在线架构预检后处理。项目没有 Flyway/Liquibase，不能按文件名盲目重放 SQL。

## 3. 图片与对象存储

- 同步整个 MinIO bucket 或 S3 兼容对象前缀。
- 保持数据库中的相对对象键不变，只调整 `MINIO_URL_PREFIX`。
- 抽查电影、剧集、动漫海报以及腾讯频道发帖图片。

优先使用 MinIO Client 在对象层迁移并保持对象键不变：

```powershell
mc mirror --overwrite source/gying target/gying
```

停写后的最终同步只有在确认目标 bucket 应与源完全一致时才使用 `--remove`。不要直接复制运行中的 MinIO 数据目录作为一致性备份。

## 4. Docker 数据、配置与外部依赖

迁移前通过 `docker inspect` 获取真实挂载和卷名，不要根据 Compose 短名称猜测：

```powershell
docker inspect gying-movie-social-publisher-1 --format '{{json .Mounts}}'
docker inspect gying-quark-auto-save --format '{{json .Mounts}}'
docker inspect openclaw-openclaw-gateway-1 --format '{{json .Mounts}}'
```

必须迁移：quark-auto-save 配置和任务、`social-publisher-qq-accounts`、`social-publisher-weibo`、OpenClaw 配置/认证/插件、腾讯频道桥接配置。Redis 和 PanSou 缓存默认重建。`backend-logs` 只归档不恢复。NapCat 不启动、不验收、不迁移 `napcat-data`。

命名卷可以通过临时容器导出和恢复，卷名替换为 `docker inspect` 得到的真实名称：

```powershell
docker run --rm --mount source=VOLUME_NAME,target=/source,readonly `
  --mount type=bind,source="D:\gying-migration",target=/backup `
  alpine sh -c "cd /source && tar czf /backup/VOLUME_NAME.tgz ."

docker volume create VOLUME_NAME
docker run --rm --mount source=VOLUME_NAME,target=/target `
  --mount type=bind,source="D:\gying-migration",target=/backup,readonly `
  alpine sh -c "cd /target && tar xzf /backup/VOLUME_NAME.tgz"
```

恢复 `.env` 时只通过受保护介质传输；补齐新版本新增键，不能把 Cookie、Token、Authorization 或微博会话写入 Git、状态文档和日志。

```bash
cp .env.example .env
# 填写生产值
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

- 默认连接现有 MySQL、Redis、MinIO、PanSou 和 quark-auto-save。
- 只有明确需要时才启用 `embedded-deps` profile，避免端口冲突。
- 上游容器重建后重新创建 nginx，确保反向代理解析到新容器地址。
- GYING、夸克、QQ 和腾讯频道凭据在目标主机重新配置，不从文档或 Git 恢复。

当前 QQ 机器人使用 OpenClaw。迁移后检查 Gateway、认证和运行时插件路径；不要把 NapCat 作为替代入口恢复。迅雷 Authorization 是短时 access token，旧值迁移后仍可能过期，收到 401 时重新取得并在 Resource Hub 运行时配置中更新。

Codex MCP 迁移 `%USERPROFILE%\.codex\config.toml` 或 Linux 用户目录下的对应文件时，只复制脱敏配置并在新机重写 Python、仓库和项目路径。`mysql_gying` 重新注入 `MYSQL_PASSWORD`，或使用兼容回退的 `GYING_DB_PASSWORD`；`docker_tools` 要确认 Docker CLI 可用。完整 dump、restore 和含 `DELIMITER` 的 SQL 继续使用 MySQL CLI。

Windows 计划任务通过 `Export-ScheduledTask` 导出 XML，在新机重新绑定执行用户、工作目录和脚本路径。Linux 上将对应桥接和定时任务改为 systemd timer 或 cron，首次上线保持禁用，手工运行成功后再启用。

## 5. Windows 新主机

1. 安装 Docker Desktop/Engine、Compose v2、Git、MySQL CLI，并启用 WSL2/虚拟化。
2. 配置磁盘、防火墙、NTP 和 `Asia/Shanghai` 时区。
3. 在稳定目录检出与源机一致的目标提交，恢复 `.env`、数据库、对象存储和必要卷。
4. 保持 `RESOURCE_HUB_ENABLED=false`、`RESOURCE_HUB_WORKER_ENABLED=false`、`QQ_BOT_ENABLED=false`，多平台目标保持关闭，Windows 计划任务先禁用。
5. 运行 `docker compose -f docker-compose.prod.yml config`，再按 MySQL、Redis、MinIO、PanSou、quark-auto-save、`gying-source`、`social-publisher`、backend、frontend、nginx、OpenClaw 顺序恢复。

## 6. Linux 新服务器

推荐 Ubuntu/Debian 等受支持发行版，使用 Docker Engine 和 Compose v2。应用代码可放在 `/opt/gying-movie`，数据和备份放在 `/srv/gying-data`、`/srv/gying-backup` 等仓库之外的路径。

```bash
sudo apt-get update
sudo apt-get install -y git ca-certificates curl mysql-client
sudo install -d -o root -g docker /opt/gying-movie /srv/gying-data /srv/gying-backup
sudo usermod -aG docker "$USER"
```

Linux 上还必须处理：

- 防火墙只开放 `80/443` 及经过评审的管理端口，MySQL、Redis、MinIO 管理端口不要直接暴露公网；
- Docker daemon、磁盘、NTP、日志轮转和备份任务；
- bind mount 的 UID/GID、目录权限、SELinux/AppArmor 和证书文件权限；
- `systemd` 服务或 cron 定时任务的用户、工作目录、环境文件和日志路径；
- 域名 DNS、TLS 证书、反向代理和公网 MinIO/CDN 地址。

Linux 部署示例：

```bash
git clone <repository-url> /opt/gying-movie
cd /opt/gying-movie
git checkout <verified-commit>
install -m 600 /srv/gying-data/env/.env .env
docker compose -f docker-compose.prod.yml config
docker compose -f docker-compose.prod.yml up -d backend frontend gying-source social-publisher nginx
docker compose -f docker-compose.prod.yml ps
```

把 OpenClaw、MCP 和外部配置放在 `/srv/gying-data` 或用户家目录下的受限目录，不要放到 Git。用 systemd 管理宿主机桥接和 OpenClaw 时，使用 `EnvironmentFile` 或受限凭据文件，并设置 `WorkingDirectory`、重启策略和日志查看权限。

## 7. 上线验收

- 首页、详情、登录、评论、收藏、资源提交和后台审核。
- TMDB 单条同步以及 PanSou/Panso API 搜索。
- 一条计划保留资源的转存、分享、发布和链接回读。
- GYING 当前账号、候选读取和无变更检查。
- QQ 群被动查询；腾讯频道只用计划保留资源测试，避免创建一次性公开帖子。
- `mvn test`、`npm run lint`、`npm run build` 和 Compose 配置校验通过。
- 源库和目标库的表集合、关键计数、状态分布、中文文本和对象抽样一致。
- 迁移前处于中间状态的任务没有被盲目重放，外部分享和帖子没有重复创建。
- OpenClaw、GYING、夸克、频道桥接、QQ 发布账号和微博会话逐项验证；NapCat 不纳入验收。

## 8. 切换与回滚

- 保留旧数据库和对象存储只读快照，切换完成前不删除。
- 应用回滚到迁移前镜像或提交；数据库只恢复完整备份，不手工删除新表行。
- DNS/nginx 切回旧实例后，再分析迁移日志和差异。
- 旧机至少保留一个完整观察窗口，不要立即删除旧数据库、对象存储或 Docker 卷。
- 回滚前先关闭新机 Worker、机器人、频道和多平台调度；若新机已经产生写入，先评估数据回灌，不能直接用旧备份覆盖两边。

## 9. 跨平台通用检查清单

```text
[ ] 源机提交、镜像、容器、卷、挂载和配置键已记录
[ ] Worker、机器人、频道和多平台自动化已停写
[ ] MySQL dump 已导出、校验并完成恢复测试
[ ] MinIO/S3 对象已镜像并抽查公网读取
[ ] quark-auto-save、OpenClaw 和 social-publisher 持久化数据已备份
[ ] NapCat 已确认不迁移、不启动
[ ] .env、Token、Cookie 和 Authorization 已通过受保护介质注入
[ ] MCP 路径、Python、Docker CLI 和 Compose 文件名已在目标机验证
[ ] 目标机自动化保持关闭并完成应用、数据库和外部账号验收
[ ] 切换、回滚材料和旧机保留窗口已记录到状态文档
```
