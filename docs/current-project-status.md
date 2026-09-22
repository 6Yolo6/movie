# 当前项目状态

更新时间：2026-09-22

本文只记录生产环境当前能力、运行约束、待处理事项和少量可复核的验收证据。一次性任务编号、重复部署过程和基础接口状态不在这里长期保留，详细操作以 `docs/api.md`、`docs/deployment.md` 及运维参考文档为准。

## 当前目标

- 维护电影、剧集、动漫和用户提交资源的统一片库。
- 通过 TMDB、GYING、PanSou/Panso API 与网盘自动化服务补全元数据和可用资源。
- 自动转存并生成夸克、迅雷自有分享，经过校验后再写入正式资源库。
- 支持 QQ 群搜索、资源候选选择、频道发布和多平台发布审计。

## 运行架构

- 目标对外入口：Cloudflare Tunnel `gyinghub.dpdns.org` → 本机 loopback nginx → Next.js 前端/Spring Boot backend。**2026-09-21 只读复核仍观测到 nginx `0.0.0.0:80/443`、backend `0.0.0.0:8880`、quark `0.0.0.0:5005`、MinIO `0.0.0.0:9000/9001`；本次安全分支的 loopback/内部网络改动尚未部署。**
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
- “爬取我已发布资源”按账号 `/my-resources` 分页读取，复用现有片库和资源工作流；已存在的来源 ID 或 URL 自动跳过。
- 数据源请求带有统一间隔限制，图片下载和站点请求均支持超时、重试和失败记录。
- GYING 资源发布仅使用固定契约 `/res/pan/add`，绑定字段为 `binds[0][dir]` 与 `binds[0][id]`。
- GYING Source 内部接口新增 `/catalog?sort=cscore` 和 `/bt/{btId}`；请求仍受统一请求间隔、PoW 和登录态约束，认证失败只记录错误类别，不输出 Cookie 或认证材料。管理端 GYING Source 新增“元数据同步”功能，可按 `mv/ID`、`tv/ID`、`ac/ID` 或默认类型批量同步最多 60 个 GYING 影片元数据，仅同步元数据、海报和来源绑定，不触发转存或发布。
- GYING 资源入库已增加归属保护：网盘资源只有明确属于 `GYING_TARGET_USER` 的分享才进入正式片库；公共 GYING/PanSou 网盘结果只作为候选，P2P 磁力/种子仍可按实际链接入库。provider 优先由分享 URL 主机识别，避免并行数组错位被写成 `OTHER`；名称按索引读取并限制为 255 字符。
- 2026-09-22 已对确认错误的 `resource_link` 记录 2560-2564 执行软删除，未物理删除；操作前备份位于 `E:\gying-tools\backups`，文件名以 `gying-pre-gying-resource-fix-20260922-112027.sql` 开头，SHA-256 清单同目录保存。
- 新增登录用户网页端 `/resource-search`：复用 QQ 的 GYING/PanSou 候选、序号继续选择、夸克/迅雷转存、自有分享返回和二维码展示；使用 Redis/QQ 搜索频控配置，临时转存继续使用专用目录与清理任务，正式 Resource Hub 资源不参与清理。

### 注册、邀请与后台监控

- 公开注册由 `auth.register.enabled` 控制；当前生产值为关闭。注册固定要求有效邮箱并由数据库唯一索引阻止重复邮箱，新账号仍固定为普通 `USER`。
- 邮箱验证码采用可选 Resend/Brevo 事务邮件接口；只有 `auth.email_verification.enabled=true` 且本机已配置发件账号时才显示和强制校验。当前未配置邮件服务，因此验证码功能关闭，不保存或输出邮件凭据。
- 关闭公开注册后，有效邀请码仍可注册。管理员或满足账号注册时长的老用户可按配置周期生成有限数量的邀请码；数据库只保存邀请码 SHA-256，完整邀请链接仅在创建时返回。
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

### 2026-09-20 安全审计状态

