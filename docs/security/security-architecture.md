# 安全架构设计

## 1. 目标边界

本设计保持 Windows + Docker Desktop + Cloudflare Tunnel，不增加业务功能，只把现有影视资源平台分成可验证的信任区。目标是：公网只看到前端、公开 API 和图片；后台、机器人、数据库、对象存储控制面和 Docker 管理面不成为公网服务。

```text
Internet
   |
Cloudflare DNS / Tunnel / Access / WAF
   |  (only gyinghub.dpdns.org)
Windows loopback 127.0.0.1:80
   |
nginx (non-root, route filtering, rate limit, security headers)
   |----------------------|
frontend:3000         backend:8880
                          |       |       |
                    MySQL host  Redis    MinIO/S3
                    (Windows)  cache-net  internal/app network
                          |
             gying-source / social-publisher / PanSou / quark-auto-save

OpenClaw (separate Compose project) -- shared Docker network --> backend:8880
Cloudflare Tunnel must never route to MySQL, Redis, MinIO 9001, Docker API/socket or quark UI.
```

## 2. 信任区和访问矩阵

| 区域 | 允许来源 | 允许目的 | 认证/限制 |
| --- | --- | --- | --- |
| 公网页面 | Internet via Cloudflare | nginx → frontend | Cloudflare TLS; response headers; connection/request limits |
| 公共 API | Internet via Cloudflare | nginx → backend public routes | exact CORS, per-IP/category Redis/nginx limits, input bounds |
| 图片 | Browser via nginx media route | MinIO object API | allow-listed image prefixes/extensions; query stripped; GET/HEAD only |
| 管理后台/API | authenticated operator via Cloudflare Access | nginx → frontend/backend | Access policy + application JWT role ADMIN; no direct origin port |
| OpenClaw QQ Bot | OpenClaw container/network only | backend QQ Bot endpoint | internal token; backend route not in Cloudflare ingress; per-user Redis limit |
| GYING Source | backend/approved worker only | gying-source | independent token; fail closed; no host port |
| social-publisher | backend/approved worker only | social-publisher | independent token; no host port |
| Redis | backend and approved services on `cache-net` | Redis | ACL/password; no host publish; dangerous commands denied |
| MySQL | backend/source/social/MCP maintenance peer only | Windows MySQL | dedicated accounts, narrow host/netmask, firewall, TLS plan |
| MinIO console | local operator only | `9001` | loopback or private admin network; never Tunnel/public |
| Docker management | local Docker Desktop/operator | Docker Engine | never mount `docker.sock` into application containers; MCP mutation gated |

## 3. 不变量

1. **单一公网入口**：Cloudflare Tunnel 只指向 `127.0.0.1:80`；所有应用直连端口绑定 loopback 或不发布。
2. **管理双层认证**：Cloudflare Access 负责入口身份，Spring JWT/ADMIN 负责业务授权；任一层失败都拒绝。
3. **内部服务 fail closed**：缺少 token、Redis 不可用、生产凭据弱或 DB 用户为 root 时启动/请求失败，不降级为匿名。
4. **最小身份**：应用不使用 MySQL root 或 MinIO root；每个集成拥有独立凭据和可撤销范围。
5. **对象分区**：只有图片前缀可匿名读；影视文件、配置、备份和控制台私有。
6. **可恢复性**：备份在仓库外、age 加密、带清单/hash；“校验 hash”不等于“已恢复”。
7. **证据优先**：代码已具备、配置已写入、容器已重建、真实链路已验证分别记录，不能互相替代。

## 4. 部署依赖关系

### 4.1 MinIO

当前 MinIO 是独立容器，目标 nginx 配置使用 Docker DNS 名称 `minio:9000`。部署前必须：

1. 备份 MinIO 数据和配置；
2. 将现有容器接入应用网络，并设置网络 alias `minio`；
3. 将 `9000/9001` 的宿主机发布改为 loopback或取消发布；
4. 应用/Source/social endpoint 统一到内部 DNS 或 loopback 代理；
5. 应用 key 换成 scoped key，应用公开策略；
6. 用图片、私有对象、控制台三组请求验收；
7. 失败时恢复原容器/网络映射，不删除数据卷。

### 4.2 OpenClaw

现有 OpenClaw 配置曾通过 `host.docker.internal:8880` 访问 backend。收紧 backend 发布前，先把 OpenClaw 加入 `gying-net`（或建立受控共享网络），将调用地址改成 `http://backend:8880/api/qq-bot/search-reply`，保留内部 token；然后验证 QQ 搜索、未授权调用拒绝和限流。

### 4.3 数据库

账号切换必须先创建新账号、从实际容器网络验证登录和所需 SQL，再逐个切换 backend/source/social/MCP；不要先删除 root，也不要用 `%` 作为账户 Host。MySQL TLS 是独立变更，必须有证书、连接回归和回滚步骤。

## 5. 监控信号

至少告警：

- nginx/backend 的 401、403、404（内部路径探测）、413、429、5xx；
- Redis `NOAUTH`、限流器 503、连接耗尽；
- MySQL 登录失败、权限错误、连接来源变化；
- MinIO anonymous 非图片对象访问、管理 API 登录和 key 变更；
- Quark/GYING/QQ token 轮换失败；
- 备份任务失败、manifest incomplete、恢复演练失败。

日志不得包含密码、Cookie、Authorization、完整 query string、资源分享 token 或外部请求 body。
