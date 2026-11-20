# TVmaze采集频率（2026-11-19）

现役TVmaze每6小时一轮/最多10详情尝试，新增、更新、失败共用预算；Worker每60秒查到期。配置/UI支持1–720小时、预算按间隔恢复，代码默认24小时。TMDB/GYING仍24小时10次，无SQL/迁移。

| 服务 | 现役固定镜像 | 上线时间（Asia/Shanghai） | 保留的回退镜像 |
| --- | --- | --- | --- |
| backend | sha256:1b6220ae1e9041d1b2dbaa536bb004619acc54eb6a3d3cc5b26e7655b57cbe20 | 2026-11-19 22:35:20 | sha256:44a74bb1976de81b3d932ab66ae0edc587a817fd5287c20e75b3b9cda0c20f42 |
| frontend | sha256:a8e00af6b8c2e90f45a6211c1a85df8cb9108f6b540ba5d410c070cbc9ef39a6 | 2026-11-19 22:35:41 | sha256:bb21744b05ec2bde1dfc8a360ce9580bfb75c687352d579f7b8ff457c3e42d69 |

仅两应用，沿用Compose28/18层；Worker恢复true，22:37:50配置API/SQL独立确认6/10/enabled。108后端定向/11前端、TS/lint/构建、两mock视口/隔离Linux/5本机4公网入口及有界日志通过，其他来源配置保持、host镜像指向已同步。

最后回读仍为首18190：9关联/1候选/0失败、90待处理。当时预计下一到期点11月20日03:36:31，已属历史预计，未因文档整理补记该轮成功；实际6小时轮次/版本变化/长期积压待自然观测。

回退先恢复24小时间隔、暂停Worker并等相关任务空闲，使用 E:/gying-tools/releases/tvmaze-frequency-20261119/rollback覆盖和上述旧镜像；同步host指向/恢复原Worker，保留数据/检查点，沿用原备份。[首轮证据](../tvmaze-metadata-20261119/README.md)。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
