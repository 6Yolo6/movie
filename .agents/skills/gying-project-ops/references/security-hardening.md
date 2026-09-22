# GYing 安全审计与加固流程

本参考文件用于生产安全审计、必要加固和上线验收。它不新增业务功能，也不把目标 Compose 配置误写成在线事实。

## 1. 基线和状态

1. 使用 `git rev-parse --show-toplevel` 定位仓库根目录。
2. UTF-8 完整读取 `docs/current-project-status.md`、根目录 `docker-security-report.md` 和 `docs/security/`。
3. 记录分支、提交、`git status --short`、Docker 容器/网络/卷、Windows 防火墙/监听端口和外部配置路径；只记录键名和状态，不输出值。
4. 把四类状态分开写：代码已具备、配置已写入、容器已重建、真实链路已验证。
5. 当前生产仍以在线观测为准；`codex/security` 工作区中的改动必须经过部署清单后才可标记为已上线。

## 2. 必须先读的文档

- 根目录 `docker-security-report.md`：风险登记和在线基线；
- `docs/security/security-architecture.md`：信任区和访问矩阵；
- `docs/security/docker-security.md`：Compose、端口、权限和卷；
- `docs/security/cloudflare-security.md`：Tunnel/Access/WAF；
- `docs/security/database-security.md`：MySQL 账号、网络和 TLS；
- `docs/security/secret-management.md`：Cookie、Token、轮换和 Git 历史；
- `docs/security/backup-plan.md`：加密备份和恢复演练；
- `docs/security/incident-response.md`：止血、取证、轮换和恢复；
- `docs/security/deployment-checklist.md`：上线门禁；
- `docs/security/api-inventory.md`：静态 Controller 映射清单，不等价于动态渗透测试。

## 3. 只读扫描

```powershell
python -X utf8 tools/security/scan_secrets.py
python -X utf8 tools/security/scan_secrets.py --history
python -X utf8 tools/security/check_security.py --repo . --probe
docker compose -f docker-compose.prod.yml config --quiet
```

`check_security.py` 在旧生产容器仍运行时预期会报告失败；这类失败应作为部署前风险证据记录，不得通过修改期望值掩盖。

## 4. 变更前约束

- 不运行 `docker compose down -v`、全局 prune、卷删除、DROP/TRUNCATE 或无备份的账号/密钥删除。
- 数据库账号创建先用 `tools/security/provision_db_accounts.py` dry-run；用明确 IP/netmask，禁止 `%`。
- MinIO 迁移前备份 data/config，先接入网络 alias `minio`，再改 nginx/应用 endpoint；失败时恢复网络映射，不删除数据。
- backend 端口收紧前先把 OpenClaw 接入应用网络并验证 `backend:8880` 内部地址。
- Cloudflare Access 账号侧必须单独创建/核验；origin `originRequest.access` 模板不能替代 Access Application/Policy。
- 生产 `.env`、Cloudflare credentials、MySQL defaults、Quark/QQ/Weibo/Cookie 只报告路径和键名。

## 5. 部署后验收

从内到外：

1. MySQL 新账号登录、Redis ACL、MinIO policy/对象、Quark 状态；
2. `backend`、`gying-source`、`social-publisher`、PanSou、OpenClaw 内部依赖；
3. backend 认证、管理员 401/403、QQ token、Redis 限流和错误响应；
4. nginx 路径拒绝、query 清理、请求头清洗、非 root/无 socket；
5. Cloudflare Tunnel、Access、公开页面/API/图片；
6. 日志、告警、备份 manifest 和临时恢复。

回归命令：

```powershell
python -X utf8 tools/security/test_nginx.py
python -X utf8 tools/security/scan_secrets.py
python -X utf8 tools/security/verify_backup.py E:\gying-backups\<timestamp>
docker ps -a --format "{{.Names}}`t{{.Status}}`t{{.Ports}}"
```

真实 QQ 消息、外部发帖、网盘转存和资源清理只有在用户批准的维护窗口执行；fixture/health 通过不等于外部副作用已验证。

## 6. 状态更新

完成后按 `references/status-maintenance.md`：

- 更新 `docs/current-project-status.md` 的运行架构、配置与安全、仍需处理和验收；
- 明确生产部署提交/镜像、端口、Firewall、Access、DB grants、MinIO policy、备份/恢复证据；
- 若任何一项未完成，保留风险和下一步，不写“已安全加固”；
- 更新前后检查 `git status --short`，避免把外部配置或扫描输出提交到仓库。
