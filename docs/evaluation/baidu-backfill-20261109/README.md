# 百度存量慢速补全与扫码接通

2026-11-09（Asia/Shanghai）。分支 `codex/cloud-drive-workflows`。01:22:22 已按用户本轮限定授权仅部署 backend/frontend，固定代码 `1a9ff55fb6f8c573d4413a625fc00d9e964e29d0`；本机/公网18入口、194份静态/语言资源SHA、有效配置与无关容器保持通过，真实ADMIN八组页面与独立部署终验通过（26 PASS/0 FAIL/1 Agent实时argv UNKNOWN），01:34扫码任务已恢复Enabled/Ready并通过Limited/Interactive实际运行exit0与独立只读复核，原失败保留。本轮未执行真实转存、生成分享、GYING 发布或删除。

原百度与文件类型版本于00:32上线；00:48官方专用账号导入成功，服务端 `CONNECTED/configured=true`。原失败请求保留，未归因为确定网络或接口问题；后续成功不改写原失败和 UNKNOWN。

## 代码与隔离验证

慢速补全默认关闭、十分钟一条，先已有百度任务，再已有发现/资源链接、小型种子与磁力引用，最后仅百度来源搜索。持久父任务、数据库租约、子任务指针和未知结果阻断；读取状态和启动调度在没有指针时不创建父任务。宿主扫码已修复现存目录 ACL 幂等和显式 loopback 代理。

后端1,180项中1,179通过/1 Redis环境跳过、必需真实SQL与独立Java17 compile通过，385源文件与固定提交完整绑定。前端48项React、TS/lint/Next build、八组构建产物mock浏览器通过，78构建源文件和375固定运行产物绑定一致。宿主13项helper与实际Limited任务exit0通过；宿主pin探针五场景通过，实际配置CAS同步新镜像并回读、只读idle通过。上述mock和隔离验证不计真实网盘业务成功。

## 实际部署与恢复

外部发布目录：`E:/gying-tools/releases/baidu-backfill-20261109`。

| 项目 | 已验证事实 |
| --- | --- |
| Backend | image `sha256:dc9fd40464ac78c9f0b691ac90e97ad313e4c69f0154b34e606569297909c3f2`；实际JAR `5469438057b9dc1f1455258bbc13c5ab01d47902cd85b3b44dd8184481401547`；385源文件绑定 |
| Frontend | image `sha256:4b40a2912d93a1b53abb53383a99d71c7fb6f041ef3cb9d8d6d85386534463b0`；78构建源文件，375实际运行产物0差异 |
| 停止与入口 | 两个旧应用均正常SIGTERM/exit143，仅backend/frontend替换；实际镜像/JAR、有效配置、本机入口、11个无关容器保持通过；实际Compose backend15/frontend9层、其余参数与挂载保持 |
| 数据库 | 现有百度表仅只读结构preflight，`schemaChanged=false`；MySQL、队列、backend-data与state/key保留 |
| 宿主扫码 | 新backend固定pin已同步，实际配置回读/严格无handoff通过；private/profile/会话保持；01:34任务恢复Enabled/Ready，Limited/Interactive实际运行exit0，XML与installation一致及独立只读复核通过；实际stdout未捕获，idle由严格门禁和exit0推定 |
| 日常备份 | 新release整目录作为一项追加，37项apply/dry-run通过、无缺失；原覆盖保留，自然完整备份未验 |

[部署摘要](deployment/deployment-summary.json)、[构建绑定](deployment/backend-build.json)、[停止窗口备份摘要](deployment/final-checkpoint-summary.json)和[日常覆盖摘要](deployment/daily-backup-summary.json)已保存。原部署事实来自外部 `deployment/deployment-result.json`（`deployed-local-verification-passed`、`deploymentPerformed=true`、`completedAt=2026-11-08T17:22:22.776504+00:00`）；该部署助手时点的 `publicVerificationPerformed=false` 保留；后续01:24独立18入口及194份资产验收另记，不能由助手本机结果推定公网验收。源码/构建报告中的 `deployed=false` 仅描述部署前隔离构建时点，实际部署以部署结果和最终在线验收为准。

最终停止窗口检查点：`G:/gying-backups/baidu-backfill-20261109-20261108T172112.304097Z`。七份age大小/SHA、完整认证与离线内容验证通过，覆盖配置、两镜像OCI、单事务SQL26表、backend-data、state/key及host/profile/ACL；backend正常停止期间采集，不称跨服务原子快照，未恢复生产数据或导入SQL。百度会话AES-GCM/schema、同账号、同backend-data volume、归档与当前session/key一致、两次当前pair稳定及0600/0700、UID/GID10001专验通过；没有明文会话归档或凭据输出。

此前登录后检查点 `G:/gying-backups/baidu-login-session-20261109-20261108T165355.091889Z` 的三份age、host/profile/ACL、disabled任务XML、backend-data与会话GCM验证通过，保留原材料。新pin后01:24检查点 `G:/gying-backups/baidu-backfill-post-login-20261109-20261108T172410.983433Z` 已完成三份age认证：877host文件、494目录及所有NTFS ACL匹配且前后静态，精确disabled任务XML、7backend-data文件匹配，百度会话GCM/同账号/当前pair/0600与0700/UID10001专验通过；当时任务仍disabled；01:34最终已恢复Enabled/Ready，实际Limited/Interactive运行exit0与独立只读核验通过，旧备份disabled状态不作为当前任务状态。

