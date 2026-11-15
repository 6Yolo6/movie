# TMDB 新增影片的 GYING 种子跟进：2026-11-15 验收记录

当前状态：本轮 TMDB→GYING 资源跟进候选尚未上线。20:43 的 after-host-idle execute 以 Gate／gate=null 安全停止，仅保留 deployment reservation，本 run 未暂停 Worker、发送 TERM 或激活候选版本；具体原 Gate 原因仍 UNKNOWN。21:00 的只读运行态确认 13 个原容器世代与后端镜像保持、后端 Running，SQL 中 Worker 与 TMDB／GYING Updates-only 标志为 true，但 admin 仍未满足安全校验、后台 API 完整配置复验结果为 null。20:55 聚合证明当前已有管理员设备均过期，需刷新登录；20:59 轻量内存观测回升至 commit 89.86%、余量 3373.7 MiB，不能代替完整 readiness。后续需刷新后台登录，再次核对内存和全部原门禁后使用 fresh run；原 334 项业务和 85 项运维测试、候选产物继续有效，无需重建或重测。自然资源轮次验收仍 pending，所有历史失败与旧 pause→restore 证据分别保留。

## 本轮实现与验收范围

本轮补齐 TMDB `UPDATES` 新增影片保存之后的资源跟进：持久保存跟进任务，先从 GYING 严格匹配影片身份，再将允许的 P2P 元数据文件交给现有归档链路。新任务类型为 `TMDB_GYING_RESOURCE`，使用独立的终态 `TMDB_GYING_RESOURCE_BUDGET` 账本限制每 24 小时最多 10 次资源采集尝试。历史 `RANGE` 全量游标与历史 `UNKNOWN` 任务保持原状。

业务代码已冻结至提交 `b8ef54ec953e3245e8fbeb3cb7452d3392e59cf6`。独立代码审核核对 423 个 Git blobs 与原始源码清单，17 项检查通过；原始源码清单 SHA-256 为 `4ba8a13372b15d513fc76cf7e8cb3c95126823df39505bd5968fd3698d5bfa98`。V4 已完成 13 个相关测试类、334 项测试，失败／错误／跳过均为 0，其中真实 MySQL 检查 18 项。使用新建隔离环境、无网络、无宿主端口，未复用编译缓存或旧测试报告；Maven 退出码 0 在输出日志前记录，2 个测试容器已清理，生产未改变。测试证明已绑定冻结提交与全部 423 个源码文件。后端 JAR 打包退出码为 0，JAR SHA-256 为 `5b4492cd4047161c030b6c53254d7a7d8682d8ec220bef465d7b392e434b4c8e`，`BOOT-INF/classes` 下 448 个文件与隔离阶段目录匹配；本次打包没有重复运行测试。候选后端镜像 `sha256:8931619fd119eb9154ff498adace98d6f688bdff409e1e4db816969cce4fdc95` 已构建，镜像内 JAR 哈希通过校验，构建期间生产容器世代保持不变。首次部署执行随后在 `AUTHENTICATED_EXACT_EFFECTIVE_BACKEND_MODEL` 门禁处停止，后续上线仍待新回执。本目录 V1 的 16 项与 11 项检查，V2 的 28 项、V3 的 69 项与 V4 的 85 项检查，均针对运维辅助脚本或离线逻辑，不计为业务测试，也不证明实际网盘转存、分享或上传成功。

其他元数据源按用户选择先整理可用来源和建议；本轮不会据此自动接入新采集源。影片种子仍优先从 GYING 搜索获取。沿用[可用来源研究](../metadata-update-watch-20261115/sources/metadata-sources-research.md)中的 TVmaze、Wikidata 等建议；该文档对 TMDB 资源链的说明适用于其记录的上一轮代码基准，本轮跟进由本目录验收。

## 已保存的安全证据

