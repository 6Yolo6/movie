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

`QUARK_AUTO_SAVE_TOKEN` is only the WebUI/API token. quark-auto-save also needs a valid
pan.quark.cn cookie configured in its WebUI; otherwise transfer tasks can be added but
cannot actually save files.

Keep `RESOURCE_HUB_WORKER_ENABLED=false` until TMDB, PanSou and quark-auto-save are verified.
Use `POST /api/admin/resource-hub/worker/run-once?force=true` for a one-time manual pipeline run.
