# 当前项目状态

更新时间：2026-09-25

本文记录生产环境当前能力、运行约束、待处理事项与可复核的验收结论。一次性任务编号、单次发布流水和基础接口状态不长期保留；接口契约见 `docs/api.md`，部署与恢复流程见 `docs/deployment.md` 和 `.agents/skills/gying-project-ops/references/`。

## 当前目标

- 维护电影、剧集、动漫和用户提交资源的统一片库。
- 通过 TMDB、GYING、PanSou 与网盘自动化服务补全元数据和可用资源。
- 自动转存为系统自有夸克/迅雷分享，校验后写入正式资源库。
- 支持 QQ 群搜索、频道发布和多平台发布审计。

## 运行架构

- 对外入口：Cloudflare Tunnel `gyinghub.dpdns.org` → loopback nginx（`127.0.0.1:80`）→ Next.js / Spring Boot（backend `127.0.0.1:8880`）。Tunnel 配置在 `E:\gying-tools\cloudflared\config.yml`，由登录触发的计划任务 `GYing Cloudflare Tunnel` 启动。
- 核心服务：`frontend`、`backend`、`gying-source`、`social-publisher`、`nginx`；依赖 MySQL、Redis、MinIO、PanSou、`quark-auto-save`、OpenClaw QQBot。
- 生产 Compose 文件为 `docker-compose.prod.yml`（无默认 `docker-compose.yml`）；容器与 JVM 时区统一 `Asia/Shanghai`。本机 Docker CLI 位于 `C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe`。
- 代码与 Python 环境在 `D:\gying-movie\movie`；Docker Desktop 数据（`docker_data.vhdx`）位于 `E:\dockerdesktop\wsl\DockerDesktopWSL`。
- QQ 频道发帖由宿主机 `tencent-channel-cli` 计划任务执行，不经过 `social-publisher`；NapCat 已停用，不作为备用通道、迁移依赖或验收项。
- Cloudflare Tunnel 使用 HTTP/2 传输（计划任务参数 `--protocol http2`）：本机代理以 fake-IP 方式解析 `cfd.argotunnel.com`，QUIC/UDP 会被代理丢弃。健康检查用 `http://127.0.0.1:20241/ready` 或公网首页状态码；`E:\gying-tools\cloudflared\config.yml` 的 ACL 对当前用户只读，改配置需提权。
- 现役开关（2026-09-15 复核，`.env` 与 `sys_config` 一致）：`RESOURCE_HUB_WORKER_ENABLED`、`QQ_BOT_ENABLED`、QQ 频道自动发布、微博自动发布及夸克/迅雷自动转存计划任务均为启用；`QUARK_AUTO_SAVE_RUN_IMMEDIATELY=true` 确保先完成实际转存再创建自有分享；quark-auto-save 定时规则为 `0 8,18,20 * * *`。
- **Docker Desktop 启动约束**：`%LOCALAPPDATA%\docker-secrets-engine\engine.sock(.stale)` 是内核层失效的 reparse 项，无法删除或改名，按默认路径启动会在 Secrets Engine 初始化时失败。当前以独立运行目录 `G:\gying-tools\docker-runtime-recovery` 启动绕过，镜像与卷位置不变；下次重启 Windows（必要时 `chkdsk C: /f`）后应清理并恢复默认启动方式。

## 已具备能力

### 片库与治理

- 分页、分类筛选、详情、收藏、评论、站内通知与管理员审核；影片、资源链接和来源身份均支持软删除并保留历史。
- 首页三个分类按资源最近更新时间优先，并综合站内热度、TMDB 热度与可用评分；搜索/筛选页保留最新与评分排序。
- 顶部搜索框提供「最近热门搜索」（近 7 天 Redis 热词合并，回退 `site_search_log`，公开接口 `GET /api/movies/hot-searches`）；搜索页与电影/剧集/动漫分类页的筛选项默认收起，仅常显排序，有已选条件时显示可逐个关闭的摘要与「清除全部」。
- 新注册账号固定为 `USER`，新增/修改/删除资源仅 `PUBLISHER` 与 `ADMIN` 可执行；管理员可在 `/admin/users` 直接建号（USER/PUBLISHER），不受邀请码与注册开关限制。管理员发布资源不计入 `resource.max.per.user`。
- 影片元数据支持缺失海报自动补图；GYING 不可用时自动补图回退 TMDB 搜索、补齐剩余季回退 PanSou，不直接报错。
- 用户内容、注册/邀请、登录设备、留言与评论管理均已上线；Resend 已接入已验证的 `gyinghub.dpdns.org` 发件域并启用邮箱验证码，163 等邮箱投递正常，Gmail 因免费域声誉暂拒收。
- 前端品牌为「影窝」，切换英语语言时显示「FilmNest」，两者不并排展示；站点图标含 SVG favicon 与 Apple touch icon。首页热门轮播跟随明暗主题（浅色白底、深色黑底），手机端隐藏海报与长简介，保证文字与操作按钮完整可用。 首页与电影/剧集/动漫分类页不显示片库结果总数，仅在关键词搜索时显示匹配数量。

