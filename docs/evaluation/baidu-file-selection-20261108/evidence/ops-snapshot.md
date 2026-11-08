# GYing Movie 运维快照

- 采集时间：2026-11-08 23:14:19 +08:00
- 仓库：`D:\gying-movie\movie`
- 分支：`codex/cloud-drive-workflows`
- 提交：`35eaec05ce6cfe465112501554834e7a6da9b81e`
- 提交记录：2026-11-08T22:47:30+08:00 docs: record cloud drive workflow deployment and verification
- 已跟踪文件：923
- 工作树改动：47

## 状态文档
- 路径：`docs/current-project-status.md`
- 声明更新时间：更新时间：2026-11-08
- 行数：166
- 最近 Git 变更：2026-11-08T22:47:30+08:00 35eaec0 docs: record cloud drive workflow deployment and verification

## 构建与配置
- 前端：Next.js 16.3.5，React 19.2.8
- 后端：Spring Boot 3.5.16，Java 17
- 根环境变量契约键：149
- 在线 `.env` 键名称：141（未读取或输出值）
- 在线 `.env` 缺少的示例键：API_GLOBAL_RATE_LIMIT_PER_MINUTE, API_RATE_LIMIT_PER_MINUTE, APP_AI_STATE_FILE, APP_AI_STATE_KEY_FILE, APP_TRUSTED_PROXIES, BREVO_API_KEY, GYING_MCP_ALLOW_WRITES, QUARK_DIRECTORY_AI_BASE_URL, QUARK_DIRECTORY_AI_ENABLED, QUARK_DIRECTORY_AI_MODEL, QUARK_DIRECTORY_AI_TIMEOUT_SECONDS, SOCIAL_POSTER_BASE_URL
- quark-auto-save 配置路径：此处不存在（`D:\gying-movie\movie\data\quark-auto-save\quark_config.json`）
- OpenClaw 配置路径：此处不存在（`C:\Users\ASUS\.openclaw\openclaw.json`）

## 数据库 SQL
| 文件 | 行数 | 最近 Git 变更 |
| --- | ---: | --- |
| `migration_access_log_client_identity.sql` | 24 | 2026-10-14 926e61e |
| `migration_baidu_transfer_task.sql` | 30 |  |
| `migration_comments_login_devices.sql` | 30 | 2026-09-20 65d9471 |
| `migration_favorites.sql` | 19 | 2026-06-15 7c491a7 |
| `migration_fix_qq_channel_template_mojibake.sql` | 14 | 2026-07-14 f3d18d5 |
| `migration_governance.sql` | 23 | 2026-06-15 8e8db67 |
| `migration_gying_owned_share_source.sql` | 15 | 2026-08-05 5ff6b27 |
| `migration_movie_source_identity.sql` | 18 | 2026-10-08 457673a |
| `migration_notifications.sql` | 16 | 2026-06-18 3111bdc |
| `migration_qq_automation.sql` | 99 | 2026-09-09 785d075 |
| `migration_qq_channel_daily_post_config.sql` | 16 | 2026-08-04 15e2861 |
| `migration_qq_channel_post_pending_status.sql` | 3 | 2026-07-14 f3d18d5 |
| `migration_qq_channel_template_year_type.sql` | 5 | 2026-08-04 15e2861 |
| `migration_qq_transfer_cleanup_and_settings_zh.sql` | 98 | 2026-09-20 65d9471 |
| `migration_registration_invitation_monitoring.sql` | 96 | 2026-09-25 900892a |
| `migration_resource_form.sql` | 4 | 2026-09-07 592d563 |
| `migration_resource_hub.sql` | 212 | 2026-08-13 93b12d0 |
| `migration_resource_link_updated_at.sql` | 15 | 2026-07-14 f3d18d5 |
| `migration_resource_reports.sql` | 24 | 2026-06-30 8211363 |
| `migration_social_publishing.sql` | 43 | 2026-10-10 362090c |
| `migration_soft_delete_columns.sql` | 28 | 2026-07-14 f3d18d5 |
| `migration_source_identity_case_sensitive.sql` | 7 | 2026-10-08 457673a |
| `migration_sys_config.sql` | 16 | 2026-06-18 ea02876 |
| `migration_user_email_change.sql` | 15 | 2026-09-26 0d595e8 |
| `migration_user_nickname.sql` | 17 | 2026-09-26 faf20fd |
| `schema.sql` | 623 | 2026-10-14 926e61e |

