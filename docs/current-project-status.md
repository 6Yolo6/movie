# 当前项目状态

更新时间：2026-10-11

本文只汇总已记录的生产能力、运行约束、待处理事项与可复核结论；各项证据保留原日期，本次文档整理不表示重新验收。

[文档导航](README.md) · [API 契约](api.md) · [部署与回滚](deployment.md) · [迁移与恢复](../MIGRATION.md) · [发布状态规则](social-publishing.md) · [安全门禁](security/hardening-gates.md) · [历史记录](history/README.md)

## 当前目标

- 维护电影、剧集、动漫和用户提交资源的统一片库。
- 通过 TMDB、GYING、PanSou 与网盘自动化服务补全元数据和可用资源。
- 自动转存为系统自有夸克/迅雷分享，校验后写入正式资源库。
- 支持 QQ 群搜索、频道发布和多平台发布审计。

## 运行架构

- 对外入口：Cloudflare Tunnel `gyinghub.dpdns.org` → loopback nginx（`127.0.0.1:80`）→ Next.js / Spring Boot（backend `127.0.0.1:8880`）。Tunnel 配置在 `E:\gying-tools\cloudflared\config.yml`，由登录触发的计划任务 `GYing Cloudflare Tunnel` 启动。
- 核心服务：`frontend`、`backend`、`gying-source`、`social-publisher`、`nginx`；依赖 MySQL、Redis、MinIO、PanSou、`quark-auto-save`、OpenClaw QQBot。
- 生产 Compose 文件为 `docker-compose.prod.yml`（无默认 `docker-compose.yml`）；容器与 JVM 时区统一 `Asia/Shanghai`。本机 Docker CLI 位于 `C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe`。 部署必须显式指定已存在的项目名 `-p gying-movie`，不能使用目录名推导出的默认项目 `movie`。
- 代码与 Python 环境在 `D:\gying-movie\movie`；Docker Desktop 数据（`docker_data.vhdx`，约 44 GiB）位于 `G:\dockerdesktop\wsl\DockerDesktopWSL`（2026-09-25 由 E 盘迁移）。
- QQ 频道发帖由宿主机 `tencent-channel-cli` 计划任务执行，不经过 `social-publisher`；NapCat 已停用，不作为备用通道、迁移依赖或验收项。
- Cloudflare Tunnel 使用 HTTP/2 传输（计划任务参数 `--protocol http2`）：本机代理以 fake-IP 方式解析 `cfd.argotunnel.com`，QUIC/UDP 会被代理丢弃。健康检查用 `http://127.0.0.1:20241/ready` 或公网首页状态码；`E:\gying-tools\cloudflared\config.yml` 的 ACL 对当前用户只读，改配置需提权。
- 现役开关（2026-09-15 复核，`.env` 与 `sys_config` 一致）：`RESOURCE_HUB_WORKER_ENABLED`、`QQ_BOT_ENABLED`、QQ 频道自动发布、微博自动发布及夸克/迅雷自动转存计划任务均为启用；`QUARK_AUTO_SAVE_RUN_IMMEDIATELY=true` 确保先完成实际转存再创建自有分享；quark-auto-save 定时规则为 `0 8,18,20 * * *`。
- **Docker Desktop 启动约束（已解除）**：此前 `%LOCALAPPDATA%\docker-secrets-engine\engine.sock(.stale)` 为内核层失效 reparse 项，导致 Secrets Engine 初始化失败、需以独立运行目录绕过；2026-09-25 重启 Windows 后失效项已清除，`engine.sock` 在默认路径正常重建，独立运行目录已移除，Docker Desktop 回归默认启动。

## 已具备能力

### 片库与治理

- 首屏与手机回归（2026-10-11 已部署）：i18n 初始 SSR/客户端快照统一，挂载后恢复已保存/浏览器语言；阻止旧首页请求覆盖新筛选，保留分页失败后的现有结果。中文 360/375/390/430px 首页回归通过，真机验收另列。
- 分页、分类筛选、详情、收藏、评论、站内通知与管理员审核；影片、资源链接和来源身份均支持软删除并保留历史。
- 首页三个分类按资源最近更新时间优先，并综合站内热度、TMDB 热度与可用评分；搜索/筛选页保留最新与评分排序。
- 顶部搜索框提供「最近热门搜索」（近 7 天 Redis 热词合并，回退 `site_search_log`，公开接口 `GET /api/movies/hot-searches`）；搜索页与电影/剧集/动漫分类页的筛选项默认收起，仅常显排序，有已选条件时显示可逐个关闭的摘要与「清除全部」。
- 新注册账号固定为 `USER`，新增/修改/删除资源仅 `PUBLISHER` 与 `ADMIN` 可执行；管理员可在 `/admin/users` 直接建号（USER/PUBLISHER），不受邀请码与注册开关限制。管理员发布资源不计入 `resource.max.per.user`。
- 影片元数据页支持跨页多选（最多 100 部）、所选缺图补全及确认后刷新已有海报；新增单片 GYING 元数据同步。GYING 对应影片/季的详情与海报优先，严格匹配后绑定 GYING 与 TMDB 到同一 canonical 记录；TMDB 回退使用独立季海报对象，不复用整剧或其他季图片。
- 用户内容、注册/邀请、登录设备、留言与评论管理均已上线；Resend 已接入已验证的 `gyinghub.dpdns.org` 发件域并启用邮箱验证码，163 等邮箱投递正常，Gmail 因免费域声誉暂拒收。
- 前端品牌为「影窝」，切换英语语言时显示「FilmNest」，两者不并排展示；站点图标含 SVG favicon 与 Apple touch icon。首页热门轮播跟随明暗主题（浅色白底、深色黑底），手机端隐藏海报与长简介，保证文字与操作按钮完整可用。左侧抽屉与汉堡按钮在所有屏幕宽度可用，登录用户在抽屉中进入「搜索资源」，顶栏不重复展示该入口；留言页默认类型为「综合留言」，留言频率可在后台配置（`comment.rate_limit_per_minute`，默认 5 次/分钟），内容经 HTML 白名单清洗。首页与电影/剧集/动漫分类页不显示片库结果总数，仅在关键词搜索时显示匹配数量。

