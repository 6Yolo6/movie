# 接口文档

所有管理接口要求管理员 JWT；普通用户接口要求登录 JWT。响应主要使用 `{ code, message, data }`。

## 影片与用户内容

- `GET /api/movies/list`：影片分页、分类、筛选和排序。
- `GET /api/movies/filters?category=`：动态筛选项。
- `GET /api/movies/{id}`：影片详情和已审核资源。
- `GET /api/movies/series?name=`：剧集季信息。
- `POST /api/favorites/toggle?movieId=`：收藏/取消收藏。
- `GET /api/favorites/hot?period=day|week|month|all`：站内收藏热门。
- `GET /api/comments/{relateId}`、`POST /api/comments`：评论和回复。
- `POST /api/comments/{id}/upvote`、`DELETE /api/comments/{id}`：点赞、隐藏评论。
- `GET /api/notifications`、`PUT /api/notifications/read-all`：站内通知。

## 资源

- `POST /api/resources`：提交网盘、磁力、种子或在线播放资源。
- `GET /api/resources/mine`：我的投稿。
- `PUT /api/resources/{id}`：编辑自己的资源。
- `DELETE /api/resources/{id}`：软删除自己的资源。
- `POST /api/resources/{id}/report`：举报失效链接。
- `GET /api/resources/admin/all`：管理列表。
- `PUT /api/resources/{id}/audit`、`PUT /api/resources/batch/audit`：审核。
- `PUT /api/resources/admin/{id}/link-status`：更新链接健康状态。
- `POST /api/resources/admin/invalid-checks/scan`：实时检测候选。
- `POST /api/resources/admin/repair-invalid`：后台修复失效资源。

资源质量字段包括 `quality`、`subtitle`、`fileSize`、`versionNote`。

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
- `POST /published-resources/repair-by-ids`：请求体为 GYING `panlist.id` 字符串数组，最多 100 个；只验链并修复当前账号中精确匹配且明确 `INVALID` 的资源。
- `GET /jobs/{jobId}`：后台任务状态。

内部 `gying-source` 服务提供 `GET /search?q=&typeCode=&limit=`，使用当前共享会话访问
GYING 精确搜索页；TMDB canonical 影片会先按标题、类型、年份和主创严格匹配来源身份，
没有可靠结果时才回退到片库目录扫描。

向 GYING 发布网盘资源使用 `POST /res/pan/add`；表单中的 `binds[0][dir]` 传递
`mv|tv|ac` 类型，`binds[0][id]` 传递 GYING 影片 ID。不得再把类型和 ID 拼入请求路径。

## QQ 自动化

- `GET /api/qq-bot/health`：机器人配置状态。
- `POST /api/qq-bot/onebot?token=`：NapCat/OneBot 上报。
- `GET /api/qq-bot/search-reply?keyword=&userKey=&token=`：OpenClaw 被动回复文本；`userKey` 维持 5 分钟影片/网盘上下文。资源结果必须包含已验证的自有夸克分享，OpenClaw 补丁据此生成二维码。
- `/api/admin/qq-automation/*`：配置、群搜索日志和频道发帖日志。

查询先读取本地正式资源。无法精确命中时优先请求 GYING 搜索并返回带来源的影片候选，用户回复序号后才按选中的 GYING 类型和影片 ID 采集、转存；GYING 无可用资源时再进入本地 PanSou、外部 Panso API、转存和发布。模糊影片结果只返回候选，未选择前不触发资源链路。

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

## 其他管理接口

- `/api/admin/resource-reports`：举报处理。
- `/api/admin/comments`：评论管理。
- `/api/admin/users`：用户管理和启用状态。
- `/api/config`：系统配置管理。
