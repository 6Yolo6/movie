# Gying Movie 片库

Gying Movie 是一个面向电影、剧集、动漫的片库资源管理项目。用户可以浏览片库、筛选影片、收藏影片、提交网盘/磁力/种子/在线播放资源、评论留言求资源；管理员可以审核资源、处理失效举报、管理评论、用户和系统配置。

## 技术栈

- 后端：Spring Boot 3.2、MyBatis Plus、MySQL、Redis、JWT
- 前端：Next.js 16 App Router、React 19、Ant Design、Zustand、i18next
- 数据：`movie_metadata`、`resource_link`、`resource_report`、`comment`、`user_favorite`、`user_notification`

## 核心功能

- 片库浏览：分页、分类、关键词、类型、地区、语言、年份、排序。
- 动态筛选：`/api/movies/filters` 根据当前分类返回可用筛选项。
- 资源提交：支持网盘、磁力、种子、在线播放；支持清晰度、字幕、大小、版本说明。
- 审核闭环：管理员通过/拒绝资源，拒绝原因会通知上传者并在“我的投稿”展示。
- 失效举报：登录用户可举报失效链接，管理员在“举报管理”中处理、误报或标记失效。
- 评论留言：影片评论和留言板支持回复、点赞、删除，后台可管理评论状态。
- 用户体系：注册验证码、登录、JWT 鉴权、角色、禁用/启用、管理员切换账号。
- 收藏热门：用户收藏影片，热门页支持日/周/月/总榜。
- 站内通知：审核结果、链接状态变化、评论回复写入通知中心。
- 主题语言：前端支持日/夜主题和中英文切换。

## 快速启动

1. 准备 MySQL 8、Redis 6、JDK 17、Maven 3.9、Node.js 20。
2. 创建数据库并导入 `backend/src/main/resources/db/schema.sql`。
3. 复制并调整环境变量示例：`backend/.env.example`、`frontend/.env.example`。
4. 启动后端：

```bash
cd backend
mvn spring-boot:run
```

5. 启动前端：

```bash
cd frontend
npm install
npm run dev
```

默认地址：前端 `http://localhost:3000`，后端 `http://localhost:8880`。

默认管理员：`admin / admin123`。

## 文档

- [部署文档](docs/deployment.md)
- [接口文档](docs/api.md)
- [数据库迁移](docs/database.md)
- [开发文档](docs/development.md)

## 验收命令

```bash
cd backend && mvn test
cd frontend && npm run lint && npm run build
```