| 文件 | 结果 | 适用范围 |
| --- | --- | --- |
| [qq-baidu-reason-observation.json](qq-baidu-reason-observation.json) | 只读观测完成 | 区分 17:25 的旧不可用回复与 17:26 的最新百度额外验证要求 |
| [qq-baidu-current-config.json](qq-baidu-current-config.json) | 本地配置就绪 | 会话已保存，未验证当前官方登录有效性 |
| [gying-live-year-schema-readonly-v2.json](gying-live-year-schema-readonly-v2.json) | 一个可信既有身份的详情 GET 通过 | 年份字段实际为整数；仅覆盖该样本，不代表全部 GYING 数据 |
| [host-memory-20261115T125919Z.json](host-memory-20261115T125919Z.json) | 20:59 轻量内存余量回升 | commit 89.86%、余量 3373.7 MiB；未完整复验 readiness，未停进程或写配置 |
| [runtime-after-backup-stop-2026-11-15T130008.487232_0000.json](runtime-after-backup-stop-2026-11-15T130008.487232_0000.json) | 21:00 最新原运行态保持 | 原 13 世代／镜像／Running、Worker 与两 Updates 为 true；admin 仍失败、stableConfigurationExact=null |
| [rollout-v4-after-host-idle/deployment-execute-safe-stop.json](rollout-v4-after-host-idle/deployment-execute-safe-stop.json) | 20:43 最新 execute 安全停止 | Gate／gate=null，仅 deployment reservation；原 Gate UNKNOWN、本 run 无 pause／TERM／激活 |
| [admin-device-expiry-aggregate-20261115T125519Z.json](admin-device-expiry-aggregate-20261115T125519Z.json) | 20:55 当前管理员认证失效的聚合只读证明 | 88 个未撤销设备均过期，eligible=0；历史 execute Gate／owner 和所选设备仍 UNKNOWN |
| [host-memory-20261115T124826Z.json](host-memory-20261115T124826Z.json) | 20:48 宿主内存压力只读观测 | commit 98.49%、可用 501.5 MiB；未停进程或改配置，不直接证明旧 Gate 根因 |
| [runtime-after-backup-stop-2026-11-15T125434.483633_0000.json](runtime-after-backup-stop-2026-11-15T125434.483633_0000.json) | 20:54 原运行态保持 | 13 世代／原镜像／Running、Worker 和两 Updates 标志为 true；stableConfigurationExact=null，API 完整复验未成立 |
| [host-idle-window-independent-review.json](host-idle-window-independent-review.json) | 20:40 观测窗口独立复核通过 | 距最近固定业务 NextRun 6796.61 秒；未复查后续实时状态或推断历史 Running 身份 |
| [rollout-v4-idle-retry/worker-pause-ack.json](rollout-v4-idle-retry/worker-pause-ack.json) | 旧 20:33 run 的 Worker pause ACK | target=false、单次 PUT、outcome 已知；不能归到最新 20:43 run |
| [rollout-v4-idle-retry/deployment-execute-safe-stop.json](rollout-v4-idle-retry/deployment-execute-safe-stop.json) | 20:33 execute 因宿主业务任务空闲门禁停止 | 已有 Worker pause reservation，未 TERM／激活；不证明具体 Running 任务身份 |
| [rollout-v4-idle-retry/worker-restored-old-backend.json](rollout-v4-idle-retry/worker-restored-old-backend.json) | 原 Worker=true 唯一 guarded restore 后复核通过 | 13 原容器世代保持，serviceStops／activations／providerRequests 均为 0 |
| [rollout-v4-idle-retry/worker-restore-after-host-idle-stop-ack.json](rollout-v4-idle-retry/worker-restore-after-host-idle-stop-ack.json) | Worker 恢复 ACK | target=true、putAttempts=1、configurationOutcomeUnknown=false；现有登录 lastSeen 可变 |
| [host-idle-diagnostic-20261115/fixed-info-projected-20261115T124016Z.json](host-idle-diagnostic-20261115/fixed-info-projected-20261115T124016Z.json) | 20:40 原固定任务 gate 当前通过 | QQPost／XunleiSync Ready、lastResult=0；QR 不在固定名单，历史失败 snapshot 不可得 |
| [rollout-v4-after-host-idle-isolation-independent-review.json](rollout-v4-after-host-idle-isolation-independent-review.json) | 新 after-host-idle run 四文件隔离独审通过 | 原字节相同，无新逻辑、未扩任务名单；仍要求新计划真实通过全部门禁 |
| [rollout-v4/deployment-prepare-safe-stop.json](rollout-v4/deployment-prepare-safe-stop.json) | 首次 V4 prepare 被 QQ 清理队列阻塞 | NO_QQ_CLEANUP_QUEUE、无 reservation；未 Worker pause／TERM／激活 |
| [qq-cleanup-deploy-blocker.json](qq-cleanup-deploy-blocker.json) | 清理 job 415 为 QUARK／PENDING／尝试 0 | 自然 deleteAfter 为 20:29:05，无 error；未手动清理或 provider 请求 |
| [rollout-v4-idle-retry-isolation-independent-review.json](rollout-v4-idle-retry-isolation-independent-review.json) | 独立新 run 的四文件原字节隔离审核通过 | 不复制旧 overlay／prepared／reservation；复用原审核／构建／测试，原空闲门禁保持 |
| [rollout-v4/backend-rollout-v4-independent-review.json](rollout-v4/backend-rollout-v4-independent-review.json) | V4 最终实现独审通过 | plain Command、实际严格 Ready、原全部门禁／CAS／激活顺序保持，旧失败不改 |
| [rollout-v4/offline-ops-tests-attempt1.json](rollout-v4/offline-ops-tests-attempt1.json) | 85 项辅助检查本地通过 | 输入前后哈希一致；没有真实 Windows／Maven／生产操作 |
| [rollout-v4/independent-offline-tests.json](rollout-v4/independent-offline-tests.json) | 同一套 85 项独立执行通过 | 最终审核绑定该报告；独立复验不另累加测试数量 |
| [rollout-v4/adapter-tests-attempt1.json](rollout-v4/adapter-tests-attempt1.json) | 其中 41 项 adapter 专项通过 | 属于 85 项联合检查；严格错误／warning拒绝、持续锚点与45秒期限等 |
| [host-probe-diagnostic-20261115/probe-20261115T120846Z.json](host-probe-diagnostic-20261115/probe-20261115T120846Z.json) | 真实 EncodedCommand 探针返回 0／JSON 有效 | stderr 含 4 条正常 CLIXML progress，因非空不满足严格读取条件 |
| [host-probe-diagnostic-20261115/probe-output-text-20261115T120940Z.json](host-probe-diagnostic-20261115/probe-output-text-20261115T120940Z.json) | OutputFormat Text 仍有 stderr | 此参数未解决严格探针条件；早期推荐已正式纠正 |
| [host-probe-diagnostic-20261115/probe-plain-command-20261115T121106Z.json](host-probe-diagnostic-20261115/probe-plain-command-20261115T121106Z.json) | 真实 plain Command 探针通过 | 单 argv、无 shell、stderr 为空；与前两次 JSON 同字节／SHA，Ready 与 1374 ACL 行有效 |
| [synthetic-host-probe-command-mode-validation-v1.json](synthetic-host-probe-command-mode-validation-v1.json) | 合成正常／错误／警告实验通过 | 正常导入 JSON 通过；显式错误和混入 stdout 的 warning 仍被严格规则拒绝，正式纠正旧推荐 |
| [deployment-v4-design-independent-review.json](deployment-v4-design-independent-review.json) | V4 设计在实现约束满足时安全 | 设计报告写入时仅为条件性审核；后续最终实现独审另记，生产部署仍待验 |
| [rollout-v3/deployment-prepare-safe-stop.json](rollout-v3/deployment-prepare-safe-stop.json) | V3 prepare 安全停止 | HOST_PROBE_READ_FAILED、无 reservation；未生成 prepared／overlay、未 pause／TERM／激活 |
| [rollout-v3/backend-rollout-v3-independent-review.json](rollout-v3/backend-rollout-v3-independent-review.json) | V3 最终独审通过 | 新助手／HostReadyAdapter 固定哈希，原门禁与 CAS／激活顺序保持；真实上线仍待回执 |
| [rollout-v3/offline-ops-tests-attempt1.json](rollout-v3/offline-ops-tests-attempt1.json) | 69 项辅助检查本地通过 | 包含原部署／配置门禁、旧主机基线及 Ready 等待；未调用生产／Maven |
| [rollout-v3/independent-offline-tests.json](rollout-v3/independent-offline-tests.json) | 同一套 69 项独立执行通过 | 输入前后哈希一致；不另累加为 138 项，不计入业务测试数量 |
| [rollout-v3/adapter-tests-attempt3.json](rollout-v3/adapter-tests-attempt3.json) | 其中 25 项 adapter 检查通过 | 真实 Ready 才返回，45 秒总期限包含源码校验和 probe；没有 Windows 进程调用 |
| [rollout-v2/deployment-emit-runtime-safe-stop.json](rollout-v2/deployment-emit-runtime-safe-stop.json) | V2 emit-runtime 安全停止 | RuntimeError，gate 未记录；没有 pause／TERM／activation reservation |
| [rollout-v2/deployment-execute-safe-stop.json](rollout-v2/deployment-execute-safe-stop.json) | V2 外层 execute 安全停止 | 同次新 run 的外层失败，原 run 未重试 |
| [rollout-v2/deployment-v2-incomplete-runtime-independent-review.json](rollout-v2/deployment-v2-incomplete-runtime-independent-review.json) | 认证扫描定位不完整产物阶段 | 76 冻结文件和修正后的模型 SHA 正确，缺主机快照／recovery成员；checkpoint 不完整 |
| [deployment-v2-readonly-emit-diagnosis.json](deployment-v2-readonly-emit-diagnosis.json) | 后续只读完整 emit 通过 | tar 流丢弃，无新备份／激活，不证明此前具体失败条件 |
| [rollout-v2/repair-description.json](rollout-v2/repair-description.json) | 仅 4 点运维辅助修复 | 原 V1 文件／reservation／failure 保持；修复说明写入时独审待验，后续独审另有回执 |
| [rollout-v2/backend-rollout-v2-independent-review.json](rollout-v2/backend-rollout-v2-independent-review.json) | 修正版最小差异独审通过 | 原字节摘要门禁、单次激活、CAS 和不重试约束均保持；未验证生产部署 |
| [rollout-v2/independent-offline-tests.json](rollout-v2/independent-offline-tests.json) | 28 项辅助检查通过 | Unicode 真实 emit 的 mock 回归、ASCII 不变、旧转义被拒绝与父目录去重等；没有重跑 Maven |
| [deployment-v1-serialization-independent-review.json](deployment-v1-serialization-independent-review.json) | 真实认证归档验证根因 | 默认 ASCII 转义与 canonical UTF-8 摘要字节不同；JSON 配置语义完全一致 |
| [deployment-v1-unchanged-runtime.json](deployment-v1-unchanged-runtime.json) | 首次停止后只读运行态复核通过 | 旧镜像／容器 ID、Worker、受保护行与其他运行态保持；没有 pause／TERM／activation reservation |
| [deployment-v1-safe-stop.json](deployment-v1-safe-stop.json) | 首次 execute 在模型摘要门禁安全停止 | 仅 deployment reservation；主代理确认未 pause／TERM／激活，不自动重试原 run |
| [artifact-chain-independent-review.json](artifact-chain-independent-review.json) | 测试阶段、源码、打包产物链独审通过 | 448 文件的哈希／mtime 与实际 compile-skip 日志核对；没有打包前哈希快照 |
| [candidate.json](candidate.json) | 候选后端镜像／JAR 校验通过 | backend 镜像以现役基底叠加 JAR；构建时生产容器未改变，报告本身未宣称部署 |
| [backend-tests.json](backend-tests.json) | 业务测试与冻结提交／423 文件源码绑定 | 13 类 334 项测试与 18 项真实 MySQL 检查；不代表生产网盘操作 |
| [backend-package.json](backend-package.json) | JAR 打包成功，退出码 0，448 文件匹配 | 未重复测试；此报告的 image 为打包运行器镜像，候选后端镜像需单独验证 |
| [business-tests-pass-v4.json](business-tests-pass-v4.json) | 334 项相关业务测试通过，MySQL 18 项 | V4 新建隔离环境，原始源码 SHA 与冻结提交绑定；生产未改变 |
| [business-test-stages-summary.json](business-test-stages-summary.json) | V1–V3 失败和 V4 通过分别保留 | V2 原退出码未捕获，未补造 0；V1/V3 未执行业务测试类 |
| [code-independent-review.json](code-independent-review.json) | 17 项代码独审通过 | 绑定冻结提交和 423 个 Git blobs；不替代真实业务测试或上线验收 |
| [root-operational-review.json](root-operational-review.json) | 10 项部署辅助检查和 6 项配置辅助检查通过 | 主代理编写部署脚本，并独审配置脚本；没有执行生产操作 |
| [followup-declarations-final-static-tests.json](followup-declarations-final-static-tests.json) | 11 项离线／AST 检查通过 | 配置提案只追加一个目录、拒绝重复、CAS 确认顺序和日志摘要等 |
| [backend-rollout-independent-review.json](backend-rollout-independent-review.json) | 18 项独审通过 | 部署脚本静态门禁审核；真实运行时门禁仍须执行通过 |
| [evidence-manifest.json](evidence-manifest.json) | 文件 SHA-256 清单 | 核对本目录证据与外部源文件的字节一致性 |

