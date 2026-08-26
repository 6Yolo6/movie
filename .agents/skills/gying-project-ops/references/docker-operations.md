# Docker 运维

## 服务职责

| 服务或容器 | 职责 | 预期暴露方式 |
| --- | --- | --- |
| `nginx` | 公网入口和反向代理 | 主机 `80`、`443` |
| `frontend` | Next.js 服务 | 容器内部 `3000` |
| `backend` | Spring Boot API | 主机 `BACKEND_PORT`，默认 `8880` |
| `gying-source` | GYING 会话、搜索、采集和资源修复 | Compose 网络内部 `8091` |
| `social-publisher` | 多 QQ 账号和新浪微博发布 | Compose 网络内部 `8093` |
| `napcat` | 历史遗留 OneBot QQ 接入，当前停用 | 不启动、不验收、不迁移 |
| `redis` | 缓存和运行依赖 | embedded profile 或外部服务 |
| `pansou` | 资源搜索 | embedded profile 或外部 `8888` |
| `quark-auto-save` | 夸克转存 | embedded profile 或外部 `5005` |
| `minio-server` | 海报和对象存储 | 通常为外部 `9000`，控制台 `9001` |
| OpenClaw Gateway | 官方 QQ Bot 桥接 | 独立 Compose 项目 |

frontend 容器通常不直接作为公网站点；用户通过 nginx 访问。

## 状态盘点

```powershell
docker ps -a --format "{{.Names}}`t{{.Image}}`t{{.Status}}`t{{.Ports}}"
docker compose -f docker-compose.prod.yml ps
docker network ls
docker volume ls
```

通过标签定位真实部署目录：

```powershell
docker inspect gying-movie-backend-1 `
  --format '{{json .Config.Labels}}'
```

检查环境时只输出键名称。原始 `docker inspect` 可能包含密钥，不能直接粘贴到报告。

## 安全命令

```powershell
# 查看日志
docker compose -f docker-compose.prod.yml logs --tail=200 backend
docker compose -f docker-compose.prod.yml logs --tail=200 frontend gying-source social-publisher nginx

# 不重建镜像，仅重启
docker compose -f docker-compose.prod.yml restart backend

# 配置变化后重新创建
docker compose -f docker-compose.prod.yml up -d --force-recreate backend

# 重建指定服务
docker compose -f docker-compose.prod.yml up -d --build backend frontend gying-source social-publisher nginx
```

独立依赖使用 `docker logs`。关联不同容器事件时同时检查时间戳和时区。

## 健康检查

当前本机可用的检查路径：

```text
http://127.0.0.1/
http://127.0.0.1:8880/api/qq-bot/health
http://127.0.0.1:5005/
http://127.0.0.1:9000/minio/health/live
http://127.0.0.1:18789/healthz
```

`gying-source` 和 `social-publisher` 默认不发布主机端口，从容器内部检查：

```powershell
docker compose -f docker-compose.prod.yml exec -T gying-source `
  python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=8).status)"

docker compose -f docker-compose.prod.yml exec -T social-publisher `
  node -e "fetch('http://127.0.0.1:8093/health').then(r=>r.json()).then(console.log)"
```

PanSou 根路径返回 `404` 不代表服务异常。应检查容器、日志和已知 API。
Resource Hub 内部状态优先通过管理概览或带认证的内部 health 验证，不能猜测路径。

## 网络与主机访问

- 应用容器共用 `gying-net`。
- frontend 通过 `http://backend:8880` 访问 backend。
- backend 通过 `http://gying-source:8091` 和内部 token 访问 GYING 服务。
- backend 通过 `http://social-publisher:8093` 和独立 token 访问多平台发布服务。
- 容器访问主机上独立依赖时使用 `host.docker.internal` 和 Compose 的 `host-gateway`。
- 独立网络中的 OpenClaw 通过 `host.docker.internal:8880` 访问已暴露 backend。
- 浏览器地址不能包含 `host.docker.internal`；应使用 nginx 或公网 MinIO/CDN 地址。

## 持久化状态

命名卷包含 backend 日志、`social-publisher-qq-accounts` QQ 凭据、`social-publisher-weibo` 会话和可选 embedded 依赖数据。独立容器可能挂载检出目录外的路径。
`napcat-data` 是历史遗留卷，当前迁移不要导出或恢复；发现旧卷时只记录其存在，不把它列为上线依赖。
迁移前记录每个挂载的源、目标、所有者和备份方法。

存在独立容器或共享网络时，不能假定 `docker compose down` 无害。日常恢复禁止增加 `-v`。

## Compose MCP 限制

`backend/docker_mcp.py` 只在 `project_dir` 中运行普通 `docker compose`。
由于本项目使用 `docker-compose.prod.yml`，其 `compose_ps/up/down` 无法自动选择生产文件，
可能返回 `no configuration file provided`。可使用 MCP 查看 `docker_ps` 和日志，
Compose 操作必须通过 shell 显式传入 `-f docker-compose.prod.yml`。

## 常见排查

- 端口冲突：启用 `embedded-deps` 前盘点全部容器。
- backend 已改但行为未变：检查镜像创建时间、容器是否重建、配置键是否存在。
- GYING 接口失败：检查 `gying-source` 日志、共享 Session、PoW、账号状态和 backend/source token 是否一致。
- 多平台发布失败：检查 `social-publisher` 健康、内部 token、QQ 凭据卷和微博会话 readiness。
- 浏览器 API 正常但详情页失败：检查 `INTERNAL_API_URL`。
- nginx `504`：确认是否仍调用旧同步接口；长任务应返回 `jobId` 并轮询。
- 时间偏移：检查 `TZ=Asia/Shanghai`、JVM 时区、JDBC 时区和 MySQL 时间。
- NapCat 容器存在或退出：按历史遗留项处理，不重启；QQ 机器人故障应检查 OpenClaw Gateway 和后端 QQ 接口。
