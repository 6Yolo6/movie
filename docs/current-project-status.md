# 当前项目状态

更新时间：2026-07-15

## 当前项目目标

项目目标是在现有 GYing Movie 站点上接入 Resource Hub，让系统可以自动发现影视资源、转存到自己的夸克网盘、创建自己的分享链接，并把影视信息和可用资源链接沉淀回现有数据库。

核心链路：

1. 本站优先查 `movie_metadata` 和 `resource_link`。
2. 本地没有可用资源时，通过 TMDB 补影视元数据和热门待采集任务。
3. 通过 PanSou 搜索资源。
4. 通过 quark-auto-save 转存资源。
5. 通过 Quark share API 创建自己的分享链接。
6. 将最终可用链接写回 `resource_link`。
7. 前台、后台、QQ群机器人和腾讯频道发帖共用同一套资源编排能力。

## 已完成部分

- Resource Hub 后端主链路已接入：TMDB 同步、PanSou 搜索、quark-auto-save 转存、Quark 分享、发布到 `resource_link`。
- 后台 Resource Hub 页面已加入监控和配置入口，支持 TMDB 采集频率、采集条数和手动采集。
- TMDB 热门数据已和站内收藏热度拆开：
  - `movie_metadata.popularity` 保持为站内收藏数。
  - `movie_metadata.tmdb_popularity` 保存 TMDB 热度。
  - 前台 TMDB 热门使用独立接口。
- TMDB 图片已改成本地化存储思路，避免前端直接加载 `host.docker.internal` 或 TMDB 原图。
- TMDB TV 元数据匹配已加强，会优先匹配已有 `series_name`、标题和别名，减少“达顿牧场”和“达顿牧场第一季”这类重复。
- 手动单片资源搜索已支持输入片名，不再要求用户知道 `movie_id`。
- 采集不到资源的 TMDB 影片会按预告状态处理。
- 前端已补充 Resource Hub 页面和搜索页部分中英文 i18n。
- QQ 群机器人后端链路已具备基础能力：群消息进来后先查本地资源，没有则触发 Resource Hub 搜索、转存、分享，再回复用户。
- 腾讯频道 CLI 已安装并完成授权。
- 腾讯频道已创建“电影”和“电视剧”两个版块。
- 腾讯频道发帖脚本已存在，后续资源帖格式为三段：标题、链接、简介。
- 腾讯频道最新资源发帖脚本已支持从数据库取最新未发资源、按电影/电视剧选择版块，并用本地 state 文件记录已发布 `resource_link.id` 避免重复发帖。
- 本机 Docker 已安装并运行 OpenClaw Gateway，端口为 `18789` / `18790` / `3978`，`/healthz` 返回 200。
- OpenClaw 已安装 `@tencent-connect/openclaw-qqbot` 插件并绑定 QQ Bot，当前插件可连接腾讯 QQ Bot WebSocket，日志显示具备“群聊+私信+频道+交互” intents。
- NapCat 已切换到新 QQ `3929013344`，并为该账号补齐 OneBot11 HTTP server 与 HTTP client 配置：
  - HTTP server 监听 `0.0.0.0:3000`，供后端调用 `send_group_msg`。
  - HTTP client 上报到 `http://backend:8880/api/qq-bot/onebot`，token 从 `.env` 注入，不提交到仓库。
- NapCat 扫码登录已完成，`get_login_info` 返回当前账号 `3929013344`。
- NapCat 直连发群已验证，可向 QQ 群 `2166070253` 发送消息。
- 后端 `/api/qq-bot/onebot` 模拟 OneBot 群消息已验证：
  - `/movie test` 可触发“正在搜索”和最终结果回复。
  - `搜 达顿牧场` 可命中“达顿牧场 第一季”并回复影片信息。
