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
- `POST /discoveries/{id}/qq-channel-post?runNow=true`、`POST /discoveries/batch/qq-channel-post?runNow=true`：立即或排队发 QQ。
- `POST /quark/transfers/submit`、`POST /quark/transfers/{taskId}/submit`：转存。
- `GET /missing-resources`、`POST /missing-resources/{movieId}/resolve?source=GYING|PANSOU`：缺网盘资源检查和补全。
- `GET /worker/status`、`POST /worker/run-once?force=true`：Worker。
- `POST /cleanup/duplicate-tmdb?dryRun=true`、`POST /cleanup/mismatched-resources?dryRun=true`：清理预览/执行。

## GYING

基础路径：`/api/admin/gying-source`。

- `GET|PUT /account`：读取凭据配置状态或切换当前运行时账号。
- `GET /candidates/recent`、`GET /candidates/trailers`：候选。
- `POST /movies/{typeCode}/{mid}/ensure`、`POST /trailers/ensure`：确保资源。
- `POST /published-resources/check`、`POST /published-resources/repair`：检查和修复本人资源。
- `GET /jobs/{jobId}`：后台任务状态。

## QQ 自动化

- `GET /api/qq-bot/health`：机器人配置状态。
- `POST /api/qq-bot/onebot?token=`：NapCat/OneBot 上报。
- `GET /api/qq-bot/search-reply?keyword=&userKey=&token=`：OpenClaw 被动回复文本。
- `/api/admin/qq-automation/*`：配置、群搜索日志和频道发帖日志。

查询先读取本地正式资源；缺失时才进入 TMDB、PanSou/Panso API、转存和发布。模糊影片结果只返回候选，用户选择或精确匹配后才能触发资源链路。

## 其他管理接口

- `/api/admin/resource-reports`：举报处理。
- `/api/admin/comments`：评论管理。
- `/api/admin/users`：用户管理和启用状态。
- `/api/config`：系统配置管理。
