# Gying Movie

片库资源管理项目，面向电影、剧集、动漫资源收录与分享。用户可以浏览片库、按分类和元数据筛选、提交网盘或磁力资源、评论求资源；管理员可以管理用户、审核资源、调整系统配置。

## 技术栈

- 后端：Spring Boot 3.2、MyBatis Plus、MySQL、Redis、JWT
- 前端：Next.js 16 App Router、React 19、Ant Design、Zustand、i18next
- 数据：`movie_metadata` 保存影片元数据，`resource_link` 保存资源链接，`comment` 保存评论和回复

## 主要功能

- 影片列表：支持分类、关键词、类型、地区、语言、年份、评分/时间排序
- 后端分页：`/api/movies/list` 使用 MyBatis Plus 分页，默认每页 30 条，最大 60 条
- 动态筛选：`/api/movies/filters` 从数据库 JSON 字段动态拆分 `genres`、`regions`、`languages`，年份从表中动态读取
- 影片详情：展示海报、评分、演职员、简介、分季信息、资源链接
- 资源提交和审核：用户提交资源，管理员可按系统配置决定是否审核
- 评论功能：支持根评论、回复、点赞、用户/管理员删除
- 账号功能：注册、登录、JWT 鉴权、用户角色

## 本次完成项

- P2 评论点赞/回复：启用 `comment.upvotes` 和 `comment.parent_id`，后端返回根评论分页和一层回复，前端展示回复并可点赞
- P2 前端筛选条件动态获取：前端调用 `/api/movies/filters`，后端从数据库动态生成筛选项
- P2 后端查询加分页：电影列表移除固定数量查询，改为分页参数 `page`/`size`
- P3 评论删除：新增 `DELETE /api/comments/{id}`，评论作者和管理员可软删除评论
- 后端启动报错：移除重复的 `POST /api/comments` 映射，避免 Spring 启动时接口冲突

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- MySQL 8+
- Redis 6+

## 后端运行

1. 创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS gying DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 导入表结构：

```bash
mysql -uroot -p gying < backend/src/main/resources/db/schema.sql
```

3. 修改配置：

编辑 `backend/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/gying?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: Root@123
  data:
    redis:
      host: localhost
      port: 6379
app:
  cors:
    allowed-origin: http://localhost:3000
```

4. 启动：

```bash
cd backend
mvn spring-boot:run
```

后端默认端口：`http://localhost:8880`

## 前端运行

1. 配置接口地址：

在 `frontend/.env.local` 中设置：

```bash
NEXT_PUBLIC_API_URL=http://localhost:8880
```

2. 启动：

```bash
cd frontend
npm install
npm run dev
```

前端默认端口：`http://localhost:3000`

## 常用接口

- `GET /api/movies/list?page=1&size=30&category=mv&genre=剧情`：影片分页列表
- `GET /api/movies/filters`：动态筛选项
- `GET /api/movies/{id}`：影片详情和资源
- `GET /api/comments/{relateId}?page=1&size=10`：评论分页，包含回复
- `POST /api/comments`：发布评论或回复，回复时传 `parentId`
- `POST /api/comments/{id}/upvote`：点赞评论
- `DELETE /api/comments/{id}`：删除评论

## 验证

```bash
cd backend
mvn test

cd ../frontend
npm run build
```


