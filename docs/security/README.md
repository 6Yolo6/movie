# 安全文档导航

[项目状态](../current-project-status.md) · [审计摘要](../../docker-security-report.md)

最近已记录扫描 2026-10-25：58 PASS / 11 FAIL / 1 UNKNOWN；UNKNOWN 为已退出的旧迅雷探针。10月22历史为59/11/0，本次整理未重新运行线上扫描。机器 11 项台账与完整安全门禁分别跟踪，业务上线不关闭风险。

| 主题 | 唯一入口 |
| --- | --- |
| 线上失败项与候选 | [G01–G11](hardening-gates.md) |
| 完整风险 | [风险登记](risk-register.md) |
| 变更与验收 | [部署清单](deployment-checklist.md) |
| 目标信任区 | [架构](security-architecture.md) |
| Docker/网络 | [容器安全](docker-security.md) |
| MySQL | [数据库身份/TLS](database-security.md) |
| Cloudflare | [Tunnel/Access](cloudflare-security.md) · [启用证据](cloudflare-security.md#access-recovery) |
| 凭据 | [密钥管理](secret-management.md) |
| 检查点与恢复 | [备份](backup-plan.md) |
| 安全故障 | [事件响应](incident-response.md) |
| 接口静态审计 | [API 清单](api-inventory.md) |

实际操作前重新检查目标和适用证据；文档日期不是全链路重新审计日期。凭据值/原始 inspect/日志只保存在外部受限位置。完整旧证据见[原文快照](../history/README.md#git-history)。
