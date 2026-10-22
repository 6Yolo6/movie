# Cloudflare Tunnel / Access

## 最新状态（2026-10-13，Asia/Shanghai）

账号侧已启用 1 个 self-hosted 应用、1 条单邮箱 Allow，OTP、应用/策略 1h；四类管理路径回读与匿名拦截通过。本人 OTP 登录、网站 ADMIN/USER、origin JWT、WAF 未闭环。已有 Token/方案无需重复录入、申请或扩大授权。[22 项入口和精确回退](cloudflare-access-activation-20261013.md)。

## 目标暴露面

| 路径/服务 | 边界 |
| --- | --- |
| 首页、注册登录、公开影片 API | Cloudflare → loopback nginx；保留限流/参数边界 |
| 图片 | 固定前缀/扩展、GET/HEAD、上游不接收 query |
| /admin、/api/admin、/api/resources/admin、/api/resources/*/audit | Access 入口身份 + 网站 ADMIN JWT，包含根及继承子路径 |
| /api/internal、/api/qq-bot | 公网 404，专用内部认证 |
| MySQL/Redis/MinIO 控制台、Docker、OpenClaw、Quark UI | 无公网 Tunnel 路由 |

Access 不替代网站角色或 internal token；本机 nginx 不经过边缘 Access，管理 API 仍需 JWT。公开路径不新建整站 Allow/Bypass，更具体应用必须检查冲突。

## Ingress 模板

实际配置 `E:/gying-tools/cloudflared/config.yml`、原 HTTP/2 宿主任务；`deploy/cloudflared-config.example.yml` 是目标模板，不直接覆盖线上。配置顺序为内部 deny → 管理 Access 校验 → 普通路由 → catch-all 404；origin JWT audience/team 校验需单独准备检查点。

可信 nginx peer 使用明确来源，网络迁移后重新验证，禁止全部 RFC1918/0.0.0.0/0。Tunnel/Firewall 分开验收；Cloudflare 域名隐藏不替代主机拒绝入站。10/14 代理兜底和事故处理见[排障](../../.agents/skills/gying-project-ops/references/troubleshooting.md)。

## 验收矩阵

检查公开页面/API/图片、匿名后台跳转、Access 后网站 ADMIN 可用/USER 拒绝、内部/QQ 公网 404、管理控制面无路由、公网/IPv6 敏感端口拒绝。记录时间/状态/request ID，凭据不进入 URL/日志。公网普通 Python UA 曾 403，真实浏览器入口通过；不能单凭一个状态码推定策略原因。

旧准备、权限 403、恢复与命令版本见[原文](../history/snapshots/20261022/docs/security/cloudflare-security.md)。历史 Access 申请只属于准备阶段，当前按已启用对象只读核验。
