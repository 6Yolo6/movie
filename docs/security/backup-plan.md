# 备份与恢复方案

## 1. 当前状态

| 检查点 | 范围与验证 | 限制 |
| --- | --- | --- |
| 2026-10-23 AI可靠性 | 两检查点`20261023T044352.387300Z`/`20261023T045302.843250Z`各14份age，SQL/配置/两侧新key/状态/QQ微博卷/runtime hash和完整认证解密通过 | 两后端平滑排空，2条终态跨重启恢复；非异机灾难恢复 |
| 2026-10-22 应用发布 | `G:/gying-backups/20261021T160733.115483Z`，7 份 age，SQL/配置/卷/runtime hash/完整认证解密 | 仅 backend 平滑停写，无灾难恢复演练 |
| 2026-09-25 完整选定数据 | `G:/gying-backups/20260925T030328.719395Z`，19 份约 372 MiB，冻结约 31 秒，25 表行数/CHECKSUM/对象/中文恢复、MinIO 单图通过 | 不含完整系统账号/远端事务；应用/异机和私钥离线仍待 |
| 9/25 早先 partial | `security-20260925-partial`，16 文件，有限导出/在线归档 | status 保持 partial，标准验证器拒绝，不作为完整恢复点 |

工具为 `tools/security/backup.py`/`verify_backup.py`，模板 `deploy/backup-config.example.json`。外部配置 `G:/gying-secrets/backup-config.json`、私有 MySQL defaults 与 localhost 备份账号已建立，不重复开通。2026-10-23已追加现役AI部署覆盖、本机state/key与容器key卷；原配置已加密备份，dry-run通过，下一次日常自然执行尚未验收。[本轮证据](../history/releases.md#ai-reliability-20261023)。公钥/私钥分离，F 盘受限私钥仍在线，不等于离线或异机。

## 2. 目标 RPO/RTO（需业务确认）

MySQL/对象/状态卷建议每日、RPO≤24h/RTO≤4h，配置按变更保存、RTO≤2h；这些是目标，未作为 SLA 实证。Redis/PanSou 缓存重建，不作为业务恢复源。

## 3. 备份内容

gying 库和可见 routines/events/triggers；MinIO data/config/policy；backend、Quark、QQ/微博两个发布卷、OpenClaw 认证/插件；环境/实际 Compose 覆盖、Tunnel/Firewall、工具配置与加密 runtime。真实卷名来自容器，不猜短名称；系统账号/grants 另有重建方案。远端帖子/分享不能用本机 dump 撤回。

## 4. Windows 执行流程

1. 确认已有 age/私有 defaults、任务身份与路径，无明文归档，输出在 Git 外。
2. 对本轮范围核对配置并 dry-run `python -X utf8 tools/security/backup.py --config <受限配置>`。
3. 协调真实写入方/宿主任务，维护窗口 --execute，失败保留 incomplete，不伪造完整。
4. verify_backup.py 检查 manifest/hash/path；另对所有 age 做完整认证解密，再隔离恢复。

只验证 hash 不证明能解密，只解密不证明系统恢复；不扩大应用账号备份权限。

## 5. 计划任务

register-backup-task.ps1 默认 dry-run，仅在确认范围后 Apply。Interactive/limited，Docker/MySQL 未启动应失败告警，不生成空备份，不自动清理旧副本。是否注册/自然执行以实际任务证据为准。

## 6. 恢复顺序

新隔离目标关闭调度 → MySQL 数据/账号方案 → MinIO/私有对象 → 应用状态/版本 → 内部依赖/SSR/登录/角色 → nginx/公网 → 原自动化逐项恢复。旧凭据是否有效单独验证；兼容应用回退不自动覆盖新 DB。完整流程见[迁移](../../MIGRATION.md)。

## 7. 恢复演练验收

记录时间/版本/manifest/hash/解密、表对象/行数、关键读取、图片/私有对象、登录/权限和回退。完整应用/异机/系统账号/离线私钥未闭环仍保留风险。早期路径/权限/冻结/restore 报告见[原文](../history/README.md#git-history)。
