> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# 安全文档导航

[文档首页](../README.md) · [当前项目状态](../current-project-status.md)

## 先看结论和门禁

- [11 项门禁 G01–G11](hardening-gates.md)：固定风险编号、最后一次完整扫描及逐项验收要求。
- [完整部署安全清单](deployment-checklist.md)：Windows 防火墙、Cloudflare Access、分服务身份、备份和恢复等要求；范围**不止** G01–G11。
- [风险台账](risk-register.md)与[根目录审计报告](../../docker-security-report.md)：风险和证据来源。

截至 **2026-10-08 完整扫描**，G01–G11 仍全部未关闭。**2026-10-11 应用上线没有重跑完整安全扫描，也没有关闭这 11 项门禁**，见[上线记录](../code-optimization-deployment-20261011.md)。本次文档整理不构成新的安全验收或整改授权。

## 按领域查找

| 领域 | 文档 |
| --- | --- |
| 整体边界与目标网络 | [安全架构](security-architecture.md) |
| 公共、管理和内部接口 | [接口安全清单](api-inventory.md) |
| Docker 用户、权限与网络 | [Docker 安全](docker-security.md) |
| 数据库账号、grants 与传输 | [数据库安全](database-security.md) |
| Tunnel、Access 与边缘防护 | [Cloudflare 安全](cloudflare-security.md) |
| 凭据来源、存储与轮换 | [密钥管理](secret-management.md) |
| 加密备份与隔离恢复 | [备份方案](backup-plan.md) |
| 事故隔离与恢复 | [事故响应](incident-response.md) |
| 运维执行约束 | [安全加固参考](../../.agents/skills/gying-project-ops/references/security-hardening.md) |

## 使用边界

1. 先核对线上事实和证据日期，再审查每一项改动；不能把目标 Compose 文件当作已部署配置。
2. 文档和脚本存在不等于已执行。缺失、过期或冲突证据不能记 PASS；11 项台账通过也不能替代完整门禁。
3. 只读扫描与实际整改分开；未通过门禁时停止相应动作，说明阻塞，不复用历史发布授权绕过。
4. 凭据轮换、云端策略、端口/网络迁移、数据恢复和删除操作需要各自的范围、验证与回退方案。