- 全站页脚统一展示「联系方式」与「留言」，顶栏及左侧抽屉不再重复留言入口；页脚两项默认中性灰色、仅悬停高亮，不受全局超链接蓝色覆盖；联系方式弹窗展示原始 QQ 群 JPG 和 `2031798793@qq.com`，提示邮箱不常看、QQ 群优先，适配中英双语、明暗主题与手机屏幕。

### 影视资源中心

- 元数据/周榜隔离与迅雷落位修复（已部署，2026-10-05）：高频 TMDB/GYING 仅采元数据、海报、磁力/种子链接，普通 Worker 不再消费旧云盘队列；电影/剧集/动漫三个 GYING 本周热门分别配置，默认各 7 天/前 5 部，首次计划 2026-10-12 11:50 左右，新夸克周榜不注册每日追更。迅雷逐文件确认目标位置、整组完整后分享，完整分页找目录，搬移/分享重试保留季目录和文件身份。该隔离能力已于 10 月 5 日上线，现役镜像见下文迁移与恢复基线；原历史追更仍独立运行，历史根目录文件未迁移，详见 `docs/metadata-weekly-transfer.md`。

- 元数据范围续采（已部署，2026-10-04；失败隔离于 10 月 8 日补强）：TMDB / GYING 支持 1–500 的起止页范围，各榜单独立保存页码与页内进度；完成页后翻页，到结束页或空页回到起始页。未持久化的失败保留批次；GYING 已保存到独立 `METADATA_ITEM_RETRY` 的失败可与主游标分离。手动单页任务不影响自动位置，启动恢复不重置旧视频/转存任务。
- 迅雷自动队列在 SQL 中先排除已达重试上限的 FAILED 任务，再限制数量，按 `updated_at,id` 轮转，避免最早 200 条历史失败挡住新任务；合集入库名称保留明确季范围与清晰度（如「破产姐妹 第1-6季合集 1080p」）。
- 「补全系列/剩余季」支持电影、剧集及动漫。GYING 按系列名搜索并刷新已有季，TMDB 以真实季信息或官方电影合集补缺；不创建推测续集、不隐式恢复已删除记录。剧集仍只按有效合集的明确季范围复用 URL/提取码（`COLLECTION_BINDING`），电影续集仅补元数据，不扩散资源、不转存或发布。