### 影视资源中心

- 搜索合并本地 PanSou 与外部 Panso 结果并按 URL 去重；自动资源必须先转存为系统自有夸克/迅雷分享，再写入 `resource_link`。
- 夸克任务支持目录创建、剧集目录更新、失效分享重试与周转存（`update_subdir: ".*"` 递归追踪同名目录新增文件）；迅雷使用官方 Drive API 校验分享、遍历目录并筛选视频文件，Authorization 为短期凭据，支持运行时更新与 refresh token 优先。
- 管理员资源管理对失效或疑似失效的夸克、迅雷资源提供「修复并重分享」；成功后原位更新链接，并在存在 GYING 映射时同步发布。
- 资源编辑可提交 `bindMovieIds` 追加最多 50 个影片绑定，更新与追加在同一事务完成，按影片与 URL 跳过已有资源且不删除旧绑定；快速标题参数由 `resource.form.quick_params` 集中管理。
- 「已发现」转存任务支持单条与批量延迟重跑（每天 08:30 Asia/Shanghai 调度）；本轮存在 Authorization 过期或不可用的迅雷任务时整轮跳过，不启动转存；发布成功后继续同步 GYING，重跑数量与间隔由 `RESOURCE_HUB_DISCOVERED_RETRY_*` 控制。
- GYING 目录自动同步支持热门与综合评分（电影/剧集/动漫，六来源轮换），每轮上限由 `resource.hub.gying.auto_sync_max_items` 控制（当前 15），实际间隔遵循 `resource.hub.gying.auto_sync_interval_hours`（当前 1 小时）。
- GYING BT 详情页解析真实 `magnet` 与 `.torrent` 地址，以 `MAGNET`/`TORRENT` + `provider=P2P` 保存；网盘并行数组按索引读取资源名，写入 `resource_link.name` 的路径统一限制 255 字符。
- 资源链接按「影片 + 链接」去重，同一网盘链接可绑定不同季或影片；分享表单支持 URL 自动识别网盘、一键粘贴分享文案与同系列绑定。

### GYING 数据源

- 支持按类型和模式搜索、目录同步、元数据导入、资源发布与已发布资源抓取；发布使用固定契约 `/res/pan/add`（`binds[0][dir]` + `binds[0][id]`），不把类型与 ID 拼入路径。
- 上游不可用（连接错误、5xx、429、鉴权失效）时影片元数据操作不直接失败：补齐剩余季改用 PanSou 兜底（夸克+迅雷候选，按剧集名或 TMDB ID 匹配、跳过已有资源、按季号最多 5 季逐季转存入库），自动补图回退 TMDB 搜索；结果以 `mode=PANSOU_FALLBACK`、`gyingUnavailable=true` 与 `{discovered,completed,skipped,failed,reason,items}` 返回，全部失败记 `FAILED`，否则记 `SKIPPED`。
- 读取连接/响应超时为 3/20 秒，失败后 30 秒短时熔断并自动允许重试；AUTO/QQ 搜索继续尝试 PanSou，补充来源失败不丢弃已有候选。
- 归属保护：只有明确属于 `GYING_TARGET_USER` 的网盘分享进入正式片库，公共 GYING/PanSou 结果仅作候选；provider 优先按分享 URL 主机识别。
- 内部 `gying-source` 提供 `GET /search`、`GET /catalog?sort=cscore`、`GET /bt/{btId}`；认证失败只记录错误类别，不输出 Cookie 或认证材料。

### 注册、邀请与后台监控