- 根目录 `docker-security-report.md` 和 `docs/security/` 已建立，覆盖 Docker、Cloudflare Tunnel/Access、Spring Boot/API、MySQL、Redis、MinIO、Quark、QQ Bot、备份、事故响应和部署门禁；静态 Controller 清单位于 `docs/security/api-inventory.md`，共 146 个映射，不等同于动态渗透测试。
- 已写入但尚未上线的加固包括：loopback/内部 Docker 网络、nginx 路径拒绝与限流、非 root/无 socket、Redis ACL 和 fail-closed 限流、生产凭据启动校验、精确 CORS、JWT 会话校验、QQ/GYING/social 内部 token、MCP 只读门禁、加密备份工具和 secret 扫描。
- 在线风险仍未闭环：MySQL 应用会话为 `root@localhost`；MySQL 3306/33060 与 MinIO 9000/9001 监听所有地址；Windows Public Firewall Disabled；MinIO 使用 root identity 且匿名策略覆盖全部 `gying/*`；Cloudflare 账号侧 Access/WAF 未审计；Git 历史仍有凭据命中；Quark Cookie 卷权限和备份恢复尚未完成迁移。
- 本次没有修改生产 `.env`、Windows Firewall、MySQL 用户、MinIO policy/key、Cloudflare 账号策略、OpenClaw 运行配置或生产卷；因此不能把本节的代码能力写成“已部署”。

### 迁移与恢复基线

- 当前在线生产基线仍记录为 `master` 分支、部署提交 `198a26a92bdeb66931d0d60fc43d4fee4496a43b`；本次审计工作区为 `codex/security`，基线提交 `65d94715d99ebe5a7c3ed288e3c7f65f33ecb100`，未部署到生产。Docker Desktop 数据路径为 `E:\dockerdesktop\wsl\DockerDesktopWSL`。
- 2026-09-14 已恢复迁移快照 `migration-data\20260914-081539`：SHA-256 清单 4832/4832 条通过，缺失 0、不匹配 0、错误 0；完整 SQL 及持久化数据均保留在仓库外的受保护目录。
- MySQL `gying` 已恢复并通过 MCP 复核 18 张表；`movie_metadata=1631`、`resource_link=2165`，中文片名和简介抽样可读。迁移前回滚备份位于 `E:\gying-data\gying-pre-deploy-20260914.sql`。2026-09-21 已创建数据库范围受限的 `gying_app`（应用 CRUD）和 `gying_readonly`（MCP 只读）账号；本机 MCP 配置已切换到 `gying_readonly`，直连验证可读且写入被拒绝。
- MinIO、backend-data、social-publisher 两个凭据卷、quark-auto-save 配置、OpenClaw 配置/认证和 MCP 本机配置均已恢复；backend 日志只归档未恢复。未执行 `docker compose down -v`。
- 旧生产配置曾通过 `docker compose -f docker-compose.prod.yml config --quiet`；安全分支新增的必需键（专用 DB/Redis/MinIO/内部 token 等）尚未补齐，因此当前 hardened Compose contract 有意失败，不能在账号和密钥迁移前重建生产。
- NapCat 仍未恢复、未启动、未纳入验收；Redis 和 PanSou 仅按可重建依赖运行。

## 仍需处理

- **安全加固上线门禁（Critical/High）**：按 `docs/security/deployment-checklist.md` 完成 Windows 防火墙和敏感端口收紧、MySQL 专用账号、MinIO scoped key/policy、MinIO 网络 alias、OpenClaw 内部地址、Cloudflare Access/WAF、Quark ACL/Cookie 轮换、加密备份和恢复演练；完成前不得宣称生产已加固。
- **当前生产与目标配置存在明确差异**：`tools/security/check_security.py --repo . --probe` 在旧容器上仍观测到 backend 8880、nginx 80/443、quark 5005、MinIO 9000/9001 的非 loopback 发布；内部 QQ health 仍可直接返回 200，目标配置应为 nginx 404。
- **数据库**：在线旧容器仍使用 root；`require_secure_transport=OFF`，密码策略未确认。目标 `.env` 已切换到 `gying_app`，但生产容器尚未重建，需部署后再验证应用链路并保留 root 作为受控 break-glass。
- **对象存储/网盘**：MinIO 匿名公开范围和 root identity 需收紧；quark-auto-save Cookie 仍持久化在配置/状态，必须 ACL、加密备份和轮换。
- **凭据历史**：历史扫描 1,278 个 Blob 有 105 条规则命中（跨版本重复）；当前工作区扫描为 0，但所有可能有效凭据仍需 provider 侧轮换，历史重写另行审批。
- 迅雷短期凭据已改为 Edge 会话自动同步并通过计划任务复验；2026-09-22 手动运行同步脚本退出码为 0，计划任务脚本已避免无关 Compose 环境变量插值失败，并跳过易锁定的非必要 Extension State 目录。仍需监控 Edge 登录态、站点风控和接口结构变化，若独立浏览器会话退出或出现交互验证，需要人工重新登录后再恢复无人值守刷新。
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
- 本次修改已完成 backend Docker 编译、`QqBotServiceImplTest` 分页/多候选回归和全量 Maven 测试；候选上限调整为 30 条。2026-09-21 已生成本机 backend↔GYING Source 内部 `GYING_SOURCE_API_TOKEN`，并创建/验证非 root 数据库账号；生产部署预检查目前仅剩受保护 `.env` 中 `QUARK_COOKIE` 为空，未替换生产容器。