- `schema.sql` 声明的表（24）：movie_metadata, resource_link, sys_user, sys_config, comment, comment_vote, resource_report, user_favorite, user_notification, resource_hub_task, resource_discovery_result, quark_transfer_task, xunlei_transfer_task, movie_source_identity, social_publish_target, social_post_log, qq_transfer_cleanup_job, user_invitation_code, user_invitation_use, site_access_log, site_search_log, resource_operation_log, login_device, baidu_transfer_task

## Resource Hub 配置契约
- 环境变量键（72）：GYING_BASE_URL, GYING_COOKIE, GYING_DB_PASSWORD, GYING_DB_USER, GYING_MCP_ALLOW_WRITES, GYING_PASSWORD, GYING_REQUEST_INTERVAL_MS, GYING_SOURCE_API_TOKEN, GYING_SOURCE_BASE_URL, GYING_TARGET_USER, GYING_USERNAME, PANSOU_API_BASE_URL, PANSOU_API_KEY, QQ_CHANNEL_PUBLISHER_BASE_URL, QQ_CHANNEL_PUBLISHER_TOKEN, QUARK_AUTO_SAVE_BASE_URL, QUARK_AUTO_SAVE_PATH, QUARK_AUTO_SAVE_PATTERN, QUARK_AUTO_SAVE_REPLACE, QUARK_AUTO_SAVE_RUN_IMMEDIATELY, QUARK_AUTO_SAVE_TOKEN, QUARK_COOKIE, QUARK_DIRECTORY_AI_BASE_URL, QUARK_DIRECTORY_AI_ENABLED, QUARK_DIRECTORY_AI_MODEL, QUARK_DIRECTORY_AI_TIMEOUT_SECONDS, QUARK_SHARE_ENABLED, QUARK_SHARE_EXPIRED_TYPE, QUARK_SHARE_PASSCODE, QUARK_SHARE_POLL_ATTEMPTS, QUARK_SHARE_POLL_INTERVAL_MS, QUARK_SHARE_URL_TYPE, RESOURCE_HUB_AUTO_APPROVE, RESOURCE_HUB_DISCOVERED_RETRY_CRON, RESOURCE_HUB_DISCOVERED_RETRY_DELAY_MS, RESOURCE_HUB_DISCOVERED_RETRY_ENABLED, RESOURCE_HUB_DISCOVERED_RETRY_LIMIT, RESOURCE_HUB_ENABLED, RESOURCE_HUB_GYING_AUTO_SYNC_ENABLED, RESOURCE_HUB_GYING_AUTO_SYNC_INTERVAL_HOURS, RESOURCE_HUB_GYING_AUTO_SYNC_MAX_ITEMS, RESOURCE_HUB_GYING_AUTO_SYNC_PAGE, RESOURCE_HUB_GYING_AUTO_SYNC_SOURCES, RESOURCE_HUB_GYING_DISCOVERY_ENABLED, RESOURCE_HUB_PANSOU_BASE_URL, RESOURCE_HUB_PANSOU_TOKEN, RESOURCE_HUB_TMDB_AUTO_DISCOVERY_ENABLED, RESOURCE_HUB_TMDB_AUTO_SYNC_ENABLED, RESOURCE_HUB_TMDB_AUTO_SYNC_INTERVAL_HOURS, RESOURCE_HUB_TMDB_AUTO_SYNC_MAX_ITEMS, RESOURCE_HUB_TMDB_AUTO_SYNC_PAGE, RESOURCE_HUB_TMDB_AUTO_SYNC_SOURCES, RESOURCE_HUB_TMDB_DISCOVERY_COOLDOWN_HOURS, RESOURCE_HUB_TMDB_DISCOVERY_MAX_RESULTS, RESOURCE_HUB_WORKER_ENABLED, RESOURCE_HUB_WORKER_FIXED_DELAY_MS, RESOURCE_HUB_WORKER_PUBLISH_LIMIT, RESOURCE_HUB_WORKER_QUARK_LIMIT, RESOURCE_HUB_WORKER_TASK_LIMIT, RESOURCE_HUB_WORKER_XUNLEI_LIMIT, SOCIAL_PUBLISHER_BASE_URL, SOCIAL_PUBLISHER_SCHEDULER_DELAY_MS, SOCIAL_PUBLISHER_TOKEN, TMDB_API_BASE_URL, TMDB_API_KEY, TMDB_MAX_RETRIES, TMDB_REQUEST_INTERVAL_MS, WEIBO_WEB_CLIENT_VERSION, WEIBO_WEB_COOKIE, WEIBO_WEB_FINGERPRINT, WEIBO_WEB_USER_AGENT, WEIBO_WEB_XSRF_TOKEN
- 运行时 `sys_config` 键（29）：resource.hub.auto_approve, resource.hub.enabled, resource.hub.gying.auto_sync_enabled, resource.hub.gying.auto_sync_end_page, resource.hub.gying.auto_sync_interval_hours, resource.hub.gying.auto_sync_max_items, resource.hub.gying.auto_sync_page, resource.hub.gying.auto_sync_paused., resource.hub.gying.auto_sync_ranges, resource.hub.gying.auto_sync_sources, resource.hub.gying.discovery_enabled, resource.hub.tmdb.auto_discovery_enabled, resource.hub.tmdb.auto_sync_enabled, resource.hub.tmdb.auto_sync_end_page, resource.hub.tmdb.auto_sync_interval_hours, resource.hub.tmdb.auto_sync_max_items, resource.hub.tmdb.auto_sync_page, resource.hub.tmdb.auto_sync_ranges, resource.hub.tmdb.auto_sync_sources, resource.hub.tmdb.discovery_cooldown_hours, resource.hub.tmdb.discovery_max_results, resource.hub.worker.discovered_retry_cron, resource.hub.worker.discovered_retry_delay_ms, resource.hub.worker.discovered_retry_enabled, resource.hub.worker.discovered_retry_limit, resource.hub.worker.enabled, resource.hub.worker.publish_limit, resource.hub.worker.quark_limit, resource.hub.worker.task_limit

