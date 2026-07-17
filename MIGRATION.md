# 生产迁移清单

本文只描述迁移顺序。环境变量、外部服务和 QQ 配置详见 [部署文档](docs/deployment.md)，数据库增量详见 [数据库文档](docs/database.md)。

## 1. 迁移前

- 冻结写入窗口并记录当前提交、数据库版本和对象存储前缀。
- 备份 MySQL、MinIO/对象存储和 quark-auto-save 配置。
- 使用专用数据库用户，不在命令或文档中写明文密码。
- 准备生产 `.env`，至少替换数据库密码、Redis 密码、JWT Secret 和内部令牌。

```bash
mysqldump --single-transaction --routines --triggers \
  -h <source-host> -u <user> -p gying > gying_backup.sql
```

## 2. 数据库

新环境导入当前 schema：

```bash
mysql -h <target-host> -u <user> -p \
  -e "CREATE DATABASE gying CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql -h <target-host> -u <user> -p gying \
  < backend/src/main/resources/db/schema.sql
```

迁移已有数据时先导入备份，再按 `backend/src/main/resources/db/` 中尚未执行的迁移文件补齐结构。执行前用 `DESC`、`SHOW TABLES` 或迁移记录确认状态，避免重复 `ALTER TABLE`。

迁移后检查：

- `movie_metadata`、`resource_link` 主记录数和 `deleted_at` 字段。
- Resource Hub 任务、发现、转存和 QQ 日志表。
- `sys_config` 中非敏感配置项及中文字段注释。
- MySQL 时区为 `Asia/Shanghai`。

## 3. 图片与对象存储

- 同步整个 MinIO bucket 或 S3 兼容对象前缀。
- 保持数据库中的相对对象键不变，只调整 `MINIO_URL_PREFIX`。
- 抽查电影、剧集、动漫海报以及腾讯频道发帖图片。

## 4. 应用与外部依赖

```bash
cp .env.example .env
# 填写生产值
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

- 默认连接现有 MySQL、Redis、MinIO、PanSou 和 quark-auto-save。
- 只有明确需要时才启用 `embedded-deps` profile，避免端口冲突。
- 上游容器重建后重新创建 nginx，确保反向代理解析到新容器地址。
- GYING、夸克、QQ 和腾讯频道凭据在目标主机重新配置，不从文档或 Git 恢复。

## 5. 上线验收

- 首页、详情、登录、评论、收藏、资源提交和后台审核。
- TMDB 单条同步以及 PanSou/Panso API 搜索。
- 一条计划保留资源的转存、分享、发布和链接回读。
- GYING 当前账号、候选读取和无变更检查。
- QQ 群被动查询；腾讯频道只用计划保留资源测试，避免创建一次性公开帖子。
- `mvn test`、`npm run lint`、`npm run build` 和 Compose 配置校验通过。

## 6. 回滚

- 保留旧数据库和对象存储只读快照，切换完成前不删除。
- 应用回滚到迁移前镜像或提交；数据库只恢复完整备份，不手工删除新表行。
- DNS/nginx 切回旧实例后，再分析迁移日志和差异。
