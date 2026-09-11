---
name: gying-project-ops
description: 运维和维护 GYing Movie 项目及其 docs/current-project-status.md 状态手册。用于项目部署、版本升级、回滚、服务器迁移、灾难恢复、新服务器初始化、Docker 与 Compose 运维、MySQL 架构或数据维护、Codex MCP 配置、GYING Source、Resource Hub 与多平台发布运维、外部依赖检查、故障排查、运行态审计和经过验证的项目状态更新。
---

# GYing 项目运维

将本仓库作为生产系统运维。把仓库配置视为预期状态，把在线服务视为观测状态，
用 `docs/current-project-status.md` 维护两者之间经过验证的对应关系。

## 强制规则

- 资源修复必须区分夸克与迅雷 provider：单条失效资源使用 `/api/resources/admin/{id}/repair-invalid`，批量修复使用异步 job 并轮询结果；成功后复核自有分享可访问、`resource_link.link_status=NORMAL` 及可选的 GYING 同步结果。
- QQ 每日推荐属于 backend QQBot 链路，不属于 `social-publisher`；变更或验收时核对 `sys_config` 中的启用开关、时间、篇数、目标群和模板，并确认 QQ 官方主动消息权限。NapCat 已无使用，不作为备用通道、迁移依赖或验收项。
- OpenClaw 补丁同时涉及源插件、唯一安全运行时副本和 Gateway 配置；升级后必须重新应用补丁，验证 UTF-8 搜索进度文本、管理员白名单和未授权命令拒绝行为。

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

## 近期变更验收重点

- 涉及资源修复或发现重跑时，额外阅读 `references/resource-hub-operations.md` 和 `references/troubleshooting.md`，确认 provider 分流、异步 job、最终分享 URL、`resource_link` 状态及 GYING 发布结果。
- 涉及 QQ 推荐时，区分“调度器触发”“官方接口接受”和“群内真实出站”；官方主动消息失败时记录错误码，不把 NapCat 历史容器误当作默认生产依赖。
- 涉及 OpenClaw 升级或补丁时，验证唯一运行时插件路径、UTF-8 搜索进度文本、管理员 QQ/member_openid 白名单和未授权管理命令拒绝行为。

## 参考资料导航

- 仓库边界、系统拓扑、事实来源和审计基线：阅读 [项目地图](references/project-map.md)。
- 读取或更新 `docs/current-project-status.md`：阅读
  [状态文档维护](references/status-maintenance.md)。
- 部署、回滚、迁移、恢复、备份和新服务器验收：阅读
  [部署与恢复](references/deployment-recovery.md)。
- Windows 到 Windows、Windows 到 Linux 的完整迁移、数据搬迁、卷恢复、切换和回滚：先阅读仓库根目录
  `MIGRATION.md`，再阅读 [Windows 与 Linux 迁移](references/windows-migration.md)。
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
& .agents/skills/gying-project-ops/scripts/export-current-migration.ps1 -RepoRoot (git rev-parse --show-toplevel)
```

在仓库外调用时传入 `-RepoRoot`。只有本机预期承载这些服务时才给快照脚本增加
`-ProbeHealth`。脚本默认只读并对配置值脱敏；仅在显式传入 `-OutputPath` 时写入报告文件。

`export-current-migration.ps1` 是迁移数据导出脚本，会创建带时间戳的新快照并生成 SHA-256 清单。它会导出 MySQL、实际 Docker 挂载、OpenClaw、MCP、quark-auto-save 和计划任务；必须在正式切换前的维护窗口重新执行最终导出。脚本不会停止服务，且不导出 NapCat、Redis 缓存或 PanSou 缓存。