- 用户已用非机器人账号在 QQ 群真实验证：`搜 影片`、`/movie 影片`、带 @ 和不带 @ 都能解析。
- QQ 群回复已调整为只展示影片信息和正式资源链接，不再展示“状态”和“我的夸克分享”内部字段。
- 后端已支持可配置回复通道：默认 `napcat`，也可通过 `QQ_BOT_REPLY_PROVIDER=qqbot` 切到官方 QQBot API 回包。
- 本机已配置官方 QQBot 出站并完成后端重启；API 鉴权和 `GROUP_OPENID` 映射已通过，但平台返回 `40034105 主动消息失败, 无权限`，需要在 QQ 开放平台开启/授权群主动消息，或改为官方 QQBot 入站事件携带 `msg_id` 后做被动回复。
- 后端已新增 `/api/qq-bot/search-reply`，只返回搜索回复文本，不主动发消息，供 OpenClaw QQBot 被动回复调用。
- 本机 OpenClaw QQBot 插件已临时补丁：`/movie`、`/search`、`搜`、`找` 会调用后端 `search-reply` 接口，再由 OpenClaw 使用官方 `msg_id` 被动回复。
- 后端 compose 已暴露 `BACKEND_PORT`，当前用于 OpenClaw 容器通过 `host.docker.internal:8880` 调用后端。
- 已新增 `tools/patch-openclaw-qqbot-gying.ps1`，可在 OpenClaw QQBot 插件升级后重新应用资源搜索补丁。
- 已在 OpenClaw 容器内直接验证插件命令处理器：`/movie 人生切割术` 和 `搜 人生切割术` 都能拿到后端中文资源回复。
- OpenClaw QQBot 补丁已扩展到 `gateway.js`，让群聊里 @机器人后的 `搜 影片`、`找 影片` 也进入插件级直接回复路径，不再落入内置 Agent。
- 用户已在真实 QQ 群验证官方机器人 `@机器人 搜 影片` 可以搜索并回复。
- QQ 群机器人回复里的评分前缀已改为纯文本 `豆瓣`，避免星标/emoji 编码异常显示成 `??`。
- QQ 群搜索库内无元数据的影片时，会先通过 TMDB 搜索并同步完整影视元数据；找不到可信元数据时不触发 PanSou，避免误转存无关资源。
- QQ 群搜索已加入后端兜底限制：默认每个用户每分钟 5 次，默认搜索词至少 2 个字。
- QQ 群搜索已支持敏感词拦截，可通过 `QQ_BOT_BLOCKED_KEYWORDS` 配置。
- PanSou 发现结果已增加标题相关性过滤，避免“复仇者联盟5”命中“乐高复仇者联盟：红色代码”这类弱匹配资源。
- QQ 群返回资源前会处理疑似/已失效链接：通过 PanSou `/api/check/links` 检测；确认为失效的 Resource Hub 夸克分享会先检查夸克保存目录，再尝试重新创建分享；空目录、重分享失败或重分享后仍失效时会触发重新搜索该影片、重新转存并重新发布。
- QQ 机器人现在不会再因为数据库 `link_status=NORMAL` 就直接回复：Resource Hub 夸克链接每次回复前都会调用 PanSou 实时验链；有转存目录时还会通过夸克 API 确认目录非空。
- 链接首次无法确认会标记 `SUSPECTED_INVALID`；疑似链接再次无法确认时会改为 `INACTIVE/INVALID`，关联转存任务和发现结果改为 `FAILED`，释放“已有任务/重复发现”拦截并立即重新搜索。
- `QuarkShareService` 已统一增加空目录保护；空目录、分享任务无 `share_id` 等分享失败会把任务/发现置为 `FAILED`，后续成功重试会恢复发现为 `DISCOVERED` 再发布。
- 重新搜索发布时会优先按同影片同源 URL 更新原 resource_link；源 URL 变化时也会优先复活该影片的失效 Resource Hub 夸克行，避免新增重复链接。
- 2026-07-14 真实验证“穿普拉达的女王2”：原资源 `1581` 从 `ACTIVE/SUSPECTED_INVALID` 变为 `INACTIVE/INVALID`，转存任务 `28` 与发现 `53` 变为 `FAILED`，自动新建搜索任务；PanSou 本次无可用结果，因此机器人没有继续回复旧失效链接。后台同时确认该保存目录为 `EMPTY`。
- 2026-07-14 真实验证正常资源“炒翻天”：经过 PanSou 实时验链和保存目录检查后仍正常返回夸克链接。
- 后台 Resource Hub 已新增失效资源检测列表，可查看待修复资源、关联影片、转存任务、夸克保存目录是否为空、上次检测错误和下一步动作。
- “检测失效资源”按钮现在会主动调用 PanSou `/api/check/links` 批量检测当前 Resource Hub 夸克链接，而不是只看数据库里的 `link_status`；检测结果会写回 `link_status`、`validated_at` 和 `last_check_error`。
- 后台“检测并重新分享失效资源”已改成后台 job：点击后立即返回 `jobId`，前端轮询 `/api/resources/admin/repair-invalid/jobs/{jobId}`，避免 nginx 504；任务结果包含 checked/restored/reshared/rediscovered/invalid/skipped/errors。
- 后台“手动搜索资源”已改成后台 job：`runNow=true` 时 `/api/admin/resource-hub/discover` 立即返回 `jobId`，前端轮询 `/api/admin/resource-hub/discover/jobs/{jobId}`，避免 PanSou/转存/发布流水线执行时间超过 nginx 读超时。
- 详情页服务端取数已区分浏览器 API 和容器内 API：Next.js 服务端使用 `INTERNAL_API_URL=http://backend:8880`，浏览器继续经 nginx `/api`，修复 `/movie/{id}?_rsc=...` 返回 Service Unavailable 的问题。
- 后台已新增 `/admin/automation` 页面：
  - 可监控 QQ 群机器人搜索记录、状态、命中影片和资源数。
  - 可配置 QQ 群机器人最小搜索词、限流、回复资源数和敏感词。
  - 可配置腾讯频道自动发帖开关、发帖间隔、每次条数、候选数量和频道版块 ID。
  - 可查看腾讯频道发帖记录、发帖状态和失败原因。
