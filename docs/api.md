# 接口文档

## 影片

- `GET /api/movies/list?page=1&size=30&category=mv&keyword=&genre=&region=&language=&year=&sort=`：影片分页列表。
- `GET /api/movies/filters?category=mv`：动态筛选项。
- `GET /api/movies/{id}`：影片详情和已审核资源。
- `GET /api/movies/series?name=`：剧集季信息。

## 资源

- `POST /api/resources`：登录用户提交资源。
- `GET /api/resources/mine?page=1&size=20`：我的投稿。
- `PUT /api/resources/{id}`：编辑自己的资源，普通用户编辑后按审核开关重新审核。
- `DELETE /api/resources/{id}`：软删除自己的资源。
- `POST /api/resources/{id}/report`：登录用户举报失效链接，可传 `{ "reason": "..." }`。

资源字段支持：`quality`、`subtitle`、`fileSize`、`versionNote`。

## 管理接口

- `GET /api/resources/admin/all`：资源审核/管理列表。
- `PUT /api/resources/{id}/audit?status=1|2&reason=`：审核资源，拒绝可带原因。
- `PUT /api/resources/batch/audit`：批量审核。
- `PUT /api/resources/admin/{id}/link-status?status=NORMAL|SUSPECTED_INVALID|INVALID`：更新链接状态。
- `GET /api/admin/resource-reports`：资源举报列表。
- `PUT /api/admin/resource-reports/{id}/status?status=HANDLED|FALSE_REPORT|INVALID|PENDING`：处理举报。
- `GET /api/admin/comments`：评论管理列表。
- `PUT /api/admin/comments/{id}/status`：隐藏或恢复评论。
- `GET /api/admin/users`：用户管理列表。
- `PUT /api/admin/users/{id}/enabled`：启用/禁用用户。
- `GET /api/admin/resource-hub/overview`：Resource Hub 概览。
- `GET /api/admin/resource-hub/tasks?page=1&size=20&taskType=&status=`：Resource Hub 任务列表。
- `POST /api/admin/resource-hub/tmdb/metadata-sync`：创建 TMDB 元数据同步任务，可传 `{ "source": "TRENDING_MOVIE_DAY", "page": 1, "maxItems": 20, "runNow": false }`。
- `POST /api/admin/resource-hub/tmdb/metadata-sync/{taskId}/run`：运行已创建的 TMDB 元数据同步任务。
- `POST /api/admin/resource-hub/discover`：创建资源发现任务，可传 `{ "movieId": "xxx", "keyword": "片名 年份", "source": "PANSOU", "maxResults": 10, "runNow": false }`。
- `POST /api/admin/resource-hub/discover/{taskId}/run`：运行已创建的资源发现任务，当前会写入发现结果并生成夸克转存待办。
- `GET /api/admin/resource-hub/discoveries?movieId=&status=&source=&page=1&size=20`：分页查看资源发现结果。
- `POST /api/admin/resource-hub/discoveries/publish?limit=20`：批量发布待入库发现结果到正式资源列表。
- `POST /api/admin/resource-hub/discoveries/{discoveryResultId}/publish`：发布单条发现结果到正式资源列表。
- `POST /api/admin/resource-hub/quark/transfers/submit?limit=5`：批量提交待转存任务到 quark-auto-save。
- `POST /api/admin/resource-hub/quark/transfers/{taskId}/submit`：提交单个待转存任务到 quark-auto-save。
- `GET /api/admin/resource-hub/worker/status`：查看 Resource Hub Worker 开关、运行状态和批量限制。
- `POST /api/admin/resource-hub/worker/run-once?force=false`：手动运行一次 Worker。`force=true` 可在定时 Worker 关闭时手动触发。
- `GET /api/qq-bot/health`：查看 QQ Bot 开关和 NapCat 配置状态。
- `POST /api/qq-bot/onebot?token=`：OneBot/NapCat HTTP 上报入口，接收群消息并异步搜索资源。

QQ Bot 自动转存成功后会优先回复后端创建的“我的夸克分享”链接；如果夸克分享接口失败，会降级回复原始资源链接和转存状态。

## 评论、收藏、通知

- `GET /api/comments/{relateId}`：评论分页，包含回复。
- `POST /api/comments`：发布评论或回复。
- `POST /api/comments/{id}/upvote`：点赞/取消点赞。
- `DELETE /api/comments/{id}`：作者或管理员删除评论。
- `POST /api/favorites/toggle?movieId=`：收藏/取消收藏。
- `GET /api/favorites/hot?range=day|week|month|all`：热门榜。
- `GET /api/notifications`：站内通知。
- `PUT /api/notifications/read-all`：全部标为已读。
