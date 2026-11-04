# 电影身份与主题双后端上线验收

本轮获用户“可上线”限定授权，Movie backend于2026-11-04 22:39:17、Agent backend于2026-11-04 22:42:34（Asia/Shanghai）按先Movie后Agent顺序上线；实际image/JAR、受管身份、真实入口与独立只读复核通过。安全58 PASS/11 FAIL/1 UNKNOWN及G01–G11仍未关闭，第一阶段未完成。

独立只读入口共22项通过；受桥接认证的公开身份投影分页观察13805条，statusCounts={"uncertain": 3413, "supported": 5854, "contradicted": 4510, "conflict": 28}，身份不变量违规0。当前分页计数非数据库原子快照，不是主题质量或完整站点可用性结论。

实际Movie image `sha256:91dc95395cc5d30d589a3c5bf6c4e6053bb972e91ceb138f45cf78dd81d9f521`、JAR `2c1301be46e97f12da16167896cd1241efbb4abc20404d4e9c7c53672a3f5054`、代码 `d6641e749a5d36d4823589a5202d7d5f9856fd26`；Agent JAR `1d1ad1800339f94ffb4b85fa3d93bd6a495e37bd8e1dad5e2dbb59383aaa8a64`、代码 `6c277a4161b4840a89f8ecdca020818192f9eb3c`、PID21260/instance`ff9b164c-ee6f-4620-a1d1-e972d07a634f`。

Movie正常TERM143后完成五份age检查点 `G:\gying-backups\ai-identity-theme-backend-20261104-20261104T143825.278661Z`，Agent正常停止后完成三份age检查点 `G:\gying-backups\ai-agent-identity-theme-20261104-20261104T144159.678847Z`；manifest/size/hash/完整认证检查通过，二者是各自停止窗口检查点，非跨服务原子快照。两份静态应用/回退覆盖已纳入日常备份，dry-run30通过，自然完整备份未验。现役Compose在原11层末尾追加第12层 `E:\gying-tools\releases\ai-identity-theme-20261104\deployment-backend\candidate.json`；前11份文件及参数保持，11月3日第10层rollback和第11层candidate仍保留。有效配置、当前数据/state/key/token/模型/索引及无关容器和本机服务保持。

普通权限前置检查因不可读进程Path与bridge.bin访问拒绝而停止；实际管理员回执确认bridge.bin归BUILTIN\Administrators所有且只有一条ACL规则，不是共享冲突。零字节.lock的真实FileLock占用与该ACL拒绝分别记录。随后Windows实际确认的受限管理员路径完成读取/空闲/身份门禁，未修改ACL或绕过state。

应用回退保持当前SQL/state/key；回退旧Movie前先比较持久化filmIdentityRequired历史，并在候选写入者正常停止后再次核对。新增或竞态历史阻断旧版启动，必要时恢复当前候选保持运行。这些保护由已审查助手与回归验证，不称生产竞态注入；不恢复旧dump或数据。旧Agent055f…直接拷贝queryPlan，传输能保留新字段，恢复旧Agent应用通常无需回退Movie，但会失去本轮电影身份与第二主题审查机制。

独立扩展只读验收初轮误按size200分页，实际ApiSecurityFilter对internal非管理员入口限size100，因此返回400，身份投影schema/完整性检查失败；这是核验脚本请求错误，未据此改生产或回退。原失败报告raw SHA ea813a3d…保留；修正版按100分页重新只读复验通过，不称全部入口一次过。Movie部署期原15入口一次通过与该扩展验收分开记录。

原七轮仍为部署前隔离开发观测，7/7 completed不等于7条满足；模型诊断满足3/部分有用1/证据不足2/未满足1、事实null4、真人0保持。本次上线后只读GET与入口检查没有模型推理或新USER任务，网站USER、跨owner、自然TTL、真实浏览器、主题回答和真人质量仍未验。

验收只读取真实片库身份投影、健康、已注册工具schema、本机/经既有代理公网入口和部署期短日志；不登录USER、不提交推理、不重跑七轮、不制造自然过期/跨owner/真人结论。工具schema中的searchMovies/getMovieDetails/searchMovieWeb与研究内部queryCatalog分别记录。当前日志窗口无ERROR/Exception/OOM不等于长期稳定性。没有实际执行本轮应用回退、生产竞态注入、旧dump恢复或外部发布。

[回执绑定与检查点](deployment/deployment-summary.json) · [独立只读复核](deployment/evidence/post-deployment-readonly.json) · [Movie上线回执投影](deployment/evidence/movie-deployment.json) · [Agent上线回执投影](deployment/evidence/agent-deployment.json) · [初轮独立400原报告](deployment/evidence/post-deployment-readonly-initial-failed.json) · [Movie四核心回归](deployment/evidence/backend-core-validation.json) · [管理包装审查](deployment/evidence/backend-wrapper-validation.json) · [原权限预检失败](deployment/evidence/initial-permission-preflight-failure.json)