V1 辅助脚本审核绑定的 SHA-256（原版本与失败记录保留）：

- `BackendRollout.py`：`c94875de54314ac3810dfc3145e6d8c662c9b448e426252cb62101c285211e03`。
- `FollowUpDeclarations.py`：`ee4c082f187b9aa2375cf0e3c9ace11f59717e99e9b6aec153a4fa52f7c9d381`。

这些辅助脚本保存在仓库外 `E:/gying-tools/releases/tmdb-gying-followup-20261115/`。部署只影响 backend；主机配置只更新后端镜像指向，每日备份配置只追加本次发布目录，原 48 个产物变为 49 个。每次配置替换先保存独立 CAS 确认，再执行完整后验；失败时保留当前状态，停止该阶段，不自动重试或恢复 SQL。

打包类文件哈希采样发生在打包进程启动之后、compile 阶段之前，不能称为打包前基线。产物链独审已核对完成日志中的 main／test compile-skip、448 个文件的哈希与修改时间保持，以及 JAR 对应文件一致，确认此次打包保留 V4 阶段产物且未重新编译。独审报告对候选构建助手的检查与 candidate.json 的实际镜像／JAR 校验分别保留。

## 首次部署安全停止

2026-11-15 19:26:51（北京时间），首次 `execute` 因 `AUTHENTICATED_EXACT_EFFECTIVE_BACKEND_MODEL` 门禁停止。安全 failure 仅记录 `deployment-reservation.json`。主代理确认 3 个部署前加密备份已完成，但尚未暂停 Worker、尚未发送 TERM、尚未激活候选后端；因此没有上线成功回执。