- `/admin/automation` 已改为按每日定时发布时间、每日发布总条数、每条间隔秒数和发布模板来配置腾讯频道自动发帖；频道号、电影版块 ID 和电视剧版块 ID 默认从环境变量或当前已知 ID 回填。
- 腾讯频道发布模板历史 mojibake 配置已清洗；当前默认模板为“标题/链接/简介”，后端读取到旧乱码值时会自动修正。
- Resource Hub 发现结果列表已新增“发到QQ频道”手动发帖入口：后端会先确保发现结果已发布为 `resource_link`，再写入 `qq_channel_post_log.status=PENDING`；主机轮询脚本优先处理 PENDING 手动请求。
- 旧的隐藏 PowerShell 轮询进程已停止，避免与 Windows 计划任务并发调用 CLI；发帖脚本新增全局互斥锁，即使计划任务重叠或手动执行也只允许一个实例运行。
- 腾讯频道自动发帖失败的根因已定位为 `QQ_CHANNEL_GUILD_ID` / `qq.channel.guild_id` 误填了“全部”版块 ID `736090076`，真实频道 ID 是 `86486581783412489`；`.env` 和 live `sys_config` 已修正，脚本也会对旧误填值自动兜底。
- 腾讯频道发帖脚本已改为优先调用 `tencent-channel-cli.cmd`，并把 CLI stdout/stderr 写入失败信息；后续如果再失败，`qq_channel_post_log.error_message` 会保留真实 CLI 错误，而不是只有 exit code。
- 腾讯频道正文改为 UTF-8 临时 `content-file`，避开 Windows `.cmd` 对多行参数的截断；标题、链接、简介之间固定保留空行，资源链接以内联可点击语法写入正文。
- 腾讯频道发帖已支持影片海报：从 `movie_metadata.poster_url` 读取 MinIO 对象键，按 `MINIO_URL_PREFIX` 下载临时图片并通过 CLI `--image` 上传；图片下载失败时降级为无图帖子。
- 发帖配置改用 `HEX(config_value)` 读取并按 UTF-8 解码，避免多行模板被 mysql batch 输出拆行。参数捕获测试已确认正文含链接和段落空行，并传入海报图片。
- Resource Hub 批量按钮已明确语义：待入库发布只统计 `DISCOVERED` 且已有 `share_url`、尚未绑定 `resource_link` 的发现结果；发布接口会写入或更新 `resource_link`，同影片同链接不再重复新增资源。
- Resource Hub 前端流水线按钮会显示实际结果数量：Worker 处理任务数、转存提交数、发布新增/更新数、跳过和失败数，避免只显示“操作成功”但看不出为什么统计未变化。
- 2026-07-14 已重建 `backend`、`frontend` 和 `nginx` 并用真实管理员接口验证：待入库发布从错误的 41 条修正为 3 条；发布 1 条后新增 `resource_link.id=1625`，`DISCOVERED` 从 25 降到 24，待入库从 3 降到 2。
- 转存按钮已改为优先处理真正的 `PENDING`，再补做旧 `SUBMITTED` 分享重试；真实处理 1 条后待转存从 11 降到 10、提交 1、失败 0，并新增一条待入库分享结果。
- 已新增 `qq_bot_search_log` 与 `qq_channel_post_log` 两张记录表，字段和表注释已补为中文；核心历史表字段注释也已通过数据库 MCP 改为中文。
- 已用后端真实接口验证：
  - `人` 会被最小搜索词长度拦截。
  - `复仇者联盟5` 会补全 TMDB 元数据并标记未上映/未发布，不触发网盘搜索。
  - `气体人第一号` 可复用历史 pending 转存任务，创建自己的夸克分享并发布到 `resource_link` 后返回资源链接。
  - 同一 `userKey` 第 6 次/分钟搜索会被后端限流。
