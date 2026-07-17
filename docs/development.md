# 开发文档

## 验收

```powershell
cd backend
mvn test

cd ../frontend
npm run lint
npm run build
```

涉及 Compose 时再运行：

```powershell
docker compose -f docker-compose.prod.yml config --quiet
```

## 代码约定

- 后端管理员接口使用 `AuthHelper.requireAdmin`，登录用户接口使用 `AuthHelper.requireUser`。
- Resource Hub 采集保持幂等，优先复用 canonical `movie_metadata` 和现有 `resource_link`。
- `movie_metadata`、`resource_link` 和历史关系只软删除；数据库新增注释使用中文。
- 自动夸克资源必须先创建可用自有分享，再发布到正式资源表。
- 凭据只放环境变量、外部服务或进程内存，不写日志和仓库。
- 前端 API 错误优先用 `readApiError` 解析统一响应。

## 页面

- 用户：`/`、`/movie/[id]`、`/messages`、`/my-resources`、`/notifications`。
- 管理：`/admin/audit`、`reports`、`comments`、`users`、`settings`。
- 自动化：`/admin/resource-hub`、`/admin/gying-source`、`/admin/automation`。

## 文档维护

- `current-project-status.md` 只保留当前能力、长期约束和未完成任务。
- 一次性 ID、日期验证、临时补丁过程和已解决故障不要长期写入项目文档。
- API、部署和数据库信息分别只在对应文档维护，避免复制同一说明。