## MCP
- `backend/mysql_gying_mcp.py`：存在
- `backend/docker_mcp.py`：存在
- Codex MCP 配置章节：mcp_servers.cua_repl, mcp_servers.node_repl, mcp_servers.node_repl.env

## Docker
- Docker Server：29.7.2

| 容器 | 镜像 | 状态 | 端口 |
| --- | --- | --- | --- |
| gying-movie-frontend-1 | 35cce07fe8af | Up 40 minutes | 3000/tcp |
| gying-movie-backend-1 | 2925c3b129e1 | Up 40 minutes | 127.0.0.1:8880->8880/tcp |
| gying-movie-social-publisher-1 | 8b6dd6e77fa9 | Up 14 hours | 8093/tcp |
| gying-movie-quark-auto-save-1 | 4d97439c0cd0 | Up 14 hours | 0.0.0.0:5005->5005/tcp, [::]:5005->5005/tcp |
| gying-movie-gying-source-1 | gying-resource-p2p-source:20261019b | Up 14 hours | 8091/tcp |
| gying-query-maven-c43cc988fc3e | 880934ae394b | Exited (1) 3 weeks ago |  |
| gying-query-mysql-c43cc988fc3e | 7dcddc01f13b | Exited (0) 2 weeks ago |  |
| gying-xunlei-path-probe-20261006 | maven:3.9-eclipse-temurin-17 | Exited (255) 2 weeks ago |  |
| gying-movie-nginx-1 | nginx:stable-alpine | Up 14 hours | 127.0.0.1:80->8080/tcp |
| openclaw-openclaw-gateway-1 | ghcr.io/openclaw/openclaw:2026.6.11 | Up 14 hours (healthy) | 127.0.0.1:3978->3978/tcp, 127.0.0.1:18789-18790->18789-18790/tcp |
| gying-movie-redis-1 | redis:7-alpine | Up 14 hours | 6379/tcp |
| gying-movie-pansou-1 | ghcr.io/fish2018/pansou:latest | Up 14 hours | 8888/tcp |
| minio-server | quay.io/minio/minio:latest | Up 14 hours | 0.0.0.0:9000-9001->9000-9001/tcp, [::]:9000-9001->9000-9001/tcp |

- Compose 文件：存在（始终使用 `-f docker-compose.prod.yml`）

## 本机健康检查
- `http://127.0.0.1/` -> 200
- `http://127.0.0.1:8880/api/qq-bot/health` -> 200
- `http://127.0.0.1:5005/` -> 200
- `http://127.0.0.1:9000/minio/health/live` -> 200
- `http://127.0.0.1:18789/healthz` -> 200