- 腾讯频道 CLI 已重新扫码授权，`login status` 显示凭证有效且服务连通正常。
- 腾讯频道 ID 已确认：频道 `86486581783412489`，电影版块 `736142774`，电视剧版块 `736142775`，全部版块 `736090076`。
- 2026-07-14 已用管理员权限真实执行自动发帖脚本，`qq_channel_post_log.id=15` 从 PENDING 变为 POSTED，`posted_at=2026-07-14 10:45:11`，CLI 返回成功；候选查询现只发送 `link_status=NORMAL` 的资源。
- 2026-07-14 已通过腾讯频道 CLI 回读最新帖子：14:16 发布的“你会心碎”正文为完整中文，标题、链接、简介分段正常，并带有海报图片，确认 UTF-8 `content-file` 和图片上传链路已经生效。
- Resource Hub 标题相关性判断已抽成统一规则，发现、发布入库和手动排队发频道都会校验资源标题与影片标题/原名/剧集名/别名，简介里的偶然同词不再算作标题命中。
- 后台 Resource Hub 已新增“扫描标题污染资源 / 执行污染软清理”入口，对不匹配记录使用 `resource_link.status=DELETED`、`deleted_at`、`link_status=INVALID`，并将关联发现置为 `IGNORED`、转存置为 `CANCELED`，不物理删除历史。
- 2026-07-14 真实 dry-run 识别出 28 条历史污染资源，包含“痴迷→冬去春来”“鬼上车→南部档案/香港探秘地图”“航海王→007/短剧合辑”“深水→瑞克和莫蒂”“你会心碎→日期合辑”等；复核后已全部软清理，正常的别名和季名资源未受影响。
- QQ 群影片查询已改成“精确命中才进入资源链路”：本地模糊结果只作为候选提示，TMDB 同步结果也必须与中文名、原名、剧集名或单个别名完全匹配。真实验证“福尔摩斯”返回 `AMBIGUOUS` 和“福尔摩斯小姐3”候选、资源数为 0；完整搜索“福尔摩斯小姐3”仍正常返回 4 条资源。
- QQ 群 `AMBIGUOUS` 候选现支持同一用户在 5 分钟内直接回复 `1`-`10` 选择影片；真实验证“福尔摩斯”返回 10 条候选，回复 `2` 可直接返回“福尔摩斯小姐3”的 4 条资源。
- PanSou 搜索已同时接入本地服务和 `https://www.panso.best/api/search`，外部 API 使用后端环境变量 `PANSOU_API_KEY`（兼容本机旧名 `PANSO_API_KEY`），结果按 URL 去重交错合并；真实发现记录已出现 `source_ref=plugin:gying`，确认外部来源参与资源链路。
- QQ 群在夸克候选全部失效或无法保存时，会从本地 PanSou 和外部 API 返回标题匹配且未被验链判定失效的百度、阿里、UC、天翼、迅雷、115、123 等备选网盘链接，不把第三方兜底链接写入正式 `resource_link`；真实验证“哈哈哈哈哈”返回 5 条天翼/百度链接和百度提取码。
- QQ 群搜索成功后会保留用户最近影片 5 分钟，支持继续回复“百度 3”“夸克 2”“资源 8”等选择网盘与 1-10 条结果；首轮命中库内资源后，指定库里缺失的网盘仍会继续补搜，夸克选择只返回转存到自有网盘后重新创建并发布的分享。`密室大逃脱第七季` 等季数查询会直接匹配主节目，并按原词、`第7季`、紧凑数字和 `S07` 变体搜索；外部 Panso API 请求固定携带 `res=all`，避免只得到 `data.total` 而漏掉真实资源。
- 后台 Resource Hub 页面已命名为“影视资源中心”，统计口径显示为“已发现（含未分享）”和“已分享待入库”；未生成分享的记录使用“重试分享并发布”，不再显示会被无条件跳过的普通发布按钮。
- 夸克转存完成后的目录检查已改成短轮询，并支持从“转存成功但分享阶段误判空目录”的 `FAILED` 任务恢复。真实修复“尼古喵喵”：目录含 `01.mp4`、`02.mp4`，发现 `585` 与转存 `220` 已恢复，创建本站分享并发布为 `resource_link.id=1634`，状态为 `ACTIVE/NORMAL`。

