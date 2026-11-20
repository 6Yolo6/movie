# 迅雷工作流暂停与空间故障核查

2026-11-13 21:07:28–21:12:05（Asia/Shanghai），现役 Windows/Docker Desktop 生产环境，仓库分支 `codex/cloud-drive-workflows`，backend 保持 `65fd377` 对应镜像。用户要求核查迅雷空间并关闭工作流中的迅雷源。

最近 20 条迅雷转存任务中，15 条明确返回 `file_space_not_enough`，2 条源分享无视频，2 条目标已有部分或不同视频，1 条参数错误；历史空间错误共 52 条。任务 824 创建于 2026-11-12 23:22:47，官方转存错误为 HTTP 400 / code 8 / 空间不足。最新任务 825（11月13日19:33:54）为源分享无视频，因此不能把所有失败归因为容量。只读官方文件列表 HTTP 200，当前登录有效；官方 `/drive/v1/about` 配额查询被 captcha 门禁拒绝，精确已用量、剩余量未取得。

一次 ADMIN `PUT /api/admin/resource-hub/config` 仅把迅雷 `videoEnabled`、`torrentEnabled`、`discoveryEnabled`、`transferEnabled`、`shareEnabled`、`gyingPublishEnabled` 从 true 改为 false。真实后台 GET 和数据库六键复核一致，跨 30 秒再次 GET 仍全 false；源码门禁覆盖发现、视频转存、P2P 归档、分享和 GYING 发布。保存目录、批次、夸克/百度及其他 89 行 sys_config 保持，13 容器 ID/镜像/启动代次保持；无服务重启、云文件操作、队列重放或新登录。

关闭前后及 21:08:49 复核，迅雷视频/P2P 各状态数量与最大更新时间一致；历史 RUNNING/SUBMITTED/WAITING_SHARE 记录保留。首页和 QQ backend 健康入口 HTTP 200。关闭后的 Docker 日志最多 300 行读取成功、0 字节，无新日志可供错误分析；未把此项当成业务任务成功。

21:12:05 独立只读复核再次确认迅雷六开关关闭、其他两盘与认证配置原像一致、13容器与其他89配置保持；扩展 provider/source 查询覆盖6620条迅雷视频/P2P任务，创建和更新时间均早于关闭时点，没有新增或推进。

变更前 `G:/gying-backups/xunlei-workflow-pause-20261113-20261113T130722.887053Z` 保存 MySQL 单事务加密备份和精确配置加密原像，两份 SHA/大小、完整 age 认证解密与清单验证通过；在线服务持续运行，不声称跨服务原子快照或灾难恢复。

回退助手为 `E:/gying-tools/releases/xunlei-workflow-pause-20261113/operate.py`，使用 `--rollback-report` 指向同目录 `disabled-1cbd15eb78524d1b900ba8ca96c6e09a.json`。助手只在当前迅雷配置与本次关闭后状态相符时恢复原六开关，重新备份并通过真实 API 核验；不会恢复旧 SQL 或重放旧任务。用户本次要求保持关闭，未执行回退。容量和旧失败任务仍待后续处理，未清理网盘。

脱敏证据见 [summary.json](summary.json)；完整脱敏运行报告和助手留在上述仓库外目录。
