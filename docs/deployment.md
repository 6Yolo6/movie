# 部署文档

## 环境与配置

- JDK 17、Maven 3.9、Node.js 20、MySQL 8、Redis 6。
- 本地开发分别使用 `backend/.env.example` 和 `frontend/.env.example`。
- Docker 生产部署复制根目录 `.env.example` 为 `.env`；该文件是环境变量的完整清单。
- 生产必须替换数据库/Redis 密码、`JWT_SECRET`、`APP_INTERNAL_TOKEN` 和各外部服务令牌。
- 密钥、Cookie 和账号密码不得写入 Git、日志或 `sys_config`。

## 本地启动

```bash
cd backend
mvn spring-boot:run

cd ../frontend
npm install
npm run dev
```

前端默认 `http://localhost:3000`，后端默认 `http://localhost:8880`。

## Docker 生产部署

```bash
cp .env.example .env
# 填写真实值
docker compose -f docker-compose.prod.yml config --quiet
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

nginx 是对外入口。重建 backend 或 frontend 后重新创建 nginx，避免它继续使用旧容器地址。

Compose 默认连接现有 MySQL、Redis、MinIO、PanSou 和 quark-auto-save。只有需要 Compose 创建依赖时才使用：

```bash
docker compose -f docker-compose.prod.yml --profile embedded-deps up -d
```

## Resource Hub

关键配置类别以 `.env.example` 为准：

- `RESOURCE_HUB_*`：总开关、Worker、TMDB 同步和批量限制。
- `TMDB_API_KEY`、`TMDB_API_BASE_URL`：TMDB。
- `RESOURCE_HUB_PANSOU_*`、`PANSOU_API_*`：本地 PanSou 与外部 Panso API。
- `QUARK_AUTO_SAVE_*`、`QUARK_SHARE_*`：转存、保存目录和自有分享。
- `GYING_*`、`GYING_SOURCE_*`：GYING 账号、内部服务和目标发布者。

先保持定时 Worker 关闭，通过后台手动跑一条计划保留资源，确认搜索、转存、自有分享和入库后再启用调度。quark-auto-save API Token 不等于夸克 Cookie；两者都要有效。

## GYING

`gying-source` 复用一个加锁 Session，处理 PoW 和登录。环境变量提供重启后的默认账号；管理员可在 `/admin/gying-source` 临时切换当前账号，密码和 Cookie 不持久化。

生产验证顺序：健康检查、候选读取、本人资源读取、单条无变更检查，最后才执行真实发布或修复。

## QQ 群

- `QQ_BOT_*` 配置命令、限流、敏感词、回复通道和自动转存。
- NapCat 上报到 `/api/qq-bot/onebot?token=...`，后端通过 OneBot HTTP 服务回复。
- 官方 QQBot 出站需要 `GROUP_OPENID`，普通 QQ 群号不能替代。
- OpenClaw 被动回复可调用 `/api/qq-bot/search-reply`；当前本机命令补丁升级后可能被覆盖，应迁移为正式插件。

## 腾讯频道

真实版块帖子由宿主机 `tencent-channel-cli` 发布：

```powershell
tools/publish-qq-channel-feed.ps1 `
  -Title "Movie Title" `
  -Link "https://pan.quark.cn/s/xxxx" `
  -Intro "Short intro" `
  -ChannelType movie
```

资源中心的“发到QQ频道”调用宿主机桥接：

```powershell
tools/register-qq-channel-publisher-bridge.ps1
Invoke-RestMethod http://127.0.0.1:8092/health
```

后端使用 `QQ_CHANNEL_PUBLISHER_BASE_URL` 和 `QQ_CHANNEL_PUBLISHER_TOKEN`。桥接只处理指定 `PENDING` 日志；不可用时记录留给 `publish-latest-resource-to-qq-channel.ps1` 定时处理。

## 上线检查

- 数据库和对象存储有可恢复备份。
- nginx 只暴露需要的端口，HTTPS 和 CORS 指向生产域名。
- MySQL、JVM 和容器时区均为 `Asia/Shanghai`。
- 首页、登录、资源、评论、通知和管理页面正常。
- `mvn test`、`npm run lint`、`npm run build` 通过。
- 不用一次性公开资源验证自动发布。