随后于 19:32:18（北京时间），独立审核通过认证扫描真实归档证实根因：prepared 模型使用 canonical UTF-8 字节摘要，归档成员使用默认 ASCII 转义序列化，二者的 SHA-256 不同；归档 JSON 解析后的模型与 prepared、当前有效模型完全相同。因此是编码表示的校验差异，配置语义没有漂移。初次停止时仍在调查，后续根因验证另有独立报告。

停止后的只读复核确认旧后端镜像与容器 ID、Worker 和受保护行／其他运行态保持，且没有 Worker pause、backend TERM 或 activation reservation。原失败记录保留，不重试原 run、不恢复 SQL、不重放旧任务。修正版正使用独立的 `rollout-v2` 空间准备，并补 Unicode 摘要与每日备份父路径去重门禁；其审核和真实执行结果将单独保存，当前仍未上线。

V1 辅助脚本的静态检查通过仍是历史检查事实，不能代替此次失败的真实部署门禁。归档正文、配置内容和完整 prepared 文件未拷入仓库。

## V2 运维辅助修复与独审

V2 使用仓库外 `E:/gying-tools/releases/tmdb-gying-followup-20261115/rollout-v2/` 的新文件与新 run。仅调整四处：有效模型归档添加 `ensure_ascii=False`、配置助手绑定新的部署助手 SHA、每日备份追加路径保持为整个原发布父目录，以及相应的父路径去重比较。原字节 SHA 校验、全部部署门禁、单次激活／配置 CAS、不自动重试或恢复 SQL 的约束保持。

