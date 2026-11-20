# 部署验收证据（2026-11-15）

代码 `1adbc336cdadefa48be40ff37082a6de840fbd41` 的backend/frontend已各一次上线，17:40开启UPDATES，17:47最终审计PASSED。构建candidate报告中的 `deployed=false` 保留其构建时点含义，当前运行事实见activation/update/final回执。所有时间按各JSON中的时区；[本轮说明](../README.md)使用北京时间。

| 范围 | 证据 |
| --- | --- |
| 构建和真实JSON CAS | [backend候选](backend-candidate.json)、[package](backend-package.json)、[JSON CAS](mysql-json-cas-validation.json)、[前端build](frontend-build.json)、[前端候选](frontend-candidate.json) |
| 已激活/已启用 | [activation](activation-receipt.json)、[updates](update-receipt.json) |
| 真实页面两阶段 | [启用前](frontend-ui-pre-config.json)、[启用后](frontend-ui-updates-enabled.json)、[包装器路径修正](frontend-wrapper-path-normalization.json) |
| 首轮元数据/归档 | [首次观测](first-natural-observation.json)、[元数据首轮完成](first-natural-metadata-complete.json)、[归档结果](first-natural-archive-results.json)、[百度明确拒绝](new-baidu-archive-failure-observation.json) |
| 最终保持/入口/日志 | [最终审计](final-audit.json)、[Access诊断](entry-status-diagnosis.json)、[详情SSR](public-movie-ssr-acceptance.json)、[有界日志](bounded-logs.json) |
| 回退/运行声明 | [完整预部署备份](resume-backup-complete.json)、[Host prepare](host-prepared.json)、[Host CAS](host-applied.json)、[日常prepare](daily-prepared.json)、[47→48 CAS](daily-applied.json) |
| 中断和续接审查 | [partial观测](runtime-partial-observation.json)、[18项独审](ResumeIndependentOfflineReview.json)、[binding只读核对](ResumeBindingReadOnlyReview.json)、[补齐助手作者检查](CompletionReadOnlyAuthorChecks.json)、[补齐绑定](completion-readonly-binding.json) |

原备份生产者/执行停止、Access断言停止和错误`/movies`探测保留为独立JSON，不能改写成成功。补齐助手作者检查经root发现路径引用与实际Python3.10时间解析问题后修复，最终27项中含实际函数正例；旧未执行候选和报告留在外部release。TERM raw局部证明未持久化，回执仅标控制流推断，不声称某个实际退出码。

白名单JSON逐字节复制，原始外部绝对引用维持其SHA；仓库副本链接见本表。[SHA256SUMS](SHA256SUMS)核验所有本目录证据及本说明。未复制配置、env、SQL、Cookie/JWT/密钥、原响应正文或完整runtime。截图和完整ops快照在外部release `E:/gying-tools/releases/metadata-update-watch-20261115`。

17:47–17:49观测时百度18069为FAILED1/3、原策略安排18:12:48重试；本轮未手工重试，官方远端内容hash/分享目录未逐项重验。自然日常备份、次日增量实际行为、原历史UNKNOWN和安全门禁未被本次审计代验。
