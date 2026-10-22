# 生产安全审计摘要

最近扫描：2026-10-22，**59 PASS / 11 FAIL / 0 UNKNOWN**；原 G01–G11 均未关闭。此次文档整理未修改线上权限、网络、身份或策略。

| 入口 | 用途 |
| --- | --- |
| [固定 11 项台账](docs/security/hardening-gates.md) | Quark/MinIO 端口、NNP、root 进程、Redis 网络 |
| [风险登记](docs/security/risk-register.md) | 主机、身份、Access、凭据、供应链和恢复缺口 |
| [安全部署清单](docs/security/deployment-checklist.md) | 每次变更的完整前置条件与验收 |
| [当前状态](docs/current-project-status.md) | 实际镜像、检查点及最近业务发布 |
| [整理前审计原文](docs/history/snapshots/20261022/docker-security-report.md) | 9–10 月历史变量、扫描包/CVE、数量与证据路径 |

已验证部分：nginx/backend loopback、应用非 root、内部公网 404、管理员 API 鉴权；2026-09-25 本机防火墙/Quark 父目录/19 文件加密备份和隔离 DB/MinIO 恢复；2026-10-13 Access 单邮箱 OTP/限定管理路径的账号侧启用。上述分别属于各自时点，不能合并为整体安全闭环。

仍需完成：G01–G11、公网直连/IPv6、分服务 DB 身份/grants、MinIO 完整 policy/root key、Quark 单文件权限/非 root/Cookie、Access 本人登录/origin JWT/WAF、当前镜像 CVE、系统账号及异机全链路恢复、私钥离线保管。

2026-10-22 功能上线是用户知悉失败后的限定业务例外；没有依赖加固。历史 Scout/npm audit 仅覆盖当时依赖，工作区密钥扫描 0 不代表历史凭据撤销。安全检查失败按实际结果保留，不能修改期望值使之通过。
