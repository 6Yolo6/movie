# 接口文档

所有管理接口要求管理员 JWT；普通用户接口要求登录 JWT。响应主要使用 `{ code, message, data }`。

## 影片与用户内容

- `GET /api/movies/list`：影片分页、分类、筛选和排序。
- `GET /api/movies/filters?category=`：动态筛选项。
- `GET /api/movies/hot-searches?days=7&limit=8`：顶部搜索框的“最近热门搜索”，公开接口，无需登录，与 `/api/movies` 共用搜索限流。返回 `[{keyword,count}]` 按次数降序，数据取近 N 天 Redis `hot:search:<date>` ZSet 合并求和，Redis 为空或异常时回退 MySQL `site_search_log`；`days` 取值 1-30，`limit` 取值 1-20。
- `GET /api/movies/{id}`：影片详情和已审核资源。
- `GET /api/movies/series?name=`：剧集季信息。
- `POST /api/favorites/toggle?movieId=`：收藏/取消收藏。
- `GET /api/favorites/hot?period=day|week|month|all`：站内收藏热门。
- `GET /api/comments/{relateId}`、`POST /api/comments`：评论和回复；提交缺省类型为 `GENERAL`，按账号应用 `comment.rate_limit_per_minute`（默认 5 次/分钟），内容经白名单清洗。
- `POST /api/comments/{id}/upvote`、`DELETE /api/comments/{id}`：点赞、隐藏评论。
- `GET /api/notifications`、`PUT /api/notifications/read-all`：站内通知。

## 资源

- `POST /api/resources`：发布者/管理员提交网盘、磁力、种子或在线播放资源。管理员不受 `resource.max.per.user` 总量限制；发布者仍受限，提交间隔、重复链接及权限校验不变。
- `GET /api/resources/mine`：我的投稿。
- `PUT /api/resources/{id}`：发布者编辑自己的资源，管理员可编辑任意资源；可传 `bindMovieIds` 追加最多 50 个影片绑定。更新与追加在同一事务中完成，按影片和 URL 跳过已有资源，不删除旧绑定；返回 `{message, boundCount}`。
- `DELETE /api/resources/{id}`：软删除自己的资源。
- `POST /api/resources/{id}/report`：举报失效链接。
- `GET /api/resources/admin/all`：管理列表。
- `PUT /api/resources/{id}/audit`、`PUT /api/resources/batch/audit`：审核。
- `PUT /api/resources/admin/{id}/link-status`：更新链接健康状态。
- `POST /api/resources/admin/invalid-checks/scan`：实时检测候选。
- `POST /api/resources/admin/repair-invalid`：后台修复失效资源。
- `POST /api/resources/admin/{id}/repair-invalid`：管理员单条修复失效或疑似失效的夸克/迅雷云盘资源；成功后原位更新分享，并在存在 GYING 映射时尝试同步发布。

资源质量字段包括 `quality`、`subtitle`、`fileSize`、`versionNote`。

## 网页资源搜索（2026-09-24 已部署）