### 2026-09-20 验收补充

- 修复综合评分任务被代码硬编码为至少 72 小时的问题；后台 `resource.hub.gying.auto_sync_interval_hours=1` 已真实生效。2026-09-20 10:39 创建的 `CSCORE_ANIME` 轮换任务于 10:41 完成，状态为 `SUCCEEDED`；其中 1 条 GYING 项目失败，已保留在任务错误摘要中。
- 迅雷自动更新计划任务 `GYing Xunlei Token Sync` 已验证为启用、每 2 小时执行，2026-09-20 10:33 最近一次运行返回成功；同步日志仅记录状态，不记录 Authorization、Cookie 或密码。此前失败原因为自动化 Edge Profile 未继承有效登录态，现已改为复制默认 Edge 会话到临时 Profile 后执行。
- 生产重建并部署 backend、frontend、gying-source，nginx reload 成功；公网首页和留言接口返回 HTTP 200，`gying-source` `/health` 返回 200，`/bt/VyyO8` 当前可返回 P2P 资源。以上是安全分支之前的业务部署证据，不代表本次安全改动已重建生产。

### 2026-09-20 安全审计验收（工作区/隔离环境）

- 隔离 Maven/Redis 测试 194 项通过（0 failures/errors/skips）；新增 `sys_config` 脱敏/拒绝敏感写入和 QQ 限流下限测试；隔离 nginx 测试验证 UID 101、编码/矩阵路径拒绝、隐藏文件/私有媒体拒绝、媒体 query 丢弃、代理头清洗和 API 限流。
- 安全分支新增 `sys_config` 脱敏边界：管理员读取 token/cookie/password/secret 类配置只得到 `[REDACTED]`，运行时写入被拒绝；QQ 每用户限流配置在运行时至少钳制为 1 次/分钟。该代码尚未部署到生产。
- frontend 和 social-publisher `npm audit` 均为 0 findings；当前工作区 secret scan 为 0 findings；历史 secret scan 仍为 105 条跨版本命中。
- npm audit（frontend/social）为 0，但 Docker Scout 对隔离目标镜像仍发现 frontend 10 条、social 58 条、source 1 条 critical/high；现有 backend 镜像的 SARIF 已发现 17 个受影响包、61 个唯一漏洞（13 Critical、48 High），且其依赖集仍包含 Spring Boot 3.2.0，不能代表工作区 3.5.16 目标镜像。完整 JVM/Python/OS 镜像 CVE triage 和外部攻击面扫描尚未形成闭环，不能把 npm audit 0 当成镜像清洁。公网 Cloudflare Access/WAF、DB grants、MinIO policy、Firewall、加密备份恢复仍待生产维护窗口验证。
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

- 资源链接按“影片 + 链接”去重，同一网盘链接可绑定不同季/影片；管理员和影片详情分享表单支持 URL 自动识别网盘、一键粘贴分享文案、快速标题参数和同系列绑定标记。
- 同系列绑定会按当前影片的系列或 TMDB 集合提供可搜索多选候选，提交后为每个勾选季创建同一分享链接；快速标题参数由 `resource.form.quick_params` 集中管理，支持在系统设置中新增、修改、删除。
- 绑定搜索输入关键词时会跨影片标题、英文名、别名、系列名和影片 ID 检索，避免因历史影片缺少系列字段而无结果；空搜索仍优先展示同系列候选。
- 快速参数中的 `[影片名]` 是动态占位项：在影片详情页插入当前影片中文名，在管理员表单插入当前选中影片的中文名，不会写入字面量占位符。
- 迅雷分享文案中的提取码会自动补到 URL 的 `?pwd=`/`&pwd=` 参数；转存发布生成的资源名会带影片类型前缀，例如 `【动作犯罪】`。
- 2026-09-07：GYING 最近更新的确保/发布流程已独立检查夸克和迅雷，分别发布缺失 provider；迅雷服务不可用时明确失败，不再静默使用夸克代替。
- 2026-09-07：已发布资源健康接口提供 `mv/<movie-id>` 或 `tv/<movie-id>` 形式的 canonical `gyingResourceId`，可直接用于按 ID 修复，同时兼容旧 panlist ID。
- 2026-09-07：健康修复会按 provider 分别解析夸克和迅雷转存任务。