- V2 `BackendRollout.py`：`af8e31955379458b9382c9c4cf47917eddfcbad16695cc37e95071413b4812a1`。
- V2 `FollowUpDeclarations.py`：`e1ec332a24c6274f9b77655adc53b26236c26d8fd5d0900320b1f95df7aa53c8`。

独立审核和 28／28 项离线辅助检查通过，包括原 16 项与新增 12 项检查。新增回归直接执行被 mock 的真实 `emit_runtime`，证明 Unicode tar 成员字节 SHA 与 canonical 摘要一致、ASCII 字节不变、旧 Unicode 转义仍被原门禁拒绝；同时验证每日备份仅追加原发布目录、同路径不同名称与同名称不同路径均被拒绝。新助手在测试前后哈希保持。

这些检查没有调用生产 prepare／execute，也没有重跑 Maven。业务源码、候选镜像与此前 334 项业务测试证据保持；V2 的真实准备与部署由主代理另外执行，当前尚无成功上线回执。

## V2 实际执行停止与不完整检查点

2026-11-15 19:38:14（北京时间），V2 的 `emit-runtime` 与外层 `execute` 分别保存 `RuntimeError` 安全失败回执，`gate=null`，仅有 deployment reservation。主代理确认没有暂停 Worker、发送 TERM 或激活候选后端。

后续独立认证扫描确认 tar 已包含 76 个冻结文件、backend container 与 effective model，共 78 个普通文件；冻结文件 SHA 全部匹配，修正后的 Unicode 模型成员 SHA 也已匹配 prepared canonical。主机快照与 recovery prepared 成员缺失，失败阶段被推断为 `HostCheckpoint.snapshot()`。**密文认证完成只说明已存在的流可认证，不能把该不完整 checkpoint 标记为完整、可用备份。**

具体 snapshot 失败条件仍未证实。主代理随后只读执行完整 emit 成功，tar 流直接丢弃，仅保存字节数／SHA 摘要；这既不是重试部署，也不能倒推此前一定发生过 `Running` 状态竞态。后续方案正研究等待宿主任务自然进入正常 Ready 状态的 V3 新 run，不修改生产状态，不放宽原门禁。

## V3 Ready 等待适配与最终独审

V3 使用独立的仓库外 `rollout-v3/` 文件和新 run，复用同一业务源码、候选镜像与 334 项业务测试。新增 `HostReadyAdapter.py` 只针对原主机快照的两个固定 RuntimeError 条件，要求独立的实际 Running 状态证据才能等待；返回值始终来自原验证逻辑实际取得的 Ready 快照。仅用于等待的 Running 规范化副本不会作为备份快照返回。

整个等待受 45 秒单调时钟总期限限制，源码校验、严格 probe 和睡眠均计入；所有进程调用只读且超时受剩余期限限制。原 Host／PowerShell 文件哈希固定，不修改原模块或全局 subprocess。新的 prepared Ready 元数据摘要必须与 V2 既有基线一致，原字节摘要校验、Worker 修改、单次激活顺序和 host／daily CAS 逻辑保持。

- V3 `BackendRollout.py`：`8a0c46672e73e51b482113ddc5ee1225a260693c688b5dd92671b38c8a3119b1`。
- V3 `FollowUpDeclarations.py`：`1740b1e526568a72d34d996c5d6dbca913bc109c47e4fb2507ded74058234ba1`。
- V3 `HostReadyAdapter.py`：`30f4575299742f3e0ef684870c324e0b9017704768ddd7b63454f9d8805e7610`。

最终独审通过；69 项辅助检查已本地通过并由独立代理执行确认，其中包含 25 项 adapter 检查。上述测试均为离线 mock，没有执行生产 prepare／execute 或重跑 Maven。V1／V2 原材料继续保留，过去 snapshot 失败的具体条件仍未知，不能据此认定当时 QQ 登录任务处于 Running。V3 的实际 prepare 随后安全停止，未进入 execute；具体宿主读取失败原因仍待诊断，冻结 V3 文件保持。

## V3 真实 prepare 安全停止

2026-11-15 20:05:20（北京时间），V3 `prepare` 保存 `HostReadyError / HOST_PROBE_READ_FAILED` 安全回执，`reservations=[]`。主代理确认目录尚无 prepared 或 overlay，未暂停 Worker、未发送 TERM、未激活候选镜像。没有本轮部署成功或完整回退检查点的声明。

该错误码说明宿主探针读取失败，尚不能确定更具体的系统条件。主代理继续只读诊断，冻结 V3 文件保持；原 V1／V2／V3 失败分别保留，不把辅助测试通过改写成实际部署通过。

