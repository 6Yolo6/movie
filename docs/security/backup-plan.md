# 备份与恢复方案

## 1. 当前状态

- 仓库已加入 `tools/security/backup.py`、`verify_backup.py`、`deploy/backup-config.example.json` 和 Windows 计划任务注册脚本。
- 备份输出必须在 Git 工作区之外；MySQL dump、路径归档和 Docker 卷归档均通过 age 流式加密，不产生明文归档。
- 2026-09-25 已完成 gying 库完整逻辑备份、选定项目持久数据/配置的冻结快照及隔离 DB/MinIO 恢复；19 个加密文件全部验证。尚未完成 MySQL 系统账号库/整个主机、应用全链路或异机恢复，也未完成离线私钥保管，不能据此宣布整体安全上线门禁通过。

### 当前完整备份与恢复（2026-09-25 11:03–11:06）

- 当前备份：`G:/gying-backups/20260925T030328.719395Z`，manifest=`complete`，19 个文件共 371.76 MiB；`verify_backup.py` 及所有 age 认证解密通过。
- 用户在本机开通 `gying_backup@localhost`；已复核 gying 库 SELECT/SHOW VIEW/TRIGGER/EVENT、全局 SHOW_ROUTINE，以及仅当前用户/SYSTEM 可访问的 `G:/gying-secrets/mysql-backup.cnf`。没有扩大应用账号权限。
- 数据库导出使用完整 routines/events/triggers 选项，不再跳过对象；拥有元数据权限后实测 25 张 InnoDB 表，视图/触发器/事件/例程均为 0。不是用缺权限下的“看不到”推断对象不存在。
- 本地写入冻结：11:03:25–11:03:56 Asia/Shanghai，按原容器 ID 暂停/解冻 OpenClaw、backend、source、social、Quark、MinIO，另有 180 秒独立解冻 watchdog；正常完成，没有触发超时恢复。主机 QQ/迅雷计划任务不与窗口重叠，DB 行数/CHECKSUM 与关键配置哈希前后一致。远端 provider 已提交任务不能被此快照回滚，恢复时须先关闭自动化再核对。
- 除数据库和六个卷外，归档环境、Compose、实际部署覆盖、Cloudflare、MinIO data/config、OpenClaw 外部认证配置、私有备份客户端配置、防火墙策略导出和加密 Docker 运行配置。私钥不写入归档；没有明文 SQL/tar 文件。
- 独立原生 MySQL 8.0.28（专用 G 盘数据目录、loopback 13380、随机测试口令、禁用事件调度）成功恢复，25 张表的行数及逐表 CHECKSUM 全部一致，元数据对象数量与 UTF-8 验证通过；进程已关闭。
- 相同镜像的 MinIO 在 `--network none`、无发布端口的独立恢复目录启动成功：1 张公开图片 SHA-256 一致、私有元数据请求 403，测试容器已移除。不等价于完整 scoped-policy/应用账号验收。
- 完整证据位于本备份目录：`artifact-verification.json`、`database-restore-verification.json`、`minio-restore-verification.json`；窗口与容器恢复证据在 `G:/gying-tools/security-20260925/full-backup-result.json`。
- 恢复副本仍在受限 `full-mysql-restore`、`full-minio-restore` 目录。早先递归清理被执行策略拒绝，本轮未绕过重试；离线私钥、跨主机恢复及系统账号重建仍需单独闭环。

### 历史候选与准备（2026-09-25 10:50–10:58，已由上述完整备份补充）

- 工具：`G:/gying-tools/age-v1.3.2`，官方 GitHub 发布的 Windows amd64 SHA-256 校验通过；未修改系统 PATH。
- 配置：`G:/gying-secrets/backup-config.json`、`backup-recipient.txt`；当时专用 `mysql-backup.cnf` 尚不存在，现已由用户开通。实际库有 25 张 InnoDB 表，当前应用账号缺 SHOW VIEW/TRIGGER/EVENT/SHOW_ROUTINE，不扩大应用账号权限。
- 候选备份：`G:/gying-backups/security-20260925-partial`，16 个 `.age` 文件共约 371 MiB，覆盖应用表、环境/Compose/当前部署覆盖、Cloudflare、MinIO、backend/Quark/social/OpenClaw 状态及加密 Docker 运行配置。没有生成明文 SQL/tar 归档。
- 范围限制：数据库导出明确不含触发器、事件和例程元数据；在线路径/卷未停止写入，不能证明跨服务一致性。manifest 的 `status=partial` 保持不变，`verify_backup.py` 按预期拒绝，不放宽工具。
- 验证：16/16 校验和与 age 认证解密通过；独立原生 MySQL（专用 G 盘数据目录、仅 loopback 13379、随机测试口令、关闭事件调度）恢复 25 张表，行数与导出前后稳定计数全部一致，UTF-8 往返通过；进程已关闭。
- MinIO：相同镜像、独立解密数据目录、`--network none`、无发布端口，健康通过，1 张公开图片 SHA-256 与源一致，私有元数据 403；测试容器已移除。不是完整 policy/scoped 账号或应用链路验收。
- 验证报告在候选目录：`artifact-verification.json`、`table-restore-verification.json`、`minio-restore-verification.json`、`cleanup-verification.json`。恢复数据目录仍在 `G:/gying-tools/security-20260925/{mysql-restore,minio-restore}`；清理被执行策略拒绝，没有绕过重试，目录 ACL 仅当前用户/SYSTEM。
- 私钥暂存 `F:/gying-recovery-keys/gying-age-20260925.txt`，与 G 盘备份分离且 ACL 受限，但仍在线；用户必须完成离线保管，不能把不同盘符当成离线/异机备份。
- 本机交互入口：`G:/gying-tools/security-20260925/Prepare-BackupAccess.ps1` 默认 dry-run，`-Apply` 要求管理员 PowerShell并本机输入 MySQL 管理员密码；仅新建 `gying_backup@localhost` 与最小备份 grants、保存私有 defaults、导出防火墙。已存在账号/文件会拒绝覆盖，失败后需检查部分创建状态，不自动删除或修改既有账号。
- 原 E 盘模板路径仍是示例；本机后续使用已准备的 G 盘配置。专用账号、完整逻辑/持久数据备份和隔离恢复现已完成；生产端口/服务重建仍需分别复核 Firewall、Access、身份/policy 等门禁。

