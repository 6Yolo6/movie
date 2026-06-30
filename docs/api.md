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

## 评论、收藏、通知

- `GET /api/comments/{relateId}`：评论分页，包含回复。
- `POST /api/comments`：发布评论或回复。
- `POST /api/comments/{id}/upvote`：点赞/取消点赞。
- `DELETE /api/comments/{id}`：作者或管理员删除评论。
- `POST /api/favorites/toggle?movieId=`：收藏/取消收藏。
- `GET /api/favorites/hot?range=day|week|month|all`：热门榜。
- `GET /api/notifications`：站内通知。
- `PUT /api/notifications/read-all`：全部标为已读。