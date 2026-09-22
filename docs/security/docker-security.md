# Docker 安全加固

## 1. 适用文件和运行原则

生产只使用：

```powershell
docker compose -f docker-compose.prod.yml <command>
```

`docker-compose.prod.yml` 是目标配置，不代表现有容器已经重建。任何生产变更前先读取 `docs/current-project-status.md`、运行只读快照、备份数据库/凭据卷，并记录回滚点。

## 2. 端口和网络

### 目标 Compose

| 服务 | 容器端口 | 目标宿主机暴露 | 网络 |
| --- | --- | --- | --- |
| nginx | 8080 | `127.0.0.1:80` | `gying-net` |
| backend | 8880 | `127.0.0.1:${BACKEND_PORT}`（仅兼容本机/OpenClaw迁移窗口） | `gying-net` + `cache-net` |
| frontend | 3000 | 不发布 | `gying-net` |
| gying-source | 8091 | 不发布 | `gying-net` |
| social-publisher | 8093 | 不发布 | `gying-net` |
| Redis | 6379 | 不发布 | `cache-net`，`internal: true` |
| PanSou | 8888 | 不发布 | `gying-net` |
| quark-auto-save | 5005 | `127.0.0.1:5005`（管理窗口） | `gying-net` |
| NapCat | 3000/3001/6099 | 只在 `legacy-disabled` profile，默认不启动 | `gying-net` |
| MinIO | 9000/9001 | 不由本 Compose 管理；必须另行改为 loopback/内部网络 | `gying-net` alias `minio` |

### 已观测在线端口

2026-09-20 只读观测到 nginx `0.0.0.0:80/443`、backend `0.0.0.0:8880`、quark `0.0.0.0:5005`、MinIO `0.0.0.0:9000/9001`。这组结果是待修复基线，不是目标状态。

## 3. 容器权限

目标服务的共同加固：

- `no-new-privileges:true`；
- 适用服务 `cap_drop: ALL`；
- nginx/backend/source 使用只读根文件系统和受限 tmpfs；
- nginx UID 101、backend/source UID 10001、frontend UID 1001、social-publisher 使用 `node`；
- 不使用 `privileged: true`；
- 不挂载 Docker socket、Docker Desktop engine pipe 或宿主机根目录；
- 日志 `json-file` 每文件 10 MB、最多 3 个；内存和 PID 有上限。

`read_only` 不能代替应用层授权；social-publisher、Quark 和状态卷仍需要写入权限，必须只挂载必要目录。

## 4. 卷和敏感数据

| 卷/路径 | 内容 | 规则 |
| --- | --- | --- |
| `backend-logs` | 应用日志 | 日志脱敏、限制保留；不得把 token 写进日志 |
| `backend-data` | Xunlei 状态/运行数据 | 外部备份、ACL、非公开；迁移前记录 owner |
| `social-publisher-qq-accounts` | QQ 凭据 | 只给 node 用户；禁止复制到 Git/报告 |
| `social-publisher-weibo` | 微博会话 | 只给发布器；轮换时先备份再撤销 |
| `quark-auto-save-data` | Quark 配置/Cookie | 不当作加密；收紧 Windows/Docker ACL，轮换 Cookie |
| `pansou-data` | 可重建缓存 | 不作为唯一备份；不含生产密钥 |
| `redis-data` | 历史声明 | 当前目标 Redis 无持久化；不要因加固删除旧卷 |

## 5. 安全检查命令

只读检查：

```powershell
python -X utf8 tools/security/check_security.py --repo . --probe
python -X utf8 tools/security/scan_secrets.py
python -X utf8 tools/security/scan_secrets.py --history
docker compose -f docker-compose.prod.yml config --quiet
docker ps -a --format "{{.Names}}`t{{.Image}}`t{{.Status}}`t{{.Ports}}"
```

不要直接把 `docker inspect` 原文粘贴到报告；其中可能含环境变量。只报告端口、用户、privileged/socket、健康状态和键名。

## 6. 发布顺序

1. 备份 MySQL、MinIO、Quark、social/OpenClaw 状态；
2. 生成/安装强密钥，创建专用 DB/MinIO 账号；
3. 迁移 MinIO 网络和 OpenClaw backend 地址；
4. 用合成 env 在非生产环境验证 Compose；生产 `.env` 缺少必需键时应故意失败；
5. 先部署依赖和 backend，再部署 frontend/source/social/nginx；
6. 验证内部依赖、管理员未授权 401、内部路由 404、公开图片 200、私有对象拒绝；
7. 最后切换/验证 Cloudflare Tunnel 和 Access；
8. 观察日志和资源，失败按记录的旧端口/旧容器回滚，不删除卷。

## 7. 供应链

当前 frontend/social 的 `npm audit` 为零，但这不等于镜像无 CVE。Docker Scout 在本次隔离检查中对目标 frontend 发现 10 条 critical/high（包含 Next standalone/transitive tooling），对目标 social 发现 58 条（包含 Chromium/OS 和 bundled tooling），对加入 Debian security upgrade 的 source 仍发现 1 条未修复 zlib high；backend 扫描尚未闭环。生产应在 CI 中锁定版本和 digest，逐条判断运行时可达性并跟踪 fixed version；不能把 npm audit 结果当作完整 JVM、Python、OS 镜像 CVE 结论。