- `POST /api/resource-search/query`：登录用户提交 `{keyword}`（1–80 字符的片名、候选序号或翻页指令），立即返回 HTTP 202 和 `{jobId, status}`，不在连接内等待外部搜索或转存。
- `GET /api/resource-search/jobs/{jobId}`：仅任务所属用户可查询；不存在、过期、服务重启丢失或其他用户的任务统一返回 404。
- 状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`；成功时同时返回 `reply` 和 `links`，失败只返回脱敏 `message`。
- 建议每 2 秒轮询。同一用户已有进行中任务时复用该任务，不另行执行新指令；全局并发 2、等待队列 8、任务缓存最多 200 条，满载返回 429。完成结果保存 15 分钟，任务保存在内存中，不保证跨后端重启恢复。
- 网页将进行中的任务 ID 保存在当前标签页的 `sessionStorage`，刷新后继续查询。轮询网络故障只重试查询，不重新启动转存。发布此契约时需要同步更新前后端并刷新旧页面。
- 输入发送成功后自动清空已发送内容，不清除等待期间输入的新草稿；失败保留草稿。最近 20 轮对话按用户保存在当前标签页，历史候选不可执行；最新影片/资源候选与翻页支持直接点击，分享卡片支持复制、打开和二维码。
- 入口：左侧抽屉「搜索资源」（登录用户）；影片详情页在无任何资源链接时于资源区底部显示「搜索这部影片的资源」，跳转 `/resource-search?q=<片名>&auto=1` 并自动发起一次搜索。`q` 截断至 120 字符且不复用顶栏的 `keyword` 参数，避免顶部搜索框被误填。
- `resource.search.rate_limit_per_minute` 控制网页每用户每分钟搜索次数，默认 5，范围 1–60，后台保存后即时生效。资源序号选择/翻页不消耗搜索次数；选择影片、重新搜索或“查看其他资源”计入。网页 Redis bucket 与 QQ 独立，不改变 API/边缘防护限流。
- GYING 读取连接/响应超时分别为 3/20 秒；连接错误、5xx、429 或鉴权失效触发 30 秒短时熔断，之后自动允许重试。AUTO/QQ 搜索继续尝试 PanSou；补充来源失败不会丢弃已有有效候选。显式选择 GYING 的管理任务仍报告来源失败，不伪装为成功。

### 网页搜索：库内资源优先与仅二维码（2026-09-24 21:38 已部署）

- `web:` 用户精确命中本地影片时优先返回已审核、活动、未删除、健康状态正常的片库资源；不受自有转存来源筛选限制，包含人工发布。同名不同年份/类型仍需先确认影片。
- 库内快路径只读，不等待 GYING/TMDB/PanSou、分享验活或转存；用户发送“资源”/点击“搜索其他资源”后才进入已有外部候选流程。QQ 链路保持原行为。
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
- `POST /movies/{movieId}/poster/repair`、`POST /movies/{movieId}/seasons/ensure?maxPages=`、`POST /posters/repair?limit=`：影片元数据页的“自动补图 / 补齐剩余季 / 批量补图”。当 GYING 上游不可用（连接错误、5xx、429、鉴权失效）时不直接报错：补齐剩余季改用 PanSou（夸克 + 迅雷候选，按剧集名或 TMDB ID 匹配同剧集、跳过已有 ACTIVE DISK 资源、按季号升序最多 5 个目标，逐季转存并入库），自动补图回退 TMDB 搜索匹配。任务结果返回 `mode=PANSOU_FALLBACK`、`source`、`gyingUnavailable` 与 `{discovered,completed,skipped,failed,reason,items}`；全部失败记为 `FAILED`，否则记为 `SKIPPED` 并说明原因，不再表现为 GYING 源失败。

内部 `gying-source` 服务提供 `GET /search?q=&typeCode=&limit=`，使用当前共享会话访问
GYING 精确搜索页；TMDB canonical 影片会先按标题、类型、年份和主创严格匹配来源身份，
没有可靠结果时才回退到片库目录扫描。

向 GYING 发布网盘资源使用 `POST /res/pan/add`；表单中的 `binds[0][dir]` 传递
`mv|tv|ac` 类型，`binds[0][id]` 传递 GYING 影片 ID。不得再把类型和 ID 拼入请求路径。

## QQ 自动化

- `GET /api/qq-bot/health`：机器人配置状态。
- `POST /api/qq-bot/onebot?token=`：NapCat/OneBot 上报。
- `GET /api/qq-bot/search-reply?keyword=&userKey=&token=`：OpenClaw 被动回复文本；`userKey` 维持 5 分钟影片和资源候选上下文。机器人收到 `/movie`、`/search`、`搜` 或 `找` 搜索后先回“正在搜索资源，请稍后...”，也支持无空格的“搜片名/找片名”。完成后先返回影片候选；用户选定影片后再返回包含片名、年份、类型、地区、TMDB 评分和简介的影片元数据，以及最多 10 条资源名称/画质候选，其中优先展示夸克资源。只有用户回复单个资源序号时才创建并执行对应的夸克或迅雷转存任务；成功回复会再次附带影片元数据和最终自有分享。可回复“夸克”或“迅雷”筛选候选，不支持“夸克10”等按数量批量转存指令。若所选分享失效，统一回复“该分享已失效，不可访问”，并保留候选上下文，用户可继续回复其他序号，无需重新搜索；无视频文件或平台违规/拦截时也会保留候选并提示继续选择。
- `/api/admin/qq-automation/*`：配置、群搜索日志和频道发帖日志。
- `POST /api/admin/qq-automation/daily-recommendation/run`：管理员立即触发一次群推荐。官方 QQBot 主动消息受权限限制时，后端会尝试已配置的 NapCat 备用通道。
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
- `POST /logs/{id}/retry`：重试失败日志。

独立发布容器内部提供 `GET /health` 和受 `X-Internal-Token` 保护的 `POST /posts/{logId}`。原 QQ 机器人、原频道账号和原频道定时任务保持独立。

## 认证与账号（2026-09-26）

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

## 其他管理接口

- `/api/admin/resource-reports`：举报处理。
- `/api/admin/comments`：评论管理。
- `GET /api/admin/users`：分页查询用户，返回含 `nickname`；`keyword` 同时匹配用户名、昵称和邮箱。角色、启用状态等管理操作保持原契约。
- `POST /api/admin/users`：仅 ADMIN 可直接新建用户。请求 `{username, email, password, role}`；用户名 3–50 字符、邮箱必填且唯一、密码至少 12 字符且不超过 72 UTF-8 字节；`role` 默认 USER，仅允许 USER/PUBLISHER。独立于公开注册/邀请码/邮箱验证码，不允许访客绕过注册策略；成功 201，返回 `{id, username, email, role, enabled}`，不返回密码。非法输入 400，重复用户名或邮箱 409，未登录/非管理员 401/403。
- `PUT /api/admin/users/{id}`：仅 ADMIN 可编辑用户资料，请求体可含 `username`、`nickname`、`email`、`role`（`nickname` 为 1-20 字符）。用户名 3–50 字符且不与他人重复，邮箱规范化（trim + 小写）后不与他人重复，角色仅允许 USER/PUBLISHER 且不能修改自己的角色；成功 200 返回更新后的 `{id, username, email, role, enabled}`，非法输入 400，用户名或邮箱冲突 409，用户不存在 404。
- `PUT /api/admin/users/{id}/password`：仅 ADMIN 可重置指定用户密码，请求 `{password}`；密码至少 12 字符且不超过 72 UTF-8 字节。成功后调用统一 `resetPassword`，密钥哈希更新并吊销该用户全部登录设备；返回 `{message, userId, sessionsRevoked:true, self}`，`self` 表示管理员重置的是自己（当前会话同样失效）。非法输入 400，用户不存在 404。
- `GET /api/admin/config`：读取系统设置；搜索频率和留言频率未配置时分别返回虚拟默认值 5，不在读取时写数据库。
- `PUT /api/admin/config/{key}`：以 `text/plain` 保存配置。`resource.search.rate_limit_per_minute` 接受 1–60，`comment.rate_limit_per_minute` 接受 1–120；非法值 400，未存在的配置在保存时新增。
