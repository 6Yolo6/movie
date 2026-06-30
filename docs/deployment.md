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