- 临时转存与后台采集转存均在视频落盘后、创建自有分享前补齐 `救星小窝基地.jpg`：夸克源图位于网盘根目录，迅雷源图位于 `我的转存/影视剧资源分享(先转存后再查看)`；保留源图，目标已有同名图片时跳过，复制失败保留任务错误。夸克按文件回退分享时携带该图片，但不生成纯图片资源分享。
- 夸克配图复制采用账号文件接口的 `action_type + filelist` 契约；HTTP 失败错误只记录方法、路径、状态和上游 code，不回显响应正文、Cookie、查询串或分享 URL。该修复由 backend `gying-quark-copy-backend:20260927b` 上线（代码 `3276b6e`）。
- 搜索合并本地 PanSou 与外部 Panso 结果并按 URL 去重；自动网盘视频资源必须先转存为系统自有夸克/迅雷分享，再写入 `resource_link`。
- 夸克任务支持目录创建、剧集目录更新、失效分享重试与周转存（`update_subdir: ".*"` 递归追踪同名目录新增文件）；迅雷使用官方 Drive API 校验分享、遍历目录并筛选视频文件，Authorization 为短期凭据，支持运行时更新与 refresh token 优先。
- 迅雷浏览器会话同步（2026-10-08 已上线）：每两小时及用户登录时，先刷新真实 Edge 迅雷标签，必要时仅用浏览器已保存登录信息单次提交；随后获取完整凭证、验证同账户官方只读 Drive API，再加密备份并以非 root 原子写回。禁止复制密码库、临时副本轮换登录、重复错误登录或绕过验证码；失败不覆盖可用后端状态。详见 `docs/xunlei-token-sync.md`。
- 管理员资源管理对失效或疑似失效的夸克、迅雷资源提供「修复并重分享」；成功后原位更新链接，并在存在 GYING 映射时同步发布。
- 资源管理编辑实际绑定组：打开编辑器时回显全部已绑定季（同一分享的多个历史根节点合并回显）；影片较多时可输入关键词，从下方全库候选中点选加入（不限同系列，最多显示 24 条并提示其余命中），也支持按影片 ID 或完整标题直接追加。保存时校验 `bindingVersion`，并原位更新所选影片现有的同组资源及历史重复行，仅对新增绑定创建记录，不删除取消勾选的记录，也不改动其他网盘或版本。
- 「已发现」转存任务支持单条与批量延迟重跑（每天 08:30 Asia/Shanghai 调度）；本轮存在 Authorization 过期或不可用的迅雷任务时整轮跳过，不启动转存；发布成功后继续同步 GYING，重跑数量与间隔由 `RESOURCE_HUB_DISCOVERED_RETRY_*` 控制。
- GYING 目录自动同步支持热门与综合评分（电影/剧集/动漫）。2026-10-08 已部署停滞修复，当前启用 `CSCORE_MOVIE/TV/ANIME`、范围 1–15 页、每小时一批、最多 20 条；动漫 page1 offset0→page1 offset20（failed 0 / deferred 0）；电影 page5 offset40→page6 offset0（failed 0 / deferred 0）；剧集 page2 offset0→page2 offset20（failed 0 / deferred 0）。只观察自然调度，未手动推进游标。
- GYING 紧凑 `downlist.list` 磁力/种子解析与归档已部署（2026-10-06）：每片当前只选 1080P、4K 各一个版本，优先明确中文字幕且最终至少有一份；没有符合字幕条件的版本不归档，不把国语/配音视作字幕。磁力文本和真实 torrent 元数据由独立 `P2P_ARCHIVE` 队列上传到对应影片的 `磁力种子` 子目录；全部文件确认后才将自有夸克/迅雷分享入库，类型为 TORRENT、名称明确“不含视频”。不调用视频转存或 BT 离线下载，不批量删除历史记录/文件，详见 `docs/p2p-cloud-archive.md`。
- 资源链接按「影片 + 链接」去重，同一网盘链接可绑定不同季或影片；分享表单支持 URL 自动识别网盘、一键粘贴分享文案与同系列绑定。
- 影片详情页按内容而非传输方式分类计数（2026-10-11 已部署）：网盘与磁力/种子两个主分类始终显示精确数量（含 0 与超过 99），经夸克/迅雷归档的种子仍计入磁力/种子；在线播放与未知类型各自独立成分类，不并入磁力/种子；只有磁力的影片默认选中磁力/种子分类；provider 分组数量随当前分类联动。资源创建日期按后端 `yyyy-MM-dd` 原样显示，避免 SSR 与浏览器时区差异导致英文页面水合不一致。

### GYING 数据源

- 单片详情及发布前检查仅读取该片详情中的资源，按发布者识别 `ownResources`，不再遍历账号自有资源列表；显式账号已发布资源同步仍可分页。该优化随 `gying-source-detail:20260927a` 上线（代码 `40bac83`），网站重复提交保护和已有自有资源去重保持不变。
- 支持按类型和模式搜索、目录同步、元数据导入、资源发布与已发布资源抓取；发布使用固定契约 `/res/pan/add`（`binds[0][dir]` + `binds[0][id]`），不把类型与 ID 拼入路径。
- 上游不可用时自动补图回退 TMDB；「补齐剩余季」已改为元数据与已有合集绑定模式，不再用 PanSou 逐季转存。其他资源搜索/修复的 PanSou 兜底不变。
- 读取连接/响应超时为 3/20 秒，失败后 30 秒短时熔断并自动允许重试；AUTO/QQ 搜索继续尝试 PanSou，补充来源失败不丢弃已有候选。
- 归属保护：只有明确属于 `GYING_TARGET_USER` 的网盘分享进入正式片库，公共 GYING/PanSou 结果仅作候选；provider 优先按分享 URL 主机识别。
- 内部 `gying-source` 提供 `GET /search`、`GET /catalog?sort=cscore`、`GET /bt/{btId}`，以及需要内部 token 的 `/torrent-file/{typeCode}/{mid}/{btRef}`。种子下载限定本站 HTTPS、拒绝跳转、上限 2 MiB，校验原始 info 字节 BTIH 与磁力一致；认证或下载错误不输出 Cookie/临时凭据。

### 注册、邀请与后台监控