- 支持邀请码、注册开关与角色策略；邀请码 DATETIME 与 `LocalDateTime`/`Timestamp` 兼容，密码要求与后端一致（至少 12 字符、最多 72 UTF-8 字节）。
- 公开注册支持人数上限设置 `auth.register.max_users`（0=不限制，上限 0–100000，当前生产 500）；达到上限后只停止无邀请码的公开注册并给出明确提示，有效邀请码注册与管理员建号不受上限影响。
- `/resource-search` 提供登录用户网页资源搜索：精确命中片库时优先返回已审核、活动、状态正常的资源，首轮不调用外部来源也不转存；界面只展示资源名称、提取码与二维码，不出现明文 URL 与复制/打开入口。任务异步执行、每 2 秒轮询、刷新续查，发送成功后清空已发送内容。
- 后台监控页提供统计卡、14 天趋势、响应分布、热门搜索 Top10、发布成功率与多类日志 tab（中文列名与状态筛选）。
- 系统设置可在线修改 `resource.search.rate_limit_per_minute`（每用户每分钟 1–60，默认 5，保存后即时生效）；资源序号选择与翻页不计入，选择影片、重新搜索或「查看其他资源」计入，QQ 搜索频率独立。

### QQ 自动化

- 搜索先回复「正在搜索资源，请稍后...」，随后返回影片候选与资源候选（优先夸克）；只有用户回复单个资源序号才创建并执行转存任务，成功回复附带影片元数据与最终自有分享。失效分享、无视频文件或平台拦截均回复固定文案并保留候选上下文。
- QQ 每日推荐由 backend QQBot 触发（配置存于 `sys_config`），需与 QQ 官方主动消息权限配合；频道发布由宿主机 CLI 计划任务承担。
- OpenClaw 使用唯一运行时插件副本，升级后必须重新应用补丁（UTF-8 进度文本、管理员白名单、拒绝未授权命令）。

### 多平台发布

- 微博自动发布已启用，健康检查显示 configured/authenticated/ready 均为 true；未手工触发真实帖子以避免重复发布。
- 旧 `secondary`、`qq-v3` QQ 频道目标已软停用（`enabled=0`），保留历史发布审计。

## 配置与安全

- 敏感值边界：`.env`、Cloudflare credentials、MySQL defaults、Quark/迅雷/QQ/微博 Cookie 与 token 只报告路径与键名，不写入仓库、日志或本文档。
- 已上线：nginx/backend 仅发布到 loopback，内部 QQ/internal 路径 404，匿名管理接口 401，应用容器非 root，backend/gying-source/social-publisher 使用非 root 数据库账号，MinIO 已接入应用网络且 backend 不再使用 root key。
- 生产安全审计（2026-09-25 复核）：`python tools/security/check_security.py --repo . --probe` 为 53 PASS / 10 FAIL / 0 UNKNOWN，失败项集中在未收紧的 quark/MinIO 端口、部分容器缺少 `no-new-privileges` 与 UID 0 进程，未放宽检查标准。
- 凭据历史：扫描 1,278 个 Blob 命中 105 条规则（跨版本重复），当前工作区扫描为 0；可能有效的凭据仍需在 provider 侧轮换，历史重写另行审批。
- 数据中心：2026-09-25 实测 MySQL 8.0.28，`gying` 库 25 张 InnoDB 表；使用现役应用凭据从本机连接匹配 `gying_app@%`，授予 gying 库 SELECT/INSERT/UPDATE/DELETE/EXECUTE，缺少完整备份权限，不能把非 root 等同最小 Host 范围。MCP 只读账号和 `require_secure_transport=OFF`、`local_infile=OFF`、bind address `*` 为此前验收，本轮未重新查询。
- `docker compose -f docker-compose.prod.yml config --quiet` 已通过；语法与必需键通过不代表 Firewall、Access、MinIO policy 和恢复门禁通过。
- Redis 与 PanSou 仅作为可重建依赖对待；Redis 实际仅接入非 internal 的共享 `gying-net`，尚未迁入目标 `cache-net`，扫描计数不覆盖这项隔离差异；NapCat 不纳入验收。
- 2026-09-25 Windows 复核：11:33 管理员已启用 Public Firewall/default inbound Block；规则 `GYing-Hardening-20260925-PhysicalSensitiveTCP` 在物理接口 `以太网`、`WLAN 2` 上阻断入站 TCP 3306/33060/5005/8880/9000/9001（Profile Any、地址 Any），ActiveStore 复核通过。出站设置未变（有效 Allow），未修改 Domain/Private profile；敏感服务的非 loopback 监听仍未改绑，外部设备入站阻断与 IPv6 链路未验收。公网匿名管理页尚无 Access 拦截证据，管理员 API 仍要求应用认证。
- 状态文档、Skill 与安全报告只记录键名、状态与结论，不记录原始日志、密钥或完整 SQL 输出。

