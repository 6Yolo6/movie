---
name: gying-project-ops
description: 运维和维护 GYing Movie 项目及其 docs/current-project-status.md 状态手册。用于项目部署、版本升级、回滚、服务器迁移、灾难恢复、新服务器初始化、Docker 与 Compose 运维、MySQL 架构或数据维护、Codex MCP 配置、GYING Source、Resource Hub 与多平台发布运维、外部依赖检查、故障排查、运行态审计和经过验证的项目状态更新。
---

# GYing 项目运维

将本仓库作为生产系统运维。把仓库配置视为预期状态，把在线服务视为观测状态，
用 `docs/current-project-status.md` 维护两者之间经过验证的对应关系。

## 强制规则

- 使用 `git rev-parse --show-toplevel` 定位仓库根目录，不假定固定盘符或检出路径。
- 制定运维方案前，以 UTF-8 完整读取 `docs/current-project-status.md`。
- 运维请求只涉及配置、基础设施、数据维护、操作手册和验证。除非用户另行明确要求，
  不修改业务逻辑或 Resource Hub 代码。
- 不打印、提交或复制密钥值。只报告配置键名称、是否存在、来源和验证结果。
  将 `.env`、Cookie、JWT 密钥、API Key、机器人凭据和 OpenClaw 认证材料视为敏感信息。
- 先执行只读检查。运行会改变状态的命令前，说明目标、预期影响、回滚路径和已有证据。
- 生产环境中执行破坏性或难以回退的操作前，必须有明确备份并取得用户确认。
  不把 `docker compose down -v`、删除卷、全局 Docker 清理、`DROP`、`TRUNCATE`
  或物理删除历史数据作为日常维护手段。
- 通过软删除或迁移到 canonical 记录保留数据库历史。优先运行可用的 `dryRun`。
  新增或修改 MySQL 注释时默认使用中文。
- 保留工作树中的无关改动。操作前后都检查 `git status --short`。
- 始终显式使用 `docker-compose.prod.yml`；本仓库没有默认的 `docker-compose.yml`。
- 通过真实入口或真实依赖链验证操作结果，不能只依赖命令退出码。

## 标准流程

1. 确认范围和风险。
   - 确认环境、主机、检出目录、分支或提交、预期结果、维护窗口，以及是否涉及在线数据和外部账号。
   - 区分文档中的既有结论与本次必须重新验证的事实。
2. 建立基线。
   - 运行 `scripts/collect-ops-snapshot.ps1`。
   - 部署、迁移或恢复前运行 `scripts/test-ops-readiness.ps1`。
   - 按 [项目地图](references/project-map.md) 查阅对应的事实来源。
3. 确保可恢复。
   - 修改架构或数据前备份 MySQL。
   - 替换服务前记录 Git 提交、Compose 状态、镜像 ID、外部配置位置和卷挂载。
   - 备份未跟踪的外部配置，但不得把备份放进仓库。
4. 执行最小且可回退的操作。
   - 每次只改变一个层级。
   - 新环境或恢复环境中，依赖验证通过前保持调度器和自动化关闭。
5. 从内到外验证。
   - 依次验证数据库和依赖、`gying-source`、`social-publisher`、后端、前端服务端渲染、nginx 入口、机器人和频道集成。
   - 测试后再次检查日志。
6. 更新项目状态。
   - 遵循 [状态文档维护](references/status-maintenance.md)。
   - 只记录当前能力、运行架构、配置安全、仍需处理、长期不变量和验收证据。
7. 汇报结果。
   - 列出实际操作、验证证据、剩余风险、回滚状态和状态文档变化，不包含敏感值。

## 参考资料导航

- 仓库边界、系统拓扑、事实来源和审计基线：阅读 [项目地图](references/project-map.md)。
- 读取或更新 `docs/current-project-status.md`：阅读
  [状态文档维护](references/status-maintenance.md)。
- 部署、回滚、迁移、恢复、备份和新服务器验收：阅读
  [部署与恢复](references/deployment-recovery.md)。
- Compose、容器、卷、网络、健康检查、日志和端口冲突：阅读
  [Docker 运维](references/docker-operations.md)。
- 新库初始化、增量 SQL、MCP/CLI 选择、备份恢复和安全清理：阅读
  [数据库运维](references/database-operations.md)。
- `mysql_gying`、`docker_tools`、Codex 配置和 MCP 恢复：阅读
  [MCP 运维](references/mcp-operations.md)。
- GYING 来源身份、Resource Hub 配置优先级、批量补全、状态校准、转存分享和安全启停：阅读
  [Resource Hub 运维](references/resource-hub-operations.md)。
- 多 QQ 账号、微博网页会话、发布目标、凭据卷和审计日志：阅读
  [多平台发布运维](references/social-publishing-operations.md)。
- 事故排查顺序和已知故障特征：阅读 [故障排查](references/troubleshooting.md)。

只加载当前任务需要的参考资料；更新状态文件前必须加载
`status-maintenance.md` 状态维护文档。

## 脚本用法

在 PowerShell 中运行：

```powershell
& .agents/skills/gying-project-ops/scripts/collect-ops-snapshot.ps1
& .agents/skills/gying-project-ops/scripts/test-ops-readiness.ps1
```

在仓库外调用时传入 `-RepoRoot`。只有本机预期承载这些服务时才给快照脚本增加
`-ProbeHealth`。脚本默认只读并对配置值脱敏；仅在显式传入 `-OutputPath` 时写入报告文件。
