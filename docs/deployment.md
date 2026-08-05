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
- OpenClaw 被动回复调用 `/api/qq-bot/search-reply`。运行 `tools/patch-openclaw-qqbot-gying.ps1` 会安装 `qrcode`、接管影视搜索/网盘选择指令、为夸克链接发送二维码，并在未知或空 AT 时返回帮助；插件升级后需要重新执行。
- Docker 部署会把 Windows 可维护源同步到 `/home/node/.openclaw-runtime-plugins` 的非 world-writable Linux 运行副本，并自动重启 Gateway，以满足 OpenClaw 插件安全检查。
- 每个资源回复都先验证自有夸克分享；用户指定其他网盘时会在自有夸克之后追加指定数量。群回复会 @ 对应用户，上下文有效期为 5 分钟。

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

## 多平台发布

`social-publisher` 是独立容器，只负责第二 QQ 频道账号和新浪微博，不复用或覆盖原宿主机 `tencent-channel-cli` 登录状态。

关键配置：

- `SOCIAL_PUBLISHER_BASE_URL`、`SOCIAL_PUBLISHER_TOKEN`：后端访问独立发布容器。
- `QQ_CHANNEL_SECONDARY_ACCOUNT`、`QQ_CHANNEL_SECONDARY_TOKEN`：第二 QQ 账号标识和可选环境凭据；实际 CLI 鉴权失败时仍必须扫码授权。
- `WEIBO_CLI_TOKEN`、`WEIBO_CLI_REFRESH_TOKEN`：新浪微博无人值守令牌；未提供时使用设备码完成一次授权。
- `WEIBO_PUBLISH_ACTION`：可选，固定当前账号允许的微博发布动作；留空时从官方 CLI 动态命令目录选择。

首次部署：

```powershell
docker compose -f docker-compose.prod.yml up -d --build social-publisher
docker compose -f docker-compose.prod.yml exec social-publisher tencent-channel-cli login --json
# 扫码确认后
docker compose -f docker-compose.prod.yml exec social-publisher tencent-channel-cli login poll-token --json

docker compose -f docker-compose.prod.yml exec social-publisher weibo auth login --device
```

QQ 登录信息保存在 `social-publisher-qqcli` 卷，微博登录信息保存在 `social-publisher-weibo` 卷。后台 `/admin/automation` 的“多平台发布”页签可添加已授权账号下的新目标，维护频道、每日时间、每次条数、间隔和模板，并可对单个目标或全部目标手动发布下一条。页面同时提供分页发布记录、平台/状态筛选、外部帖子地址、失败原因和重试操作。候选按站内热度、TMDB 热度和资源录入时间排序；同一目标不会重复发布同一影片。自动发布初始为关闭，完成对应账号授权与单条手动验证后再逐目标开启。

当前发布器凭据档案固定为 QQ `secondary` 和微博 `default`。添加目标不会创建新的第三方登录凭据；新增真正独立的外部账号时，需要先为发布器增加独立凭据目录和授权流程。

新浪微博集成使用微博开放平台官方 CLI，命令目录和账号套餐权限由平台动态返回。官方入口：`https://open.weibo.com/cli/index`。

## 上线检查

- 数据库和对象存储有可恢复备份。
- nginx 只暴露需要的端口，HTTPS 和 CORS 指向生产域名。
- MySQL、JVM 和容器时区均为 `Asia/Shanghai`。
- 首页、登录、资源、评论、通知和管理页面正常。
- `mvn test`、`npm run lint`、`npm run build` 通过。
- 不用一次性公开资源验证自动发布。
