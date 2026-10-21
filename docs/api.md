# 接口文档

[文档导航](README.md) · [发布状态与运维](social-publishing.md) · [当前项目状态](current-project-status.md)

本文维护接口契约，不代表本次已执行真实请求或写入验收。

所有管理接口要求管理员 JWT；普通用户接口要求登录 JWT。响应主要使用 `{ code, message, data }`。

## 影片与用户内容

- `GET /api/movies/list`：影片分页、分类、筛选和排序。
- `GET /api/movies/filters?category=`：动态筛选项。
- `GET /api/movies/hot-searches?days=7&limit=8`：顶部搜索框的“最近热门搜索”，公开接口，无需登录，与 `/api/movies` 共用搜索限流。返回 `[{keyword,count}]` 按次数降序，数据取近 N 天 Redis `hot:search:<date>` ZSet 合并求和，Redis 为空或异常时回退 MySQL `site_search_log`；`days` 取值 1-30，`limit` 取值 1-20。
- `GET /api/movies/{id}`：影片详情和已审核资源。
- `GET /api/movies/{id}/recommendations?limit=8`：公开只读相关推荐，返回影片数组（`id/titleCn/category/year/posterUrl/genres/rating/resourceCount`）；按当前影片内容及系列关系选择，排除自身、停用和软删除影片。默认 8 项，limit 限定为 1–12；无候选返回空数组，影片不存在返回 404、非法 ID 返回 400。
- `GET /api/movies/series?name=`：剧集季信息。
- `POST /api/favorites/toggle?movieId=`：收藏/取消收藏。
- `GET /api/favorites/hot?period=day|week|month|all`：站内收藏热门。
- `GET /api/comments/{relateId}`、`POST /api/comments`：评论和回复；提交缺省类型为 `GENERAL`，按账号应用 `comment.rate_limit_per_minute`（默认 5 次/分钟），内容经白名单清洗。
- `POST /api/comments/{id}/upvote`、`DELETE /api/comments/{id}`：点赞、隐藏评论。
- `GET /api/notifications`、`PUT /api/notifications/read-all`：站内通知。

## 资源

- `POST /api/resources`：发布者/管理员提交网盘、磁力、种子或在线播放资源。管理员不受 `resource.max.per.user` 总量限制；发布者仍受限，提交间隔、重复链接及权限校验不变。
- `GET /api/resources/mine`：我的投稿。
- `GET /api/resources/{id}/bindings`：发布者读取自己的资源实际绑定组，管理员可读取任意资源绑定组，返回新鲜资源值、已绑定影片、资源 ID 列表和 `bindingVersion`；同系列、URL、资源类型和网盘一致，且上传者相同或任一侧上传者为空的历史分组会合并回显；两个不同的非空上传者仍分开，读取/编辑鉴权不变，修复旧记录拆分根节点后再次编辑只带出部分季的问题；同系列但不同网盘、链接版本或未实际绑定的影片不会混入。
- `PUT /api/resources/{id}`：发布者编辑自己的资源，管理员可编辑任意资源；可传 `bindMovieIds` 绑定最多 50 个影片。编辑时应先读取 `bindings` 并回传 `bindingVersion`；已有绑定按影片原位更新 URL、提取码和元数据，仅对新增绑定创建记录，不删除取消勾选的历史记录。目标影片存在不同资源链接或版本陈旧时返回 409，返回 `{message, updatedBindings, boundCount}`。
- `DELETE /api/resources/{id}`：软删除自己的资源。
- `POST /api/resources/{id}/report`：举报失效链接。
- `GET /api/resources/admin/all`：管理列表。
- `PUT /api/resources/{id}/audit`、`PUT /api/resources/batch/audit`：审核。
- `PUT /api/resources/admin/{id}/link-status`：更新链接健康状态。
- `POST /api/resources/admin/invalid-checks/scan`：实时检测候选。
- `POST /api/resources/admin/repair-invalid`：后台修复失效资源。
- `POST /api/resources/admin/{id}/repair-invalid`：管理员单条修复失效或疑似失效的夸克/迅雷云盘资源；成功后原位更新分享，并在存在 GYING 映射时尝试同步发布。