## 2. 目标 RPO/RTO（需业务确认）

| 对象 | 目标频率 | 建议 RPO | 目标 RTO | 备注 |
| --- | --- | --- | --- | --- |
| MySQL | 每日全量 + 保留 binlog（若可用） | ≤24h（未接入连续复制前） | ≤4h | 单事务 dump，不锁业务表 |
| MinIO 图片/文件 | 每日增量或快照 | ≤24h | ≤4h | 公开图片可重建，私有文件必须备份 |
| backend/social/Quark/OpenClaw 状态卷 | 每日 | ≤24h | ≤4h | 凭据卷必须加密并单独限制访问 |
| Cloudflare/Windows 配置 | 每次变更 + 每日 | ≤1d | ≤2h | 凭据文件只做加密归档 |
| Redis/PanSou cache | 不作为恢复源 | 可丢失 | 可重建 | 不把缓存当业务数据备份 |

RPO/RTO 是目标，不是已验证承诺；首次恢复演练后再调整。

## 3. 备份内容

`deploy/backup-config.example.json` 的模板包含：

- MySQL `gying`；
- 仓库外 `.env`（加密归档，不把值写入 manifest）；
- Cloudflare Tunnel 配置/凭据目录；
- MinIO data/config；
- backend-data、Quark、social QQ/Weibo、OpenClaw 状态卷。

生产配置必须核对路径和卷名；不存在的卷会被工具拒绝，避免“成功备份空卷”。

## 4. Windows 执行流程

1. 在仓库外创建 age recipient 文件（只放公钥），私钥离线保存；
2. 创建 MySQL defaults 文件，ACL 限制当前用户/SYSTEM；
3. 复制模板到受保护目录，填写路径和卷名，不把真实配置提交 Git；
4. 先 dry-run：

   ```powershell
   python tools/security/backup.py --config E:\gying-secrets\backup-config.json
   ```

5. 在维护窗口执行：

   ```powershell
   python tools/security/backup.py --config E:\gying-secrets\backup-config.json --execute
   python tools/security/verify_backup.py E:\gying-backups\<timestamp>
   ```

`verify_backup.py` 只验证 manifest/hash 和路径安全，明确**不证明可解密或可恢复**。

## 5. 计划任务

先运行无 `-Apply` 的 dry-run，确认 Docker Desktop、MySQL、age、路径和权限均可用：

```powershell
.\tools\security\register-backup-task.ps1 `
  -ConfigFile E:\gying-secrets\backup-config.json `
  -PythonExe E:\python3.10.6\python.exe
```

确认输出后才允许在维护窗口加 `-Apply`。任务以当前 Windows 用户、Interactive/limited 权限运行；Docker Desktop 未启动时应失败并告警，不要静默生成空备份。脚本不会自动删除旧备份。

## 6. 恢复顺序

1. 在隔离主机/目录导入 age 私钥；
2. 验证 manifest、解密单个小文件和 hash；
3. 恢复 MySQL 到临时实例，运行表/行数/关键查询校验；
4. 恢复 MinIO 到临时数据目录，验证图片、私有对象和策略；
5. 恢复 backend/source/social/Quark/OpenClaw 卷，必要时先撤销旧 token；
6. 用新 secret 启动隔离 Compose，运行内部依赖、登录、管理员授权、媒体和机器人 fixture 测试；
7. 只有验收通过才切换 Tunnel/DNS；
8. 保留旧环境可回滚，确认新环境稳定后再按保留策略处理旧副本。

## 7. 恢复演练验收

必须记录：绝对时间、备份 manifest/hash、解密结果、MySQL 版本和表数量、MinIO 对象抽样、服务健康、登录/管理员拒绝、回滚决定。不要记录任何凭据值。至少每季度演练一次，关键凭据轮换或大版本升级后提前演练。