## 宿主探针真实诊断与 V4 设计

20:08–20:11（北京时间）的三次只读探针诊断均返回 0，stdout 都是相同的 437687 字节有效 JSON／SHA，任务身份、Ready 状态、XML 与 1374 行 ACL 校验有效，没有保存原始输出。EncodedCommand 的 stderr 有 1233 字节，CLIXML 格式检查确认仅含 4 条正常 progress；加入 OutputFormat Text 后 stderr 仍非空，未解决问题。改用固定参数列表 `powershell -NoProfile -Command`、派生源码作为一个 argv、无 shell 的诊断，stderr 为空。

正式合成验证报告纠正了早期写死的 OutputFormat Text 推荐。普通 Command 的正常系统模块导入返回精确 JSON 和空 stderr；显式错误仍被退出码／stderr 拒绝，警告若混入 stdout 也被完整 JSON 解码拒绝。没有忽略或过滤错误流来放宽检查。

V4 条件性设计审核要求继续固定原 Host／PowerShell 源码与哈希、完整身份／XML／ACL、45 秒总期限、一个持续元数据锚点，并只返回再次取得的原始严格 Ready 快照。只修改探针调用方式，不把 probe 对象作为归档快照，不放宽空 stderr 与完整 JSON 条件。随后 V4 最终实现独审与 85 项联合辅助检查已通过，真实 prepare／execute 仍待对应回执。

V3 20:05:20 失败时的原 stderr 未保存；这些后续诊断不能证明过去任务曾处于 Running，也不解决 V2 当时 snapshot 的具体历史失败条件。原 V1／V2／V3 失败和冻结材料保持。

## V4 最终实现与 85 项辅助检查

V4 使用独立 `rollout-v4/` 文件与新 run。最终实现独审通过，固定 probe 派生源码以单 argv 的 plain Command 调用，继续要求退出码 0、空 stderr、完整 JSON、严格身份／XML／ACL 和同一元数据锚点；probe Ready 只允许再次执行原严格快照，实际返回值仍须是原始严格 Ready 快照。原 archive、Worker、execute、idle、配置 CAS 函数与顺序保持，V3 冻结文件、旧 binding／V2 基线及所有旧失败不改。

- V4 `BackendRollout.py`：`bcfff01eb52c4c7c44b2733cd4d8168c06ac4d76b47b08c40a2af7406f2748f7`。
- V4 `FollowUpDeclarations.py`：`96039a5159483577e384177fae070cca25c577ec433fca20a5c52c9ed920c1c9`。
- V4 `HostReadyAdapter.py`：`7c799fa149eb80681a3aeda15739ca25d3601fbd19d58f76cb3d95b02d780625`。

85 项联合辅助检查本地通过并由独立代理执行确认，其中包含 41 项 adapter 检查。所有输入前后哈希一致，未调用真实 Windows 探针、Maven 或生产 prepare／execute；原业务代码、候选镜像与 334 项业务测试保持。首次 V4 prepare 已通过宿主快照及原 V2 元数据基线，随后在 QQ 清理队列门禁停止；实际部署和完整回退备份仍待回执。

## 首次 V4 prepare 的自然清理队列阻塞

2026-11-15 20:19:55（北京时间），首次 V4 prepare 在 `NO_QQ_CLEANUP_QUEUE` 门禁安全停止，`reservations=[]`。主代理确认宿主快照及原 V2 prior 已通过，本地 overlay 已写，但没有完整 prepared 或生产修改 reservation，未暂停 Worker、发送 TERM 或激活候选后端。

20:21:33 的只读阻塞报告记录清理 job `415` 为 `QUARK / PENDING`、尝试 0，关联转存任务 `2287`，自然 deleteAfter 为 20:29:05，没有 error。主代理 20:25 再查仍为同一状态、无新增阻塞，等待原系统自然处理；未手动云操作或清理请求。

新同级目录 `rollout-v4-idle-retry/` 的四个文件与冻结 V4 helper／binding 原字节相同，隔离独审通过。新目录没有复制旧 overlay、prepared、reservation 或 receipt；使用新 CLI 进程，不再次调用旧 V4 prepare，不修改 helper、重建镜像或重跑测试。待自然清理完成后，原 `NO_QQ_CLEANUP_QUEUE` 等全部门禁仍须真实通过才能生成新 prepared 和执行。

## V4 idle-retry 的宿主空闲门禁停止与 Worker 恢复

2026-11-15 20:33:11（北京时间），新 `rollout-v4-idle-retry` 的 execute 在 `NO_RUNNING_HOST_BUSINESS_TASK` 门禁安全停止。主代理确认三个部署前加密备份已经认证，Worker pause 一次并收到 ACK；失败回执同时保留 deployment 和 worker-pause 两个 reservation。尚未发送 TERM、尚未激活候选版本，因此不能继续使用此前各尝试均未 pause 的概括。

主代理随后执行唯一 guarded restore，将原 Worker=true 恢复；20:34:38 的 ACK 记录 `putAttempts=1`、`configurationOutcomeUnknown=false`，20:34:43 的恢复复核确认 13 个原容器世代完全相同。服务停止／激活／provider 请求均为 0；旧失败、prepared 引用和恢复证据分别保留，不重新执行旧 run、不恢复 SQL 或重放旧业务任务。完整 prepared 文件没有进入仓库。

