# 项目地图

## 仓库边界

| 区域 | 用途 | 运维事实来源 |
| --- | --- | --- |
| `backend/` | Spring Boot 3.2、Java 17、MyBatis Plus、MySQL、Redis、MinIO 客户端 | `pom.xml`、`Dockerfile`、`application*.yml` |
| `frontend/` | Next.js 16、React 19、Ant Design | `package.json`、`Dockerfile`、`next.config.ts` |
| `nginx/` | 公网入口和 `/api/` 反向代理 | `nginx/nginx.conf` |
| `crawler/` | `gying-source` 服务、GYING 会话/采集及历史一次性工具 | `gying_crawler.py`、`Dockerfile`；旧迁移脚本需单独审查 |
| `tools/` | 原 QQ 频道发帖、OpenClaw 补丁和计划任务注册 | 各 PowerShell 脚本 |
| `social-publisher/` | 多 QQ 账号和新浪微博独立发布服务 | `Dockerfile`、Node.js 服务与微博网页发布器 |
| `docs/` | 部署、数据库、API、Resource Hub 和当前状态 | `current-project-status.md` 是当前运维交接手册 |
| `backend/src/main/resources/db/` | 新库架构和手工增量 SQL | SQL 文件；没有自动迁移引擎 |
| `docker-compose.prod.yml` | 生产应用拓扑 | 始终显式传入 `-f` |
| `.env.example` | 生产环境变量键契约 | 示例值不能视为在线状态 |

项目级运维 Skill 位于 `.agents/skills/gying-project-ops/`。不要为了完成运维审计而修改应用代码。

## 运行拓扑

```text
浏览器
  -> nginx :80/:443
     -> frontend :3000（容器内部）
     -> backend :8880
        -> MySQL :3306
        -> Redis :6379
        -> MinIO/S3 :9000
        -> gying-source :8091
           -> GYING 站点、MySQL、MinIO
        -> PanSou :8888 与外部 Panso API
        -> quark-auto-save :5005
        -> Quark share API
        -> social-publisher :8093
           -> 独立 QQ 账号目录 / 新浪微博网页会话

QQ/OpenClaw/NapCat
  -> 后端 QQ 接口
  -> 共用 Resource Hub 与 resource_link 流水线
```

Compose 核心应用服务包括 `nginx`、`backend`、`frontend`、`gying-source` 和 `social-publisher`；`napcat` 为可选 QQ 接入。
`redis`、`pansou`、`quark-auto-save` 位于 `embedded-deps` profile。
现有部署通常将 PanSou、quark-auto-save、MinIO 和 OpenClaw 作为独立容器运行。
不能因为 profile 中存在这些服务就重复启动依赖。

## 稳定数据模型

- `movie_metadata` 是影视 canonical 主表。
- `resource_link` 是正式发布资源表。
- `resource_hub_task` 保存元数据和发现任务。
- `resource_discovery_result` 保存发布前的外部发现结果。
- `quark_transfer_task` 保存夸克转存和分享任务。
- `movie_source_identity` 连接 TMDB、GYING 外部身份与本地 canonical 影片，电影或未知季号使用 `0`。
- `sys_config` 保存运行时可调整的非敏感开关和限制。
- `qq_bot_search_log` 与 `qq_channel_post_log` 保存机器人和原频道审计记录。
- `social_publish_target` 与 `social_post_log` 保存独立多平台发布目标和审计记录。
- 历史清理必须非破坏性：将关联数据迁移到 canonical 记录，再用状态和时间戳软删除旧记录。

## 配置层级

判断实际行为时按以下优先级核实：

1. 在线进程或容器环境：密钥、地址、GYING 当前内存账号、发布账号会话和启动默认值。
2. 后端加载的 `sys_config`：受支持的运行开关和限制。
3. `application-prod.yml`、`docker-compose.prod.yml` 和 `.env.example`：预期接线方式。
4. `docs/current-project-status.md`：最近一次已验证观察。
5. 旧部署文档：仅供参考，必须与当前文件重新核对。

环境变量写入文件不代表运行中容器已经加载。声明配置生效前，检查容器中的配置键是否存在，
并重启或重建正确服务后验证。

## 分支同步基线

`origin/codex/gying-script` 的 `06cc627b`（2026-08-06）提供当前文档基线：

- Compose 增加 `gying-source`，内部端口 `8091`，backend 通过内部 token 调用。
- `schema.sql` 定义 15 张核心表，并包含 `movie_source_identity`、`social_publish_target` 和 `social_post_log`；已有数据库使用对应增量 SQL。
- Resource Hub 同时使用本地 PanSou、外部 Panso API、GYING 来源身份和 quark-auto-save。
- 缺资源批量补全每批最多 20 部；发现状态可 dry-run 校准；分享失败需要区分重跑、重建任务和重新搜索。
- 电影合集与多季剧集必须定位目标子目录并收窄分享范围，不能把整套合集发布到单片或单季。
- MySQL MCP 优先读取 `MYSQL_PASSWORD`，兼容回退 `GYING_DB_PASSWORD`。
- Compose 增加 `social-publisher` 和持久化 QQ 账号卷；微博会话只从部署环境注入。
- 数据库新增多平台发布目标/日志表，以及 GYING 自有分享来源和频道模板校准迁移。

这些是分支代码和文档能力，不自动证明当前服务器已经部署。容器、表数量、任务数量、登录状态和健康结果都必须重新采集。

## 证据命令

```powershell
git rev-parse --show-toplevel
git status --short --branch
git log -1 --format="%H %cI %s"
docker ps -a --format "{{.Names}}`t{{.Image}}`t{{.Status}}`t{{.Ports}}"
docker compose -f docker-compose.prod.yml ps
```

当前工作树与实际部署目录不一致时，优先检查 Docker 标签
`com.docker.compose.project.working_dir` 和 `com.docker.compose.project.config_files`。