- 支持邀请码、注册开关与角色策略；邀请码 DATETIME 与 `LocalDateTime`/`Timestamp` 兼容，密码要求与后端一致（至少 12 字符、最多 72 UTF-8 字节）。
- 公开注册支持人数上限设置 `auth.register.max_users`（0=不限制，上限 0–100000，当前生产 500）；达到上限后只停止无邀请码的公开注册并给出明确提示，有效邀请码注册与管理员建号不受上限影响。
- `/resource-search` 提供登录用户网页资源搜索：精确命中片库时优先返回已审核、活动、状态正常的资源，首轮不调用外部来源也不转存；界面只展示资源名称、提取码与二维码，不出现明文 URL 与复制/打开入口。任务异步执行、每 2 秒轮询、刷新续查，发送成功后清空已发送内容。
- 后台监控页（2026-10-11 已部署重构）：统计卡、14 天趋势、各类日志 tab 保留；请求/错误统计只统计 `event_type='API'`，访客数与页面浏览量分开计数；概览带统计日期与 Asia/Shanghai 时区，发布结果区分已发布/失败/进行中/未确认，缺失日期零值补齐；日志改为服务端搜索、状态筛选与真实分页（带 `type` 时返回 `{records,total,page,size,pages}`，省略 `type` 保持旧结构），热门搜索在 Redis 不可用或缓存为空时回退当日 SQL；概览与日志独立加载、失败重试，30 秒自动刷新且在隐藏标签页暂停。
- 系统设置可在线修改 `resource.search.rate_limit_per_minute`（每用户每分钟 1–60，默认 5，保存后即时生效）；资源序号选择与翻页不计入，选择影片、重新搜索或「查看其他资源」计入，QQ 搜索频率独立。
- 账号自助能力（2026-09-26）：登录页与注册页已全量中英多语言（`login*`/`register*` 文案统一走 i18n，不再硬编码英文）；「我的资料」可修改绑定邮箱，需邮箱验证码且每 90 天限改 1 次，冷却期内返回 `emailChangeAvailableAt` 并由前端提示下次可修改时间；重置密码改为必须邮箱验证码（`POST /api/auth/reset-password/code` 发码，`POST /api/auth/reset-password` 携带 `emailCode`），成功后吊销该账号全部登录设备；`GET /api/auth/me` 追加 `email`、`emailUpdatedAt`、`emailChangeAvailableAt`、`emailVerificationEnabled`。
- 昵称与登录（2026-09-26）：新增 `sys_user.nickname`，站内展示统一使用昵称（空白自动回退用户名），评论与回复、回复通知、资源上传者和管理端用户列表均按此规则；用户可在「我的资料」自行修改昵称（1–20 字符、禁止控制字符），用户名仅用于登录且不可修改；登录标识支持**用户名或邮箱**，注册用户名不允许含 `@` 以避免标识歧义；管理端编辑用户可改昵称，`keyword` 同时匹配用户名、昵称与邮箱。

### QQ 自动化

- 网站资源搜索与 QQ 共用内置拒绝词库 `backend/src/main/resources/moderation/search-blocklist.txt`（220 条覆盖项），叠加 `qq.bot.blocked_keywords` 的实时配置；每次请求读取配置快照，覆盖输入、候选名称与旧候选选择/翻页/换资源。NFKC、大小写、标点/空格/零宽归一化，短英文标签加边界避免 AV 误伤 Avatar；关键词策略仍需维护和人工审核，不能保证识别所有伪装内容。
- QQ 上映判断取各地区最早有效日期；待上映或遗留 `TRAILER` 标记的影片如果已有已审核、正常资源，优先直接返回库内资源，不先搜索外部影片候选或拒绝。

- 搜索先回复「正在搜索资源，请稍后...」，随后返回影片候选与资源候选（优先夸克）；只有用户回复单个资源序号才创建并执行转存任务，成功回复附带影片元数据与最终自有分享。失效分享、无视频文件或平台拦截均回复固定文案并保留候选上下文。
- QQ 每日推荐由 backend QQBot 触发（配置存于 `sys_config`），需与 QQ 官方主动消息权限配合；频道发布由宿主机 CLI 计划任务承担。
- OpenClaw 使用唯一运行时插件副本，升级后必须重新应用补丁（UTF-8 进度文本、管理员白名单、拒绝未授权命令）。

### 多平台发布

- 发布状态保护（2026-10-11 已部署）：按日志 ID 原子认领，外部调用前持久化 PUBLISHING；仅 PENDING/PREPARE_FAILED 可普通重试，PREPARING/PUBLISHING/UNKNOWN/历史 FAILED 不自动重放。管理端 retry 对 POSTED 也返回 409，内部 publisher 则复用已有结果；并非跨资源版本/同影片的全局 exactly-once。完整契约见[发布运维](social-publishing.md)。
- 手机发布管理（<768px）改为可编辑目标卡片和日志卡片；主要操作至少 44px，长文本换行、筛选/分页可用，切换桌面不丢草稿，手机和桌面共用重试保护规则。候选及上线版本各 14 个发布场景通过；无真实发布操作。
- 微博自动发布已启用，健康检查显示 configured/authenticated/ready 均为 true；未手工触发真实帖子以避免重复发布。
- 旧 `secondary`、`qq-v3` QQ 频道目标已软停用（`enabled=0`），保留历史发布审计。

## 配置与安全

