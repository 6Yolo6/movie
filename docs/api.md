# 接口文档

[导航](README.md) · [静态路由清单](security/api-inventory.md) · [发布规则](social-publishing.md)

管理接口要求 ADMIN，普通用户接口按各方法要求 JWT。响应依接口返回对象、数组或分页结构，不能统一假定 `{code,message,data}`。本文是源码契约；真实验收见[当前状态](current-project-status.md)。完整旧说明见[整理前原文](history/README.md#git-history)。

## 影片与用户内容

| 接口 | 契约 |
| --- | --- |
| `GET /api/movies/list`、`/filters?category=`、`/series?name=`、`/{id}` | 公开分页、动态筛选、系列及详情；详情仅展示可见资源 |
| `GET /api/movies/hot-searches` | 默认 days=7、limit=10；范围 1–30/1–20；返回 `[{keyword,count}]`，Redis 空/故障回退 SQL，与影片搜索共用限流 |
| `GET /api/movies/{id}/recommendations` | 默认 8、范围 1–12；排除自身/停用/软删除；无候选空数组，非法 ID 400、不存在 404 |
| `POST /api/favorites/toggle?movieId=`、`GET /api/favorites/hot?period=` | 收藏切换；热门 period=day/week/month/all；其他读接口见路由清单 |
| `GET /api/comments/{relateId}`、`POST /api/comments` | 评论/回复；提交默认 GENERAL，白名单清洗，默认 5 次/分钟 |
| `POST /api/comments/{id}/upvote`、`DELETE /api/comments/{id}` | 点赞、隐藏；服务端校验所有权/权限 |
| `GET /api/notifications`、`PUT /api/notifications/{id}/read`、`/read-all` | 本人通知、单条/全部已读 |

## 资源

- `POST /api/resources`：PUBLISHER/ADMIN；管理员免 `resource.max.per.user` 总量，其他间隔/重复校验仍执行。质量字段 `quality/subtitle/fileSize/versionNote`。
- `GET /mine`、`DELETE /{id}`：本人投稿/软删除；`POST /{id}/report` 举报。
- `GET /{id}/bindings`：本人资源或 ADMIN；返回绑定影片、资源 ID 与 `bindingVersion`。同系列/URL/类型/provider 且上传者相同或一侧空的历史组可合并；两个非空不同上传者不合并。
- `PUT /{id}`：先读 bindings，再传 `bindingVersion`、最多 50 个 `bindMovieIds`。已有绑定原位更新，只新增新绑定，不删除取消勾选历史行；版本陈旧或目标冲突 409，成功 `{message,updatedBindings,boundCount}`。
- 管理读取 `/admin/all`、`/admin/invalid-checks`；审核 `PUT /{id}/audit`、`/batch/audit`；健康 `PUT /admin/{id}/link-status`；检测 `POST /admin/invalid-checks/scan`。
- 单条 `POST /admin/{id}/repair-invalid` 按 QUARK/XUNLEI 修复 INVALID/SUSPECTED_INVALID；批量 `POST /admin/repair-invalid` 返回 jobId，轮询 `GET /admin/repair-invalid/jobs/{jobId}`。自有新分享可访问、url_hash/link_status=NORMAL、任务与可选 GYING 同步分别核验。[修复规则](resource-hub.md#失效资源单条修复)。

本节相对路径均基于 `/api/resources`；其余管理删除/表单/候选接口见静态清单。

## 网页资源搜索

| 接口/约束 | 行为 |
| --- | --- |
| `POST /api/resource-search/query` | 登录用户 `{keyword}` 1–80 字符；202 `{jobId,status}`；不等待外部链路 |
| `GET /api/resource-search/jobs/{jobId}` | 仅本人；不存在/过期/重启丢失/他人均 404；QUEUED/RUNNING/SUCCEEDED/FAILED |
| 容量与恢复 | 并发 2、等待 8、缓存 200；满载 429；完成保留 15 分钟，内存不跨重启；同用户在途复用 |
| 前端 | 每 2 秒轮询；sessionStorage 续查；网络失败只重查、不重转存；成功清已发送草稿，失败保留；最近 20 轮候选仅最新可操作 |
| 搜索频率 | `resource.search.rate_limit_per_minute` 默认 5、范围 1–60；资源选择/翻页不计，选影片/重新搜/其他资源计；网页与 QQ bucket 独立 |

抽屉入口游客可见，游客提示登录且不调用 API；详情无资源时跳 `/resource-search?q=<片名>&auto=1`，q 截断 120 字符。GYING 超时 3/20 秒，连接/5xx/429/鉴权失败熔断 30 秒；AUTO/QQ 回退 PanSou，显式 GYING 管理任务如实失败。

### 网页搜索：库内资源优先与仅二维码

精确片名先返回已审核/活动/未删除/NORMAL 的库内资源，包含人工资源；同名年份/分类冲突先确认。快路径只读，不等待来源/验活/转存；明确要求其他资源才进入外部候选。网页只显示名称、提取码、二维码；地址过长不给明文回退。二维码可解码，不是防提取。QQ 待上映/TRAILER 也先检查有效库内资源。

## Resource Hub

基础 `/api/admin/resource-hub`，完整方法见[路由清单](security/api-inventory.md)。

| 类别 | 主要相对路径 |
| --- | --- |
| 配置/调度 | GET overview/config/tasks/worker/status；PUT config；POST worker/run-once |
| 元数据 | POST tmdb/metadata-sync、tmdb/metadata-sync/{taskId}/run |
| 发现 | POST discover、discover/{taskId}/run；GET discover/jobs/{jobId}、discoveries |
| 发布/重跑 | POST discoveries/{id}/publish、discoveries/publish、discoveries/{id}/retry-share-publish、discoveries/batch/publish、discoveries/batch/retry-share-publish、discoveries/retry-discovered |
| 校准/频道 | POST discoveries/reconcile（dryRun=true、limit 默认 2000）；discoveries/{id}/qq-channel-post、discoveries/batch/qq-channel-post（runNow=true） |
| 转存 | POST quark/transfers/submit、quark/transfers/{taskId}/submit |
| 补资源 | GET missing-resources；POST missing-resources/{movieId}/resolve、missing-resources/batch/resolve；source=GYING/PANSOU，批量最多 20 部 |
| 清理 | POST cleanup/duplicate-tmdb、cleanup/mismatched-resources；先 dryRun=true |

发现筛选 `keyword/movieId/status/source/sortOrder/page/size`。内部 `/api/internal/resource-hub` 的定向 p2p sync、发现发布、双盘任务 run、ingest/health 需内部认证，公网拒绝。高频 Worker 的元数据政策与独立队列见[资源中心](resource-hub.md)，不能由旧接口字段推断自动视频消费已启用。

## GYING

基础 `/api/admin/gying-source`；所有写操作有实际副作用，ensure 不都等于纯元数据。

- GET/PUT account 只公开配置状态、切换运行时账号；GET candidates/recent、trailers、catalog；异步 GET jobs/{jobId}。
- POST recent/ensure：`{typeCode,mid}` 数组最多 60；movies/{typeCode}/{mid}/ensure、trailers/ensure、catalog/ensure、ingest 等见清单。
- POST published-resources/check、repair、sync；sync 按 source_id/URL 去重。repair-by-ids 接受最多 100 个 panlist.id，只修当前账号精确且明确 INVALID 项。
- POST movies/{movieId}/metadata/sync：严格匹配 canonical，补双源身份/季号，不导入资源；poster/repair：GYING 本季优先，TMDB 本季回退。
- POST posters/repair：无 body 补缺（limit 默认 50、最多 100）；`{movieIds,refreshExisting}` 最多 100，刷新已有必须显式 1–100 ID，空选择不扩成全库。
- POST movies/{movieId}/seasons/ensure：异步；GYING `includeResources=false`，TMDB 唯一真实季/官方合集回退。仅有效已审核 NORMAL 合集的明确季范围复用 URL/提取码；单季/未知/失效/fid 不扩散，不转存、不额外外发。电影续集仅元数据。mode=METADATA_AND_EXISTING_COLLECTION 或 METADATA_ONLY_COLLECTION；status=COMPLETED/PARTIAL/SKIPPED，返回 created/refreshed/bound/missingSeasons/warnings/items，新增绑定来源 COLLECTION_BINDING。

source 内部搜索 `/search`、目录 `/catalog`、BT `/bt/{btId}` 与认证 `/torrent-file/{typeCode}/{mid}/{btRef}` 见[P2P](gying-p2p-collection.md)。GYING 网盘外发固定 `POST /res/pan/add`，`binds[0][dir]=mv|tv|ac`、`binds[0][id]=外部影片ID`，不拼进路径。

### 网站与 QQ 共用的拒绝关键词

基础词库 `backend/src/main/resources/moderation/search-blocklist.txt`：普通归一化子串、`@token` 英文边界；`qq.bot.blocked_keywords` 动态追加，空值不关闭基础库。NFKC/大小写/空白/标点/零宽归一化；入口、名称、旧选择/翻页/切换均复查，拒绝清上下文。仍需人工审核，不能保证识别全部伪装。

## QQ 自动化

- `/api/qq-bot/health` 仅最小存活；search-reply/onebot 需 Authorization 或 X-QQ-Bot-Token 头，不能用旧 query token。keyword/userKey 最多100字符，userKey 保留5分钟上下文；公网均拒绝。onebot 历史兼容，NapCat已停用。
- `/movie`、`/search`、搜/找（含无空格）先回进度，再给影片候选；选影片只导元数据并给最多 10 个资源，夸克优先；仅选单资源序号才按 provider 转存并返回最终自有分享。不支持“夸克10”批量；失效/无视频/平台拦截保留候选供另选。
- `/api/admin/qq-automation` 管理 overview/config/bot-searches/channel-posts；每日推荐属于 backend，模板/目标/官方权限先核对。`POST /api/admin/qq-automation/daily-recommendation/run` 会真实发送，必须核对目标、官方主动消息权限及当前授权。
- 模板变量 `title/year/genres/rating/summary/resources/detailUrl`；默认不含 localhost 详情地址。原频道 CLI 与独立发布器分开。[发布规则](social-publishing.md)。

## 多平台发布

基础 `/api/admin/social-publishing`：overview、QQ 账号扫码/状态/删除、微博 login/status、targets CRUD、publish-next、logs/retry，全部完整路径见清单。

普通 retry 仅 PENDING/PREPARE_FAILED 且目标存在/启用；其余含 POSTED 返回 409，不改状态。内部 publisher `POST /posts/{logId}` 受 `X-Internal-Token` 保护，POSTED 返已有结果；HTTP 200 仍可能 ok=false/status=UNKNOWN。runNow=false 仍可写队列，批量空目标表示全部启用；账号删除会删凭据并停目标、保留日志。[状态/回滚](social-publishing.md)。

## 认证与账号

| 接口（基础 `/api/auth`） | 契约 |
| --- | --- |
| POST login | 用户名优先，未命中且含 @ 再按小写邮箱；5 分钟 10 次；返回 JWT |
| GET registration-policy；POST email-code/register | 注册策略/邀请码；用户名 3–50 且无 @；邮箱码 60 秒冷却、IP 每小时 10 次；密码至少 12 字符且最多 72 UTF-8 字节 |
| PUT profile | nickname 1–20 码点、无控制字符；空白旧昵称展示回退用户名；用户名不可自行改 |
| POST reset-password/code；POST reset-password | 登录用户绑定邮箱发码，返掩码邮箱；`{password,emailCode}` 校验后吊销全部设备；缺邮箱/无效码 400 |
| POST email/code；PUT email | `{email}` 发码、`{email,emailCode}` 更换；90 天冷却 429、重复 409、相同 400；返更新时间/可改时间 |
| GET me/devices；DELETE devices/{id} | 本人资料/设备；me 包含昵称/邮箱/冷却时间/emailVerificationEnabled |

验证码 register/reset-password/change-email 独立前缀、均 5 分钟。邮件服务未配置时当前代码 `emailVerificationEnabled=false`，验证码验证直接放行；部署需核对策略。

## 后台监控

ADMIN、no-store；直接 JSON。overview 今日/14 天、date/timeZone；请求/4xx/5xx 只计 event_type=API，页面浏览另计、访客按 PAGE_VIEW 的 visitor_hash 去重。

`GET /api/admin/monitoring/logs`：type=access/searches/resourceOperations/qqSearches/socialPosts 时返 `{records,total,page,size,pages}`；省略 type 兼容旧多数组。q ≤100 为字面子串（%/_/! 不扩通配）、size 1–100、超页钳制、空页 1；非法 type/status 400。status=all/success/failure，发布另有 pending/attention。访问日志实际 IP/UA（UA ≤1024 码点），旧值 null，禁止伪造头回填；字段契约见[数据库](database.md#5-访问日志客户端身份)，原[身份验收](history/releases.md#earlier-releases)保留历史范围。hot-searches?date&limit 指定日 Redis 回退 SQL。

发布摘要 POSTED/FAILED+PREPARE_FAILED/PENDING+PREPARING+PUBLISHING/UNKNOWN 分开；成功率只除已发布+失败。资源数量按可见内容：DISK、MAGNET/TORRENT（含网盘种子归档）、ONLINE、未知分别计，保留 0 和 >99。

## 其他管理接口

- `/api/admin/users`：分页 keyword 匹配用户名/昵称/邮箱；POST `{username,email,password,role}`，role 仅 USER/PUBLISHER、默认 USER，独立注册策略；201，重复 409、非法 400、鉴权 401/403。
- PUT users/{id}：昵称 1–20、用户名 3–50、规范邮箱唯一；USER/PUBLISHER、不能改本人角色。password：重置后吊销全部设备，返 sessionsRevoked/self。enabled/role/impersonate 等见清单，不能从摘要推断无副作用。
- GET config：未配置搜索/评论频率只返虚拟默认 5；PUT config/{key} 为 text/plain，搜索 1–60、评论 1–120，非法 400、缺键保存时新增。
- 评论/举报/邀请管理路径见静态清单。

## AI 片库助手

`/api/ai/**` 网站 JWT，游客 401；确定性检索/推荐独立于模型。接口响应不含模型名称。

| 接口 | 契约 |
| --- | --- |
| GET status | available/busy、catalogAvailable=true |
| POST query；GET jobs/{id} | message 1–1000；history ≤8 条、偶数且 user/assistant 交替，总预算 10000 字符；可选 requestKey/previousJobId，未知字段 400；直接结果 200 SUCCEEDED，模型队列 202；6 次/分钟、任务仅本人 |
| DELETE jobs/{id} | 取消本人模型任务，终态 FAILED；跨用户 404，旧回执拒绝；当前生产已部署，网页 Abort 不自动调用取消 |
| GET search | keyword 1–60；page/size 默认 1/20、size 1–30、有效页钳制；kind=search；30 次/分钟 |
| GET natural-search | query 1–1000、同分页与搜索 bucket；kind=natural_search、filters/indexVersion/catalogTruncated；非法范围/时长 400，空 total=0/pages=0/page=1 |
| GET recommendations | 本人收藏/90 天衰减浏览，最多 12、featureVersion；10 次/分钟；排除收藏/已看、冷启动保留 |
| POST views | 仅 `{movieId}`，1–64字母数字下划线连字符，额外字段400；30次/分钟，30分钟SQL去重（无唯一约束）；返ok/recorded |

自然搜索执行已识别类型/分类/年份范围/评分/最长分钟/排除类型/有限主创地区语言；近几年=当前年起五个日历年，高分≥8，评分取存量最大值；缺失/单集时长不通过最大时长。ICU/BM25+有限主题扩展，片库快照 TTL 60 秒/最多 50000、达到上限标注截断，返回原文片段/理由，不保证任意口语理解。[实现与评测边界](ai-architecture.md#4-bm25embedding-与融合)。

query 可选 `requestKey` 为本人直接结果/模型队列提供保留窗口内幂等，8–64 位字母数字下划线连字符；同键换内容 409。`previousJobId` 只引用本人尚未过期的成功任务，由服务端建立候选和计划上下文，失效/跨用户 404。仅明确片名与简单泛推荐直接返回，复杂条件/联网/追问进入结构化研究。内部固定 `/api/internal/ai/catalog/query` 受桥接认证；网站任务另核 job/lease，受控本机直连可空任务上下文。接口及模型均不能执行任意 SQL/URL。

生产已启用加密队列/回执、续期租约和有界 attempt；不配置状态文件的环境仍只有内存状态。取消、幂等/恢复及保留期限见[执行可靠性](ai-architecture.md#7-租约回执与恢复)，分流与研究见[AI架构](ai-architecture.md)。
