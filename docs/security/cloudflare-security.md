# Cloudflare Tunnel / Access 安全设计

## 1. 在线基线

在线配置位于仓库外：`E:\gying-tools\cloudflared\config.yml`。截至 2026-09-20，观测到一个 hostname：

```text
gyinghub.dpdns.org -> http://127.0.0.1:80
catch-all -> http_status:404
```

没有在该 origin 配置中发现 Access origin-validation 段；Cloudflare 账号侧 Access Application、Policy、WAF 和 rate limiting 没有纳入本次本机检查。公网探针的 403/网络错误只能说明接受结果未闭环，不能证明 Access 已启用。

## 2. 目标暴露面

| 路径/服务 | 公网目标 | 处理 |
| --- | --- | --- |
| `/`、公开前端静态资源 | 允许 | Cloudflare → loopback nginx → frontend |
| 公开影片/评论/列表 API | 允许但限流 | 精确 CORS、nginx + Redis 限流、参数边界 |
| `/media/<image-prefix>/<image>` | 允许 | 仅图片前缀/扩展，清空 query，GET/HEAD |
| `/admin/**`、`/api/admin/**`、资源审核/管理 | Access + ADMIN JWT | 未登录在边缘拒绝；即使绕过边缘，Spring 仍需 ADMIN |
| `/api/internal/**`、`/api/qq-bot/**` | 禁止公网 | Tunnel ingress 和 nginx 都返回 404；机器人走内部网络 |
| MySQL 3306/33060、Redis 6379 | 禁止 | 不写入 ingress；Windows 防火墙/监听进一步限制 |
| MinIO 9001 控制台、S3 管理 API | 禁止公网 | 本机/内部管理；图片由 nginx 受控代理 |
| Docker API/socket、OpenClaw 管理 Gateway | 禁止公网 | loopback/本机管理；不配置 Tunnel route |
| quark-auto-save UI | 禁止公网 | loopback + 本机/Access 管理，不能把 Cookie UI 暴露给 Internet |

## 3. Ingress 模板

`deploy/cloudflared-config.example.yml` 是模板，不是可直接替换的线上配置。上线前替换 tunnel UUID、credentials path、team name 和 audience，并在 Cloudflare 账号侧创建相同路径的 Access Application/Policy。

顺序必须是：内部 deny → 管理 Access → 普通公开路由 → catch-all 404。每次修改后：

```powershell
cloudflared tunnel ingress validate --config E:\gying-tools\cloudflared\config.yml
cloudflared tunnel ingress rule https://gyinghub.dpdns.org/admin/users
cloudflared tunnel ingress rule https://gyinghub.dpdns.org/api/internal/resource-hub/health
```

如果本机版本不支持上述子命令，使用同版本 `cloudflared tunnel --config ... ingress validate` 的等价形式，并把输出脱敏保存。

## 4. Access policy

建议至少建立三个策略：

1. **管理员 Application**：`/admin/*`、`/api/admin/*`、`/api/resources/admin/*`、资源审核路径；仅指定管理员邮箱/组或硬件 MFA；默认 deny；短会话；禁止匿名。
2. **维护 Service Token Application**：只给明确的 CI/维护客户端，限定路径和 HTTP 方法；token 不写 URL/query，不写日志。
3. **公开 Application**：普通页面和公开 API；不把管理员路径作为同一条“允许所有”规则的例外。

Access 是入口身份层，不替代 backend JWT、ADMIN 角色、内部 token 或 nginx 路径拒绝。边缘规则和 origin 规则必须重复关键 deny，防止误配时内部端点泄漏。

## 5. Origin 和 Windows 主机

- Tunnel origin 只监听 loopback；Cloudflare Tunnel 进程凭据位于仓库外，ACL 仅允许当前账号/SYSTEM。
- Windows 网络配置必须把不必要的 Public profile 规则改为显式允许；对 3306/33060、9000/9001、8880、5005 做 inbound deny 或 loopback-only。
- 不以“Cloudflare 隐藏了域名”作为防火墙替代；攻击者可直接扫描主机公网地址。
- nginx `trusted-tunnel-peers.conf` 只允许复核过的 loopback/Docker peer；网络迁移后必须重新确认 peer IP，不能改为 `0.0.0.0/0` 或全部 RFC1918。

## 6. 验收矩阵

| 请求 | 预期 |
| --- | --- |
| 未登录 `GET /` | 200 |
| 未登录公开列表 API | 200/按限流返回 |
| 未登录 `/admin/` | Cloudflare Access 拦截，不到 origin；直接 origin 也不能绕过业务认证 |
| 未登录 `/api/admin/users` | 401 或边缘拒绝 |
| 任意 `/api/internal/*`、`/api/qq-bot/*` | 404，且不泄露 health/诊断 |
| MinIO 控制台域名/9001 | 无公网路由/连接失败 |
| 直接公网 `:8880/:5005/:9000/:9001` | 防火墙拒绝 |
| Access 登录后普通 USER 调管理员 API | 403/401，不能仅凭 Access 放行 |
| 公开图片带恶意 query | 返回图片或对象错误，但上游不能看到 query/token |

每次验收记录绝对时间、Cloudflare Ray ID（如有）、origin 日志 request ID 和结果，不记录 token/cookie。