- 2026-10-11 业务增量上线只替换四个应用，沿用现有环境/挂载/网络/端口/安全选项；G01–G11 与完整安全门禁仍未关闭，本轮未重跑完整安全扫描，不将应用上线或加密检查点当成整体安全通过。
- 敏感值边界：`.env`、Cloudflare credentials、MySQL defaults、Quark/迅雷/QQ/微博 Cookie 与 token 只报告路径与键名，不写入仓库、日志或本文档。
- 已上线：nginx/backend 仅发布到 loopback，内部 QQ/internal 路径 404，匿名管理接口 401，应用容器非 root，backend/gying-source/social-publisher 使用非 root 数据库账号，MinIO 已接入应用网络且 backend 不再使用 root key。
- 生产安全审计（2026-10-08 续验）：新版 `tools/security/check_security.py --repo . --probe` 为 **59 PASS / 11 FAIL / 0 UNKNOWN**，原 11 项仍全未关闭。比旧 53 PASS 多出的 6 项为预期容器完整性和一个生产网络诊断容器的检查，不是生产整改。新增固定 `G01–G11` 台账，显式 false、缺失/重复证据、Docker 查询失败和 socket 改名挂载不再假通过；详见 `docs/security/hardening-gates.md`。
- 凭据历史：扫描 1,278 个 Blob 命中 105 条规则（跨版本重复），当前工作区扫描为 0；可能有效的凭据仍需在 provider 侧轮换，历史重写另行审批。
- 数据中心：2026-09-28 实测 MySQL 8.0.28，`gying` 库 25 张 InnoDB 表；三个应用容器仍共用 `gying_app`，本机现役凭据连接匹配 `gying_app@%`，grants 为 gying 库 SELECT/INSERT/UPDATE/DELETE/EXECUTE。实测 `require_secure_transport=OFF`、`local_infile=OFF`、`secure_file_priv=NULL`、MySQL/MySQL X bind address 均为 `*`。本轮只读，未修改账号、grants 或数据；MCP 只读身份沿用此前验收。
- `docker compose -f docker-compose.prod.yml config --quiet` 已通过；语法与必需键通过不代表 Firewall、Access、MinIO policy 和恢复门禁通过。
- Redis 与 PanSou 仅作为可重建依赖对待；2026-09-28 Redis 实际仍仅接入非 internal 的共享 `gying-movie_gying-net`，未迁入目标 `cache-net`，该差异已纳入扫描失败计数；未认证 PING 被拒绝。NapCat 不纳入验收。
- 2026-09-28 Windows ActiveStore 复核：Public/Private Firewall 已启用、默认入站 Block、出站 Allow；Domain 未启用，本轮未改动。规则 `GYing-Hardening-20260925-PhysicalSensitiveTCP` 仍在物理接口 `以太网`、`WLAN 2` 阻断入站 TCP 3306/33060/5005/8880/9000/9001（Profile Any）；原非 loopback 监听未改绑。9 月 25 日用户报告同网有线客户端到 `192.168.1.147` 的 6 端口全部 False、网站可打开并注册，仅为历史客户端证据；公网直连/IPv6 入站仍未验收。
- Cloudflare Access（2026-10-08 只读）：既有受保护 DPAPI Token 文件已存在，Token 验证 active、Zone API 200，但目标账号 Access organization/identity providers/applications API 均 403。不是尚未录入 Token，也未证明 Access 已部署；公网匿名管理页面仍 200、管理员 API 401。需核对目标账号的 Access 权限/资源范围，不重复保存或扩大到所有账号，不在聊天发送凭据。云端应用/策略、OTP 和真实登录验收仍未完成。
- 状态文档、Skill 与安全报告只记录键名、状态与结论，不记录原始日志、密钥或完整 SQL 输出。

### 迁移与恢复基线

- 当前在线镜像（2026-10-11）：backend `gying-code-optimization-backend:20261010a`、frontend `gying-code-optimization-frontend:20261010a`、source `gying-code-optimization-gying-source:20261010a`、publisher `gying-code-optimization-social-publisher:20261010a`，业务提交 `362090c`。构建日为 10 月 10 日，实际于 10 月 11 日协调切换；nginx 仅重载，其余 7 个容器身份/启动/重启计数与配置不变。详见 `docs/code-optimization-deployment-20261011.md`。
- 当前应用恢复检查点：`G:/gying-backups/20261011T022133.799192Z` 的 10 个 age 文件 hash/认证解密通过，SQL 25 表及完成标记可读，配置归档 hash 与源文件一致；包含四个应用状态卷和运行参数，不是完整灾难恢复演练。发布/固定旧镜像/回退守护在 `E:/gying-tools/releases/code-optimization-20261010`；新发布状态、记录或配置变化必须先人工核对，不能仅回退某个发布组件。

