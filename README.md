# Gying Movie

片库资源管理项目，面向电影、剧集、动漫资源收录与分享。用户可以浏览片库、按分类和元数据筛选、提交网盘或磁力资源、评论求资源；管理员可以管理用户、审核资源、调整系统配置。

## 技术栈

- 后端：Spring Boot 3.2、MyBatis Plus、MySQL、Redis、JWT
- 前端：Next.js 16 App Router、React 19、Ant Design、Zustand、i18next
- 数据：`movie_metadata` 保存影片元数据，`resource_link` 保存资源链接，`comment` 保存评论和回复，`user_notification` 保存站内通知

## 主要功能

- 影片列表：支持分类、关键词、类型、地区、语言、年份、评分/时间排序
- 后端分页：`/api/movies/list` 使用 MyBatis Plus 分页，默认每页 30 条，最大 60 条
- 动态筛选：`/api/movies/filters` 从数据库 JSON 字段动态拆分 `genres`、`regions`、`languages`，年份从表中动态读取
- 影片详情：展示海报、评分、演职员、简介、分季信息、资源链接
- 资源提交和审核：登录用户可提交网盘、磁力、种子或在线播放资源，管理员可按系统配置决定是否审核
- P2P 资源校验：磁力链接必须使用 `magnet:?xt=urn:btih:`，种子资源必须提交 http(s) `.torrent` 链接
- 资源治理：用户可举报失效资源，后台可筛选疑似失效链接并标记状态
- 我的投稿：用户可查看自己的资源投稿、审核状态、链接健康状态和举报数，并可删除自己的投稿
- 站内通知：资源审核通过/拒绝、链接状态变更、评论被回复时自动通知用户，前端提供未读徽标和通知中心
- 收藏和热门：用户可收藏影片，热门页支持日榜、周榜、月榜和总榜
- 评论功能：支持根评论、回复、点赞、用户/管理员删除
- 账号功能：注册验证码、登录、JWT 鉴权、用户角色、个人密码重置、管理员快速切换用户账号

## 本次完成项

- P2 评论点赞/回复：启用 `comment.upvotes` 和 `comment.parent_id`，后端返回根评论分页和一层回复，前端展示回复并可点赞
- P2 前端筛选条件动态获取：前端调用 `/api/movies/filters`，后端从数据库动态生成筛选项
- P2 后端查询加分页：电影列表移除固定数量查询，改为分页参数 `page`/`size`
- P3 评论删除：新增 `DELETE /api/comments/{id}`，评论作者和管理员可软删除评论
- 后端启动报错：移除重复的 `POST /api/comments` 映射，避免 Spring 启动时接口冲突
- P1 数据库初始化补齐：`schema.sql` 合并资源治理、收藏、系统配置、剧集季信息等字段和索引，避免新环境缺表缺字段
- P1 投稿闭环：普通登录用户可提交资源，默认进入审核；详情页支持网盘/磁力/种子/在线播放类型
- P1 失效反馈：详情页新增资源失效举报，资源卡片展示疑似失效/已失效状态和举报数
- P1 我的投稿：新增 `/my-resources` 页面和 `/api/resources/mine` 接口，用户可追踪并删除自己的投稿
- P1 注册防刷：后端强制校验验证码，避免绕过前端直接注册
- P1 站内通知：新增 `/notifications` 页面和 `/api/notifications` 接口，审核结果、链接状态变更会写入用户通知并展示未读数
- P1 用户管理增强：角色调整不再允许前端/接口提升为 ADMIN，管理员可选择启用用户并免密切换账号测试权限
- P1 评论回复通知：回复对象是其他用户时，系统向原评论作者发送站内通知，并可跳转到对应影片
- P1 P2P 投稿校验：前后端分别校验磁力和 `.torrent` 种子链接，提取码仅保留给网盘资源

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
- `POST /api/resources`：登录用户提交资源，支持 `DISK`、`MAGNET`、`TORRENT`、`ONLINE`
- `GET /api/resources/mine?page=1&size=20`：查看我的投稿和审核/链接状态
- `DELETE /api/resources/{id}`：删除自己的资源投稿
- `POST /api/resources/{id}/report`：举报资源失效
- `GET /api/notifications?page=1&size=20&unreadOnly=false`：查看站内通知
- `GET /api/notifications/unread-count`：获取未读通知数
- `PUT /api/notifications/{id}/read`：标记单条通知已读
- `PUT /api/notifications/read-all`：标记全部通知已读
- `POST /api/admin/users/{id}/impersonate`：管理员免密切换到指定启用用户
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