20:40:16 的只读安全投影记录原固定集合精确为 `GYing Cloudflare Tunnel`、`GYing QQ Channel Auto Post` 和 `GYing Xunlei Token Sync`。Tunnel Running 是原允许状态，两项业务任务为 Ready 且 lastResult=0，`runningFixedBusinessTasks=[]`、`originalTaskGateCurrentlyPasses=true`。最近固定业务任务的 NextRun 为 22:33:33（北京时间）。报告中的 QR 投影不属于固定名单，历史失败 snapshot 未保存；这次当前通过不能倒推 20:33 具体哪个任务 Running，也不能归因 QR。

新同级 `rollout-v4-after-host-idle/` 最初仅含冻结 V4 的三 helper 和 binding 四个普通文件，与已审版本和前一 run 的字节完全相同；旧 overlay／prepared／reservation／receipt／ACK 未复制，新 run 隔离独审通过。没有 V5 逻辑、没有扩任务名单或新增等待；仍复用原 V2 prior 和原发布父目录，所有宿主与空闲门禁仍须实时通过。主代理已确认新 prepare 生成，其 SHA-256 为 `bb62c1b639859ebc4c8c9f60ddb56ff2fc192430f6b96550da4bf3c562f6a2d3`；未复制 prepared 正文。随后 execute 于 20:43 安全停止，具体记录见下一节；prepare 或隔离审核通过不等于上线。

## V4 after-host-idle 最后停止、内存压力与当前运行态

2026-11-15 20:43:35（北京时间），`rollout-v4-after-host-idle` 的 execute 保存 `Gate` 安全失败，`gate=null`、`hostSnapshotFailureCode=null`，仅有 deployment reservation。主代理确认本 run 没有 Worker pause、TERM 或 activation。原 Gate 的具体来源和条件暂为 UNKNOWN，不把摘要未记录 gate 改写成已确认的故障根因。

主代理只读观察到三个部署前 manifest 标注 complete 及认证 flags。这些清单标记不等于辅助程序完整 finish／最终校验已返回成功，也不能把 stdout 中两项产物推断为 SQL 阶段之前失败。本目录不复制该轮清单或加密备份正文，不声明全部备份门禁已通过；原失败与 checkpoint 保留待后续审核。

20:48:26 的安全内存观测显示 commit 32759.9 MiB／limit 33261.4 MiB，使用率 98.49%，余量 501.5 MiB；物理可用 1560.7 MiB。没有写配置或停止进程。主代理另报告 PowerShell launch 出现 `0x800705AF` 资源不足观察；这与宿主内存压力相容，但发生在失败之后，不能直接证明 20:43 原 Gate 的原因。

20:54:34 的真实只读运行态报告确认 13 个原容器世代完全相同、后端原镜像一致且 Running，SQL 中 Worker=true、TMDB Updates-only=true、GYING Updates-only=true。本 run 的 pause／TERM／activation reservation 均缺失，provider 请求／新登录请求／配置写入均为 0。后台完整稳定配置结果为 `stableConfigurationExact=null`，已有管理员设备校验返回 `EXISTING_ACTIVE_ADMIN_DEVICE_REQUIRED`；该安全条件要求有效期超过 now+10 分钟，失败不等于证明已经过期，不能据此声称完整 API 配置后验通过。

20:40 原固定任务窗口的独审只核对既有投影与配置摘要、固定集合和 NextRun 计算：距最近计划业务运行 6796.61 秒。独审没有重查真实任务，未证明之后所有实时 gates，也没有推断旧历史 Running／QR 身份。因此本轮候选仍未上线；此时提出释放内存与刷新后台登录后以 fresh run 再执行全部原门禁的条件。随后内存余量已有回升，最新恢复条件见后续记录。旧 run 不重试、SQL 不恢复、旧任务不重放；同一已测业务源码、334 项业务测试、85 项运维检查、JAR 与候选镜像无需重建或重测。

## 当前管理员设备过期的聚合证明

20:55:19（北京时间）的安全只读聚合报告确认当前启用管理员的未撤销设备 88 个，实际已过期 88 个，当前未过期与超过 now+10 分钟门槛的 eligible 数量均为 0；当前最新未撤销 expiry 为 20:52:49。因此当前认证失败和当前设备真实过期已获聚合证明，需要刷新后台登录。报告只保留计数、时间、定义、引用哈希与确定性边界；没有输出 device ID、JTI、Token、Cookie、用户行或原 SQL 数据，没有登录或写入操作。

按当前最新 expiry 减去 10 分钟得到 20:42:49，位于 20:41:30 的 prepare 与 20:43:35 的停止之间。这仅与十分钟余量在期间耗尽相容；当前聚合不能确定当时所选设备身份，也不能证明历史 expiry／revocation／用户 role 和 enabled 状态保持，因此不能倒推 20:43 当时设备已真实过期。原失败的 specific Gate 与 exception owner 仍为 UNKNOWN，也未归因宿主内存。