- 历史 P2P 部署基线（2026-10-06）：backend `gying-p2p-backend:20261006d`、frontend `gying-weekly-transfer-frontend:20261005a`、source `gying-p2p-source:20261006b`。本轮仅替换 backend/source；nginx 平滑重载，其余 8 个容器 ID、启动时间、重启计数不变，所有环境与挂载保持。无架构迁移。功能提交 `bdc0ada`、夸克 PDS 上传兼容 `7f907c7`、成功重试错误清理 `228617b`；最新 JAR SHA-256 与测试产物一致。发布材料 `E:/gying-tools/releases/gying-p2p-20261006`；`rollback-d.ps1` 可回退后端到 c，`rollback-b.ps1` 可回退到初版 P2P 采集 a，原周榜镜像也保留。最新检查点 `G:/gying-backups/20261006T115631.510103Z` 的 7 个 age 文件 hash/认证解密通过、SQL 25 表可读；本轮未做恢复演练，也不覆盖远端网盘回滚。
- 当前 GYING 数据源为 `gying-code-optimization-gying-source:20261010a`，2 MiB 限额已前置到登录/PoW 的 text/json 探测之前，并覆盖 GET 重试。系列搜索、紧凑 BT 筛选、受保护种子读取与非 root 身份保持；10 月 8 日上线修复补强精确身份和季/Part 解析。`movie_source_identity.external_id` 已为 `utf8mb4_bin`，续验确认 `dWXo`（卡萨布兰卡）与 `dwXo`（蝙蝠侠）各绑定独立影片，身份 season 均为 0；剧集自然批次后复核《大明王朝1566》主表 season 已为 1。
- 迁移快照 `migration-data\20260914-081539`：SHA-256 清单 4832/4832 通过，缺失 0、不匹配 0；迁移时点 `movie_metadata=1631`、`resource_link=2165`，迁移前回滚备份 `E:\gying-data\gying-pre-deploy-20260914.sql`。
- 已恢复的持久化数据：MinIO、backend-data、social-publisher 两个凭据卷、quark-auto-save 配置、OpenClaw 配置/认证与本机 MCP 配置；backend 日志只归档未恢复。
- 回滚材料包含 MySQL dump 与 `.env` 的 Windows DPAPI CurrentUser 加密副本，仅能在原主机/账号解密，不等同异机灾难恢复；未执行 `docker compose down -v`，未删除任何卷。
- 2026-09-25 完整逻辑/持久数据备份为 `G:/gying-backups/20260925T030328.719395Z`：19 个 age 文件、约 372 MiB，manifest=`complete`，hash/认证解密全部通过。使用 `gying_backup@localhost` 导出 gying 库（包含触发器/事件/例程选项；实测这些对象及视图均为 0），归档 MinIO、六个状态卷、当前部署覆盖、受限备份配置、环境、Cloudflare、防火墙导出及加密 Docker 运行配置；不包含 MySQL 系统账号库或远端网盘事务。
- 备份窗口为 11:03:25–11:03:56 Asia/Shanghai：6 个原始写入容器暂停约 31 秒后全部解冻，主机发布/同步计划不与窗口重叠；数据库行数/校验和及关键配置在窗口前后稳定。未重建生产容器，重启计数未变；早先 `security-20260925-partial` 保留为历史候选，不能混作当前完整备份。
- 独立 MySQL 完整逻辑恢复的 25 张表行数与逐表 CHECKSUM 均一致，数据库对象数量和中文往返通过；无网络 MinIO 恢复后健康正常、1 张公开图片 SHA-256 一致、私有元数据 403。测试 MySQL 已关闭、MinIO 测试容器已移除；受限恢复目录 `G:/gying-tools/security-20260925/{full-mysql-restore,full-minio-restore}` 保留。早先候选恢复副本清理被执行策略拒绝，本轮没有绕过重试。

## 仍需处理

- **手机与发布边界**：当前证据为 Headless Edge 响应式视口，不等于 iOS Safari/Android 真机、真实账号编辑或外部发布验收。UNKNOWN/崩溃在途记录仍需人工核对；跨资源版本同影片去重和海报响应完整缓冲仍需独立改造。
- **入口波动（2026-10-11）**：上线前 10:12:35 本机正常、公网短暂 530；未作任何 Tunnel/代理/DNS/凭据变更即自行恢复，连续 7 轮入口/拒绝检查通过后才开始切换。上线后入口正常，但间歇故障根因未关闭；不把本次部署当作该故障修复。
- **失败隔离线上分支待续验（2026-10-08）**：停滞修复与二进制来源身份迁移均已上线；动漫 page1 offset0→page1 offset20（failed 0 / deferred 0）；电影 page5 offset40→page6 offset0（failed 0 / deferred 0）；剧集 page2 offset0→page2 offset20（failed 0 / deferred 0）。自然批次尚未触发失败分支，线上隔离重试仍未实证；不为补验收而制造生产错误或重放旧队列。发布/回滚材料在 `E:/gying-tools/releases/p2p-workflow-recovery-20261008`，应用回滚保留二进制身份排序规则。
- **历史磁力/种子失败队列（2026-10-08 续验）**：截至 2026-10-08 20:03:56+08:00，归档任务 228 条、成功 170、失败 58；既有耗尽重试的 58 条（夸克 4、迅雷 54）未重置。部署后新增归档资源 8 条，其中 8 条活动/已审核/NORMAL；不以资源行代替本轮远端分享内容复验。旧失败阶段及受控恢复约束见 `docs/p2p-cloud-archive.md`；恢复需明确任务清单，不重放历史视频/发布队列。

- 元数据/周榜与迅雷后续验证（2026-10-05）：新版本已按用户授权部署，安全复核仍 53 PASS / 11 FAIL / 0 UNKNOWN，既有安全整改未完成。首次周榜执行仍待观察，未手动触发周榜视频转存/社交发布；GYING 自然补采状态见下条。迅雷根目录 798 个视频中 67 个有精确任务关联，历史迁移需重新核对分享绑定、备份原父目录并确认；夸克 2026-10-04 清单中 656 个发布关联目录保留，56 个未匹配发布仅人工确认、30 个空目录仍有追更，不能直接删除。公网浏览器自动化间歇 ERR_CONNECTION_CLOSED，完整 4 场景未验收；curl IPv4、Tunnel ready 和本地生产页面回归正常。

- **周榜首次计划待观察**：此前记录的三个周榜首次计划为 2026-10-12 11:50 左右，尚未手动触发视频转存或社交发布；GYING 高频元数据停滞已修复，最新自然游标证据见本节首条，不再把旧卡死游标当作当前状态。

- **历史入口故障观察（2026-10-06 复核）**：本轮宿主 backend、nginx、本地/公网首页与影片详情/API 均正常；公网详情已显示磁力种子双盘资源。未更改 Tunnel、代理/DNS、防火墙或凭据，不把一次验收认定为历史间歇故障根因已消除。2026-09-28 遗留的 Resource Hub/Xunlei RUNNING 记录未重置或重放，QQ 与历史视频任务全链路仍需独立验收。

