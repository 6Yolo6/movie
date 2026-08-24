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

生产 `quark-auto-save` 使用 `/app/config/quark_config.json` 持久卷，当前全局计划为 `0 8,18,20 * * *`。
GYing 的剧集和动漫任务使用 `runweek: [1]`（每周一按上述三个时段执行）自动检查来源更新并周转存；正常任务缺省配置可通过管理 API `/update?token=...` 批量补齐。
迁移前必须备份配置，只修改 `/GYing Resource Hub/tv/`、`/GYing Resource Hub/anime/` 下没有 `shareurl_ban` 且没有 `runweek` 的任务，保留 `runweek: []` 禁用任务和封禁任务不变。
首次转存仍由 Resource Hub 手动任务直接执行，不受 `runweek` 过滤；更新来源后由周计划负责后续追更。API token 由 quark-auto-save WebUI 凭据派生，不能与夸克 Cookie 混用，也不得写入日志或文档。

## GYING

`gying-source` 复用一个加锁 Session，处理 PoW 和登录。环境变量提供重启后的默认账号；管理员可在 `/admin/gying-source` 临时切换当前账号，密码和 Cookie 不持久化。

生产验证顺序：健康检查、候选读取、本人资源读取、单条无变更检查，最后才执行真实发布或修复。

## QQ 群

- `QQ_BOT_*` 配置命令、限流、敏感词、回复通道和自动转存。
- NapCat 上报到 `/api/qq-bot/onebot?token=...`，后端通过 OneBot HTTP 服务回复。
- 官方 QQBot 出站需要 `GROUP_OPENID`，普通 QQ 群号不能替代。
- OpenClaw 被动回复调用 `/api/qq-bot/search-reply`。运行 `tools/patch-openclaw-qqbot-gying.ps1` 会安装 `qrcode`、接管影视搜索/候选选择指令、为夸克链接发送二维码，并在未知或空 AT 时返回帮助；插件升级后需要重新执行。搜索收到后先回复“正在搜索资源，请稍后...”。
- Docker 部署会把 Windows 可维护源同步到 `/home/node/.openclaw-runtime-plugins` 的非 world-writable Linux 运行副本，并自动重启 Gateway，以满足 OpenClaw 插件安全检查。
- 群机器人先展示影片候选，用户选定影片后再展示资源名称/画质候选；回复单个资源序号后才执行所选夸克或迅雷转存。可用“夸克”或“迅雷”筛选，但不支持“夸克10”等数量批量转存。多次失败、无视频文件或违规/平台拦截会及时返回明确提示；群回复会 @ 对应用户，上下文有效期为 5 分钟。

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

`social-publisher` 是独立容器，负责多个 QQ 频道账号和新浪微博，不复用或覆盖原宿主机 `tencent-channel-cli` 登录状态。

关键配置：

- `SOCIAL_PUBLISHER_BASE_URL`、`SOCIAL_PUBLISHER_TOKEN`：后端访问独立发布容器。
- `QQ_CHANNEL_ACCOUNTS_ROOT`：容器内多个 QQ 账号的独立凭据目录，生产 compose 固定为 `/data/qq-accounts` 并挂载持久卷。
- `WEIBO_WEB_COOKIE`：新浪微博网页端当前登录会话 Cookie，只写入部署环境，不写入数据库或 Git。
- `WEIBO_WEB_XSRF_TOKEN`：可选；未配置时从 Cookie 中的 `XSRF-TOKEN` 自动读取。
- `WEIBO_WEB_FINGERPRINT`：网页发帖请求中的浏览器 `fp` 参数，必须与当前会话配套。
- `WEIBO_WEB_CLIENT_VERSION`、`WEIBO_WEB_USER_AGENT`：可选的网页客户端版本和 User-Agent 覆盖值。

首次部署：

```powershell
docker compose -f docker-compose.prod.yml up -d --build social-publisher
```

QQ 登录信息按账号标识分别保存在 `social-publisher-qq-accounts` 卷；微博网页会话从部署环境读取。后台 `/admin/automation` 的“多平台发布”页签可新增 QQ 账号并生成授权二维码，扫码后自动轮询授权结果；移除账号时会删除该账号凭据并停用其频道目标，历史发布记录保留。页面还可维护频道、每日时间、每次条数、间隔和模板，并可对单个目标或全部目标手动发布下一条。候选按站内热度、TMDB 热度和资源录入时间排序；同一目标不会重复发布同一影片。

微博凭据档案固定为 `default`；QQ 账号使用页面填写的 2-32 位账号标识，每个标识对应独立 `.qqcli` 登录目录。先完成扫码授权，再为该账号添加一个或多个频道目标。

微博发布器固定向 `https://www.weibo.com/ajax/statuses/update` 发送网页表单请求，参数包括正文、公开可见性和浏览器 `fp`。当前仅发布文本和资源链接，不上传海报。响应中的登录失效、频率限制和安全验证会分类写入发布记录；遇到验证码时不会自动处理。

Cookie 与 `fp` 会随网页登录状态变化而失效。更新 `.env` 后执行 `docker compose -f docker-compose.prod.yml up -d --build social-publisher` 重新加载；建议先手动发布一条确认成功，再开启微博目标自动发布。

## 上线检查

- 数据库和对象存储有可恢复备份。
- nginx 只暴露需要的端口，HTTPS 和 CORS 指向生产域名。
- MySQL、JVM 和容器时区均为 `Asia/Shanghai`。
- 首页、登录、资源、评论、通知和管理页面正常。
- `mvn test`、`npm run lint`、`npm run build` 通过。
- 不用一次性公开资源验证自动发布。
