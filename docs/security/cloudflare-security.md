# Cloudflare Tunnel / Access

## 最新状态（2026-10-13，Asia/Shanghai）

账号侧已启用 1 个 self-hosted 应用、1 条单邮箱 Allow，OTP、应用/策略 1h；四类管理路径回读与匿名拦截通过。本人 OTP 登录、网站 ADMIN/USER、origin JWT、WAF 未闭环。已有 Token/方案无需重复录入、申请或扩大授权。[原验收与回退](#access-recovery)。

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

旧准备、权限403和首次创建字段错误的详细过程从[Git来源](../history/README.md#git-history)读取；旧申请不是当前待创建任务，现役按已启用对象核验。

<a id="access-recovery"></a>
## 精确对象核验与回退

2026-10-13原验收为33离线守护、11路径预检和22本机/公网入口；所有探针匿名GET，本人OTP/网站角色/origin JWT未代验。第一次创建字段错误触发其精确撤销后重新启用，失败目录不能用于撤销现役对象。

现役原记录 `G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d` 保对象id/归属/意图/hash/回读，配置和邮箱不提交。只读核验入口：

```powershell
python -X utf8 'G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/deploy_access.py' verify
```

该目录已有POST意图，不再次apply。中断先核事件和云端对象，不清意图或盲重发；需要撤销且当前范围明确授权时才用同目录rollback，先核精确归属，Token无权或归属变化停止自动撤销。原字段设置、失败对象与完整步骤按Git旧文件追溯。

本人官方页OTP、Access之后ADMIN可用/USER拒绝、origin audience/team JWT和WAF仍需单独验收，不要求重复录入已有Token。文档整理没有更新云端策略或重新核查平台API。
