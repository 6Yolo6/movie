> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# 安全加固部署检查清单

> 执行提醒（2026-10-08）：下文保留的“Public Firewall 仍关闭/尚未执行”等防火墙准备文字属于历史阶段。本次只读已看到 Public/Private 启用；不要据旧文字重复运行 `-Apply`。先核对当前有效策略与最新状态，公网/IPv6 等缺证据项仍不得勾选。

固定编号 `G01–G11`、只读进度工具、隔离候选与分步前置条件见 [11 项门禁与续验](hardening-gates.md)。机器台账只覆盖原 11 个容器/网络项，不代替本清单的 Access、身份、Firewall 和恢复验收。

> 这是部署门禁，不是“已部署”证明。当前生产尚未完成本清单。

2026-09-25 复核：nginx/backend loopback、应用非 root、MinIO alias 和部分入口已验证；迅雷补丁已随 backend:20260924c 上线，在线状态文件为 600。检查项仍按实际证据逐项验收，不把此清单视为全量上线证明。Public Firewall 已于 11:33 启用并完成本机策略/健康验收，同网有线客户端已报告 6 端口全 False，公网直连/IPv6 验证仍缺；Redis 仍在共享网络，专用备份账号、完整 gying 逻辑/持久数据备份与隔离恢复已通过；Firewall、Access、应用身份/policy 等仍需独立闭环，目前不得批量重建生产。

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
- [x] Quark 卷根目录边界收紧：2026-09-25 19:11 `/app/config` 实测 `0777 → 0700`，UID 65534 访问拒绝，UID 0 服务正常，2 文件加密检查点/6 文件内容校验/9 项健康通过；无重启，证据见 `G:/gying-tools/security-20260925/quark-directory-20260925-191131-90e50cb0/result.json`。
- [ ] Quark 单文件最小权限、非 root 迁移及旧 Cookie 按需撤销。上项仅完成父目录访问限制，现有文件自身模式与 Cookie 值未改，不合并标记完成。
- [ ] 迅雷状态文件先备份；外部同步与 backend 自身持久化都经实际重复写入验证 owner `10001:10001`、mode `600`，不输出内容。补丁已部署；2026-09-25 09:27、10:33 同步任务均退出 0，两次 stat 均为 `600`，但 backend 自身独立重复写入证据仍缺，保留未完成。

## C. 依赖和网络迁移

- [ ] MinIO 备份后接入 `gying-net` alias `minio`；9000/9001 不再监听所有接口。alias 已有，但 backend/GYING Source 运行环境的 endpoint 仍为 `host.docker.internal:9000`；先验证迁移调用链及 Access/身份门禁，不能直接改绑端口或批量重建。
- [ ] Redis 从共享 `gying-net` 迁入 `cache-net`（internal=true），backend 连接、认证/ACL 和限流验证通过；没有宿主发布不等于网络已隔离。
- [ ] OpenClaw 接入应用网络，调用 `http://backend:8880/api/qq-bot/search-reply`；不依赖 backend 公网/宿主机端口。
- [x] Windows Firewall Public profile 启用且默认入站 Block；物理接口 `以太网`、`WLAN 2` 入站 TCP 3306/33060/9000/9001/8880/5005 显式 Block，Profile Any。2026-09-25 11:33 管理员实施、11:34 本任务独立 ActiveStore/出站不变/9 项健康复核通过；本项勾选本机策略实施，同网客户端结果见 F 节；公网直连/IPv6 验收仍未完成。
- [ ] Cloudflare Tunnel 仅指向 `127.0.0.1:80`；Access/WAF/默认 deny 已在账号侧验证。2026-10-08 只读复验：既有 DPAPI Token 为 active、Zone API 200，但目标账号 Access organization/providers/apps 均 403；需核对 Access 权限/资源范围，而非重复录入同一 Token。未修改云端策略；实际 OTP/既有应用冲突/登录和回退仍未验收。

### Windows 防火墙单步执行与故障定位

当前已成功实施，**不要重复运行 `-Apply`**。实施/回滚点：`G:/gying-tools/security-20260925/firewall-attempts/20260925-113327-6e2eac1b/`；`result.json=applied`、`firewall-before.wfw` 已保存（897024 bytes），独立策略核验见同目录 `independent-verification-20260925-113454.json`。原 180 秒 watchdog 已退出、活动标记消失、无回退或回退错误；后续独立健康报告为 `G:/gying-tools/security-20260925/firewall-attempts/20260925-113458-e40a05c2/result.json`（9/9）。

- 原脚本把夸克根路径的正常 302 误判为故障；新版严格验证跳转同源 `/login` 且登录页为 200，不跟随任意跳转。另复现本机公网默认/IPv6 curl TLS 握手失败（exit 35）；显式 IPv4 后通过，不关闭 TLS 校验，不改代理、DNS 或网卡。IPv6 和外部入站可达性仍须独立验证。
- `-CheckOnly` 无需提权，只做 9 项 HTTP/就绪检查并写脱敏报告，不验证所有部署门禁。`-Apply` 需管理员，会重新校验完整备份及恢复记录、备份 hash；基线通过后才保存新策略快照、启用 Public 入站 Block 并添加物理接口 TCP 3306/33060/5005/8880/9000/9001 阻断。Public 启用作用于该 profile 下的连接；新增端口规则仅限物理网卡，不改出站设置、不重建容器。
- 每次结果在 `G:/gying-tools/security-20260925/firewall-attempts/<attempt>/`（`baseline.json`、`result.json`）；基线失败列出具体 URL/状态/curl exit，不创建活动标记或策略快照。旧根目录 `firewall-step-before.json` 与 `.wfw` 保留，不再阻挡重试；活动变更仍拒绝并发执行。日志不含响应正文、Cookie、查询参数值或原始错误输出。
- 旧脚本副本与发布 hash 在 `G:/gying-tools/security-20260925/scripts-before-probe-fix-20260925-113003-028c7771/`。生产执行入口依赖同目录 `firewall_health.ps1` 和 `Rollback-FirewallStep.ps1`，须按已验证的三文件版本配套发布。
- 实施后的健康/有效策略检查失败将调用回退；独立隐藏 watchdog 在 180 秒未提交时尝试回退，仅删除本次命名规则并恢复先前 Public enabled/default-inbound，不导入覆盖全部策略。检查 `rollback.json` 或 `rollback-error.json`；后者需要人工处理，不能当成已恢复。30 项安全工具测试包含模拟回退；真实防火墙应用已验收，真实回退未触发。

```powershell
# 可先在普通 PowerShell 执行，只读
& 'G:\gying-tools\security-20260925\Apply-FirewallStep.ps1' -CheckOnly
# 以下是已完成的实施命令，仅作记录，勿重复执行
# & 'G:\gying-tools\security-20260925\Apply-FirewallStep.ps1' -Apply
# 仅在需要回退本次已实施变更时，使用其实际报告目录（勿选旧尝试）
# & 'G:\gying-tools\security-20260925\Rollback-FirewallStep.ps1' -AttemptDirectory 'G:\gying-tools\security-20260925\firewall-attempts\20260925-113327-6e2eac1b'
```

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
