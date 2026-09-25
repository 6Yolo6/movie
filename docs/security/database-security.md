# MySQL 安全方案

## 1. 在线观测（区分当前配置与历史查询）

- 2026-09-25 实测 MySQL 8.0.28，gying 库有 25 张 InnoDB 表；本轮未重新查询服务器安全变量。
- 2026-09-25 Windows 监听复核：`3306` 和 `33060` 仍有非 loopback 监听，Public Firewall 关闭；`bind_address=*`、`mysqlx_bind_address=*` 为 2026-09-24 查询结果。
- `require_secure_transport=OFF`；`local_infile=OFF`；`secure_file_priv=NULL`。
- 2026-09-25 三个应用容器配置已均为非 root 的 `gying_app`；本机用现役凭据实测 `CURRENT_USER()=gying_app@%`，grants 为 USAGE 与 gying 库 SELECT/INSERT/UPDATE/DELETE/EXECUTE。这不替代每个容器来源/其他用户的验收；通配 Host 和共用身份仍不符合目标。MCP 为此前只读实测。
- 应用账号仍缺完整备份权限，未扩大其 grants；用户已创建 `gying_backup@localhost`，实测库级 SELECT/SHOW VIEW/TRIGGER/EVENT 及全局 SHOW_ROUTINE，凭据仅当前用户/SYSTEM 可读。拥有元数据权限后确认视图/触发器/事件/例程均为 0。
- 2026-09-20 应用使用 root 的记录仅为历史基线，不再作为当前状态；分服务身份、最小 grants、TLS 和防火墙仍待闭环。本轮未变更账号或数据库。

这些结论是本次审计的最高优先级阻断项之一。

## 2. 目标账号矩阵

| 账号 | 用途 | 目标权限 | Host 规则 |
| --- | --- | --- | --- |
| `gying_app` | Spring backend | `SELECT, INSERT, UPDATE, DELETE` on `gying.*` | 明确 Docker 网段/掩码，禁止 `%` |
| `gying_source` | GYING Source | 按实际读写需求的 CRUD；完成验证后继续收紧 | 明确来源网段 |
| `gying_social` | social-publisher | 按实际发布审计需求的 CRUD；完成验证后继续收紧 | 明确来源网段 |
| `gying_readonly` | MCP/审计 | `SELECT, SHOW VIEW` | 维护主机/只读 peer |
| `gying_backup` | 备份 | `SELECT, SHOW VIEW, TRIGGER, EVENT`，必要时 `SHOW_ROUTINE` | 仅备份主机/任务 |
| `root` | DBA break-glass | 不给应用；保留独立管理凭据 | 仅本机/维护窗口 |

`tools/security/provision_db_accounts.py` 默认 dry-run，要求每个账号独立且至少 24 字节凭据，拒绝把 `%` 作为 account host。脚本不会删除 root 或修改现有用户。MySQL Host 可以使用明确的 IP/netmask 表达式；先从连接日志/临时登录验证实际 Docker 来源，再确定范围。

## 3. 安全切换流程

1. 备份并保存回滚点；记录当前用户/Grant 摘要（只记录账号和权限，不记录密码）。
2. 计算 backend/source/social 的真实来源地址和所需 SQL，执行：

   ```powershell
   $env:MYSQL_ADMIN_USER = '...'
   $env:MYSQL_ADMIN_PASSWORD = '...'
   $env:GYING_APP_DB_PASSWORD = '...'
   # 其余账号使用独立值；不要把这些命令粘贴到日志
   python tools/security/provision_db_accounts.py --host 127.0.0.1 --port 3306 --database gying --account-host <明确IP或IP/掩码>
   ```

3. 仅在人工确认 dry-run 输出后加 `--apply`；先从每个容器用新账号执行只读探针和最小业务写入。
4. 修改外部 `.env`/运行时 secret，重建一个服务，验证日志和真实入口，再继续下一个。
5. 复核应用绝不再出现 `root@...`；不要立即删除 root，等至少一个完整备份/恢复周期和回滚窗口后再处理。

## 4. 传输和服务器配置

目标顺序：

- Windows Defender Firewall：Public/Private profile 默认拒绝入站，只允许 loopback、Docker Desktop 必要内部流量和明确维护来源；不要把 3306/33060/9000/9001 作为任意公网例外。
- MySQL：监听 loopback/受控 Docker 网关，关闭不需要的 X Protocol（33060）或同样限制来源。
- 建立受信任 CA/证书后，将 `require_secure_transport=ON`；JDBC 使用验证 CA 的 SSL mode，而不是长期 `useSSL=false`。
- 启用并验证密码校验策略、失败登录审计、账号锁定/轮换流程；变量名和可用插件以当前 MySQL 8.0.28 实际输出为准。
- 保持 `local_infile=OFF`；不要把 `secure_file_priv` 改成空字符串。

TLS 变更必须先在隔离连接器验证，避免一次性让 backend/source/social/备份全部断连。

## 5. SQL 注入和数据边界

本次静态检查重点是搜索、排序、分页和资源管理：

- DTO/分页/body 有统一上限；动态 `LIMIT` 只接受代码/白名单限制后的整数；
- 业务查询优先 MyBatis 参数绑定，关键词使用参数化条件；
- 管理写操作要求 JWT ADMIN，并保留审计日志；
- 不把用户 URL、Cookie、Authorization 写入日志或 SQL；
- `sys_config` 中的 token/cookie/password/secret 类键必须在后台返回时脱敏并禁止通过运行时配置接口写入，MCP 默认只读。

这不是替代 DAST/SQLMap 的完整证明；上线前仍要用 fixture 数据对搜索、资源提交、审核、排序和批量接口进行授权/边界测试。

## 6. 备份和恢复

MySQL 使用 `tools/security/backup.py` 通过 `mysqldump --single-transaction --quick --no-tablespaces` 产生流并用 age 加密；不要生成明文归档。备份账号和恢复演练要求见 `backup-plan.md`。2026-09-25 已使用独立 `gying_backup@localhost` 完成 gying 库完整逻辑备份（routines/events/triggers 选项启用）及隔离恢复：25 张表行数和逐表 CHECKSUM 一致，视图/触发器/事件/例程数量匹配；当前备份目录为 `G:/gying-backups/20260925T030328.719395Z`。这不是 MySQL 系统账号库或全主机恢复，仍需离线私钥与异机/应用验收；早先 partial 目录保留为历史候选，详见 `backup-plan.md`。