资源质量字段包括 `quality`、`subtitle`、`fileSize`、`versionNote`。

## 网页资源搜索

- `POST /api/resource-search/query`：登录用户提交 `{keyword}`（1–80 字符的片名、候选序号或翻页指令），立即返回 HTTP 202 和 `{jobId, status}`，不在连接内等待外部搜索或转存。
- `GET /api/resource-search/jobs/{jobId}`：仅任务所属用户可查询；不存在、过期、服务重启丢失或其他用户的任务统一返回 404。
- 状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`；成功时同时返回 `reply` 和 `links`，失败只返回脱敏 `message`。
- 建议每 2 秒轮询。同一用户已有进行中任务时复用该任务，不另行执行新指令；全局并发 2、等待队列 8、任务缓存最多 200 条，满载返回 429。完成结果保存 15 分钟，任务保存在内存中，不保证跨后端重启恢复。
- 网页将进行中的任务 ID 保存在当前标签页的 `sessionStorage`，刷新后继续查询。轮询网络故障只重试查询，不重新启动转存。发布此契约时需要同步更新前后端并刷新旧页面。
- 输入发送成功后自动清空已发送内容，不清除等待期间输入的新草稿；失败保留草稿。最近 20 轮对话按用户保存在当前标签页，历史候选不可执行；最新影片/资源候选与翻页支持直接点击，分享卡片遵循下方“仅二维码”规则，不提供明文链接复制/打开入口。
- 入口：左侧抽屉「搜索资源」（登录用户）；影片详情页在无任何资源链接时于资源区底部显示「搜索这部影片的资源」，跳转 `/resource-search?q=<片名>&auto=1` 并自动发起一次搜索。`q` 截断至 120 字符且不复用顶栏的 `keyword` 参数，避免顶部搜索框被误填。
- `resource.search.rate_limit_per_minute` 控制网页每用户每分钟搜索次数，默认 5，范围 1–60，后台保存后即时生效。资源序号选择/翻页不消耗搜索次数；选择影片、重新搜索或“查看其他资源”计入。网页 Redis bucket 与 QQ 独立，不改变 API/边缘防护限流。
- GYING 读取连接/响应超时分别为 3/20 秒；连接错误、5xx、429 或鉴权失效触发 30 秒短时熔断，之后自动允许重试。AUTO/QQ 搜索继续尝试 PanSou；补充来源失败不会丢弃已有有效候选。显式选择 GYING 的管理任务仍报告来源失败，不伪装为成功。

### 网页搜索：库内资源优先与仅二维码

- `web:` 用户精确命中本地影片时优先返回已审核、活动、未删除、健康状态正常的片库资源；不受自有转存来源筛选限制，包含人工发布。同名不同年份/类型仍需先确认影片。
- 库内快路径只读，不等待 GYING/TMDB/PanSou、分享验活或转存；用户发送“资源”/点击“搜索其他资源”后才进入已有外部候选流程。QQ 对待上映或残留 `TRAILER` 标记的影片同样先检查库内有效资源，避免错误拒绝；上映判断取所有地区日期的最早有效值。
- 网页只渲染二维码、资源名称和提取码，移除明文 URL 与复制/打开入口；二维码内容即分享地址，可被解码，不构成强制手机 App 或防提取措施。过长地址显示二维码不可用提示，不回退为明文。

## Resource Hub

基础路径：`/api/admin/resource-hub`。

- `GET /overview`、`GET|PUT /config`：概览和运行配置。
- `GET /tasks`：任务分页，可按类型和状态筛选。
- `POST /tmdb/metadata-sync`、`POST /tmdb/metadata-sync/{taskId}/run`：TMDB 同步。
- `POST /discover`、`GET /discover/jobs/{jobId}`、`POST /discover/{taskId}/run`：资源发现。
- `GET /discoveries?keyword=&movieId=&status=&source=&sortOrder=&page=&size=`：发现结果。
- `POST /discoveries/{id}/publish`、`POST /discoveries/publish`：单条/待发布批量入库。
- `POST /discoveries/{id}/retry-share-publish`：重跑转存，必要时重新搜索后发布。
- `POST /discoveries/batch/publish`、`POST /discoveries/batch/retry-share-publish`：处理请求体中的发现 ID 数组。
- `POST /discoveries/reconcile?dryRun=true&limit=2000`：重评历史标题误判/任务冲突并同步失败任务状态。
- `POST /discoveries/{id}/qq-channel-post?runNow=true`、`POST /discoveries/batch/qq-channel-post?runNow=true`：立即或排队发 QQ。
- `POST /quark/transfers/submit`、`POST /quark/transfers/{taskId}/submit`：转存。
- `POST /api/internal/resource-hub/quark-transfers/{taskId}/run`：使用 internal token 定向执行一条夸克转存任务，不扫描其他队列。
- `POST /api/internal/resource-hub/discoveries/{discoveryResultId}/publish`：使用 internal token 定向发布一条已有自有分享的发现结果。
- `POST /api/internal/resource-hub/xunlei-transfers/{taskId}/run`：使用 internal token 定向执行一条迅雷转存任务，不扫描其他队列。
- `GET /missing-resources`、`POST /missing-resources/{movieId}/resolve?source=GYING|PANSOU`：缺网盘资源检查和补全。
- `POST /missing-resources/batch/resolve?source=GYING|PANSOU`：按请求体中的影片 ID 数组批量补全，最多 20 部。
- `GET /worker/status`、`POST /worker/run-once?force=true`：Worker。
- `POST /cleanup/duplicate-tmdb?dryRun=true`、`POST /cleanup/mismatched-resources?dryRun=true`：清理预览/执行。

## GYING

基础路径：`/api/admin/gying-source`。

- `GET|PUT /account`：读取凭据配置状态或切换当前运行时账号。
- `GET /candidates/recent`、`GET /candidates/trailers`：候选。
- `POST /recent/ensure`：请求体为最近更新表格中所选的 `{typeCode,mid}` 数组，最多 60 部。
- `POST /movies/{typeCode}/{mid}/ensure`、`POST /trailers/ensure`：确保资源。
- `POST /published-resources/check`、`POST /published-resources/repair`：检查和修复本人资源。
- `POST /published-resources/sync?limit=`：分页读取当前账号已发布资源，按 GYING `source_id` 或 URL 跳过本地已有记录；新资源复用影片元数据入库流程并写入 `resource_link`。
- `POST /published-resources/repair-by-ids`：请求体为 GYING `panlist.id` 字符串数组，最多 100 个；只验链并修复当前账号中精确匹配且明确 `INVALID` 的资源。
- `GET /jobs/{jobId}`：后台任务状态。
- `POST /movies/{movieId}/metadata/sync`：严格匹配 GYING 并同步到既有 canonical 影片，回填 `seriesName` / `season`，保留并补充 TMDB 身份，不导入资源。
- `POST /movies/{movieId}/poster/repair`：GYING 对应影片/季海报优先；缺失或来源异常时回退 TMDB 本季图，不复用整剧或其他季封面。
- `POST /posters/repair?limit=50`：无请求体时补全最多 100 部缺图影片；可传 `{"movieIds":["id1","id2"],"refreshExisting":false}` 只补所选缺图。`refreshExisting=true` 替换所选已有海报，必须显式提供 1–100 个 ID；空选择不扩大成全库操作。
- `POST /movies/{movieId}/seasons/ensure?maxPages=`：电影、剧集和动漫均可异步补齐系列/季元数据，优先 GYING（`includeResources=false`），缺失时回退 TMDB 唯一剧集匹配与真实季信息。刷新现有 GYING 元数据与双源身份，并补充缺失成员；已有 ACTIVE、已审核、NORMAL 的合集分享按明确季范围绑定原 URL/提取码，保留旧 canonical 与绑定，重复操作不重复创建。单季/未知范围/失效/带 `fid` 子目录的链接不扩散，且不发起转存或额外外部发布。结果 `mode=METADATA_AND_EXISTING_COLLECTION`，含 `status=COMPLETED|PARTIAL|SKIPPED`、`metadataCreated`、`metadataRefreshed`、`gyingCreated`、`tmdbCreated`、`bound`、`existingBindings`、`missingSeasons`、`warnings`、`items`；新绑定来源标记 `COLLECTION_BINDING`。
 电影及电影型动漫按 TMDB 官方合集成员补缺（`mode=METADATA_ONLY_COLLECTION`），每部保留独立 movie ID，来源身份季号仍为 0，不把一条电影资源推测绑定到其他续集。

内部 `gying-source` 服务提供 `GET /search?q=&typeCode=&limit=`，使用当前共享会话访问
GYING 精确搜索页；TMDB canonical 影片会先按标题、类型、年份和主创严格匹配来源身份，
没有可靠结果时才回退到片库目录扫描。

向 GYING 发布网盘资源使用 `POST /res/pan/add`；表单中的 `binds[0][dir]` 传递
`mv|tv|ac` 类型，`binds[0][id]` 传递 GYING 影片 ID。不得再把类型和 ID 拼入请求路径。

### 网站与 QQ 共用的拒绝关键词

- 内置词库：`backend/src/main/resources/moderation/search-blocklist.txt`，覆盖明确成人资源词、繁体变体、英文词与成人站点标识；普通行归一化子串匹配，`@token` 使用英文边界避免 AV 误伤 Avatar。
- `qq.bot.blocked_keywords` 是管理员追加词，每次请求读取最新配置，无需重启；逗号、分号、顿号和换行分隔，空配置不关闭基础词库。
- NFKC、大小写、空格、标点和零宽字符归一化；入口、候选名称、旧候选选择/翻页/切换资源均复查。拒绝后清除候选上下文，不依赖 PanSou 过滤。
- 关键词策略不能保证识别所有伪装资源，也可能误匹配；需要持续维护并保留人工审核。

## QQ 自动化

- `GET /api/qq-bot/health`：机器人配置状态。
- `POST /api/qq-bot/onebot?token=`：NapCat/OneBot 上报。
- `GET /api/qq-bot/search-reply?keyword=&userKey=&token=`：OpenClaw 被动回复文本；`userKey` 维持 5 分钟影片和资源候选上下文。机器人收到 `/movie`、`/search`、`搜` 或 `找` 搜索后先回“正在搜索资源，请稍后...”，也支持无空格的“搜片名/找片名”。完成后先返回影片候选；用户选定影片后再返回包含片名、年份、类型、地区、TMDB 评分和简介的影片元数据，以及最多 10 条资源名称/画质候选，其中优先展示夸克资源。只有用户回复单个资源序号时才创建并执行对应的夸克或迅雷转存任务；成功回复会再次附带影片元数据和最终自有分享。可回复“夸克”或“迅雷”筛选候选，不支持“夸克10”等按数量批量转存指令。若所选分享失效，统一回复“该分享已失效，不可访问”，并保留候选上下文，用户可继续回复其他序号，无需重新搜索；无视频文件或平台违规/拦截时也会保留候选并提示继续选择。
- `/api/admin/qq-automation/*`：配置、群搜索日志和频道发帖日志。
- `POST /api/admin/qq-automation/daily-recommendation/run`：管理员立即触发一次群推荐，属于真实消息发送。需核对 QQ 官方主动消息权限与目标群；NapCat 生产已停用，不作为默认备用或验收路径。
- 群每日推荐配置包含启用开关、时间、篇数、目标群号和消息模板。模板支持 `{{title}}`、`{{year}}`、`{{genres}}`、`{{rating}}`、`{{summary}}`、`{{resources}}`、`{{detailUrl}}`；默认模板不包含本地 `localhost` 详情链接，需部署到服务器后再在管理页显式加入 `{{detailUrl}}`。

查询先读取本地正式资源。无法精确命中时优先请求 GYING 搜索并返回带来源的影片候选；用户回复影片序号后只导入对应元数据，再返回资源名称/画质候选。用户继续回复单个资源序号后，才按选中的资源和提供方创建、执行转存并发布自有分享；GYING 无可用资源时再进入本地 PanSou、外部 Panso API 的候选发现流程。模糊影片结果和资源候选在用户确认前都不会触发转存。

## 多平台发布

基础路径：`/api/admin/social-publishing`。

- `GET /overview`：目标列表、发布统计和独立发布容器的 QQ/微博网页会话状态。
- `GET /qq-accounts`：列出独立发布器中的 QQ 账号及授权状态。
- `POST /qq-accounts/login`：为新的账号标识生成 QQ 授权二维码并启动后台轮询。
- `GET /qq-accounts/{accountKey}/login-status`：查询扫码授权结果。
- `DELETE /qq-accounts/{accountKey}`：删除 QQ 账号凭据并停用其发布目标，保留历史日志。
- `POST /targets`：为已授权 QQ 账号或微博 `default` 网页会话添加发布目标。
- `PUT /targets/{id}`：更新目标名称、频道号、版块、启用状态、每日时间、每次条数、间隔和模板。
- `POST /targets/{id}/publish-next?runNow=true`：对单个目标发布下一条热度候选。
- `POST /publish-next?runNow=true`：对请求体中的目标 ID 批量发布；空数组表示全部启用目标。
- `GET /logs?status=&platform=&page=&size=`：发布审计日志。
- `POST /logs/{id}/retry`：仅 PENDING/PREPARE_FAILED 可普通重试，且目标必须存在并启用；PREPARING/PUBLISHING/UNKNOWN/历史 FAILED/未识别状态及 POSTED 均返回 409，不重置原日志。

独立发布容器内部提供 `GET /health`（匿名只返回存活信息）和受 `X-Internal-Token` 保护的 `POST /posts/{logId}`。内部 publisher 对 POSTED 返回已有结果，与管理端 retry 的 409 不同；HTTP 200 也可能包含 `ok:false / status:UNKNOWN`，必须检查业务状态。

`publish-next`、重试和账号删除有真实副作用；`runNow=false` 仍可写入队列，不是 dry-run。完整状态表、去重限制及协调部署/回滚见[发布运维](social-publishing.md)。原 QQ 机器人、原频道账号和原频道定时任务保持独立。

## 认证与账号

- `POST /api/auth/login`：登录标识支持**用户名或邮箱** + 密码，返回 JWT。先按用户名精确匹配，未命中且含 `@` 时按规范化小写邮箱匹配；用户名不含 `@`。按账号 5 分钟 10 次限流。
- `POST /api/auth/email-code`：公开注册发送邮箱验证码。需注册策略允许（公开注册未达上限或携带有效邀请码）；按邮箱 60 秒冷却、按客户端 IP 每小时 10 次限流。
- `GET /api/auth/registration-policy?invite=`：返回是否允许公开注册、是否已达注册上限、邀请码是否有效等。
- `POST /api/auth/register`：注册。用户名 3-50 字符且不能含 `@`（避免与邮箱登录标识冲突），昵称默认等于用户名；开启邮箱验证时需 `emailCode`；公开注册额度用尽后仍可用邀请码注册或由管理员建号。
- `PUT /api/auth/profile`：登录用户修改**站内昵称**，请求体 `{nickname}`。昵称 1-20 个字符（按码点计）且禁止控制字符，用户名不可修改；成功返回 `{message, nickname}`。
- 站内展示统一使用昵称：`sys_user.nickname` 为空或空白时回退登录用户名；评论、回复通知、资源上传者和管理端用户列表均按此规则展示。
- `POST /api/auth/reset-password/code`：登录用户请求重置密码验证码。仅对有绑定邮箱的账号发送，返回 `{message, email}`，`email` 为掩码形式（如 `ab***@example.com`）；未绑定邮箱返回 400。
- `POST /api/auth/reset-password`：请求体 `{password, emailCode}`。必须通过 `emailCode` 校验（用途 `reset-password`）；密码至少 12 字符且不超过 72 UTF-8 字节。成功后吊销该用户全部登录设备；验证码缺失、过期或错误返回 400。
- `POST /api/auth/email/code`：登录用户请求更换邮箱验证码，请求体 `{email}`（新邮箱，须合法且与当前邮箱不同）。返回 `{message}`；60 秒冷却，按 IP 每小时 10 次限流。
- `PUT /api/auth/email`：请求体 `{email, emailCode}`。先校验验证码（用途 `change-email`）再更新邮箱；邮箱每 90 天只能修改 1 次，冷却期内返回 429，邮箱已被占用返回 409，与原邮箱相同返回 400；成功返回 `{message, email, emailUpdatedAt, emailChangeAvailableAt}`。
- `GET /api/auth/me`：返回 `{id, username, nickname, role, email, emailUpdatedAt, emailChangeAvailableAt, emailVerificationEnabled}`；`emailChangeAvailableAt` 仅在 90 天冷却期内有值，未配置邮件服务时 `emailVerificationEnabled=false` 且验证码校验直接放行。
- 验证码用途隔离：注册沿用 `register:email:code:`，重置密码与更换邮箱分别使用 `email-code:reset-password:`、`email-code:change-email:` 前缀，有效期均为 5 分钟。

## 后台监控

以下接口保持管理员 JWT 鉴权（匿名 401、普通用户 403），直接返回 JSON 对象，不包含访客/IP/UA 哈希。

- `GET /api/admin/monitoring/overview`：今日概览及近 14 天趋势，返回 `date`、`timeZone`、各今日统计、`traffic`、`hotSearches`。`requestsToday`/趋势 `requests` 只统计 `event_type=API`；页面浏览单独统计，访客按页面浏览中的 `visitor_hash` 去重；4xx/5xx 仅来自 API。
- 发布摘要按今日创建的记录计数：`socialPostedToday=POSTED`、`socialFailedToday=FAILED/PREPARE_FAILED`、`socialPendingToday=PENDING/PREPARING/PUBLISHING`、`socialUnknownToday=UNKNOWN`。成功率的分母仅为已发布 + 失败，不把处理中或未知结果算成失败；本监控页没有重试或发帖动作。
- `GET /api/admin/monitoring/logs?type=access&q=&status=all&page=1&size=20`：只查询指定日志流，返回 `{records,total,page,size,pages}`。`type` 为 `access/searches/resourceOperations/qqSearches/socialPosts`；旧调用不传 `type` 时仍返回原多类数组结构。访问日志追加 `client_ip`、`user_agent`，关键词搜索覆盖 IP/UA；历史值为 null，UA 最多 1024 个 Unicode 字符。仅 ADMIN 可读，响应为 `Cache-Control: no-store`；IP 只采用可信代理解析结果或直连地址，不信任任意请求头。
- `q` 为最多 100 字符的字面量子串（`%`、`_`、`!` 不展开为通配符），查询字段随日志类型变化。筛选在计数与分页前执行，不限于最近 50 条；`size` 限制为 1–100，越界页钳制到末页，空结果页为 1。
- 通用 `status=all/success/failure`：访问日志为 2xx–3xx / 4xx–5xx，站内搜索为有结果 / 无结果；发布成功为 POSTED，失败为 FAILED/PREPARE_FAILED。仅平台发布额外支持 `pending`（PENDING/PREPARING/PUBLISHING）、`attention`（UNKNOWN）。无效类型或不适用的状态过滤返回 400。
- `GET /api/admin/monitoring/hot-searches?date=YYYY-MM-DD&limit=20`：指定日期热词；Redis 为空或故障时按该日边界回退数据库，不影响其他概览查询。

影片详情的资源数量在客户端按**当前可见列表**计算：DISK 为网盘，MAGNET/TORRENT 为磁力/种子（含通过网盘分享的种子归档）；ONLINE 与未知类型各自独立，不计入磁力/种子。各网盘子标签同时显示该盘的资源条数，保留 0 与超过 99 的精确值。

## 其他管理接口

- `/api/admin/resource-reports`：举报处理。
- `/api/admin/comments`：评论管理。
- `GET /api/admin/users`：分页查询用户，返回含 `nickname`；`keyword` 同时匹配用户名、昵称和邮箱。角色、启用状态等管理操作保持原契约。
- `POST /api/admin/users`：仅 ADMIN 可直接新建用户。请求 `{username, email, password, role}`；用户名 3–50 字符、邮箱必填且唯一、密码至少 12 字符且不超过 72 UTF-8 字节；`role` 默认 USER，仅允许 USER/PUBLISHER。独立于公开注册/邀请码/邮箱验证码，不允许访客绕过注册策略；成功 201，返回 `{id, username, email, role, enabled}`，不返回密码。非法输入 400，重复用户名或邮箱 409，未登录/非管理员 401/403。
- `PUT /api/admin/users/{id}`：仅 ADMIN 可编辑用户资料，请求体可含 `username`、`nickname`、`email`、`role`（`nickname` 为 1-20 字符）。用户名 3–50 字符且不与他人重复，邮箱规范化（trim + 小写）后不与他人重复，角色仅允许 USER/PUBLISHER 且不能修改自己的角色；成功 200 返回更新后的 `{id, username, email, role, enabled}`，非法输入 400，用户名或邮箱冲突 409，用户不存在 404。
- `PUT /api/admin/users/{id}/password`：仅 ADMIN 可重置指定用户密码，请求 `{password}`；密码至少 12 字符且不超过 72 UTF-8 字节。成功后调用统一 `resetPassword`，密钥哈希更新并吊销该用户全部登录设备；返回 `{message, userId, sessionsRevoked:true, self}`，`self` 表示管理员重置的是自己（当前会话同样失效）。非法输入 400，用户不存在 404。
- `GET /api/admin/config`：读取系统设置；搜索频率和留言频率未配置时分别返回虚拟默认值 5，不在读取时写数据库。
- `PUT /api/admin/config/{key}`：以 `text/plain` 保存配置。`resource.search.rate_limit_per_minute` 接受 1–60，`comment.rate_limit_per_minute` 接受 1–120；非法值 400，未存在的配置在保存时新增。


## AI 片库助手

全部 `/api/ai/**` 使用网站用户 Bearer JWT，游客 401。页面标注内测；回答不返回模型名称。

- `GET /api/ai/status`：返回模型队列的 `available/busy` 和 `catalogAvailable=true`；确定性找片/推荐可独立于模型提供。
- `POST /api/ai/query`：`{message,history}`，message 1–1000 字符、history 最多八条，仅 user/assistant、总预算 10,000 字符。明确片名或推荐请求立即返回 HTTP 200 的 `{jobId,status:"SUCCEEDED",result}`；详情/资源追问进入队列，HTTP 202，轮询 `GET /api/ai/jobs/{id}`。任务仅所属用户可读，6 次/分钟。
- `GET /api/ai/search?keyword=&page=1&size=20`：完整标题/英文名/别名/系列名检索。keyword 1–60 字符，size 钳制为 1–30，页码钳制到有效页；返回 answer、evidence 和 `catalog:{kind:"search",keyword,total,page,pages,size}`。30 次/分钟。
- `GET /api/ai/recommendations`：最多 12 项，返回 `catalog.kind="recommendation"`、personalized 与逐片 reasons。收藏及近期浏览画像、内容/共同收藏、评分/热度与多样性重排；10 次/分钟。自然语言 query 另可携年份、分类、类型与高分约束。
- `POST /api/ai/views`：仅 `{movieId}`，登录用户实际详情浏览记录。影片须活动且未软删除；30 分钟内同用户/影片按 SQL 去重，返回 `{ok:true,recorded:boolean}`，30 次/分钟。不使用访客 IP 推断用户。
- `evidence[].queriedAt` 是本次查询时间；资源 NORMAL 与数量来自片库存量过滤，未对外部分享实时验活。

`/api/internal/ai/**` 只允许专用 `X-AI-Bridge-Token`，公网 nginx 返回 404；本机桥接主动轮询固定回环，不公开 Ollama 或助手监听。算法与当前上限见 [实现与上线](ai-recommendations-20261020.md)。
