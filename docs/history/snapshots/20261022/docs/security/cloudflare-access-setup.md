> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# Cloudflare Access 本机准备与账号授权

更新时间：2026-09-26。**当前仅准备完成，尚未创建或修改任何 Cloudflare Access 应用/策略。**

## 已准备的范围

- 站点：`gyinghub.dpdns.org`。
- 管理员：用户指定的 1 个完整邮箱，实际值仅保存在仓库外受限方案文件，不使用邮箱域通配或 Everyone。
- 受保护路径：`/admin`、`/api/admin`、`/api/resources/admin`、`/api/resources/*/audit`。普通首页、注册/登录、公开影片、用户自己的资源、举报、图片和资源搜索不纳入 Access。
- 路径根本身需要保护；不要把 `/admin` 简化为只填 `/admin/*` 而漏掉根路径。Cloudflare 的路径规则会继承到子路径，中间的 `*` 可以跨路径段；已有更具体应用可能覆盖规则，必须先审查冲突。
- 计划使用 One-time PIN、1 小时 Access 会话，并保留应用自身 JWT/ADMIN 校验；邮件白名单不替代网站管理员角色。
- 不创建整站 Allow/Bypass 规则，不扩展 Tunnel，不公开 MySQL、MinIO、Quark 或机器人。

## 外部文件

目录：`G:/gying-tools/security-20260926/`，ACL 已保护。

- `cloudflare-access-plan.json`：确定的账号 ID、管理员邮箱、路径及认证意图；账号 ID 来自当前 Tunnel 配置明确引用的凭据文件，未复制 Tunnel secret。
- `Prepare-CloudflareAccess.ps1`：本机隐藏录入 Token、DPAPI CurrentUser 保存、调用只读预检。默认不带参数为 dry-run。
- `cloudflare_access.py`：严格路径验证、账号/OTP/Zone/现有应用冲突检查；工具只有 GET，不包含创建、修改或删除 API。
- `initial-preflight-20260926.json`：`needs_local_token`，`cloud_changed=false`。这不是云端配置成功证明。

## 需要用户做的一次授权

在 Cloudflare 控制台的个人资料/API Tokens 中创建 **自定义 API Token**，不要使用 Global API Key：

| 范围 | 权限 | 级别 | 用途 |
| --- | --- | --- | --- |
| Account | Access: Apps and Policies | Edit | 后续创建经过审查的应用/策略；本次录入后仍只读 |
| Account | Access: Organizations, Identity Providers, and Groups | Read | 检查 Zero Trust 是否初始化、One-time PIN 是否存在 |
| Zone | Zone | Read | 验证站点属于同一账号中的 active Zone |

资源范围只选择当前 Tunnel 所属账号，以及管理 `gyinghub.dpdns.org` 的那一个 Zone；不要选所有账号/所有 Zone，也不要增加 DNS Edit、Tunnel Edit 等权限。可以设置短有效期（例如维护当天）；若找不到对应 Zone，先确认账号归属，不扩大范围绕过检查。

在**当前服务器、当前 Windows 用户**的 PowerShell 执行（无需管理员）：

```powershell
& 'G:\gying-tools\security-20260926\Prepare-CloudflareAccess.ps1' -StoreToken
```

在隐藏提示中粘贴 API Token。**不要把 Token、Global API Key、账号密码或邮箱验证码发进聊天。**脚本不会回显 Token，不写入命令行/历史，不改变进程环境；保存文件为 `G:/gying-secrets/cloudflare-access-token.dpapi`，必须由同一 Windows 用户解密。

已有 Token 文件不会被覆盖。若输错、过期或需要替换，返回维护任务处理，不自行放宽 ACL。录入后只做 API 只读预检并输出新的脱敏 `access-preflight-*.json`；把结果发回即可。

以下情况会停止，不能直接套用策略：账号未初始化 Zero Trust、缺 One-time PIN、Zone 不属于预期账号、存在重叠 Access 应用、凭据/权限/网络错误。不会自动注册计划、变更计费、增加身份提供方、覆盖已有策略或撤销账号凭据。

## 后续实施门禁

1. 阅读预检和现有应用/策略，确认不覆盖更具体规则；公开入口健康必须正常。
2. 备份现有账号侧配置，记录精确新建对象 ID 与仅删除本次新建对象的回退范围。
3. 新建精确路径应用及单邮箱 Allow，明确关联 One-time PIN；不要添加 Everyone 或广泛 Bypass。
4. 验证匿名后台被 Access 拦截，公开页面/API/注册不受影响；用户自己完成邮箱 PIN 登录，再确认仍需网站 ADMIN 身份。
5. 再安排 origin Access JWT 校验（当前 Tunnel 配置不自动替换），单独保存 team/audience 和回退点。
6. 任何部分缺证据均保持未完成。GET 预检成功不等于写权限、Access 登录、云端策略或 origin JWT 校验已成功。

## 2026-09-26 入口恢复记录

Access 预检时发现公网 HTTP 530 / Cloudflare 1033，Tunnel ready=503、活动连接 0，本地首页/公开列表/依赖正常。11:11:55–11:12:02 只重启现有 `GYing Cloudflare Tunnel` 计划任务，沿用 HTTP/2 和原配置，未改 DNS/Access/凭据/Docker。随后 ready=200、活动连接恢复，9 项健康全部通过，匿名 `/admin/movies` 仍为 200；这不是 Access 拦截。

恢复记录：`G:/gying-tools/security-20260926/tunnel-recovery-20260926-111155.json`；健康记录：`G:/gying-tools/security-20260925/firewall-attempts/20260926-111242-826661c2/result.json`。本轮没有证明 Tunnel 断连的根因，不将重启恢复当作永久修复。

## 核对依据

以下官方文档于 2026-09-26 读取；API 和 UI 可能继续变化，实际创建前再次核对：

- https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/
- https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/
- https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/one-time-pin/
- https://developers.cloudflare.com/api/resources/zero_trust/subresources/access/subresources/applications/methods/create/