### 迁移与恢复基线

- 当前前后端：`gying-library-qr-backend:20260925c`、`gying-library-qr-frontend:20260925j`（2026-09-25 Asia/Shanghai 部署，容器重启次数 0）。发布包与 deploy/rollback override 位于 `E:\gying-tools\releases\`（`registration-limit-invite-20260925`、`registration-resend-20260925`、`rebrand-yingwo-20260925`、`hot-search-filters-20260924`、`gying-skip-20260924`、`monitoring-page-20260924`、`library-qr-20260924`、`search-admin-20260924-2035`、`business-fixes-20260924-1950`），更早版本镜像标签保留在本地。
- 迁移快照 `migration-data\20260914-081539`：SHA-256 清单 4832/4832 通过，缺失 0、不匹配 0；迁移时点 `movie_metadata=1631`、`resource_link=2165`，迁移前回滚备份 `E:\gying-data\gying-pre-deploy-20260914.sql`。
- 已恢复的持久化数据：MinIO、backend-data、social-publisher 两个凭据卷、quark-auto-save 配置、OpenClaw 配置/认证与本机 MCP 配置；backend 日志只归档未恢复。
- 回滚材料包含 MySQL dump 与 `.env` 的 Windows DPAPI CurrentUser 加密副本，仅能在原主机/账号解密，不等同异机灾难恢复；未执行 `docker compose down -v`，未删除任何卷。
- 2026-09-25 完整逻辑/持久数据备份为 `G:/gying-backups/20260925T030328.719395Z`：19 个 age 文件、约 372 MiB，manifest=`complete`，hash/认证解密全部通过。使用 `gying_backup@localhost` 导出 gying 库（包含触发器/事件/例程选项；实测这些对象及视图均为 0），归档 MinIO、六个状态卷、当前部署覆盖、受限备份配置、环境、Cloudflare、防火墙导出及加密 Docker 运行配置；不包含 MySQL 系统账号库或远端网盘事务。
- 备份窗口为 11:03:25–11:03:56 Asia/Shanghai：6 个原始写入容器暂停约 31 秒后全部解冻，主机发布/同步计划不与窗口重叠；数据库行数/校验和及关键配置在窗口前后稳定。未重建生产容器，重启计数未变；早先 `security-20260925-partial` 保留为历史候选，不能混作当前完整备份。
- 独立 MySQL 完整逻辑恢复的 25 张表行数与逐表 CHECKSUM 均一致，数据库对象数量和中文往返通过；无网络 MinIO 恢复后健康正常、1 张公开图片 SHA-256 一致、私有元数据 403。测试 MySQL 已关闭、MinIO 测试容器已移除；受限恢复目录 `G:/gying-tools/security-20260925/{full-mysql-restore,full-minio-restore}` 保留。早先候选恢复副本清理被执行策略拒绝，本轮没有绕过重试。

## 仍需处理

- **安全加固门禁（Critical/High）**：按 `docs/security/deployment-checklist.md` 完成 Windows 防火墙外部入站验收与敏感端口改绑、DB 分服务身份与 grants、MinIO policy 与 root key 轮换、OpenClaw 接入内部网络、Cloudflare Access/WAF、Quark ACL 与 Cookie 轮换、加密备份与恢复演练；不得把部分上线写成整体安全闭环。
- **生产与目标配置差异**：quark 5005、独立 MinIO 9000/9001 仍监听非 loopback；OpenClaw/Redis/quark/PanSou/MinIO 缺少 `no-new-privileges`，其中 quark/PanSou/MinIO 存在 UID 0 进程；Redis 仍在共享网络而非 internal cache-net，需备份后逐项收紧。
- **恢复门禁剩余项**：专用备份账号/私有 defaults、19 文件完整逻辑与持久数据备份、短暂冻结窗口和隔离 DB/MinIO 恢复已验证。仍需应用全链路、MySQL 系统账号重建、异机恢复及私钥离线保管；本机隔离验证不是整机灾难恢复。age 位于 `G:/gying-tools/age-v1.3.2`，配置为 `G:/gying-secrets/backup-config.json`；私钥在 F 盘受限目录且仍在线，不得在聊天中提供。
- **防火墙剩余验收**：备份账号与防火墙单步已完成，不要重复创建账号或再次执行 `-Apply`。已实施尝试为 `G:/gying-tools/security-20260925/firewall-attempts/20260925-113327-6e2eac1b/`，包含变更前策略导出、成功结果与独立核验；活动标记已清除，watchdog 已退出，未回退。下一步从独立设备验证上述 6 个 TCP 端口无法经物理接口访问，并单独验证 IPv6；本机 HTTP 探针不替代外部入站证据。需要撤销本次变更时，管理员使用配套 `Rollback-FirewallStep.ps1 -AttemptDirectory` 指向该目录，只恢复本次规则与 Public enabled/default-inbound，详见部署清单。
- **迅雷持续同步待验收**：计划任务 2026-09-25 09:27、10:33 两次运行均返回 0，状态文件 mtime 对应更新至 10:33:36，旧“自动同步未恢复”的结论已过时；仍需观察后续周期和实际授权有效性，任务退出码/文件更新不替代真实链路验收。本轮未读取凭据内容或手动触发刷新/转存。
- **迅雷凭据权限持续性待验收**：私有临时文件 + 原子替换补丁已随 `backend:20260924c` 上线（在线 jar 含 `PrivateFileWriter`），2026-09-25 两次同步周期后的 stat 均为 `10001:10001 / 600`。backend 自身独立重复写入始终保持 600 的生产证据仍待补齐，不需重复发布或用一次 chmod 代替验收。
- **Docker 运行目录与磁盘**：完成 Windows 重启后清理 `%LOCALAPPDATA%\docker-secrets-engine` 失效 socket 并恢复默认启动；E 盘空间偏紧（2026-09-25 约 6.3 GiB；新备份放在 G 盘），建议把 `docker_data.vhdx` 迁往空间充足的磁盘。可清理本次产生的临时目录 `run-stuck-*`、`run-recovery-*`。
- **数据质量**：历史遗留的乱码任务、重复目录和无视频分享需按资源价值逐批人工确认，优先 dry-run 与软删除。
- **外部依赖稳定性**：GYING 图片源和部分外部网盘接口存在偶发超时、风控或响应结构变化，需保留重试与失败审计，不把单次 HTTP 200 视为业务成功；持续观察 GYING BT 登录态、PoW 与响应结构变化，认证失效时只更新外部登录态、不降低采集频率。
- **QQ 临时转存清理**：代码、数据库迁移与安全边界测试已完成，仍需在群内真实搜索并选择一个可丢弃资源，复核到期后夸克/迅雷临时目录被删除且正式目录不变。
- **邮箱验证码**：已启用（`auth.email_verification.enabled=true`）。Resend 发件域 `gyinghub.dpdns.org` 已验证，地址 `noreply@gyinghub.dpdns.org`（DKIM/SPF/DMARC 生效）；163 收件实测 `delivered`。Gmail 对 `dpdns.org` 免费域返回 550 unsolicited/mail blocked，多次内容调整仍被拒，Gmail 用户注册仍可能收不到验证码；待域名声誉建立或换用自有域名后可缓解，无需改代码。
- 豆瓣评分没有稳定官方 API，不作为生产链路强依赖。

## 长期不变量

- `movie_metadata` 是影片主表，`resource_link` 是可用资源表，`movie_source_identity` 保存外部来源绑定；GYING 外部 ID 与本地 canonical 影片 ID 必须区分。
- 资源状态与发布状态必须可追踪；失效修复优先原位更新，避免同片产生多条活动自有分享。
- 自动化只处理明确匹配的影片与资源，不确定匹配必须转为人工候选。
- 删除、迁移与重复清理默认 dry-run，并保留可回滚的软删除或迁移记录。
- MySQL `global/session time_zone=SYSTEM`，与 Asia/Shanghai 一致（`NOW()` 与 `UTC_TIMESTAMP()` 相差 8 小时）。
- 任务已注册不等于已运行；被禁用的调度器、Worker、计划任务与机器人必须在文档中显式区分。

## 验收

当前结论（2026-09-24 / 09-25 复核）：

- 后端全量测试 239 项通过（0 failures/errors，1 项 Redis 集成测试按环境跳过）；前端 `tsc --noEmit` 0 错误。
- 浏览器回归在隔离镜像与公网环境均通过，覆盖热门搜索面板与跳转、筛选默认收起与摘要、资源搜索对话与二维码展示、管理员建号与搜索频率保存、注册与编辑绑定；公网复验无页面运行时错误。
- 入口复核：本地与公网 `/`、`/admin/movies`、`/resource-search`、`/api/movies/hot-searches` 均返回 200；匿名管理接口 401，内部 QQ 路径 404。
- 品牌与首页轮播复核：中文「影窝」/英文「FilmNest」按语言切换且不并排；浅色/深色轮播背景与文字正确切换；375px 手机端轮播内容完整、按钮单行无裁切；768/1024/1200/1440 导航无横向溢出。
- 首页与分类页结果总数复核：均不显示「N 条结果」，关键词搜索仍显示匹配数量。
- 注册上限与邮件复核（2026-09-25）：临时把上限设为当前用户数 `3` 时，无邀请码策略返回 `registrationLimitReached=true`、`registrationAllowed=false`，发码接口 403 `Public registration limit reached; an invite code is required`；带有效邀请码时策略仍返回 `registrationAllowed=true`。清理临时邀请码后上限恢复生产值 `500`。Resend 域名 `gyinghub.dpdns.org` 三条记录 verified、DMARC 已添加，发件地址 `noreply@gyinghub.dpdns.org`；163 邮箱（`yolo136@163.com`）实测 delivered，Gmail 550 拒收；邮箱验证码开关已启用，Gmail 用户暂收不到验证码。
- 本轮未创建真实账号、未修改生产搜索频率、未手工触发转存或发布；模拟 API 回归与单测不替代真实扫码转存和外部副作用验收。
- 安全续审（2026-09-25 防火墙变更前）：只读扫描 53 PASS / 10 FAIL / 0 UNKNOWN，安全工具单测 16 项通过、工作区 secret scan 0 findings；运维就绪 10 PASS / 2 WARN / 0 FAIL（迁移文档漂移、新库覆盖）。未重跑上述业务全量测试，未重启生产或修改防火墙/生产凭据。
- 防火墙单步验收（2026-09-25）：用户实施记录 `20260925-113327-6e2eac1b/result.json` 为 applied，变更前后各 9/9 健康检查通过；本任务 11:34 再查 ActiveStore 的 profile/端口/网卡/方向/动作与预期一致，出站未变，回退守护进程为 0 且无回退记录。独立 `-CheckOnly` 再次 9/9 通过（`G:/gying-tools/security-20260925/firewall-attempts/20260925-113458-e40a05c2/result.json`）；10 个容器运行且未暂停，restart count 均为 0、启动时间早于本次变更。未改数据库、代理/DNS/TLS 或生产容器；此前工具 30 项测试包含模拟回退，真实回退未触发，整体安全门禁仍未通过。
- 完整备份阶段：19/19 加密文件 hash/认证解密通过，25/25 表恢复计数与 CHECKSUM 一致，视图/触发器/事件/例程数量匹配；隔离 MinIO 图片与拒绝探针通过。源站/公网首页、公开列表与管理员/内部路径拒绝复测正常；未进行应用全链路/异机恢复，整体安全门禁仍未通过。

可重复执行的验收：

```powershell
Set-Location -LiteralPath 'D:\gying-movie\movie\backend'; mvn test
Set-Location -LiteralPath 'D:\gying-movie\movie\frontend'; npx tsc --noEmit; npm run lint; npm run build
Set-Location -LiteralPath 'D:\gying-movie\movie'
docker compose -f docker-compose.prod.yml config --quiet
docker ps --format "{{.Names}}\t{{.Image}}\t{{.Status}}"
python -X utf8 tools/security/check_security.py --repo . --probe   # 只读安全扫描
curl.exe -s -o NUL -w "%{http_code}\n" http://127.0.0.1/
curl.exe --disable --ipv4 -s -o NUL -w "%{http_code}\n" https://gyinghub.dpdns.org/
```

## 常用校验

- 运行态快照：`.agents/skills/gying-project-ops/scripts/collect-ops-snapshot.ps1`；部署前就绪检查：`scripts/test-ops-readiness.ps1`。
- 替换 backend 或 gying-source 后需重载/重启 nginx，避免其缓存旧容器 IP 导致 `/api/*` 返回 502。
- 本机端口 3009/3019/3029 位于 Windows 排除段（2970–3069），前端隔离验收改用 18080。
- PowerShell 读取本文档需显式 UTF-8（Windows PowerShell 5.1 用 `Get-Content -Encoding UTF8`）。