## GYING 数据源集成

- 已将 `crawler/gying_crawler.py` 扩展为可复用的数据源工具，支持 `ingest`、`crawl-user`、`publish`、`update` 和内部 `serve` 模式；站点账号、密码、Cookie、数据库和 MinIO 配置继续只从环境变量读取。
- 已新增 `gying-source` Compose 服务、后台 `/api/admin/gying-source` 接口和 `/admin/gying-source` 管理页面，可按 GYING 影片 ID 一键抓取入库，也可按本站 `resource_link.id` 发布或更新 GYING 网盘资源。
- 站点资源读取使用 `/res/downurl/{type_code}/{mid}`，并从 `panlist.id` 保存稳定的站点资源 ID 到 `resource_link.source_ref`；发布使用 `/res/pan/add/{type_code}/{mid}`，更新使用 `/res/pan/edit/{panlist_id}`，默认 `is=0`。
- 已创建并校验本机 skill：`C:\Users\Administrator\.codex\skills\gying-source-sync`，包含操作流程和 API reference，`quick_validate.py` 已通过。
- 2026-07-15 真实抓取验证 `mv/EGER`：影片“后室”、海报和本人网盘资源成功写入数据库，抓取结果为 `resourcesFound=1`，重复执行通过后台页面返回 `resourcesUpdated=1`。
- 2026-07-15 真实发布/更新验证本站资源 `resource_link.id=1606`：发布到 GYING 返回 `panlist.id=3vM8N` 和“发布成功”，同值更新返回“修改成功”；浏览器在 GYING `/user/content_list` 回读到“火遮眼”资源，项目后台更新按钮也返回 `sourceId=3vM8N`。
- 已重建并运行 `backend`、`frontend`、`gying-source` 和 `nginx`；浏览器管理员端确认数据源页面、抓取结果和更新结果均正常显示。

## 未结束任务

- QQ 群机器人后续可把当前脚本补丁升级为私有 OpenClaw 插件或上游配置，减少对 `node_modules` 补丁的依赖。
- 用真实未入库且已上映影片再测一次“TMDB 补元数据 -> PanSou 搜索 -> 转存 -> 分享 -> 入库 -> 回发”的完整链路。
- 腾讯频道自动资源帖还需要管理员重新启用计划任务：
  - 当前 Windows 任务 `GYing QQ Channel Auto Post` 已注册但状态为 `Disabled`，普通权限无法启用。
  - 用管理员 PowerShell 重新运行 `tools/register-qq-channel-auto-post-task.ps1`；脚本现会在创建后显式执行 `/ENABLE`，失败会直接报错。
  - 启用后观察下一次定时是否继续写入 `qq_channel_post_log.status=POSTED`；失败时直接查看记录中的 CLI 原始错误。
- OpenClaw QQBot 还需要补充真实群聊验证记录：
  - 用户已验证 `/bot-ping` 和 `搜 影片` 都可回复。
  - 如后续仍要做主动消息，需要捕获 QQ Bot 使用的 `GROUP_OPENID`，因为 OpenClaw 主动消息 target 使用 openid，不直接使用普通 QQ 群号。
  - 评估是否让 OpenClaw 直接接业务 Agent，或仅作为官方 QQ Bot 消息入口，把资源查询转发到后端接口。
