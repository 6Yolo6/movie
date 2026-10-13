# Cloudflare Access 后台入口启用验收

验收时间：2026-10-13 20:59（Asia/Shanghai）。**账号侧边缘保护已启用；管理员本人 OTP 登录和 origin JWT 校验尚未完成。**

## 实际变更

- 新建 1 个 self-hosted 应用与 1 条应用专属 Allow 策略；仅允许此前确认的 1 个完整管理员邮箱，实际邮箱仅保存在仓库外受限记录。
- 仅保护 `gyinghub.dpdns.org` 的 `/admin`、`/api/admin`、`/api/resources/admin`、`/api/resources/*/audit`，包括路径根及 Cloudflare 的继承子路径。中间 `*` 可跨路径段；没有整站、Everyone 或公开路径 Bypass 应用。
- `allowed_idps` 仅 OTP，策略同时要求相同 `login_method`；应用和策略会话为 `1h`，全局登录会话不变。应用到期后可重新验证/续期，不是每天一小时额度，也不保证每小时再次发码。
- `http_only_cookie_attribute=true`、`allow_authenticate_via_warp=false`、`app_launcher_visible=false` 已从 Cloudflare API 回读确认。实际登录后 Cookie 发放仍留待登录验收。
- 首页、注册/登录、公开 API、用户资源、资源搜索和图片路径不纳入 Access。网站原有 JWT/ADMIN 校验保留；localhost 不经过新增边缘应用。
- 未改 DNS、Tunnel、套餐/计费、全局身份提供方、Token、业务容器、数据库、端口或防火墙，未触发真实发布/转存。

## 验证证据

| 检查 | 结果 |
| --- | --- |
| 云端基线 | Token active；组织/OTP/Zone 可读且归属一致；创建前应用/策略均 0，OTP 恰好 1 个 |
| 离线守护 | 33 项通过；含范围扩展、Everyone、额外 IdP、WARP、会话、Cookie 字段、回退归属和禁止重复 POST |
| 路径预检 | 11 项通过；包含管理根路径、后代、中间通配与公开路径排除 |
| 云端回读 | 四个精确 destinations、单邮箱/OTP、会话、HttpOnly/WARP 与全局设置/身份提供方不变均通过 |
| 公网/本机 | 22/22 通过：11 个公开/本机入口 + 11 个受保护管理路径 |
| 邮箱登录入口 | 全新匿名请求实际到达预期 Access 域名，HTTP 200，存在邮箱输入框；未提交邮箱/PIN |
| 原有运行态 | 10 个生产容器 + 1 个既有诊断容器的 ID、镜像、启动时间、状态、重启次数与变更前一致 |
| 外部文件 | Tunnel 配置、原 DPAPI Token 和原方案 SHA-256 与变更前一致 |
| 运维就绪 | 11 通过 / 1 既有新库架构警告 / 0 失败；不替代安全门禁 |
| 安全扫描 | 2026-10-13 20:59 为 59 PASS / 11 FAIL / 0 UNKNOWN，G01-G11 未关闭 |

受保护探针包含 `/admin`、`/admin/`、`/admin/movies`、`/admin/users`、`/api/admin`、`/api/admin/monitoring/overview`、`/api/resources/admin`、`/api/resources/admin/1/repair-invalid`、`/api/resources/1/audit`、`/api/resources/batch/audit`、`/api/resources/one/two/audit`，均为匿名 GET，仅验证边缘登录跳转，没有触发管理写操作。

公开/本机探针覆盖首页、注册页、登录页、影片列表、注册策略、资源搜索、用户资源未登录 401、internal 404，以及 localhost 首页/管理页 200、管理 API 401。页面 GET 和策略 API 通过不等于真实注册、账号登录或发帖已验收。

## 中断恢复与失败记录

1. 原检查点仅完成脚本/测试，没有已发出的创建请求；接续前已核对授权和云端现状。
2. 修正 Windows PowerShell 继承 `PSModulePath` 导致 ACL 模块加载失败；真实 ACL 仅当前用户/SYSTEM，无 reparse，未放宽权限。
3. 原探针只接受 HTML，两个 JSON API 返回 406；接受 HTML/JSON 后为 200，未改变预期状态或业务服务。
4. 一次 Cloudflare API 连接错误发生在创建请求前；只读复核六类接口均成功、应用/策略仍 0 后才继续，没有盲目重发不确定的 POST。
5. 首次实际新建的请求/回读使用了错误 Cookie 字段名，未通过严格回读验收。自动仅撤销该次应用及关联策略，应用/策略恢复 0、11/11 入口正常；独立查询旧应用 404、公网管理页 200/管理 API 401。失败目录保留。
6. 对照官方 API 改为 `http_only_cookie_attribute`，补充字段契约回归，使用全新独立记录完成启用；未降低安全要求或清除旧 POST 意图。

## 证据和精确回退

- 当前受限记录：`G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/`。包含原方案/hash、云端基线、写前意图、精确新建对象 ID、API 回读、入口检查和事件日志。
- 已回退的失败尝试：`G:/gying-tools/security-20260926/access-enable-20261013-202207-6ff5d4/`；不得用于撤销当前应用。
- 脱敏本机证据：`tmp/cloudflare-access-resume-20261013-01a13541/`，包括运行态/文件 hash、33 项回归、真实回退与独立登录页验收；不提交原始云端配置、邮箱、Cookie 或 Token。

只读复验，不会重发创建请求：

```powershell
python -X utf8 'G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/deploy_access.py' verify
```

仅需撤销**本次当前应用**时执行；脚本先核对已记录对象归属，不覆盖全账号策略：

```powershell
python -X utf8 'G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/deploy_access.py' rollback
```

已发出过创建请求的目录禁止重新运行 `apply`。发生中断时先读事件/状态并核对云端，不清除写前意图、不盲目重发。Token 无权限或对象归属变化时停止自动回退，由管理员核对，不能删除不属于本次的对象。

## 仍需用户/后续验收

- 管理员本人打开公网后台，使用批准邮箱完成 OTP；验证码只输入 Cloudflare 官方页面，不发聊天。
- 通过 Access 后，验证网站 ADMIN 可以使用后台；验证普通 USER 即使有边缘会话也仍被网站拒绝。此轮没有读取账号密码、模拟用户登录或代收邮件。
- origin Access JWT 校验需独立准备 Tunnel audience/配置与回退点；本轮没有替换 Tunnel 配置。WAF、其余容器/网络/身份/恢复门禁仍未关闭，不能宣称整体安全加固完成。

## 官方依据

2026-10-13 核对：

- [创建 self-hosted 应用](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/)
- [路径继承与通配](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/)
- [Create Access Application API](https://developers.cloudflare.com/api/resources/zero_trust/subresources/access/subresources/applications/methods/create/)
