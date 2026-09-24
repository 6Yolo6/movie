# 当前项目状态

更新时间：2026-09-24

本文只记录生产环境当前能力、运行约束、待处理事项和少量可复核的验收证据。一次性任务编号、重复部署过程和基础接口状态不在这里长期保留，详细操作以 `docs/api.md`、`docs/deployment.md` 及运维参考文档为准。

## 当前目标

- 维护电影、剧集、动漫和用户提交资源的统一片库。
- 通过 TMDB、GYING、PanSou/Panso API 与网盘自动化服务补全元数据和可用资源。
- 自动转存并生成夸克、迅雷自有分享，经过校验后再写入正式资源库。
- 支持 QQ 群搜索、资源候选选择、频道发布和多平台发布审计。

## 运行架构

- **Docker 运行态说明（2026-09-24 21:30 恢复）**：20:48 构建验收时 Docker/WSL 出现 `sdd`/`loop1` 读取 I/O 错误，CLI 与 HTTP 探针随后超时，E 盘一度仅剩约 1 GiB；停止 Docker 后容量回升至 14 GiB 以上。经强制停止 Docker、`wsl --shutdown`、重建 `%LOCALAPPDATA%\Docker\run` 后引擎恢复（21:44 复检再次把 `%LOCALAPPDATA%\Docker\run` 整目录替换为新建的干净目录，旧目录改名为 `run-stuck-20260924-214433`，默认启动的 Ingest 报错来源已消除），10 个生产容器与命名卷全部回到运行状态，网站本地与公网恢复 200。残留缺陷：`%LOCALAPPDATA%\docker-secrets-engine\engine.sock(.stale)` 属内核层失效的 reparse 项，文件删除、改名、`fsutil`、`\\?\` 前缀路径和 reparse 句柄级删除均返回“文件无法访问”（错误 1920），其父目录还被句柄占用无法改名，Docker Desktop 默认启动会在 Secrets Engine 初始化处失败；当前以独立运行目录（`G:\gying-tools\docker-runtime-recovery`）启动 Docker Desktop 绕过，镜像与卷数据位置不变。其彻底清除需重启 Windows 或对 C 盘执行 `chkdsk /f`，尚未执行；后续清理可删除本次产生的临时目录 `run-stuck-*`、`run-recovery-*`。
- 对外入口：Cloudflare Tunnel `gyinghub.dpdns.org` → loopback nginx → Next.js/Spring Boot。2026-09-24 只读复核确认 nginx `127.0.0.1:80`、backend `127.0.0.1:8880`；quark `5005`、独立 MinIO `9000/9001` 与 Windows MySQL `3306/33060` 仍监听非 loopback 地址，安全加固仅部分上线。
- 核心服务：`frontend`、`backend`、`gying-source`、`social-publisher`。
- 数据与依赖：MySQL、Redis、MinIO、PanSou、`quark-auto-save`；生产 Compose 文件为 `docker-compose.prod.yml`。
- 本机 Docker Desktop 数据路径按新机配置位于 `E:\dockerdesktop\wsl\DockerDesktopWSL`；项目代码和 Python 虚拟环境保留在 `D:\gying-movie\movie`。Cloudflare Tunnel 运行文件位于 `E:\gying-tools\cloudflared`。
- QQ 群机器人使用 OpenClaw QQBot；QQ 频道发帖使用宿主机 `tencent-channel-cli` 任务发布到站长自己的频道，不经过 `social-publisher`。`social-publisher` 当前仅承载微博及保留的历史发布审计；旧 `secondary`、`qq-v3` QQ 频道目标已软停用（`enabled=0`、`auto_post_enabled=0`），不删除历史日志。OpenClaw QQBot、Resource Hub worker、微博自动发布以及夸克/迅雷自动转存计划任务处于现役链路。NapCat 仍不参与当前生产链路。
- 容器和 JVM 时区统一为 `Asia/Shanghai`。

## 已具备能力

### 片库与治理

- 片库分页、分类筛选、详情、收藏、评论、站内通知和管理员审核。
- 首页三个分类按资源最近更新时间优先，并综合站内热度、TMDB 热度和可用评分排序；搜索/筛选页仍保留最新和评分排序。
- 海报底部以来源图标展示豆瓣、IMDb、TMDB 评分及热度，标题移到海报外部。
- 电影、资源链接和来源身份均支持软删除，历史记录保留。
- 新注册账号固定为普通 `USER`，只能浏览、收藏、评论和举报资源；新增、修改、删除资源仅允许 `PUBLISHER` 或 `ADMIN`。
- TMDB 采集支持去重和本地片库匹配；模糊匹配只提供候选，不直接触发转存。
- 管理端支持缺失海报自动补图。GYING 海报下载按 `avif`、`webp`、`jpg`、`png` 回退并重试，只有返回有效 `posterUrl` 才计为成功。
- 顶部搜索框提供“最近热门搜索”（2026-09-24 上线）：桌面端火焰按钮或输入框聚焦展开浮层，移动端抽屉内直接展示，点击热词直接搜索；数据取近 7 天真实搜索次数（Redis `hot:search:<date>` ZSet 合并，为空或异常时回退 MySQL `site_search_log`），公开接口 `GET /api/movies/hot-searches?days=7&limit=8` 与 `/api/movies` 共用搜索限流。
- 首页搜索/筛选与电影、电视剧、动漫分类页的筛选项默认收起，只保留排序；展开后才渲染类型/地区/语言/年份，收起时有已选条件则显示可逐个关闭的 Tag 摘要与“清除全部”。

### 影视资源中心

- 资源搜索合并本地 PanSou 与外部 Panso 结果，并按 URL 去重。
- 自动资源必须先转存为系统自有夸克或迅雷分享，再写入 `resource_link`。
- 夸克任务支持目录创建、剧集目录更新、失效分享重试和周转存；`update_subdir: ".*"` 用于递归追踪同名目录新增文件。
- 迅雷任务使用官方 Drive API 校验分享、遍历目录和筛选视频文件；Authorization 为短期凭据，支持运行时更新和 refresh token 优先。
- 失败任务保留状态和错误，重试尽量复用已有转存结果，避免重复调用转存接口。
- 管理员资源管理对失效或疑似失效的夸克、迅雷资源提供单条“修复并重分享”；成功后原位更新资源链接，并在存在 GYING 影片映射时自动同步发布链接。
- GYING 目录自动同步支持热门电影/剧集/动漫与综合评分电影/剧集/动漫（`CSCORE_MOVIE`、`CSCORE_TV`、`CSCORE_ANIME`）；综合评分来源按轮换任务采集，每轮上限由 `resource.hub.gying.auto_sync_max_items` 控制；实际间隔直接遵循 `resource.hub.gying.auto_sync_interval_hours`，当前配置为 1 小时。三个来源按轮换方式执行，因此不是每小时同时抓取三类。
- GYING BT 详情页可解析实际 `magnet` 与 `.torrent` 地址；磁力/种子以 `resource_link.type=MAGNET/TORRENT`、`provider=P2P`、`source=GYING`、`url_hash`、`source_ref` 和 `source_url` 保存，BT 详情页本身不会误存为资源 URL。GYING 网盘并行数组响应会按索引读取资源名称，所有写入 `resource_link.name` 的 GYING 路径统一限制为 255 个字符，避免整列标题数组被误写入导致 `Data too long for column 'name'`。
- 管理端资源管理支持按 `DISK`、`MAGNET`、`TORRENT`、`ONLINE` 筛选；磁力和种子链路均保留严格类型/URL 校验，不能把 BT 详情页地址误当成种子资源入库。
- 留言与影片评论支持 `GENERAL`、`REQUEST`、`INVALID_RESOURCE`、`SUGGESTION`、`OTHER` 类型；后台可按类型、状态、影片和关键词筛选，关键词使用参数化条件组合，影片评论可跳转到对应详情页评论区。
- 登录设备记录登录 IP、User-Agent、登录时间和最近活动时间；用户可在 `/devices` 查看并撤销设备授权，撤销后对应 JWT 立即失效。

### GYING 数据源

- 支持按类型和模式搜索、目录同步、元数据导入、资源发布与已发布资源抓取。
- GYING 上游不可用（连接错误、5xx、429、鉴权失效）时影片元数据操作不再直接失败：`补齐剩余季` 改用 PanSou 兜底（夸克+迅雷候选、按剧集名或 TMDB ID 匹配同剧集、跳过已有 ACTIVE DISK 资源、按季号最多 5 个目标逐季转存入库），`自动补图` 回退 TMDB 搜索匹配；结果以 `mode=PANSOU_FALLBACK`、`gyingUnavailable=true` 与 `{discovered,completed,skipped,failed,reason,items}` 返回，全部失败记 `FAILED`，否则记 `SKIPPED` 并给出原因。
- “爬取我已发布资源”按账号 `/my-resources` 分页读取，复用现有片库和资源工作流；已存在的来源 ID 或 URL 自动跳过。
- 数据源请求带有统一间隔限制，图片下载和站点请求均支持超时、重试和失败记录。
- GYING 资源发布仅使用固定契约 `/res/pan/add`，绑定字段为 `binds[0][dir]` 与 `binds[0][id]`。
- GYING Source 内部接口新增 `/catalog?sort=cscore` 和 `/bt/{btId}`；请求仍受统一请求间隔、PoW 和登录态约束，认证失败只记录错误类别，不输出 Cookie 或认证材料。管理端 GYING Source 新增“元数据同步”功能，可按 `mv/ID`、`tv/ID`、`ac/ID` 或默认类型批量同步最多 60 个 GYING 影片元数据，仅同步元数据、海报和来源绑定，不触发转存或发布。
- GYING 资源入库已增加归属保护：网盘资源只有明确属于 `GYING_TARGET_USER` 的分享才进入正式片库；公共 GYING/PanSou 网盘结果只作为候选，P2P 磁力/种子仍可按实际链接入库。provider 优先由分享 URL 主机识别，避免并行数组错位被写成 `OTHER`；名称按索引读取并限制为 255 字符。
- 2026-09-22 已对确认错误的 `resource_link` 记录 2560-2564 执行软删除，未物理删除；操作前备份位于 `E:\gying-tools\backups`，文件名以 `gying-pre-gying-resource-fix-20260922-112027.sql` 开头，SHA-256 清单同目录保存。
- 2026-09-22 已部署提交 `80d7e4c`：backend、frontend、gying-source、social-publisher 和 nginx 已重建；nginx 与 backend 仅发布到 loopback，公网首页、注册策略和 `/resource-search` 返回 200，公开 `/api/qq-bot/*` 返回 404。backend/gying-source/social-publisher 已使用非 root 数据库账号与非 root 容器用户；MinIO 已创建并验证 scoped 应用身份，旧 root 身份未删除以保留回滚路径。
- 新增登录用户网页端 `/resource-search`：复用 QQ 的 GYING/PanSou 候选、序号继续选择、夸克/迅雷转存、自有分享返回和二维码展示；网页使用独立 Redis 搜索频控配置，临时转存继续使用专用目录与清理任务，正式 Resource Hub 资源不参与清理。
- 2026-09-24 已上线网页搜索异步任务与 2 秒轮询、刷新续查和进行中任务复用；任务完成结果保留 15 分钟，不跨后端重启恢复。GYING 读取采用 3/20 秒连接/响应超时及 30 秒短时熔断，自动搜索继续尝试 PanSou；单个补充来源失败时保留已有候选。PanSou 本地请求超时为 3/30 秒。
- 网页搜索支持连续对话：服务端接受后自动清空已发送内容，保留等待期间新输入的草稿；可点击影片/资源候选及翻页，复制、打开或展示分享二维码。最近 20 轮历史按用户保存在当前标签页，刷新可续查进行中任务；历史候选禁用，避免误用旧序号。
- 后台系统设置可修改 `resource.search.rate_limit_per_minute`：每用户每分钟 1–60 次，默认 5，保存后即时生效；资源序号选择和翻页不计入，选择影片、重新搜索或“查看其他资源”计入。QQ 搜索频率与网页独立，API/边缘防护限流不变。

### 注册、邀请与后台监控

- 公开注册由 `auth.register.enabled` 控制；当前生产值为关闭。注册固定要求有效邮箱并由数据库唯一索引阻止重复邮箱，新账号仍固定为普通 `USER`。
- 邮箱验证码采用可选 Resend/Brevo 事务邮件接口；只有 `auth.email_verification.enabled=true` 且本机已配置发件账号时才显示和强制校验。当前未配置邮件服务，因此验证码功能关闭，不保存或输出邮件凭据。
- 关闭公开注册后，有效邀请码仍可注册。管理员或满足账号注册时长的老用户可按配置周期生成有限数量的邀请码；数据库只保存邀请码 SHA-256，完整邀请链接仅在创建时返回。
- 2026-09-24 已上线邀请码 DATETIME 的 `LocalDateTime`/`Timestamp` 兼容修复及邀请码/邮箱验证码表单绑定修复；前端密码要求与后端保持至少 12 字符、最多 72 UTF-8 字节一致。
- 管理员可在 `/admin/users` 新建普通用户或发布者：填写用户名、邮箱、初始密码和角色，无需邀请码、不受公开注册开关限制；仍校验用户名/邮箱唯一、密码至少 12 字符且不超过 72 UTF-8 字节，不开放创建 ADMIN。
- 管理员从影片详情等入口发布资源也不受 `resource.max.per.user` 总量限制；发布者仍受限，提交间隔、重复链接及权限校验不变。
- 后台 `/admin/monitoring` 提供今日页面访问、匿名访客、API 请求、搜索、资源操作和错误统计，并可检索访问日志、站内/QQ 搜索日志、资源复制/分享操作及平台发布日志。访问日志不保存 Cookie、Authorization 或完整 IP。
- 站内搜索同时写入 MySQL 审计表和 Redis 当日 ZSET 热搜缓存；热搜键按日期隔离并自动过期。

### QQ 自动化

- 搜索先回复“正在搜索资源，请稍后...”，随后展示影片元数据和资源候选。
- 候选内部最多保留 30 条，首屏每页展示 10 条且夸克优先；可用“下一页/上一页”浏览更多，用户选择单条资源后才创建对应转存任务。
- 夸克或迅雷分享失效时统一提示“该分享已失效，不可访问”，并保留当前候选上下文，用户可继续选择其他序号。
- 搜索、转存、分享和失败结果写入自动化日志，便于管理员审计和重试。
- QQ 群搜索并选择后产生的新转存使用 `QQ_BOT` 来源标记和专用临时目录：夸克 `/GYing QQ Temp`、迅雷 `/影视剧资源分享(先转存后再查看)/GYing QQ Temp`；默认在回复成功 10 分钟后清理物理目录并使临时资源链接失效。清理任务同时校验来源标记、精确目标路径和安全根目录，Resource Hub 定时采集及正式库目录不参与清理。
- OpenClaw QQBot 的搜索进度提示使用 JavaScript Unicode 转义写入运行时插件，避免 Windows PowerShell 代码页导致“正在搜索资源，请稍后...”乱码；补丁脚本已覆盖旧运行时副本并重启网关。
- 原 QQ 群机器人支持按管理端配置每天 09:00（Asia/Shanghai）发送近期更新影片及有效资源推荐；目标群号、篇数、时间和消息模板均可在 `/admin/automation` 配置，默认模板不发送本地 `localhost` 详情地址，后续部署到服务器时可通过 `{{detailUrl}}` 自行加回。支持 `{{title}}`、`{{year}}`、`{{genres}}`、`{{rating}}`、`{{summary}}`、`{{resources}}` 和 `{{detailUrl}}` 占位符。2026-09-15 已开启每日推荐配置；OpenClaw QQBot 官方 Gateway 已取得访问令牌并完成 WebSocket READY。QQ 频道发帖仍由独立发布器负责。历史上 QQ 官方主动消息曾返回 `40034105` 权限错误，需以实际出站结果为准；本次未手工触发真实群消息。

### 多平台发布

- `/admin/automation` 管理 QQ 频道账号、频道目标和微博目标。
- 支持单目标、批量、定时、间隔、模板和失败重试；发布记录保存 `PENDING`、`POSTED`、`FAILED` 及外部地址。
- QQ 和微博使用独立凭据目录或运行时配置，互不覆盖原有机器人环境。

## 配置与安全

- Cookie、Authorization、JWT、refresh token、密码、API Key 和 Cloudflare Tunnel 凭据只允许存在于 `.env`、外部服务配置或进程内存，不提交到 Git 或写入业务日志；Tunnel 凭据位于仓库外 `E:\gying-tools\cloudflared\credentials`，ACL 已限制为当前账号和 SYSTEM。
- 迅雷 Drive API 短期 Authorization 由本机独立 Edge 会话辅助刷新：运行依赖位于 `E:\gying-tools\xunlei-auth-helper`，每次同步从本机 Edge 默认配置复制到临时自动化配置后执行，避免占用正在使用的浏览器 Profile，Windows 计划任务 `GYing Xunlei Token Sync` 每 2 小时及用户登录时执行。辅助程序会触发官方网页客户端使用已保存的 refresh_token，再从 Edge 会话存储和已认证请求中选取新的有效 Authorization；只有 Token 真正变化时才原子更新 backend-data，未变化会记录 `unchanged`，失败会记录为失败，不输出 token、Cookie 或密码；Edge 登录会话失效时仍需重新授权。
- OpenClaw QQBot 已配置命令 owner/elevated 白名单为 QQ 号 `3929013344` 及其已确认的群 `member_openid` `EF8032478420C84E5A645799B62F1892`，关闭聊天侧 bash/config/mcp/plugins/debug/restart 命令，全局拒绝 `exec/read/write/edit/apply_patch` 工具并禁用 elevated 工具执行；agent 默认沙箱已设为 `all`。插件入口会在 `/bot-*`、`/stop`、`/approve` 执行前再次校验白名单，未授权请求直接拒绝，不进入 AI 或执行队列；影视搜索命令保持可用。QQ 群事件使用 `member_openid`，可通过群内发送一条消息并查看 OpenClaw QQBot 日志中的 `author.member_openid` 获取。
- OpenClaw 已按迁移快照恢复到独立容器，使用快照匹配的 `ghcr.io/openclaw/openclaw:2026.6.11` 镜像；状态卷使用 `openclaw_openclaw_home` 与 `openclaw_openclaw_state` Docker 命名卷，Gateway 健康检查通过。2026-09-15 已启用 QQBot 通道并验证官方 Gateway `READY`，QQBot 插件的唯一活动运行时路径为 `/home/node/.openclaw-runtime-plugins/qqbot-project/...`，旧全局插件源已归档。保留一个不阻断的兼容性警告：旧 QQBot manifest 尚未声明 `channelConfigs`，后续插件升级时再处理。
- 生产操作前后检查 `git status --short`；涉及数据库或卷的高风险操作必须先备份并保留回滚点。
- 公网域名 `https://gyinghub.dpdns.org` 已加入后端 CORS 允许来源；生产 `.env` 的 `CORS_ALLOWED_ORIGIN` 与 `APP_PUBLIC_BASE_URL` 必须保持为该域名，避免浏览器 POST 被 Spring 拒绝为 `Invalid CORS request`。
- 不执行 `docker compose down -v`、删除卷、`DROP`、`TRUNCATE` 或物理删除核心历史数据作为日常维护手段。
- 自动采集只有在生成并校验自有分享后才允许发布，第三方原始链接不得直接写入正式资源。

### 生产安全审计状态（2026-09-24 复核）

- 根目录 `docker-security-report.md` 和 `docs/security/` 覆盖生产安全风险、部署门禁与回滚要求；静态 API 清单不等同于动态渗透测试。
- 已观测上线：nginx/backend loopback 发布、内部 QQ/internal 路径 404、匿名管理员 API 401、应用容器非 root、三服务数据库配置非 root、内部 token 已配置。MinIO 已接入 `gying-movie_gying-net` 并有 `minio` alias，图片入口抽样 200；backend 使用的 MinIO key 与 root key 不同，但本次未重新审查完整 policy。
- `check_security.py --repo . --probe` 为 53 PASS / 10 FAIL / 0 UNKNOWN。已修复 `docker top` 缺少 PID 列导致的 UNKNOWN；失败为 quark/MinIO 端口、5 个旧依赖容器缺少 `no-new-privileges`、quark/PanSou/MinIO 的 root 进程，未放宽检查标准。Windows Public Firewall 关闭、MySQL TLS/权限、Cloudflare Access/WAF、完整 MinIO policy、密钥轮换及加密恢复仍需单独验收。
- 上述安全复核仅涉及只读生产检查、仓库修复和隔离测试；随后 19:52 和 20:39 的业务发布仅替换前后端镜像，未修改生产 `.env`、Firewall、数据库账号、MinIO policy、Cloudflare 策略或卷挂载。新增迅雷私有原子写入代码明确排除在本次发布包外，不代表在线凭据权限或登录态已修复。

### 迁移与恢复基线

- 当前前后端发布于 2026-09-24 23:20（Asia/Shanghai）：`gying-library-qr-backend:20260924c` 与 `gying-library-qr-frontend:20260924g`，发布包 `E:\gying-tools\releases\hot-search-filters-20260924`（回滚到 backend `20260924b` / frontend `20260924d`）；此前 23:08 发布的 PanSou 兜底与提示语修正位于 `E:\gying-tools\releases\gying-skip-20260924`（backend `20260924b` / frontend `20260924d`，回滚到 `20260924` / `20260924c`）。两次发布均只替换 backend/frontend 镜像，未修改生产 `.env`、数据库结构、卷挂载或其他服务，默认 Compose 标签已指向新版；更早的 21:38 库内优先/仅二维码发布仍以 `gying-library-qr-backend/frontend:20260924` 保留。
- 2026-09-14 已恢复迁移快照 `migration-data\20260914-081539`：SHA-256 清单 4832/4832 条通过，缺失 0、不匹配 0、错误 0；完整 SQL 及持久化数据均保留在仓库外的受保护目录。
- MySQL `gying` 已恢复并通过 MCP 复核 18 张表；`movie_metadata=1631`、`resource_link=2165`，中文片名和简介抽样可读。迁移前回滚备份位于 `E:\gying-data\gying-pre-deploy-20260914.sql`。2026-09-21 已创建数据库范围受限的 `gying_app`（应用 CRUD）和 `gying_readonly`（MCP 只读）账号；本机 MCP 配置已切换到 `gying_readonly`，直连验证可读且写入被拒绝。
- MinIO、backend-data、social-publisher 两个凭据卷、quark-auto-save 配置、OpenClaw 配置/认证和 MCP 本机配置均已恢复；backend 日志只归档未恢复。未执行 `docker compose down -v`。
- 2026-09-24 使用现有生产环境配置执行 `docker compose -f docker-compose.prod.yml config --quiet` 已通过，未输出解析后的敏感值；语法/必需键通过不代表 Firewall、Access、MinIO policy 和恢复门禁通过。
- NapCat 仍未恢复、未启动、未纳入验收；Redis 和 PanSou 仅按可重建依赖运行。

## 仍需处理

- **Docker Secrets Engine socket 残留在 C 盘**：`%LOCALAPPDATA%\docker-secrets-engine\engine.sock` 与 `engine.sock.stale` 无法删除/改名，Docker Desktop 走默认路径启动仍会在 Secrets Engine 初始化时报错。当前通过独立运行目录启动可用；需在下次重启 Windows（必要时 `chkdsk C: /f`）后清理，并把 Docker Desktop 恢复为默认启动方式。E 盘仅剩约 14 GiB，Docker 数据盘 `docker_data.vhdx`（约 32.5 GiB）长期建议迁移到空间充足的磁盘。
- **库内优先与仅二维码已上线（2026-09-24 21:38）**：网页精确命中本地影片时直接读取已审核、活动、未删除且状态正常的片库资源（含人工发布的非自有来源），同名不同年份/类型先要求确认；首轮不调用 GYING/TMDB/PanSou，也不做分享验活或转存，用户点“搜索其他资源”或发送“资源”才进入外部候选流程，QQ 链路保持原行为。回复与界面不再出现明文资源 URL 和复制/打开按钮，只保留资源名称、提取码与自动展示的二维码（提示使用夸克/迅雷 App 扫码）；二维码内容即分享地址，可被解码，不构成强制 App 或防提取控制。
- **实用验收范围**：前后端已于 2026-09-24 21:38 更新，使用者需刷新旧页面；后端重启前的搜索候选上下文已失效，需重新搜索。本轮未创建真实账号、未修改生产搜索频率、未改写真实影片资源、未手工触发转存或发布；浏览器回归、频率保存与扫码展示均使用模拟 API，不替代真实扫码转存与外部副作用验收。
- **安全加固剩余门禁（Critical/High）**：按 `docs/security/deployment-checklist.md` 完成 Windows 防火墙和敏感端口收紧、DB 分服务身份/grants、MinIO policy/root-key 轮换、OpenClaw 内部网络、Cloudflare Access/WAF、Quark ACL/Cookie 轮换、加密备份和恢复演练；不得将部分上线写成整体安全闭环。
- **当前生产与目标配置仍有部分差异**：2026-09-24 nginx/backend loopback 与 nginx 内部路由拦截正常；quark 5005、MinIO 9000/9001 仍为非 loopback。OpenClaw/Redis/quark/PanSou/MinIO 缺少 `no-new-privileges`，其中 quark/PanSou/MinIO 存在 UID 0 进程；需备份后滚动收紧，不能删除既有卷。
- **数据库**：backend、gying-source、social-publisher 当前共用非 root `gying_app` 配置；按服务独立身份及完整 grants 仍需复核。MCP 实测身份为 `gying_readonly@127.0.0.1`，`require_secure_transport=OFF`、`local_infile=OFF`、MySQL/MySQLX bind address 均为 `*`；密码策略和 TLS 迁移仍待完成。
- **对象存储/网盘**：MinIO 网络 alias 已存在，backend 已不使用 root key；匿名策略、scoped policy 和 root key 轮换仍需复核。OpenClaw 仅接入默认 bridge，未接入应用网络；quark Cookie 配置在线权限仍为 `0:0 / 755`，需要私有权限、加密备份和轮换。
- **凭据历史**：历史扫描 1,278 个 Blob 有 105 条规则命中（跨版本重复）；当前工作区扫描为 0，但所有可能有效凭据仍需 provider 侧轮换，历史重写另行审批。
- **迅雷自动同步当前未恢复稳定运行**：2026-09-24 10:02（Asia/Shanghai）复核，最近一次成功为 2026-09-23 20:33；随后至 2026-09-24 08:33 连续 6 轮失败，计划任务最近退出码为 1。脱敏诊断显示官方刷新接口 HTTP 400、无可用候选；backend 状态文件的 Authorization 已于 2026-09-24 08:33:27 过期，且没有 refresh token。需在本机 Edge 默认 Profile 检查迅雷登录态，重新登录或完成官方页面要求的交互验证后，再运行计划任务并验证有效凭据实际更新；HTTP 400 的具体原因尚未确认，不将写入补丁通过视为刷新恢复。
- **迅雷凭据文件权限待部署修复**：2026-09-24 在线文件仍为 `10001:10001 / 644`。本轮已修复 backend 自身持久化路径，改为私有随机临时文件、POSIX `600` / Windows owner-only ACL、同目录原子替换和失败清理；同步脚本已有改动保留不覆盖。新代码尚未部署，维护窗口需先备份，再验证后端实际重复写入后持续保持 `600`；这不修复迅雷官方登录/刷新失败。
- GYING 图片源和部分外部网盘接口存在偶发超时、风控或响应结构变化，需保留重试和失败审计，不把单次 HTTP 200 视为业务成功。
- 综合评分自动采集已写入 `sys_config` 的六来源轮换配置，每轮上限为 15；当前综合评分间隔为 1 小时并按来源轮换。代码已增加公共网盘不入正式库和 provider/名称错位防护。仍需持续观察 GYING BT 页面登录态、PoW 与响应结构变化，遇到认证失效时只更新外部登录态，不降低采集频率。
- 历史遗留的乱码任务、重复目录和无视频分享仍需按资源价值逐批人工确认，优先 dry-run 和软删除。
- QQ 临时转存清理代码、数据库迁移和安全边界测试已完成；还需由群内实际搜索并选择一个可丢弃资源，复核对应夸克/迅雷临时目录在到期后被删除，同时确认正式 Resource Hub 目录不变。
- 微博自动发布已启用；social-publisher 健康检查显示微博 configured/authenticated/ready 均为 true。为避免无意重复发帖，本次只验证调度器和凭据状态，未手工触发真实帖子。
- 豆瓣评分没有稳定官方 API，不作为生产链路的强依赖。
- 邮箱验证码当前按设计关闭。若启用，需先在宿主机安全配置 `MAIL_PROVIDER`、对应服务的 API Key、`MAIL_FROM_ADDRESS`、`MAIL_FROM_NAME`，验证发件人后再将 `auth.email_verification.enabled` 改为 `true`。
- 2026-09-15 已按用户确认启用 `RESOURCE_HUB_WORKER_ENABLED`、`QQ_BOT_ENABLED`、QQ 频道自动发布、微博自动发布以及夸克/迅雷自动转存计划任务；`.env` 与 MySQL `sys_config` 已统一复核为启用，`QUARK_AUTO_SAVE_RUN_IMMEDIATELY=true`，确保 Resource Hub 新增单条任务先完成实际转存再创建自有分享；quark-auto-save 的定时规则仍为 `0 8,18,20 * * *`。
- MySQL `global/session time_zone=SYSTEM`；2026-09-15 MCP 观测 `NOW()` 与 `UTC_TIMESTAMP()` 相差 8 小时，符合 Asia/Shanghai。Compose 服务设置 `TZ=Asia/Shanghai`；计划任务已按该时区配置。

## 验收

- 2026-09-24 23:20 顶部热门搜索与筛选收起发布：后端全量 239 项测试通过（0 failures/errors，1 项 Redis 集成测试按环境跳过），含 `MonitoringServiceHotKeywordsTest` 5 项（Redis 合并排序、limit 与空词过滤、MySQL 回退、双源失败返回空、边界钳制）；前端 TypeScript 检查 0 错误，浏览器验收 `tools/tests/hot-search-filters-ui.cjs` 在隔离镜像上以模拟 API 通过 5 项（热词面板与请求参数、点击热词跳转并关闭面板、筛选默认收起/展开、分类页 Tag 摘要与清除全部、移动端抽屉热词），并在公网真实环境复验热词来自线上数据、分类页默认收起且无页面错误；本地与公网 `/`、`/admin/movies`、`/resource-search`、`/api/movies/hot-searches` 均返回 200。仅替换 backend/frontend。
- 2026-09-24 23:08 GYING 不可用改用 PanSou 发布：`GyingSourceWorkflowService` 新增 PanSou 补齐季与 TMDB 搜索补图兜底，后端全量 234 项测试通过（0 failures/errors，1 项跳过），发布后本地与公网页面、影片列表接口均 200；该版本同时移除了资源搜索页底部说明小字并修正后台任务 SKIPPED/FAILED 提示。
- 2026-09-24 21:38 库内优先/仅二维码发布：白名单快照在 Java 17 + 独立 Redis 下全量 224 项测试通过（0 failures/errors/skips），前端生产构建、变更页面 ESLint/TypeScript 与 8 项回复解析/历史单元测试通过；浏览器模拟 API 回归在隔离镜像与公网静态资源上均通过，覆盖连续输入、候选与翻页、失败保留、移动端溢出、库内优先与“搜索其他资源”显式外搜、二维码自动展示且页面无明文 URL/复制/打开入口、管理员建号与频率保存，以及此前注册/编辑绑定回归。仅替换 backend/frontend：nginx `-t` 与 reload 成功，重启窗口内出现 10 条上游连接拒绝日志（探针落在容器替换间隙），之后日志恢复 200；新容器重启次数 0、卷挂载与其他服务启动时间不变，默认 `latest` 标签已指向新镜像。
- 2026-09-24 21:30 Docker 故障恢复：强制停止 Docker Desktop、`wsl --shutdown` 并重建 `%LOCALAPPDATA%\Docker\run` 后引擎恢复（21:44 复检再次把 `%LOCALAPPDATA%\Docker\run` 整目录替换为新建的干净目录，旧目录改名为 `run-stuck-20260924-214433`，默认启动的 Ingest 报错来源已消除），10 个生产容器与全部命名卷完整保留，未执行卷删除、镜像清理或数据恢复；`verification-and-incident.json`、容器前后快照与发布/回滚 override 均保存在 `E:\gying-tools\releases\library-qr-20260924`。
- 本轮回滚材料包含新生成的 25 张表 MySQL dump 与 `.env` 的 DPAPI CurrentUser 加密副本（内存解密与哈希校验通过，无明文落盘），以及镜像回滚 override；备份仅可在原 Windows 主机/账号解密，不等同异机灾难恢复。
- 2026-09-24 20:39 搜索与管理员功能发布：白名单快照 Java 17/独立 Redis 全量 220 项测试通过（0 failures/errors/skips），前端生产构建、变更页面 ESLint、7 项回复解析/历史单元测试通过；隔离镜像及线上静态资源上的浏览器模拟 API 回归均通过，涵盖连续输入、候选/翻页、失败保留、移动端溢出、USER/PUBLISHER 创建/确认密码/冲突恢复、频率默认值和保存，以及此前注册/编辑绑定回归。仅替换 backend/frontend，nginx 检查与 reload 成功；前后端重启次数 0，卷挂载及其他服务启动时间不变，reload 后抽查无新增错误。
- 本轮回滚目录为 `E:\gying-tools\releases\search-admin-20260924-2035`，包含源码哈希、镜像回滚 override、容器前后快照及验收摘要；新备份含 25 张表的 MySQL dump 与 `.env` 的 DPAPI CurrentUser 加密副本，已在内存解密/哈希验证，无明文备份、无数据库恢复/结构变更。该备份仅适用于原 Windows 主机/账号，不代表异机灾难恢复。
- 2026-09-24 19:52 业务修复部署：白名单发布包 Java 17 编译及独立 Redis 全量 210 项测试通过（0 failures/errors/skips；排除另一批 6 项迅雷私有写入测试），前端生产构建通过。仅重建 backend/frontend，nginx 配置检查与 reload 成功；新容器重启次数 0、原卷挂载不变。公网和本地首页/注册/资源搜索/资源管理页面、注册策略和影片 API 返回 200，真实浏览器影片详情 SSR 返回 200；匿名资源管理与搜索任务接口 401、QQ 内部路径 404。公网部署后的 3 项模拟 API 浏览器回归通过。发布瞬间有 1 条 frontend DNS 暂不可解析日志，reload 后前后端/nginx 无新增错误；Python 默认 User-Agent 的公网探针曾返回 403，以显式浏览器 User-Agent 和真实浏览器重验为 200，未调整边缘安全策略。
- 2026-09-24 邀请码真实链路验收：仅新建测试邀请记录 `id=2`（`OPS-CHECK`，历史测试周期，不占现有额度），经新 backend 与真实 MySQL 验证有效码策略 200/允许注册；随即撤销并过期，复验 200/禁止注册，MCP 确认 `REVOKED`、使用次数和注册使用记录均为 0。未输出完整测试码，未注册账号。
- 本次应用回滚材料位于 `E:\gying-tools\releases\business-fixes-20260924-1950`：旧镜像标签、部署/回滚 Compose override、源码哈希、验收摘要，以及包含 25 张表的 MySQL dump 和 `.env` 的 Windows DPAPI CurrentUser 加密副本。已在内存验证解密与哈希；仅能由原 Windows 主机/账号解密，不等同于异机灾难恢复演练。没有恢复数据库、修改数据库结构、改写现有邀请或资源记录，也没有改动其他服务配置。
- 2026-09-24 业务修复隔离验收：Java 17 Maven 编译与全量 216 项测试通过（0 failures/errors/skips），含独立 Redis 原子限流集成、邀请码日期兼容、搜索异步任务归属/重复提交、GYING 熔断恢复、来源降级和编辑绑定校验；前端 Docker 生产构建及 TypeScript 检查通过；`tools/tests/web-resource-flows.cjs` 在隔离生产构建上以模拟 API 完成 3 项真实浏览器回归（邀请码/验证码输入、搜索轮询与刷新续查、编辑追加绑定），无页面运行时错误。ESLint 无错误，仅保留既有 API 登录跳转警告；secret scan 为 0 findings。测试未连接生产数据库、未注册真实用户、未转存/发布真实资源，未改变生产容器。
- 2026-09-24 安全复核：修复 Docker 进程采集后为 53 PASS / 10 FAIL / 0 UNKNOWN；Python 安全工具 16 项通过。backend 全量 Maven/隔离 Redis 测试 201 项通过（0 failures/errors/skips），其中 6 项新增凭据写入回归（对应本轮测试执行时源码，不覆盖随后出现的并行业务改动）；Windows Java 17 独立验证 owner-only ACL、重复原子替换及失败清理通过。当前工作区 secret scan 为 0；测试 Redis 容器/网络已移除，生产未重建。新迅雷写入代码待维护窗口部署，在线文件仍为 `644`。

- 2026-09-24：`python -X utf8 tools/tests/test_xunlei_token_activation.py` 的 3 项隔离回归全部通过，覆盖首次写入（含中文）、原子替换、失败时保留原文件并清理临时文件；测试使用无网络、无生产卷、`cap-drop=ALL` 的非 root 容器。Node 语法检查与 `git diff --check` 通过，PowerShell 解析已于 2026-09-23 通过。2026-09-23 18:33、20:33 的真实计划任务成功证明写入补丁可用，但其后刷新仍失败，不能替代持续登录态验收。2026-09-24 本机 nginx 首页与注册策略接口返回 200，QQ 内部 health 路径返回 404；未触发转存、分享或发布。已有受保护回滚副本位于 `E:\gying-tools\xunlei-auth-helper\rollback-20260922-202335`，其中旧凭据不视为有效恢复凭据。
- 2026-09-17：系统设置现有配置说明已全部更新为中文，新增 QQ 临时清理配置默认启用、保留 10 分钟；数据库已创建 `qq_transfer_cleanup_job`。后端完整测试 163 项全部通过，生产镜像重建并部署后首页、设置页、自动化页、影片列表、筛选和 QQ Bot health 均返回 HTTP 200。使用一次性注册账号真实验证默认角色为 `USER`，提交资源返回 403，测试账号随后已删除。
- 2026-09-19：`GYing Xunlei Token Sync` 已切换为从实际 Edge 默认配置复制到临时自动化 Profile，脚本会触发官方网页客户端使用 refresh_token；手动启动计划任务验证返回 0，并完成新的真实更新验证。脚本区分 `updated/unchanged/failed`，backend 支持状态文件变更热加载。认证内容未写入日志或状态文档。
- 2026-09-19：资源类型筛选、留言类型/安全查询、评论管理跳转和登录设备页面已部署；MySQL `resource_link` 当前有效资源为 `DISK=2085`、`MAGNET=110`、`TORRENT=0`，`comment` 现有记录均为 `GENERAL=8`。修正 `Comment` 实体到 `comment.comment_type` 的显式映射后，backend 镜像重新构建并通过生产入口复核留言查询 200、管理员资源接口未认证 401、登录设备接口未认证 401；未执行卷删除。
- 2026-09-19：用户使用 `test` 账号完成登录设备创建和浏览器授权撤销验证；数据库已记录设备浏览器/操作系统、IP 和时间。随后修正 `/api/auth/devices` 的数据库下划线字段到前端驼峰字段映射，并将时间统一输出为带时区 ISO 字符串；backend 已重新构建部署，公网首页和留言接口返回 200，未认证设备接口返回 401。
- 2026-09-18：已创建命名 Cloudflare Tunnel，并将 `gyinghub.dpdns.org` CNAME 路由到本机 nginx；Tunnel 配置位于 `E:\gying-tools\cloudflared\config.yml`，Windows 计划任务 `GYing Cloudflare Tunnel` 登录时启动。旧 Quick Tunnel 已停止。真实公网验证：首页、影片列表 API 和 AVIF 图片均返回 HTTP 200。
- 2026-09-16：重建 backend 后 nginx 曾缓存旧 backend 容器 IP，导致 `/api/*` 短暂返回 502（日志为 upstream connection refused）；重启 nginx 重新解析 `backend` 服务后恢复。当前 nginx 解析到运行中的 backend，未认证请求 `/api/admin/resource-hub/tasks?page=1&size=20` 正确返回 401，未再返回 502。后续替换 backend/gying-source 后需同步重载或重启 nginx。
- 2026-09-14 至 2026-09-15：完成新机迁移与启用验收；MySQL MCP `list_tables/query_sql`、Docker MCP `docker_ps` 均成功。Compose 核心服务、Redis、PanSou、quark-auto-save、MinIO、nginx、前端、backend、GYING Source、social-publisher 和 OpenClaw 均处于运行态；入口和健康检查返回 HTTP 200。
- 2026-09-15：MinIO `gying/mv/0319/384.avif` 抽样返回 HTTP 200；`gying-source`、`social-publisher` 内部 `/health` 返回 200；Redis 返回 `PONG`；backend 通过 nginx 和直连 QQ health 均返回 200，显示 `enabled=true`、NapCat 未配置、QQBot 已配置。
- 2026-09-15：OpenClaw Gateway `http://127.0.0.1:18789/healthz` 返回 200，QQBot 官方 Gateway 返回 HTTP 200、WebSocket 已连接并收到 `READY`；认证材料仅验证“已配置”，不在文档中记录值。QQ 频道 bridge `http://127.0.0.1:8092/health` 返回 200，Windows 计划任务 `GYing QQ Channel Auto Post` 已创建并启用，脚本使用当前仓库路径。
- 2026-09-15：修复 Resource Hub 夸克任务只建目录未转存的问题，恢复 `QUARK_AUTO_SAVE_RUN_IMMEDIATELY=true` 并重建 backend；定向复验“托尼”和“电动维纳斯”均完成转存、自有分享、发现结果 `SAVED` 和正式资源 `ACTIVE/NORMAL`。迅雷运行凭据更新后定向任务完成转存、自有分享、发现结果 `SAVED` 和正式资源 `ACTIVE/NORMAL`。宿主机已安装并扫码授权 `tencent-channel-cli@1.0.10`；5 条因 CLI 缺失失败的频道帖子均已真实重试为 `POSTED`，其中两条遇到 `20063` 频控后按 300 秒间隔重试成功。QQ 频道计划任务已重新启用，下一次计划运行时间为 2026-09-16 09:00（Asia/Shanghai）。
- 2026-09-15：综合评分真实任务 `CSCORE_MOVIE` 单条验收成功，`resource_hub_task` 状态为 `SUCCEEDED`；重建后的 `gying-source` `/health` 返回 200，`/catalog` 的电影、剧集、动漫 `sort=cscore` 均返回 3 条，`/bt/VyyO8` 返回 5 条 `MAGNET/P2P` 资源。前端使用生产 Dockerfile 完成 Next.js 编译，nginx 入口返回 HTTP 200。
- 2026-09-03：修复 GYING 自动补图并重建 `gying-source`、`backend`、`nginx`；实测钢铁侠 `vPW8` 写入 `movie_metadata.poster_url=mv/vPW8/384.avif`，MinIO 地址返回 200。
- 2026-08-26：完成生产运维基线复核，Compose 服务、数据库依赖和核心入口可按运维脚本检查；未执行破坏性数据操作。

### 2026-09-21 变更补充

- 修复 GYING 爬取和补全季资源时的 `Data too long for column 'name'`：并行数组资源名称按同索引读取，不再把整组名称数组转换为字符串；Crawler、GYING 工作流发布和资源入库路径均对 `resource_link.name` 做 255 字符边界保护。已通过 Python 编译和容器内归一化回归验证。
- QQ 群资源候选上下文在成功选择后继续保留：已选资源从候选页移除，用户可直接回复其他序号继续选择；候选超过 10 条时按每页 10 条展示，可回复“下一页”“上一页”翻页，内部最多保留 30 条候选。
- 管理端 GYING Source 增加按 GYING ID 批量同步元数据入口，支持最多 60 个 ID，和资源补全流程分离。
- 本次修改已完成 backend Docker 编译、`QqBotServiceImplTest` 分页/多候选回归和全量 Maven 测试；候选上限调整为 30 条。2026-09-21 已生成本机 backend↔GYING Source 内部 `GYING_SOURCE_API_TOKEN`，并创建/验证非 root 数据库账号；当时部署预检查尚缺受保护 `.env` 中 `QUARK_COOKIE`；该项为 2026-09-21 历史状态，2026-09-24 Compose contract 已通过。

### 2026-09-20 验收补充

- 修复综合评分任务被代码硬编码为至少 72 小时的问题；后台 `resource.hub.gying.auto_sync_interval_hours=1` 已真实生效。2026-09-20 10:39 创建的 `CSCORE_ANIME` 轮换任务于 10:41 完成，状态为 `SUCCEEDED`；其中 1 条 GYING 项目失败，已保留在任务错误摘要中。
- 迅雷自动更新计划任务 `GYing Xunlei Token Sync` 已验证为启用、每 2 小时执行，2026-09-20 10:33 最近一次运行返回成功；同步日志仅记录状态，不记录 Authorization、Cookie 或密码。此前失败原因为自动化 Edge Profile 未继承有效登录态，现已改为复制默认 Edge 会话到临时 Profile 后执行。
- 生产重建并部署 backend、frontend、gying-source，nginx reload 成功；公网首页和留言接口返回 HTTP 200，`gying-source` `/health` 返回 200，`/bt/VyyO8` 当前可返回 P2P 资源。以上是安全分支之前的业务部署证据，不代表本次安全改动已重建生产。

### 2026-09-20 安全审计验收（工作区/隔离环境）

- 隔离 Maven/Redis 测试 194 项通过（0 failures/errors/skips）；新增 `sys_config` 脱敏/拒绝敏感写入和 QQ 限流下限测试；隔离 nginx 测试验证 UID 101、编码/矩阵路径拒绝、隐藏文件/私有媒体拒绝、媒体 query 丢弃、代理头清洗和 API 限流。
- 2026-09-20 隔离验证 `sys_config` 敏感配置脱敏/写入拒绝与 QQ 限流下限；该轮测试时尚未部署。后续部分加固已于 2026-09-22 发布，当前运行状态以上方 2026-09-24 复核为准。
- frontend 和 social-publisher `npm audit` 均为 0 findings；当前工作区 secret scan 为 0 findings；历史 secret scan 仍为 105 条跨版本命中。
- npm audit（frontend/social）为 0，但 Docker Scout 对隔离目标镜像仍发现 frontend 10 条、social 58 条、source 1 条 critical/high；2026-09-20 旧 backend 镜像的 SARIF 已发现 17 个受影响包、61 个唯一漏洞（13 Critical、48 High），且其依赖集仍包含 Spring Boot 3.2.0，不能代表工作区 3.5.16 目标镜像。完整 JVM/Python/OS 镜像 CVE triage 和外部攻击面扫描尚未形成闭环，不能把 npm audit 0 当成镜像清洁。公网 Cloudflare Access/WAF、DB grants、MinIO policy、Firewall、加密备份恢复仍待生产维护窗口验证。
- 验收命令：`python -X utf8 tools/security/check_security.py --repo . --probe`、`python -X utf8 tools/security/test_nginx.py`、`python -X utf8 tools/security/scan_secrets.py`；旧容器上的 `check_security.py` 失败项按预期记录为部署前风险。frontend 使用 `next build --webpack` 通过；默认 Turbopack 构建在 Windows 上因 Next 配置文件 EXDEV rename 环境错误失败，未归因于业务代码。

### 2026-09-19 验收补充

- 注册策略公网接口返回 200，确认公开注册关闭、邀请码注册开启、邮箱必填、邮箱验证码因未配置邮件服务而关闭；无邀请码注册请求返回 403。
- 新增注册/邀请/监控数据库表和邮箱唯一索引已在线存在，变更前 MySQL 备份保存在 `E:\gying-tools\backups`，未执行卷删除或破坏性清理。
- `/admin/monitoring` 前端生产构建通过；真实请求已产生页面访问、站内搜索、资源操作记录，Redis 热搜返回测试关键词及计数。公网首页、注册页和注册策略接口均返回 HTTP 200。
- QQ 频道图片链路已在容器内用真实 AVIF 海报完成下载、JPEG 转换并通过 CLI `--image` dry-run；宿主机自己的频道发布任务保持启用，旧 `secondary`、`qq-v3` 目标已软停用。
- 修复前端 `api()` 未自动读取 Zustand 持久化登录态的问题：现会在未显式提供 Authorization 时自动附加本地登录 Token；生产 Docker 构建通过并重建 frontend，`/invitations`、`/admin/monitoring` 的本地与公网页面入口均返回 HTTP 200。
- backend 其余测试在完整测试轮次通过，按新的“首轮直接回复片库资源链接”行为更新断言后，`QqBotServiceImplTest` 28 项全部通过；frontend 生产构建与 social-publisher 6 项测试通过。

### 2026-09-21 安全复核（工作区/隔离环境）

- 使用审计专用 Redis 隔离容器重新执行容器内 Maven 全量测试：195 项，0 failures/errors/skips；Redis 原子限流集成通过。测试专用容器和网络已停止并移除，生产容器、生产卷和 Maven 缓存未修改。
- 重新通过 `git diff --check`、当前工作区 secret scan（0 findings）、历史 secret scan（1,278 个 Blob / 105 条规则命中）、Python 安全工具测试（10 项）、nginx 隔离回归（PASS）、social-publisher 安全测试（3 项）和 Python `compileall`。
- 使用合成凭据执行 `docker compose -f docker-compose.prod.yml config --quiet` 通过；真实生产 `.env`、外部账号和生产容器未替换。
- 只读在线探针仍观测到旧部署的非 loopback 端口和内部路由响应；这些 FAIL 保留为部署前证据，不把目标 Compose 或隔离测试写成生产已上线。

## 长期不变量

- `movie_metadata` 是影片主表，`resource_link` 是可用资源表，`movie_source_identity` 保存外部来源绑定。
- 资源状态和发布状态必须可追踪；失效修复优先原位更新，避免同片产生多条活动自有分享。
- 自动化只处理明确匹配的影片和资源；不确定匹配必须转为人工候选。
- 删除、迁移和重复清理默认使用 dry-run，并保留可回滚的软删除或迁移记录。

## 常用校验

```powershell
cd backend
mvn test

cd ../frontend
npm run lint
npm run build

cd ..
docker compose -f docker-compose.prod.yml config --quiet
```

## 已发现任务重跑

- 影视资源中心支持夸克、迅雷“已发现”转存任务的批量延迟重跑，可在发现结果页手动触发。
- 定时任务每天 08:30（Asia/Shanghai）运行；本轮存在迅雷任务但 Authorization 已过期或不可用时，整轮跳过，不启动转存。
- 单条“重试分享并发布”以及批量重跑在本地资源发布成功后，会继续同步发布到 GYING；重跑数量和间隔可通过 `RESOURCE_HUB_DISCOVERED_RETRY_*` 配置。

## 资源新增

- 2026-09-24 已上线编辑追加绑定：管理员资源编辑及影片详情编辑可提交 `bindMovieIds`，先校验全部目标影片，再在同一事务中更新与追加；按影片/链接跳过已有资源，不删除旧绑定。切换编辑对象或主影片时清除未提交的旧绑定选择。
- 资源链接按“影片 + 链接”去重，同一网盘链接可绑定不同季/影片；管理员和影片详情分享表单支持 URL 自动识别网盘、一键粘贴分享文案、快速标题参数和同系列绑定标记。
- 同系列绑定会按当前影片的系列或 TMDB 集合提供可搜索多选候选，提交后为每个勾选季创建同一分享链接；快速标题参数由 `resource.form.quick_params` 集中管理，支持在系统设置中新增、修改、删除。
- 绑定搜索输入关键词时会跨影片标题、英文名、别名、系列名和影片 ID 检索，避免因历史影片缺少系列字段而无结果；空搜索仍优先展示同系列候选。
- 快速参数中的 `[影片名]` 是动态占位项：在影片详情页插入当前影片中文名，在管理员表单插入当前选中影片的中文名，不会写入字面量占位符。
- 迅雷分享文案中的提取码会自动补到 URL 的 `?pwd=`/`&pwd=` 参数；转存发布生成的资源名会带影片类型前缀，例如 `【动作犯罪】`。
- 2026-09-07：GYING 最近更新的确保/发布流程已独立检查夸克和迅雷，分别发布缺失 provider；迅雷服务不可用时明确失败，不再静默使用夸克代替。
- 2026-09-07：已发布资源健康接口提供 `mv/<movie-id>` 或 `tv/<movie-id>` 形式的 canonical `gyingResourceId`，可直接用于按 ID 修复，同时兼容旧 panlist ID。
- 2026-09-07：健康修复会按 provider 分别解析夸克和迅雷转存任务。