- 历史数据还需要持续清理：
  - 合并 TMDB 采集生成的重复影视条目。
  - 继续清理早期 QQ 群搜索临时创建的 `qq_<hash>` 占位影片；本轮已清理 28 条“资源名与影片标题不匹配”的误转存链，但占位影片自身标题被错误元数据覆盖的情况仍需按原搜索词单独核验。
  - 迁移或归并重复条目的资源、任务和发现结果。
  - 不直接删除历史数据，优先软停用或迁移到 canonical 影片。
- 豆瓣评分暂未接入，当前没有可靠公开官方 API；不要依赖非官方接口作为稳定生产能力。
- 已发布到腾讯频道的乱码测试帖是一次错误验证产物，后续可以手动删除或保留为测试记录。

## 关键架构决策

- 不替换原有片库模型，继续以 `movie_metadata` 为影视主表，以 `resource_link` 为可用资源链接表。
- Resource Hub 采集结果先进入发现、转存、发布三个阶段，不直接把外部搜索结果写成可用资源。
- 自动采集资源必须创建“我的夸克分享链接”后再发布，避免把第三方原始链接直接暴露为本站资源。
- QQ 群查询优先读取 `resource_link`；库内已有可用链接时直接回复，不再对已有链接重复转存。只有库内无可用链接时才调用 PanSou 外部搜索并进入转存、分享、发布流程。
- QQ 群库外搜索必须先有可信影视元数据：优先 TMDB 补全，不再只用用户输入创建空字段占位影片。
- QQ 群本地模糊匹配只能用于候选提示，不能直接选片；只有片名、原名、剧集名或独立别名与用户关键词完全匹配时，才允许验链、外部搜索、转存和回复资源。
- QQ 群后端要保留独立限流，即使 QQ 群本身已做发言限制，也不能把外部搜索、转存、分享入口完全暴露给高频请求。
- QQ 群敏感词由环境变量配置，不写死在代码里；命中后不进入 TMDB、PanSou 或转存链路。
- 失效链接处理优先复用已有转存任务和保存路径重新分享；如果保存目录为空、无法创建分享、Quark share task 没有返回 `share_id`，或重新分享后仍被 PanSou 检测为失效，则触发重新搜索该影片并创建新的转存/分享链路。
- 失效资源重新搜索成功发布出新 Resource Hub 夸克链接时，会把新链接回写到原失效 `resource_link`，再软删除新产生的重复资源行，避免影片详情页继续引用旧失效链接。
- 后端容器和 JVM 必须固定北京时间：compose 里设置 `TZ=Asia/Shanghai`，`JAVA_OPTS` 里设置 `-Duser.timezone=Asia/Shanghai`；`application.yml` 和 `application-prod.yml` 的 JDBC 默认时区也统一为 `Asia/Shanghai`。live MySQL `NOW()` 与 `UTC_TIMESTAMP()` 当前相差 8 小时，说明数据库侧是北京时间，历史数据时间偏差主要来自旧后端容器/JVM 默认 UTC。
- QQ 群入站仍可继续用 NapCat/OneBot 监听，出站回复可配置为 NapCat 或官方 QQBot；官方 QQBot 出站必须使用 `GROUP_OPENID`，不能使用普通 QQ 群号。
- `movie_metadata.id` 仍兼容原爬虫片库 ID；TMDB 新数据使用合成 ID。后续如要引入自增主键，需要单独设计迁移，不能直接改现有主键。
- 热度语义拆分：
  - 站内热度来自用户收藏。
  - TMDB 热度只用于外部热门采集和 TMDB 热门展示。
- Docker 不默认新建 Redis、PanSou、quark-auto-save 等依赖容器；优先连接用户已有容器，避免端口冲突。
- 敏感信息不提交进仓库。TMDB Key、Quark cookie、QQ/OpenClaw token、NapCat token 只放环境变量或本机配置。
- QQ 群聊搜索优先走机器人身份，因为机器人身份更适合自动化、审计和长期运行；NapCat 路径仍可作为已有 QQ 号群聊接入方式。
- 腾讯频道“版块帖子”当前优先使用 `tencent-channel-cli` / 腾讯频道 community skill，因为该路径已验证可创建频道帖子并选择“电影”“电视剧”版块；OpenClaw QQBot 更适合频道消息或群聊消息，暂不把它视为版块帖子发布的唯一通道。