报告对引用 prepared、失败、清单、运行态和内存证据的路径／哈希分别保留，仅安全聚合 JSON 原字节进入仓库。清单 complete／认证 flags 与 SQL 密文元数据不能独立证明 checkpoint finish 正常返回；此前失败边界保持。

## 最新轻量内存回升与运行态保持

20:59:19（北京时间）的轻量内存报告显示 commit 29887.6 MiB／limit 33261.4 MiB，使用率 89.86%，余量 3373.7 MiB，物理可用 2580.2 MiB。相比 20:48 的压力观测余量已有回升；该报告没有写配置或停止进程，只证明当次资源数值，未完成全部宿主／备份／空闲 readiness 校验。

21:00:08 的最新只读运行态再次确认 13 个原容器世代完全相同、原后端镜像一致且 Running，SQL Worker 和 TMDB／GYING Updates-only 标志均为 true，本 run pause／TERM／activation reservation 均缺失。既有管理员设备仍返回 `EXISTING_ACTIVE_ADMIN_DEVICE_REQUIRED`，`stableConfigurationExact=null`；没有新登录、provider 请求或配置写入，仍不能宣称完整后台 API 配置后验。

本轮以未上线的证据交付结束。后台登录问题仍 pending；刷新登录后，还须重新检查最新内存余量与全部原宿主、空闲、备份和配置门禁，使用 fresh run。内存回升不是上线成功回执，不重用旧失败 run；自然资源验收继续 pending，已冻结源码和已通过测试不重建或重测。

## 隔离测试阶段记录

| 阶段 | 实际记录 | 失败原因与后续 |
| --- | --- | --- |
| V1 `871b1a49291c` | testCompile 失败，退出码 1，业务测试类未执行 | typed wrapper 推断问题修正后，重新创建 V2 环境 |
| V2 `9aa0d2a9662b` | 334 项，失败 2、错误 1、跳过 0；仅 MySQL 测试失败 | 测试任务的 due 时间前提不成立；同时 GBK 输出异常导致原结果漏记退出码／汇总。只读 reconciliation 保留真实失败，原退出码仍未知 |
| V3 `ea18bf0e51fd` | Maven resources 阶段失败，退出码 1，业务测试类未执行 | 只读编译缓存产物权限问题；随后 V4 完整重新编译 |
| V4 `ec23c218fba5` | 13 类 334 项全部通过，MySQL 18 项通过，退出码 0 | 新建隔离环境完整重跑；作为最终业务测试证据 |

失败原因说明来自实施代理，数量和退出码捕获状态已按安全 result／reconciliation JSON 核对。旧失败记录保持原样，V4 的通过单独记录。原始日志与 Surefire XML 未拷入仓库。

## QQ 百度不可用的调查结论

17:26:28 的 QQ audit `2638` 与临时任务 `5` 对应同一影片。任务状态为 `ACTION_REQUIRED / NEW`，尝试 1 次，保存的安全错误码是 `CHALLENGE_REQUIRED`，尚无持久化转存计划，未记录转存或分享提交阶段。最新已知原因是百度挑战验证；没有留存具体接口和原始 errno，不能进一步断言是哪种验证码。

17:25:10 的 QQ audit `2637` 为 `BAIDU_NOT_READY`。后续只读观测显示本地配置就绪，不能用当前状态倒推当时不可用的具体原因；该条原因仍未解决。历史任务 `2` 的 `SOURCE_NO_ALLOWED_FILES`、任务 `4` 的 `SOURCE_INVALID` 只说明各自历史任务，不能替代最新任务 `5` 的原因。

配置报告中的 `CONNECTED`、`credentialConfigured=true` 和 `baiduTemporaryLocalReady=true` 只证明已保存的本地会话与开关满足本地判断，未进行当前官方登录验证。GYING 永久归档任务 `18069` 属于另一条工作流，不能用来归因 QQ 临时请求。调查未重放 QQ 请求、未发消息、未转存、未清理，也未修改配置。

## 后续恢复条件与尚待真实回执

用户刷新后台登录后，重新检查资源余量、既有管理员设备与全部原空闲／宿主门禁，再使用 fresh run。最新轻量内存余量已有回升，完整 readiness 尚未复验。当前候选不重建，已通过的 334 项业务和 85 项运维检查不重复运行；历史失败保留，不重试旧 run 或恢复 SQL。

- backend 的真实部署回执、正常 TERM 退出结果、6 个加密回退产物的完整认证及门禁验证，以及真实入口验收。
- 主机镜像指向替换回执、每日备份 48→49 的真实 native dry-run、新 run 完整配置后验与日志摘要。
- 下一次自然更新轮次中的新任务、严格匹配和归档结果。未强制创建或重放任务来制造验收；自然轮次待验也不等于新影片已完成上传。

本目录仅保留字段、状态、布尔值、必要任务 ID、检查数量、SHA-256 和安全摘要。原 SQL、配置值、env、Token、Cookie、provider 响应正文和日志正文留在私有环境，不进入仓库。
