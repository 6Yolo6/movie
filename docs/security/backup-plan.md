# 备份与恢复方案

## 1. 当前状态

- 仓库已加入 `tools/security/backup.py`、`verify_backup.py`、`deploy/backup-config.example.json` 和 Windows 计划任务注册脚本。
- 备份输出必须在 Git 工作区之外；MySQL dump、路径归档和 Docker 卷归档均通过 age 流式加密，不产生明文归档。
- 当前任务没有执行真实加密备份、解密恢复或恢复演练；既有迁移/回滚备份位于仓库外，不能替代本方案的持续验证。

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
