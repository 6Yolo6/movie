# 部署文档

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- MySQL 8+
- Redis 6+

## 后端配置

复制 `backend/.env.example`，按实际环境配置数据库、Redis、JWT 和 CORS。

Spring Boot 会读取下列环境变量，未设置时使用开发默认值：

- `SERVER_PORT`
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `MINIO_URL_PREFIX`
- `APP_CORS_ALLOWED_ORIGIN`
- `JWT_SECRET`
- `JWT_EXPIRATION_MS`

生产环境必须替换 `JWT_SECRET`，长度至少 32 字节。

## 前端配置

复制 `frontend/.env.example` 为 `frontend/.env.local`：

```bash
NEXT_PUBLIC_API_URL=http://localhost:8880
```

生产环境如果通过同域反向代理转发 `/api`，可以将该值留空。

## 启动

```bash
cd backend
mvn spring-boot:run

cd ../frontend
npm install
npm run dev
```

## Resource Hub

Set these variables in the root `.env` used by Docker Compose or by the backend process:

- `RESOURCE_HUB_ENABLED=true`
- `TMDB_API_KEY`
- `RESOURCE_HUB_PANSOU_BASE_URL`
- `QUARK_AUTO_SAVE_BASE_URL`
- `QUARK_AUTO_SAVE_TOKEN`
- `QUARK_AUTO_SAVE_PATH`
- `QUARK_AUTO_SAVE_RUN_IMMEDIATELY`
- `RESOURCE_HUB_WORKER_ENABLED` (default `false`)
- `RESOURCE_HUB_WORKER_FIXED_DELAY_MS` (default `60000`)
- `RESOURCE_HUB_WORKER_TASK_LIMIT` (default `5`)
- `RESOURCE_HUB_WORKER_QUARK_LIMIT` (default `5`)
- `RESOURCE_HUB_WORKER_PUBLISH_LIMIT` (default `20`)
- `QUARK_COOKIE` (optional fallback; prefer configuring the cookie in quark-auto-save WebUI)

`docker-compose.prod.yml` does not start Redis, PanSou or quark-auto-save by default. Point
`REDIS_HOST`, `RESOURCE_HUB_PANSOU_BASE_URL` and `QUARK_AUTO_SAVE_BASE_URL` at your existing
services, for example `host.docker.internal` plus the published ports. If you need the compose
file to create embedded dependency containers, start it with `--profile embedded-deps` and adjust
ports so they do not conflict with existing services.

`QUARK_AUTO_SAVE_TOKEN` is only the WebUI/API token. quark-auto-save also needs a valid
pan.quark.cn cookie configured in its WebUI; otherwise transfer tasks can be added but
cannot actually save files.

Keep `RESOURCE_HUB_WORKER_ENABLED=false` until TMDB, PanSou and quark-auto-save are verified.
Use `POST /api/admin/resource-hub/worker/run-once?force=true` for a one-time manual pipeline run.

After a Quark transfer succeeds, the backend can use the same Quark cookie to create your own
share link from the saved folder:

- `QUARK_SHARE_ENABLED=true`
- `QUARK_SHARE_URL_TYPE=1` for public links, `2` for passcode links
- `QUARK_SHARE_EXPIRED_TYPE=1` permanent, `2` 1 day, `3` 7 days, `4` 30 days
- `QUARK_SHARE_PASSCODE=` optional when `QUARK_SHARE_URL_TYPE=2`

## QQ Bot / NapCat

The backend can receive OneBot-compatible HTTP events from NapCat and reply to QQ groups through
NapCat `send_group_msg`.

Set these variables when enabling the group bot:

- `QQ_BOT_ENABLED=true`
- `QQ_BOT_WEBHOOK_TOKEN`
- `QQ_BOT_ALLOWED_GROUPS` (comma-separated group IDs; empty allows all groups)
- `QQ_BOT_COMMAND_PREFIXES` (default `找,搜,/movie,/search`)
- `QQ_BOT_MAX_RESULTS` (default `3`)
- `QQ_BOT_AUTO_TRANSFER` (default `true`)
- `QQ_BOT_NAPCAT_BASE_URL`
- `QQ_BOT_NAPCAT_ACCESS_TOKEN`
- `NAPCAT_ACCOUNT` (used by the Docker service)

Configure NapCat HTTP event reporting to:

```text
http://backend:8880/api/qq-bot/onebot?token=${QQ_BOT_WEBHOOK_TOKEN}
```

Also enable a NapCat OneBot HTTP server so the backend can call `send_group_msg`:

- host: `0.0.0.0`
- port: `3000`
- token: same value as `QQ_BOT_NAPCAT_ACCESS_TOKEN`

Keep `QQ_BOT_NAPCAT_BASE_URL=http://napcat:3000` when the backend and NapCat run in the same
Docker Compose network.

When auto transfer is enabled, the bot saves matched Quark resources through quark-auto-save and
then replies with the generated "my Quark share" link when the Quark share API accepts the saved
folder. If share creation fails, the bot still returns the original resource links and the transfer
status.
