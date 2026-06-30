# 开发文档

## 本地验收

每轮修改至少运行：

```bash
cd backend
mvn test

cd ../frontend
npm run lint
npm run build
```

## 代码约定

- 后端管理员接口统一使用 `AuthHelper.requireAdmin`。
- 登录用户接口统一使用 `AuthHelper.requireUser`。
- 资源删除使用软删除：`resource_link.status = DELETED`。
- 评论删除使用隐藏状态，不物理删除历史内容。
- 前端 API 错误优先用 `readApiError` 解析 `{ code, message, data }` 或旧字符串响应。

## 重点页面

- `/`：片库首页。
- `/movie/[id]`：详情、资源提交、举报、评论。
- `/messages`：留言板。
- `/my-resources`：我的投稿和拒绝原因。
- `/notifications`：站内通知。
- `/admin/audit`：资源审核。
- `/admin/reports`：举报管理。
- `/admin/comments`：评论管理。
- `/admin/users`：用户管理。
- `/admin/settings`：系统设置。