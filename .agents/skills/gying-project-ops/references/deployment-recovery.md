# 部署与恢复

## 操作前检查

1. 确认目标主机、域名、检出目录、分支或提交以及维护窗口。
2. 运行本 Skill 的两个脚本。
3. 确认 Docker Engine 和 Compose v2 可用。
4. 盘点已有容器、端口、网络、挂载和外部配置路径。
5. 备份 MySQL，并确认备份文件可读取、可列出表。
6. 备份以下外部配置和持久化数据：
   - 根目录 `.env`；
   - MinIO 对象数据或云端 Bucket；
   - quark-auto-save 配置目录，其中包含 Cookie 和插件凭据；
   - OpenClaw 配置与认证目录，其中包含机器人密钥和搜索 token；
   - 使用 NapCat 时的配置卷；
   - QQ 频道计划任务定义和本地发帖状态；
   - `social-publisher-qq-accounts` 卷及多平台发布表；
   - 微博网页会话键的受保护来源，不记录其值。
7. 记录当前 Git 提交和镜像 ID，以便回滚。

不得把这些备份放入 Git。

## 常规部署

在实际部署目录执行，不要在任意工作树中操作：

```powershell
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml build backend frontend gying-source social-publisher
docker compose -f docker-compose.prod.yml up -d backend frontend gying-source social-publisher nginx
docker compose -f docker-compose.prod.yml ps
```

只重建受影响服务。前端构建上下文可能耗时较长，应先观察构建进度。环境变量改变后要重建或
重新创建 backend，不能仅凭 `.env` 已修改就认为容器已经加载。

按以下顺序验证：

1. MySQL、Redis、MinIO、PanSou/Panso 和 quark-auto-save；
2. `gying-source` 健康、当前账号状态和只读候选；
3. `social-publisher` 健康、QQ 账号授权和微博 readiness；
4. backend 到依赖、`gying-source` 和 `social-publisher` 的连接；
5. 后端健康检查或只读接口；
6. frontend 通过 `INTERNAL_API_URL=http://backend:8880` 完成服务端渲染；
7. nginx 的 `/api/` 和 `/` 入口；
8. Resource Hub 管理概览；
9. 本次涉及的机器人和频道集成。

## 新服务器上线

推荐使用 Linux、Docker Engine 和 Compose v2。应用数据应位于 Git 检出目录之外，
或使用有明确备份路径的命名卷。

1. 完成系统补丁、防火墙、SSH、NTP 和磁盘监控。
2. 安装 Docker，为非 root 运维账号授予所需权限。
3. 将仓库检出到稳定路径，例如 `/opt/gying-movie`。
4. 根据 `.env.example` 创建 `.env`，从密钥管理系统或受保护文件注入敏感值。
5. 明确 MySQL、Redis、MinIO、PanSou、quark-auto-save 使用托管服务、已有容器，
   还是 `embedded-deps`，不能无意混用。
6. 启动自动化前恢复 MySQL 和对象存储。
7. 仅在需要对应集成的主机恢复 quark-auto-save、OpenClaw 和 NapCat 配置。
8. 逐项启动并独立验证依赖。
9. 启动 `gying-source`，只验证健康、账号状态和候选读取。
10. 恢复 QQ 凭据卷并启动 `social-publisher`，保持所有自动发布关闭。
11. 保持 Resource Hub 和 Worker 关闭，启动 backend。
12. 启动 frontend 和 nginx，验证浏览器和 API 路径。
13. 手动启用 Resource Hub，运行一次受控流水线，再启用 Worker。
14. 分别手工验证各发布账号后，最后启用机器人、频道和多平台发布计划任务。
15. 在 `docs/current-project-status.md` 记录新拓扑和验收证据。

## 数据库迁移

使用不会把密码写入命令历史的方式导出：

```bash
mysqldump --single-transaction --routines --triggers \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  -h SOURCE_HOST -u SOURCE_USER -p gying > gying_YYYYMMDD_HHMMSS.sql
```

检查备份大小、文件头、表清单和校验和。先恢复到新数据库，比较表集合和关键数据量，
再切换 backend。新库和增量迁移规则见 [数据库运维](database-operations.md)。

## 环境恢复顺序

1. 停止 Resource Hub Worker、频道发帖计划任务和机器人自动转存。
2. 保留事故证据和现有日志。
3. 将 MySQL 恢复到已验证时间点。
4. 恢复 MinIO/对象存储和外部服务配置。
5. 启动并验证 MySQL、Redis、MinIO、PanSou/Panso 和 quark-auto-save。
6. 启动并验证 `gying-source`，确认默认账号和内部 token。
7. 恢复 `social-publisher-qq-accounts` 卷，启动发布容器并保持自动发布关闭。
8. 在自动化关闭状态启动 backend。
9. 启动 frontend 和 nginx。
10. 启用 Worker 前处理遗留的 `RUNNING`、`SUBMITTED` 等任务状态。
11. 恢复 OpenClaw、NapCat 和频道自动化。
12. 分目标手工验证多平台发布，再执行完整验收并更新项目状态。

不能直接重跑遗留任务。先检查其外部副作用，避免重复转存、分享或发帖。

## 回滚

- 仅应用故障：部署已记录的上一 Git 提交或镜像；没有数据变更时保持数据库不动。
- 兼容的新增字段迁移：只有旧版本能容忍新表和字段时，才只回滚应用代码。
- 不兼容的数据或架构变更：恢复变更前备份，或执行单独评审的前向修复；不能临时编写破坏性逆向 SQL。
- 外部集成故障：先关闭对应 Worker 或计划任务，保留审计队列供后续重放。

不得用删除卷代替回滚。

## 验收清单

- nginx 首页和 API 通过公网入口成功；
- backend 使用生产 profile 和 Asia/Shanghai；
- frontend 服务端渲染可访问 `backend:8880`；
- MySQL 表集合和关键数量与源环境一致；
- Redis 与对象存储可访问；
- Git 和日志中没有敏感值；
- `gying-source` 健康、账号配置状态和来源身份表正确；
- `social-publisher` 健康、QQ 凭据卷已挂载、微博 readiness 与目标默认关闭状态正确；
- Resource Hub 依赖和配置概览正确；
- 启用调度前，受控手工 Resource Hub 操作成功；
- 仅在启用时测试 OpenClaw、NapCat 和 QQ 频道；
- 已记录备份、回滚材料、部署提交和剩余任务。
