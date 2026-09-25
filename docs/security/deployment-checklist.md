# 安全加固部署检查清单

> 这是部署门禁，不是“已部署”证明。当前生产尚未完成本清单。

2026-09-25 复核：nginx/backend loopback、应用非 root、MinIO alias 和部分入口已验证；迅雷补丁已随 backend:20260924c 上线，在线状态文件为 600。检查项仍按实际证据逐项验收，不把此清单视为全量上线证明。Public Firewall 仍关闭，Redis 仍在共享网络，专用备份账号、完整 gying 逻辑/持久数据备份与隔离恢复已通过；Firewall、Access、应用身份/policy 等仍需独立闭环，目前不得批量重建生产。

## A. 变更前

- [ ] 读取 `docs/current-project-status.md`、根目录 `docker-security-report.md` 和本清单。
- [ ] 确认分支/提交、维护窗口、回滚点；`git status --short` 已记录。
- [x] 本机使用 G 盘备份配置与便携 age，官方摘要、受限 ACL、`mysql-backup.cnf` 和 `gying_backup@localhost` 的登录/grants 已验证（2026-09-25）。
- [ ] 不接受 `G:/gying-backups/security-20260925-partial` 替代完整恢复点：已验证 16 个加密文件、25 张表、MinIO 单图；仍缺完整 DB 对象、跨服务静默一致性与离线私钥。
- [x] 用户已本机执行备份账号开通；只新增 localhost 备份身份并导出防火墙，不改应用/root 账号。账号与私有 defaults 已由本任务复核。
- [x] 当前完整逻辑/持久数据备份位于 `G:/gying-backups/20260925T030328.719395Z`：19 文件、短时冻结窗口、hash/解密、25 表行数/CHECKSUM、对象数量与隔离 MinIO 抽样均通过；MySQL 系统账号、离线私钥和完整应用/异机验收不在此勾选覆盖范围。
- [ ] 生产 `.env`、Cloudflare config、MySQL defaults、age 私钥 ACL 已核对；不打印值。
- [ ] 运行 `python -X utf8 tools/security/scan_secrets.py`，当前工作区无命中。
- [ ] 现有容器、端口、网络、卷和 Windows Firewall 已截图/脱敏记录。

## B. 身份和密钥

- [ ] DB 已创建 `gying_app`、`gying_source`、`gying_social`、readonly、backup 账号；每个独立强密码。
- [ ] `CURRENT_USER()`/`SHOW GRANTS` 验证通过，应用不再是 root。
- [ ] MinIO scoped key/policy 已创建；root key 已有轮换计划。
- [ ] Redis 密码/ACL、JWT、内部 token、QQ/Quark/第三方 token 已从外部 secret 注入。
- [ ] GYING Source token 非空且 source 缺 token 时请求拒绝。
- [ ] Quark Cookie 卷 ACL/文件模式收紧，旧 Cookie 已按需要撤销。
- [ ] 迅雷状态文件先备份；外部同步与 backend 自身持久化都经实际重复写入验证 owner `10001:10001`、mode `600`，不输出内容。补丁已部署；2026-09-25 09:27、10:33 同步任务均退出 0，两次 stat 均为 `600`，但 backend 自身独立重复写入证据仍缺，保留未完成。

## C. 依赖和网络迁移

- [ ] MinIO 备份后接入 `gying-net` alias `minio`；9000/9001 不再监听所有接口。
- [ ] Redis 从共享 `gying-net` 迁入 `cache-net`（internal=true），backend 连接、认证/ACL 和限流验证通过；没有宿主发布不等于网络已隔离。
- [ ] OpenClaw 接入应用网络，调用 `http://backend:8880/api/qq-bot/search-reply`；不依赖 backend 公网/宿主机端口。
- [ ] Windows Firewall Public profile 启用；3306/33060/9000/9001/8880/5005 无不必要公网入站规则。已准备 `G:/gying-tools/security-20260925/Apply-FirewallStep.ps1 -Apply`（仅物理网卡入站阻断、不改出站、不重建容器，健康失败/超时自动回退）；自动 UAC 启动未成功，当前仍未执行，需管理员本机运行。
- [ ] Cloudflare Tunnel 仅指向 `127.0.0.1:80`；Access/WAF/默认 deny 已在账号侧验证。

## D. Compose 预检和发布

用合成值做语法验证，不把真实值写入输出：

```powershell
# 在隔离 PowerShell 进程设置必需键，然后执行
 docker compose -f docker-compose.prod.yml config --quiet
```

- [ ] 未提供必需生产键时 Compose/生产 validator 会失败，而不是启动默认凭据。
- [ ] `docker compose -f docker-compose.prod.yml up -d --build backend frontend gying-source social-publisher nginx` 前已确认依赖网络/账号。
- [ ] 不执行 `down -v`、volume 删除、全局 prune、DROP/TRUNCATE。
- [ ] 每个服务按顺序重建并观察日志；失败回滚到旧镜像/容器，不删除卷。

## E. 入口验收

```powershell
python -X utf8 tools/security/check_security.py --repo . --probe
python -X utf8 tools/security/test_nginx.py
```

- [ ] `/` 200；公开列表 API 200。
- [ ] 未认证 `/api/admin/users` 为 401/边缘拒绝。
- [ ] `/api/internal/**`、`/api/qq-bot/**` 为 404，不能看到 health 诊断。
- [ ] 私有媒体/非图片对象 404/403；公开图片 GET/HEAD 成功，带 query 不被上游接收。
- [ ] API 登录/搜索/写入超限返回 429，并有 `Retry-After`。
- [ ] CORS 只允许正式 origin；伪造 forwarded headers 不改变客户端身份。
- [ ] Redis 未认证 PING 被拒绝；Redis 故障时限流接口 fail closed。
- [ ] backend/source/social 运行用户非 root；无 privileged/socket 挂载；发布端口均 loopback或无发布。

## F. Cloudflare/外部链路

- [ ] 未登录管理员请求被 Access 拦截（2026-09-25 公网 `/admin/movies` 仍为 200，尚未取得拦截证据）；Access 登录后普通 USER 仍不能调用 ADMIN API。
- [ ] 直接访问主机公网 IP 的 8880/5005/9000/9001/3306 被防火墙拒绝。
- [ ] MinIO 控制台未进入 Tunnel；Cloudflare catch-all 返回 404。
- [ ] OpenClaw QQ 搜索走内部 token；每用户一分钟 5 次限流生效。
- [ ] 公开首页、影片列表、图片、评论和登录在真实域名上通过；不触发真实外部发帖/转存，除非维护窗口明确批准。

## G. 收尾

- [ ] `docker ps`、Compose 状态、日志、Windows Firewall、Cloudflare 配置和数据库 grants 再次脱敏记录。
- [ ] 执行前端/social npm audit；运行 Java/Python/容器扫描（工具不可用时明确记录）。
- [ ] 加密备份 `verify_backup.py` 通过，并安排真实解密/恢复演练；不要把 hash 验证写成恢复成功。
- [ ] 更新 `docs/current-project-status.md`：明确“代码已具备/配置已写入/容器已重建/真实链路已验证”。
- [ ] 保存回滚路径、剩余风险和下次复核日期。
