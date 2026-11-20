# TMDB新增元数据的GYING资源跟进（2026-11-15）

新TMDB UPDATES元数据保存时，同事务持久化TMDB_GYING_RESOURCE意图，按名称、类型、年份、季数及唯一canonical严格匹配GYING，再交既有P2P下载/归档链。独立预算每24小时10次资源尝试，单项3次、失败至少24小时后重试，未上映延后；资源失败不重抓元数据，旧RANGE/UNKNOWN不回填或重放。

backend b8ef54e/镜像8931619f…于22:27:42仅backend上线，22:33:45独立终验PASS。最终334项含18隔离真实MySQL全过，源码/JAR绑定、8真实入口及有界Docker日志通过；Worker和两个UPDATES=true，host pin/daily49项CAS/dry-run通过。原辅助脚本多阶段停止浓缩为key目的路径错误：V4停后先恢复原backend/Worker，V5修/run/gying-ai路径后成功；旧失败/UNKNOWN保留。

恢复材料：预部署 G:/gying-backups/tmdb-gying-predeploy-20261115-20261115T142612.040527Z、停机 G:/gying-backups/tmdb-gying-stopped-backend-20261115-20261115T142733.365466Z共六age完整认证。旧镜像/覆盖 E:/gying-tools/releases/tmdb-gying-followup-20261115/rollout-v5-after-key-path-fix-20261115T142029Z。回退暂停UPDATES并审查资源意图/归档阶段，保留当前SQL/data/state/key和上传文件。

自然新资源下载/上传、跨日重复跳过与完整自然备份仍待观测；当次新资源意图/预算为0，ARCHIVE_QUEUED仅入队。[最终回执](rollout-v5-after-key-path-fix-20261115T142029Z/final-verification.json)。

QQ百度最新为ACTION_REQUIRED/CHALLENGE_REQUIRED、尚无转存计划，在百度页面处理；更早BAIDU_NOT_READY原因未知，本地CONNECTED不能倒推官方登录有效。[调查证据](qq-baidu-reason-observation.json)。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
