# 迅雷 Token 刷新修复与真实验收

2026-11-11，ASUS Windows 主机，Asia/Shanghai。仅修改并激活 `tools/xunlei-env-browser-login.cjs` 及其回归测试；原计划任务、backend/frontend 镜像和百度调度保持。

## 原因与修复

原 Token 于11月9日18:33:15到期。11月11日修复前，完整同账户凭据仍返回官方 HTTP401，管理接口 `xunleiAuthorizationExpired=true`。计划任务反复停在 `env_browser_renewal / login_origin_rejected`，没有提交密码。

受限浏览器实际加载了官方页面及 JavaScript，但拦截器阻断官方 `/risk` POST，首页显示网络加载失败，登录组件未初始化。精确放行后，已有会话恢复自然续期。新的空白受限诊断 profile 也实证官方 `/risk` 请求为151B JSON对象、无 Authorization/密码字段，并加载出唯一可信登录 iframe；诊断没有读取或提交账号密码。

修复只允许官方 HTTPS 精确 `/risk` 的 POST，要求唯一 `application/json` Content-Type、可解析的非null对象、无 Authorization/密码字段。损坏 JSON、multipart、表单、数组、标量及超深对象均拒绝；Bearer 信任边界未扩大。密码兜底在守护检查后显式导航固定登录页，有界等待唯一官方 iframe，每轮优先接受经验证的同账户自然续期，所有填表动作仍重复核验来源，密码 signin 最多一次。

## 实际结果

11月11日23:03:43启动原 `GYing Xunlei Token Sync` 任务，23:03:53安全结果记录 `status=updated / live.status=renewed / validated=true / loginSubmitted=false / needsInteraction=false`，任务 `LastTaskResult=0`。Windows TaskInfo另报告 LastRunTime为23:03:03，保留原接口值；本轮启动时间及完成时间以控制器和安全结果分别记载。

23:04独立复核新凭据：同账户、access/refresh/user/client/device/captcha齐全，覆盖下次125分钟，官方固定文件列表 HTTP200，backend持久文件权限10001:10001/600。管理入口 HTTP200且 `xunleiAuthorizationExpired=false`，到期时间为 **11月12日10:49:07**。本轮0密码提交、0网盘内容写入；13容器代次摘要前后相同，百度仍enabled=true、intervalMinutes=10、batchLimit=1、PENDING且无blocker。

原两小时任务保持启用/Ready，11月11日23:05查询时 NextRunTime为11月12日00:33:33；未来运行不计入本轮已通过结果。已打开的管理页面需要刷新才能读取新状态。

## 验证与回退

62项 Node回归、21项Windows权限/DPAPI/清理/语法检查通过；项目就绪11 PASS/1历史架构覆盖WARN/0 FAIL。本次变更秘密扫描0 findings；全库扫描的2条既有命中位于历史protectedFileHashes/bridgeToken，已按生成源码核实为文件SHA指纹，原文件不改。新增回归覆盖实际导航和延迟iframe、自然续期、risk正文边界、未知来源、持久守护及单次signin。

外部脚本回退位于 `E:/gying-tools/releases/xunlei-token-refresh-20261111/scripts-before/`；原运行字节SHA已核验。正式同步生成的DPAPI旧状态备份再次认证解密并与修复前backend SHA一致，未写出明文。脚本回退保留当前有效凭据；旧凭据已经过期，不用旧状态覆盖新状态。应用无需重启，backend按mtime热加载。

真实验证码/短信和本轮空白profile密码提交未发生，保留原保护和人工恢复步骤；不能用fixture或11月2日密码登录结果代验未来挑战。

## 证据

- [修复前页面](evidence/login-before.json)、[实际请求阻断](evidence/network-before.json)、[受限bootstrap](evidence/risk-bootstrap.json)、[新profile官方iframe及JSON正文](evidence/fresh-login-shape.json)。
- [旧凭据官方401](evidence/active-before.json)、[新凭据官方200及权限](evidence/active-after.json)、[管理入口修复前](evidence/admin-before.json)、[管理入口修复后](evidence/admin-after.json)。
- [原计划任务](evidence/task-after.json)、[DPAPI备份认证](evidence/backup-verification.json)、[脚本回退清单](evidence/scripts-before-manifest.json)。
- [验证汇总](evidence/validation-summary.json)、[62项Node报告](evidence/node-tests-final.txt)、[扫描复核](evidence/secret-scan-review.json)、[操作后快照](evidence/ops-after.md)、[SHA清单](evidence/SHA256SUMS.txt)。

证据只含固定状态、布尔、数量、时间与摘要；账号密码、token、Cookie、原接口正文和profile未归档。
