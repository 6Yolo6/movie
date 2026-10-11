# 部署与回滚

[文档导航](README.md) · [当前项目状态](current-project-status.md) · [开发测试](development.md) · [迁移与恢复](../MIGRATION.md)

## 1. 选择正确流程

| 场景 | 使用范围 |
| --- | --- |
| 已有部署的业务增量 | 本文：验证候选 → 固定镜像 → 只替换受影响服务 → 真实入口验收 |
| 新服务器、跨主机或灾难恢复 | [迁移手册](../MIGRATION.md)：隔离目标、恢复与数据切换 |
| 表结构或数据变更 | [数据库](database.md)：单独审查增量、备份与恢复路径 |
| 安全加固 | [安全导航](security/README.md)：完整门禁与逐项授权，不混入普通业务发布 |
| 仅文档整理 | 链接/格式/事实一致性检查后提交；不重建服务或执行 SQL |

**门禁失败、测试失败、目标不清或操作难回退时，停止相应动作。** G01–G11 与完整安全门禁是不同范围；历史业务发布不意味着风险已消除，也不是以后跳过门禁的授权。实际版本、镜像和恢复检查点只在[当前状态](current-project-status.md)及对应部署报告维护。

## 2. 部署前必须具备

- 明确目标主机、实际检出目录、分支/提交、维护窗口和服务清单；保留无关工作树改动，不把未审查内容构建进候选。
- 使用[运维 Skill](../.agents/skills/gying-project-ops/SKILL.md)建立脱敏快照，运行就绪检查；记录容器 ID、镜像 ID、启动时间、原有重启计数、UID、网络/端口及挂载。
- 比较**实际有效配置**与仓库目标配置。现役部署使用显式 Compose 项目名 `gying-movie`、`docker-compose.prod.yml` 与经审查的运行覆盖文件；不能让目录名推导为另一个 `movie` 项目。
- 按变更范围完成[测试](development.md)，先构建/验证候选，再替换线上。镜像使用唯一标签并核对 ID 或 digest；保留旧镜像及同一套运行参数。
- 准备适用本次窗口的[加密备份](security/backup-plan.md)，验证清单、大小、hash 与完整认证解密；涉及恢复能力的门禁还需隔离恢复验证，不能用“备份文件存在”代替。
- 协调相关写入方与在途任务：Resource Hub、网盘转存、backend 发布调度、宿主机频道计划及手工入口按影响范围核对。记录暂停前开关并准备恢复；不批量关闭无关任务，不重置旧 RUNNING/UNKNOWN。
- 发布状态机涉及 backend/publisher 联动，必须先阅读[发布运维](social-publishing.md)。不能单边降级，也不能在外部结果不确定时强行切换。

## 3. Compose 与最小替换

仓库没有默认 `docker-compose.yml`。以下为 **PowerShell 模板**，覆盖文件与受影响服务必须来自本次审查，不是通用一键部署脚本：

    $repo = git rev-parse --show-toplevel
    Set-Location -LiteralPath $repo
    $override = '<经审查的运行覆盖文件绝对路径>'
    $compose = @('-p', 'gying-movie', '-f', 'docker-compose.prod.yml', '-f', $override)
    docker compose @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose 校验失败，停止部署' }
    docker compose @compose ps

只运行 `config --quiet`，不把包含真实环境值的完整 `config` 输出写入聊天或普通日志。配置解析通过不代表安全/数据门禁通过。

在候选、备份、写入协调及门禁均满足后，按清单替换：

    $affected = @('<本次受影响服务名>')
    docker compose @compose up -d --no-deps --no-build --pull never @affected
    if ($LASTEXITCODE -ne 0) { throw '替换未完成，保留现场并按回退方案处理' }

- 覆盖文件必须将清单内每个服务固定到已验证镜像；`--no-build --pull never` 不会替你固定标签内容。
- 不把不带服务清单的 `up -d --build` 当作日常升级；不要隐式启动 `embedded-deps`，已有外部依赖不得被替换或重复创建。
- 环境变量修改需要核对实际容器加载。前端 `NEXT_PUBLIC_API_URL` 是构建参数：生产保持空值走同域，SSR 的 `INTERNAL_API_URL` 指向内部 backend。
- 替换上游后，nginx 需要刷新容器地址。通常只校验和重载，不为此重建 nginx 或其他依赖：

    docker compose @compose exec -T nginx nginx -t
    if ($LASTEXITCODE -ne 0) { throw 'nginx 配置无效，不执行重载' }
    docker compose @compose exec -T nginx nginx -s reload
    if ($LASTEXITCODE -ne 0) { throw 'nginx 重载失败，检查真实入口与回退条件' }

## 4. 验收：由内到外

| 层级 | 必须核对 |
| --- | --- |
| 运行身份与配置 | 实际镜像/产物 hash、UID、环境摘要、挂载、端口/网络与审查一致 |
| 内部读取链路 | backend → DB/Redis/MinIO/source/publisher；frontend SSR → backend |
| 本机入口 | nginx 首页、详情、公共 API 和图片；不能只看容器 running |
| 公网入口 | 同样的真实页面/接口；匿名管理 API 拒绝，内部/QQ 路径不暴露 |
| 受影响 UI | 中英/明暗主题、手机和桌面、加载/失败路径及控制台错误 |
| 任务与外部副作用 | 目标、发布日志及有关任务与维护前基线对照，区分原调度自然运行和人工测试 |
| 未改服务 | 容器身份、启动/重启计数及配置保持基线；不假设所有历史重启计数都为零 |

连续观察窗口与日志检查要记录具体时间。HTTP 200、单测、mock API 和视口截图不是同一种证据；尤其[发布状态](social-publishing.md)需读取业务结果，不通过试发帖子验证健康。

真实转存、修复、扫码账号变更、群消息或发帖须另有明确范围和授权；没有执行则写“未验收”，不能写成功。Tunnel/代理出现间歇故障时保留独立问题，不归功于无关应用上线。

## 5. 回滚与退出条件

- 应用回滚前重新核对这段窗口内的数据、目标配置和外部结果；触发回退守护时先保留证据，不能自动恢复 SQL 或状态表。
- 仅应用故障且数据契约兼容：恢复记录过的旧镜像和有效配置，只替换受影响服务并重载 nginx，再完成同样的真实入口验收。
- 发布状态机联动回退按[发布文档](social-publishing.md#5-部署凭据更新与回滚)执行；新记录可能让旧版本不再安全。
- `movie_source_identity.external_id` 的 `utf8mb4_bin` 在应用回滚时保留，详见[数据库](database.md#4-外部来源-id-大小写)。
- 数据或外部副作用不兼容时停止自动回退，另行评审恢复/前向修复；禁止删除卷、清空历史、重放队列或全局 Docker 清理。
- 按维护前记录恢复被临时暂停的开关，保留原本禁用的项目；不能为了验收擅自启用新的发布计划。

## 6. 记录与交接

部署报告至少写清提交、构建日/上线日、实际镜像与服务范围、备份及回退位置、测试种类/数量/skip、入口与日志观察、外部操作是否发生、未完成门禁。随后只更新[当前状态](current-project-status.md)的相关章节，历史材料进[历史索引](history/README.md)。

Resource Hub、P2P、迅雷会话和 QQ 的操作详情从[文档导航](README.md)进入，避免在本部署手册重复可变配置和真实发布命令。
