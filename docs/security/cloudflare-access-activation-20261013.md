# Cloudflare Access 后台入口启用验收

2026-10-13 20:59 Asia/Shanghai账号侧已启用；本人OTP/网站角色/origin JWT仍待。[完整原文](../history/snapshots/20261022/docs/security/cloudflare-access-activation-20261013.md)、[现行边界](cloudflare-security.md)。

## 实际变更

1 self-hosted应用+1单邮箱Allow，仅/admin、/api/admin、/api/resources/admin、/api/resources/*/audit及根/继承子路径（中间*可跨段），无整站/Everyone/Bypass。OTP唯一IdP+同login_method、应用策略1h，全局不变；非每天额度、不保证每小时发码。回读HttpOnly=true/WARP=false/launcher=false，实际cookie需登录验。公开页/API/用户资源/搜索/图片不入Access，网站JWT/ADMIN保留，localhost不经边缘。DNS/Tunnel/token/计费/容器/DB/端口未改。

## 验证证据

33离线守护、11路径预检、云端字段/单邮箱/归属/设置、22本机公网入口通过；匿名真实邮箱输入页200未提交邮箱/PIN。11旧容器/外部Tunnel-Token文件hash不变；readiness11通过/1schema警告；安全59/11/0保留。探针全匿名GET，不做管理写入/账号注册登录。

## 中断恢复与失败记录

先核无创建请求/云端0对象；修PowerShell模块环境/JSON探针Accept错误。一连接异常创建前只读复核后继续；首次错误Cookie字段触发精确撤销其应用/策略、旧对象404/入口正常。改官方字段补测、全新记录启用，不降低要求/清POST意图/盲重发。

## 证据和精确回退

当前 `G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d` 保对象ID/归属/意图/hash/回读；失败202207-6ff5d4不能撤当前对象。脱敏本机tmp/cloudflare-access-resume-20261013-01a13541，不提交云端配置/邮箱/Token。

```powershell
python -X utf8 'G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/deploy_access.py' verify
```

verify只读。仅当前范围获准撤销时rollback，先核精确归属，不全账号删除：

```powershell
python -X utf8 'G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d/deploy_access.py' rollback
```

发过POST目录禁止再apply；中断先事件/云端核对，不清意图/不猜结果重发；Token无权或归属变化停止自动撤销。

## 仍需用户/后续验收

本人批准邮箱官方页OTP（PIN不进聊天），通过Access再验ADMIN可用/USER仍拒；origin audience/team JWT与WAF独立检查点，不能仅账号侧应用当源站验证，G01–G11和其他风险未闭环。不重复创建/录入Token。

## 官方依据

原报告保留2026-10-13核对的Cloudflare创建应用/路径继承/API引用，仅当时依据，本轮文档整理未联网更新平台契约。