- **GYING 发布写入仍需验收**：单片全账号遍历导致的超时已修复并部署，真实 backend→source 单片查询实测 7.68 秒；目录和搜索正常。新 source 的自动 `/publish` 请求中另观察到 2 条 `RuntimeError` 上游错误，尚不能仅凭通用日志区分重复提交提示、网站拒绝或发布后复核失败。未手工重放发布，真实写入不标记通过；后续需按单条任务核对远端结果后再决定重试，避免重复副作用。
- **迅雷配图实盘验收**：夸克配图复制已通过失败任务 `1583` 和 `1597` 的受控重试验证；`1583` 在部署后实际走完复制分支（原目录 0 张图/10 个视频，复核为 1 张图/10 个视频），分享令牌有效；迅雷指定位置源图已只读确认存在，但仍需选择可丢弃的迅雷临时资源或采集任务复核真实复制、重试幂等及最终分享可访问。
- **Access 授权范围待核对**：2026-10-08 已确认本机有可解密的 active Token，Zone API 可读，但目标账号 Access 三类 API 均为 403。用户需核对该目标账号 Access: Apps and Policies 及 Organizations/Identity Providers/Groups 的权限和资源范围；不重复录入现有 Token、不自动轮换/覆盖凭据。调整授权后仍先只读检查组织/OTP/既有应用冲突，再审查创建、真实登录及回退方案。
- **安全加固门禁（Critical/High）**：按 `docs/security/deployment-checklist.md` 完成 Windows 防火墙公网/IPv6 入站验收与敏感端口改绑、DB 分服务身份与 grants、MinIO policy 与 root key 轮换、OpenClaw 接入内部网络、Cloudflare Access/WAF、Quark ACL 与 Cookie 轮换、加密备份与恢复演练；不得把部分上线写成整体安全闭环。
- **端口改绑前置依赖（2026-10-08 复核）**：backend 的 `MINIO_ENDPOINT` 与 source 的 `GYING_MINIO_ENDPOINT` 仍经 `host.docker.internal:9000`；先迁移并验证内部调用，再改绑 MinIO，不能仅凭 alias 已存在操作。现有 Quark root-owned 私有配置卷须单独验证非 root 迁移；四个空数据隔离候选启动成功不覆盖生产卷。Access Token 已 active 但目标账号 API 403，分服务身份/policy 与恢复门禁也未闭环，不批量重建。
- **生产与目标配置差异**：quark 5005、独立 MinIO 9000/9001 仍监听非 loopback；OpenClaw/Redis/quark/PanSou/MinIO 缺少 `no-new-privileges`，其中 quark/PanSou/MinIO 存在 UID 0 进程；Redis 仍在共享网络而非 internal cache-net，需备份后逐项收紧。
- **恢复门禁剩余项**：专用备份账号/私有 defaults、19 文件完整逻辑与持久数据备份、短暂冻结窗口和隔离 DB/MinIO 恢复已验证。仍需应用全链路、MySQL 系统账号重建、异机恢复及私钥离线保管；本机隔离验证不是整机灾难恢复。age 位于 `G:/gying-tools/age-v1.3.2`，配置为 `G:/gying-secrets/backup-config.json`；私钥在 F 盘受限目录且仍在线，不得在聊天中提供。
- **防火墙剩余验收**：备份账号与防火墙单步已完成，不要重复创建账号或再次执行 `-Apply`。已实施尝试为 `G:/gying-tools/security-20260925/firewall-attempts/20260925-113327-6e2eac1b/`，包含变更前策略导出、成功结果与独立核验；活动标记已清除，watchdog 已退出，未回退。已取得用户报告的同网有线电脑 IPv4 测试：上述 6 端口均 False，网站可打开并可注册。该结论仅覆盖该客户端到 `192.168.1.147` 的不可连接结果，未单独排除路由/客户端隔离，也不是公网直连或 IPv6 测试；后两项继续保留待验收。需要撤销本次变更时，管理员使用配套 `Rollback-FirewallStep.ps1 -AttemptDirectory` 指向该目录，只恢复本次规则与 Public enabled/default-inbound，详见部署清单。
- **迅雷登录失效后续验收**：2026-10-08 新版真实浏览器刷新与 Token 同步已上线，官方只读 Drive API、同账户写回、计划任务再次运行均通过；已恢复原两小时及登录触发。当前会话已登录，未强制登出测试保存密码提交/验证码流程；需观察自然失效后的单次自动填充登录及长期调度，backend 独立 refresh-token 续期也尚未单独触发。详见 `docs/xunlei-token-sync.md`。
- **Docker 运行目录与磁盘**：`docker_data.vhdx` 已于 2026-09-25 由 E 盘迁移至 `G:\dockerdesktop\wsl\DockerDesktopWSL`，E 盘由约 3.1 GiB 恢复至 47.3 GiB、G 盘余约 220 GiB。
- **数据质量**：历史遗留的乱码任务、重复目录和无视频分享需按资源价值逐批人工确认，优先 dry-run 与软删除。
- **外部依赖稳定性**：GYING 图片源和部分外部网盘接口存在偶发超时、风控或响应结构变化，需保留重试与失败审计，不把单次 HTTP 200 视为业务成功；持续观察 GYING BT 登录态、PoW 与响应结构变化，认证失效时只更新外部登录态、不降低采集频率。
- **QQ 临时转存清理**：代码、数据库迁移与安全边界测试已完成，仍需在群内真实搜索并选择一个可丢弃资源，复核到期后夸克/迅雷临时目录被删除且正式目录不变。
- **邮箱验证码**：已启用（`auth.email_verification.enabled=true`）。Resend 发件域 `gyinghub.dpdns.org` 已验证，地址 `noreply@gyinghub.dpdns.org`（DKIM/SPF/DMARC 生效）；163 收件实测 `delivered`。Gmail 对 `dpdns.org` 免费域返回 550 unsolicited/mail blocked，多次内容调整仍被拒，Gmail 用户注册仍可能收不到验证码；待域名声誉建立或换用自有域名后可缓解，无需改代码。
- 豆瓣评分没有稳定官方 API，不作为生产链路强依赖。