回退保持当前SQL、table、会话、state/key和任务计划。任意补全父任务或指针均阻断旧消费者；百度在途/未知意图、禁用文件类型或读取失败同样阻断旧版。若停止新writer后旧版回退门禁失败，恢复固定新候选；本轮未实际回退，不导入旧dump、不重置或重放队列。

## 上线只读验收与边界

01:24独立本机/公网18入口PASS/0 FAIL，包括首页/片库/同片详情200、匿名ADMIN源站401/公网Access302、internal/QQ404，新增补全匿名门禁两项。[18入口证据](deployment/independent-entrypoints-after.json)。194份资产均首尝试通过、0失败/0重试：本机与经既有7897代理公网各95静态文件加2份语言资源，Resource Hub HTML与候选SHA一致。[资产白名单证据](frontend-admin-verification/public-baidu-assets.json)。

01:24–01:28真实ADMIN网页八场景（中英、明暗、390与1280视口）通过：同实际API显示CONNECTED/configured=true，百度两文件类型和四阶段全关，补全关闭/10分钟/1条/无父任务/零计数；视频与种子独立、未保存草稿刷新丢弃、补全读取不建父任务，无溢出。复用既有有效device短期内存JWT，未登录或新增设备；drive/backfill配置键0行保持，16个统计POST本地拦截，0生产写入、0页面/HTTP错误。[ADMIN摘要](frontend-admin-verification/frontend-admin-summary.json)、[八场景](frontend-admin-verification/live-admin-ui.json)、[API与配置保持](frontend-admin-verification/live-admin-verification.json)。 恢复扫码任务后的最终三个GET均200，CONNECTED、关闭/10分钟/1条/无父任务及配置保持再次通过，无浏览器或写入请求；[收尾元数据](frontend-admin-verification/frontend-final-metadata.json)。

01:26独立运行态终验26 PASS/0 FAIL/1 UNKNOWN，UNKNOWN仅普通权限下Agent实时argv；受管记录、固定JAR、进程创建时间、唯一监听和四health通过。本轮backend/frontend实际Compose15/9层、完整385/78源文件、实际JAR/375前端产物0差异、有效配置/挂载/参数保持，11个无关容器0变化。[独立运行态证据](deployment/independent-production-identity-after.json)，原外部报告 `verification/ops-baseline-20261109/independent-production-identity-after.json` 保留；不把stored记录等同实时argv。01:22:22–01:37 Docker stdout/stderr限定日志ERROR/Exception/OOM匹配0，两应用运行中、restarts0。[短窗口日志](deployment/final-logs.json)。

任务启用恢复助手通过strict-idle26项与XML/CAS/异常脱敏14项隔离fixture及独立只读审查；原准备阶段语法/测试报告兼容失败在执行前修复，保留原失败证据。首次恢复已Enable为true/Ready，Windows Export XML省略默认true的Enabled节点，导致task_XML_rejected；保护器不能确认owned XML，未自动disable，尚未显式Start且installation未改。失败报告保留于外部 `verification/ops-baseline-20261109/independent-baidu-enabled-restoration.json`。01:32通过已认证三age中的旧任务XML，私下核对旧false单节点与新true省略节点，其余全部XML精确一致；按新XML确切SHA CAS恢复disabled，导出精确回原旧disabled XML SHA，未显式Start、未改host/installation。[默认节点实证与精确回退](deployment/independent-baidu-enabled-default-xml-proof.json)，原外部路径 `verification/ops-baseline-20261109/independent-baidu-enabled-default-xml-proof.json` 保留。v2随后完成默认节点兼容：唯一Settings、Enabled合法0/1节点，省略仅表示true且与实际Enabled一致，重复/属性/子元素/非法值拒绝，去掉Enabled后严格比较其余XML。新PS5 fixture19 PASS/0 FAIL与独立审查通过。01:34实际恢复成功，任务Enabled/Ready、Limited/Interactive实际运行LastTaskResult0，新dc9fd4 pin、installation.taskEnabled=true、XML SHA与实际均一致，host源码/配置未改；strict无handoff在执行前、Enable后、Run后三次通过，独立post-readonly PASS。真实task stdout未捕获，idle由上述严格门禁和exit0推定，未新增扫码登录或网盘副作用。[实际恢复v2](deployment/independent-baidu-enabled-restoration-v2.json)与[恢复后独立只读核验](deployment/independent-baidu-enabled-restoration-post-readonly.json)保存最终证据；[新pin后三age检查点](deployment/independent-baidu-backfill-post-login-checkpoint.json)和原失败材料保留。慢速补全保持默认关闭；真实转存、分享、GYING发布、自然过期/UNKNOWN恢复与自然完整备份待实际验收。

安全基线仍58 PASS / 11 FAIL / 1 UNKNOWN，G01–G11未关闭；沿用用户本轮两服务限定上线授权，不扩大依赖安全变更范围。扫码、宿主维护和只读验证不构成真实内容发布授权。