## 已踩坑清单

- 不要重复启动 PanSou、Redis、quark-auto-save 容器；用户已有容器在运行，重复启动会端口占用。
- quark-auto-save 的 WebUI cookie 和配置文件可能被覆盖；用户也提供了环境变量兜底，后续排查时要先检查容器内最终配置。
- quark-auto-save token 只是 WebUI/API token，不等于夸克账号 cookie。
- TMDB API Key 应放 `.env`，不要写入代码或提交。
- `movie_metadata.popularity` 不能再写 TMDB 热度，否则会污染原首页热门逻辑。
- TMDB poster URL 不应返回 `host.docker.internal` 给浏览器，浏览器访问会失败；应使用前端可访问的本地/MinIO URL。
- 同一部影片不要因为多条发现结果创建多个自己的分享链接；除非旧分享失效或违规，否则一次分享即可复用。
- 新 TMDB TV 数据要尽量匹配已有剧集名、季名和别名，不要轻易新增一条 `tmdb_*` 重复影片。
- PanSou 搜不到资源通常说明流媒体资源还未出现，此时影片应标记为预告状态，而不是反复无限重试。
- PowerShell 通过 stdin JSON 调用 `tencent-channel-cli` 发送中文会出现乱码；后续发帖脚本应走命令参数或确保 UTF-8 输入，不再用普通 PowerShell 管道传中文 JSON。
- 已经出现乱码的帖子不会因为脚本修复自动恢复，需要重新发布或手动删除旧帖。
- `tencent-channel-cli` 登录凭证会过期；自动发帖真实验证前先跑 `tencent-channel-cli login status --json`，过期则重新扫码授权。
- 文档和脚本如果出现连续异常汉字或问号乱码，说明文件内容可能已经 mojibake，需要按原意重写为 UTF-8。
- OpenClaw Docker 首次启动可能因为 `openclaw.json` 缺 `gateway.mode` 进入重启循环；修复方式是在 `C:\Users\Administrator\.openclaw\openclaw.json` 设置 `gateway.mode=local`、`gateway.bind=lan`，然后强制重建网关容器。
- OpenClaw QQBot 插件会提示 `channelConfigs` manifest 警告和 `contracts.tools` 警告，目前不阻止 QQ Bot WebSocket 连接和消息通道启动；后续若要依赖其 agent tools，需要复查插件版本。
- OpenClaw QQBot 主动发送 target 使用 `qqbot:c2c:OPENID`、`qqbot:group:GROUP_OPENID`、`qqbot:channel:CHANNEL_ID`，不要把普通 QQ 号、普通 QQ 群号或频道短号直接当 target。
- NapCat 每个 QQ 账号有独立配置文件，例如 `onebot11_3929013344.json`；切换账号后旧号的 OneBot11 配置不会自动复用。
- 修改 NapCat OneBot11 配置后需要重启 NapCat，但重启可能触发 QQ 重新手Q验证或扫码，操作前要预期这一步。
- PowerShell 管道向 Linux 容器传中文 JSON 时可能出现编码偏差；测试 OneBot 中文命令时优先使用真实 NapCat 上报，或在手工 payload 中使用 `\u` 转义。
- 当前 OpenClaw QQBot 资源搜索处理器是本机 `node_modules` 补丁，OpenClaw/插件升级可能覆盖；升级后要重新应用或改造成正式插件。
- OpenClaw QQBot 插件原本只把 `/` 开头内容送进 `matchSlashCommand`；`搜 影片` 这类中文文本会进入内置 Agent。当前内置 Agent 配置的 `openai/gpt-5.5` 在本机不可用，会导致无回复，所以资源搜索必须走插件级直接回复快路径。
- PowerShell 脚本向 JS 文件写入中文正则可能 mojibake；补丁脚本里插入 JS 的中文逻辑要用 `\u` 转义，避免插件启动时报 `Invalid regular expression`。
- QQ Bot markdown 对部分 emoji/特殊符号不稳定；机器人业务回复尽量使用纯文本标签，例如评分用 `豆瓣 9.1`，不要用星标符号。
- 在脏工作区提交时，只 stage 当前任务相关文件；不要顺手带入无关提交。