## 长期不变量

- 发布结果不确定时保留审计状态，不能把 UNKNOWN/PUBLISHING/历史 FAILED 批量改回 PENDING；backend 与 publisher 的状态机版本必须协调部署/回退。
- `movie_metadata` 是影片主表，`resource_link` 是可用资源表，`movie_source_identity` 保存外部来源绑定；GYING 外部 ID 与本地 canonical 影片 ID 必须区分。
- 资源状态与发布状态必须可追踪；失效修复优先原位更新，避免同片产生多条活动自有分享。
- 自动化只处理明确匹配的影片与资源，不确定匹配必须转为人工候选。
- 删除、迁移与重复清理默认 dry-run，并保留可回滚的软删除或迁移记录。
- MySQL `global/session time_zone=SYSTEM`，与 Asia/Shanghai 一致（`NOW()` 与 `UTC_TIMESTAMP()` 相差 8 小时）。
- 任务已注册不等于已运行；被禁用的调度器、Worker、计划任务与机器人必须在文档中显式区分。

## 验收

- 代码优化与手机适配（2026-10-11 已部署）：候选镜像内 source 54、publisher 31 项通过；候选与生产 nginx 上分别通过发布 14 + 首页 10 个 mock 浏览器场景。本机/公网 8 项真实入口、backend→source/publisher、frontend→backend 链路通过；四应用实际镜像/UID/环境/挂载/网络复核一致，日志无 ERROR/Exception。发布行与目标配置守护保持不变，未执行 SQL 迁移、重放历史任务或真实发布。业务全量 523 项（5 跳过）等实现阶段证据及本次恢复材料见 `docs/code-optimization-20261009-round2.md`、`docs/code-optimization-deployment-20261011.md`。

- 后台监控与资源分类计数（2026-10-11 已部署）：候选镜像 `gying-monitoring-backend:20261011a`（提交 `de83b39`，镜像内 `app.jar` SHA-256 与测试产物一致）与 `gying-monitoring-frontend:20261011a` 只替换 backend/frontend。后端全量 557 项测试（5 跳过，含 4 项隔离 MySQL 集成）、监控 16 个与资源计数 10 个浏览器场景在隔离环境、候选容器与生产 nginx 入口均通过；真实影片 `tmdb_movie_969681` SSR 实测网盘 2、磁力/种子 126、provider 分组 2/1/1，与数据库明细一致。切换仅停 backend 生成加密检查点（8/8 产物认证通过，目录 `G:\gying-backups\20261011T124714.996872Z`），未改 schema、未迁移、未重放发布、未做真实转存或发帖；9 个未改动容器的 ID/启动时间/重启计数/镜像与基线一致，回滚守护二次通过（`social_post_log` 193 POSTED/12 FAILED 未变）。安全扫描仍为 59 通过/11 失败，本次为已披露的业务例外发布，未关闭任何安全门禁。详见 `docs/monitoring-resource-counts-20261011.md`。

- 2026-10-08 及以前的测试、部署和恢复记录已移至[历史运维验收](history/operations-through-20261008.md)，保留原日期和未验证范围；归档不关闭本页待处理事项或安全门禁。

可重复执行的验收按范围选择，不在生产检出目录直接启动开发环境：

- 代码与手机/桌面回归：[开发与测试](development.md#3-常规回归入口)。
- 部署前后真实入口、固定镜像和回滚核验：[部署与回滚](deployment.md)。
- 安全只读扫描与固定台账：[安全门禁](security/hardening-gates.md)；不要把仓库就绪检查当成完整安全扫描。

## 常用校验

- [运行态快照](../.agents/skills/gying-project-ops/scripts/collect-ops-snapshot.ps1)与[部署前就绪检查](../.agents/skills/gying-project-ops/scripts/test-ops-readiness.ps1)：按运维范围执行，不替代数据/安全/外部副作用验收。
- 替换上游容器后先校验 nginx 配置再平滑重载，避免缓存旧 IP；通常无需重建 nginx，具体命令见[部署](deployment.md#3-compose-与最小替换)。
- 本机端口 3009/3019/3029 位于 Windows 排除段（2970–3069），前端隔离验收改用 18080。
- PowerShell 读取本文档需显式 UTF-8（Windows PowerShell 5.1 用 `Get-Content -Encoding UTF8`）。
