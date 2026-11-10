# 百度运行态、重复预览元数据与上线验收（2026-11-10）

本记录汇总已核实结果，时间均为 Asia/Shanghai。当前 backend 为上传ID兼容修复 `f8b0653981726ced283b6ec9649491c5c233729c`，2026-11-10 16:10:32实际启动、16:12:08完成仅backend上线，frontend保留原构建/运行代次，Compose20/12。完整suite1426项/1425通过/1既有Redis跳过及固定416源码/440类资源/image/JAR通过；R2实际23次GET/23响应（含20入口）与375前端产物、元数据/权限/34保护全文和五项最终保持通过。16:17:21宿主pin、16:22 post三age完整认证与16:28日常42→43覆盖通过，自然完整备份未验；日志仅核16:10:32–16:26:58Docker双流。13:56原baf6ab2部署、MobLand schema阻塞消失/实际任务payload阻塞，以及Troll八组真实确认开关/16截图保留历史；194静态请求和八组浏览器为R2引用证据，未重跑。百度前两条新影片正常跳过，第三条nArx15:02磁力文本上传失败、新17622 UNKNOWN；3/3授权测试额度已用完，真实归档/自有分享未成功，旧UNKNOWN17584/17606保持，修复上线后未再实跑，不恢复10分钟1条或自然轮观察。

## 原13:56部署的固定版本与实际运行（历史）

| 项目 | 已核实结果 |
| --- | --- |
| 分支与 backend 源码 | `codex/cloud-drive-workflows`；固定提交 `baf6ab263633fd8b472941934a33fde6ddc9f2bb`；416 个源码文件哈希无差异 |
| backend 镜像 | `sha256:6cf9f7725a728115547b613369b361076a65ae7d2a7469c657efbd73477c9c2d` |
| backend 实际 JAR | `a47830cdd768298840ed078ae654f29b5cfd8d0e08241634fa74045f6e6212c8`；实际启动 2026-11-10 13:05:12 |
| frontend 保留镜像 | `sha256:7e172578b6f954bb368e973a7dca5331747e99b0963a6d6b74896942b42e52ad`；实际启动 2026-11-10 12:34:59，本次未替换 |
| frontend 构建来源 | `63edac7e98f9723458e9742c276e416437d4f254`，完整 Git frontend tree 与本次固定提交相同；90 个源码、375 个实际运行产物无差异 |
| 完成回执 | `deployed-local-verification-passed`，`backendOnly=true`、`frontendPreserved=true`、`schemaChanged=false`；DBA 元数据实际核验通过，助手部署阶段 SQL 写入为 0 |

原完整 backend suite 实际运行于 2026-11-10 02:43:01–02:49:08；126 份 Surefire XML 独立累加为 1,424 项，1,423 通过、失败/错误 0、跳过 1，必需真实 MySQL SQL/独立 Java17 compile/exit0 通过。唯一跳过为 `RedisRateLimiterIntegrationTest.realRedisAtomicConcurrencyExpiryAndAcl`，属于既有 Redis 环境条件。02:51–02:54 的 `backend-build.json` 绑定固定 416 源码、提交、image/JAR 与临时夹具清理，`testsRerun=false`，复用上述完整 suite，本次上线未重新跑 1,424 项测试。

15:14:43–15:21:14 上传ID字符校验修复候选完成另一轮完整 backend suite，126份 Surefire XML 独立累加 `1426 tests / 1425 passed / 0 failures / 0 errors / 1 skipped`，`BaiduWebClientTest` 全部44项通过；唯一跳过仍为 `RedisRateLimiterIntegrationTest.realRedisAtomicConcurrencyExpiryAndAcl`。必需真实 MySQL SQL、独立 compile、exit0与两个隔离fixture清理通过。修复源码与suite快照两个文件SHA一致；修复已提交并推送 `f8b0653981726ced283b6ec9649491c5c233729c`；固定本地backend image/JAR及16:12实际部署已另行核验，原13:56的 `baf6ab2` 部署继续引用02:43–02:49的原1424项suite。

新suite原回执 `G:/gying-tools/builds/baidu-recovery-merge-20261110/query-boundaries-mysql-1a0b381c7039/result.json` SHA-256 `9c213a0d06f715edbd8fb151a4876cb20515f2a7c04a0c91c3fbe5c1fe95588b`；独审 `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/upload-id-fix-suite-independent-review-20261110.json` SHA-256 `86a53e2aaf0fc5916eb159ff92e7eb5fcd42e78bf92eaccb741b663d21a6302b`，未重复运行测试或调用生产服务。

15:38:22–15:41:40 固定修复提交 `f8b0653981726ced283b6ec9649491c5c233729c` 完成本地离线 Java17 package 与候选镜像验证，416份源码与提交/suite快照相同，440份JAR类/资源逐项一致，两个构建/验证容器均清理；`testsRerun=false`，复用前述1426项完整suite。候选image `sha256:9886a25b4fcf985842dcb8db7a27e770cce5b415c033d5777534d3ff3e3699be`，JAR `1b28cbed6cda49e0fe82e78912cb438a39bd61c21c8ff1c0da80d2c41341dbd8`；原环境/用户/entrypoint/base layers保持，未加载生产环境或挂载生产数据。构建回执 `E:/gying-tools/releases/baidu-upload-id-compat-20261110/backend-build-raw.json` SHA-256 `ab4acbcaabfed716d2dd385992dbc6e6bdb9dd072795217a9f91ab9b183d7471`。此时生产仍为原 `baf6ab2`，新image尚未部署。

## 上传ID兼容修复的R2实际部署与验收

上传ID校验已接受长度1–1024、不含空白的可打印ASCII opaque值，block请求query与commit表单分别正确编码；完整suite与44项BaiduWebClient测试通过，修复提交 `f8b0653981726ced283b6ec9649491c5c233729c` 已推送。R2发布目录为 `E:/gying-tools/releases/baidu-upload-id-compat-20261110`。本节均来自实际完成回执，前述15:38–15:41“生产仍baf”只描述构建时点。

| 项目 | R2实际结果 |
| --- | --- |
| backend实际运行 | container `7f2c7ef4b7fb86baa29c302ed43d756730ebbdc0b55ded2383378c1bebd76e16`；16:10:32启动、16:12:08完成；image `sha256:9886a25b4fcf985842dcb8db7a27e770cce5b415c033d5777534d3ff3e3699be`，实际JAR `1b28cbed6cda49e0fe82e78912cb438a39bd61c21c8ff1c0da80d2c41341dbd8` |
| 最小部署范围 | 仅backend；Compose backend20/frontend12；含frontend原12:34:59代次在内的全部12个非backend代次保持。旧backend正常TERM退出143，未强停、retry或rollback |
| 七age检查点 | `G:/gying-backups/baidu-upload-id-compat-20261110-20261110T080836.010565Z`；manifest `f7c8ca8837aa4a6e578bc8f52a2eb1d1c5b4147d1dfaed2534d8762c30554614`；7份完整认证及内容核验 |
| 实际只读验收 | 16:12:48–16:13:56 passed；23次GET/23响应，包含本机/公网20入口、direct backend health、catalog prime与已有device ADMIN GET |
| 前端及历史证据 | 375实际产物逐项匹配；194静态HTTP请求与八组浏览器/16截图引用此前保留证据，freshStaticRequests=0、browserGroupsRerun=0 |
| 最终保持 | all13Generations/allHistoricalWholeRows/sessionPair/completeAuthorizationRows/frozenInputs五项passed；16:32:30再次核对13 exact代次、其他12代次与六份保护整行通过 |

七age包含1640配置members、886host members/1374 ACL、SQL31表/1过程/34完整保护触发器、7backend-data、队列/key与会话AES-GCM/同账号/当前pair/UID-GID10001/0600及0700权限；未导入SQL或恢复生产数据，不称跨服务原子快照。29份helper source保持冻结并逐项纳入fresh `checkpoint-plan-v4-final.json`，configurationFiles=1634；旧v4计划在verifier进一步收紧后触发哈希漂移拒绝，旧计划及preimages保留，新增final计划没有绕过哈希门禁。部署前75个显式member在 `G:/gying-backups/baidu-upload-id-compat-predeployment-final-materials-20261110-20261110T080601.446615Z` 全部size/SHA/完整认证，来源manifest `7857881771783c55d1ecd91d7266a1728ba1dae0747577840d5fa90a006cad17`、归档manifest `d50ca686756542ca5e2cf3b93b9c255e6cd4546fb6d21354d16c7caae8940740`。旧release的230member完整认证作为独立历史补材绑定保留，不代替R2新事实。

R2再次核实应用零参数元数据过程、READS SQL DATA/SQL SECURITY DEFINER、全局只读SHOW_ROUTINE、精确grants及34触发器全文。应用已有有效设备用于短期内存JWT ADMIN GET，newDevicesOrLogins=0，正常login_device.last_seen_at可更新；完整authorization rows按既有允许范围保持。provider请求、新影片测试、服务变化、显式SQL写入及upload/share/publish/merge为0。

16:17:21宿主pin完成，private host config仅变预期backend image，SHA `4b316a6c4438d9daa6898a5897db96cd5ac84cebf3f9a0029d96b1b9ce0a3346` → `d81e0d408b6bcd5ea6ed57ddf5e1a63083d61d7aaea6f9139dff16385d5743d1`；pinned installation SHA `ca22d727b23a1f7ce6fc030512877e4c0cdd9d3b690a161611c13b9ee1a993e4`。原任务Enabled/Ready、XML/ACL及880文件保持，taskChanges/remoteRequests=0，未开浏览器、自动retry或restore。16:22:18–16:22:59 post-pin在 `G:/gying-backups/baidu-upload-id-compat-post-pin-20261110-20261110T082218.955200Z` 完整认证3份，manifest `71c6220e7443d89faa705f2966f4e69b41b57f447b744a16e4317255a30feee1`；880文件/494目录、7backend-data、GCM pair/UID10001/0600/0700与任务保持，parentProgressAllowed=false、全部保护历史精确保持。首次report参数超出允许目录，在reservation前拒绝；无archive/source/SQL/config/service/cloud写入，原拒绝回执保留，修正参数范围后完成，不计生产故障或重新部署。

16:26:12–16:28:04日常备份配置唯一CAS由42→43，仅增加R2 release路径；前后真实dry-run43/exit0、原规则/其他字段与owner/group/DACL保持，configurationOutcomeUnknown=false。当前配置SHA `5c060abd865fc9ace8a082c16273881fa9c1ef76e480b4b3c2355a9316fb1e99`；未生成实际或自然完整备份。16:10:32–16:26:58仅检查当前backend Docker stdout/stderr，分别2310/0字节，ERROR/Exception/OOM行匹配0；未打印或保存原日志，applicationFileLogsInspected=false，不推广为应用文件日志或全天保证。

R2保护resource_hub_task完整行17580/17583/17584/17606/17622及baidu_transfer_task:1，fresh baseline SHA `d969aa9893ee92ce30bd8dee6ba71be81c71442973d08434d9d4866753a328a6`。父17583仍revision63/processed5/skipped5/succeeded0/failed0/deferred3、PAUSED、enabled=false、interval10/batch1/currentChild=null/nextRun=null/无lease。3/3测试额度已消耗，UNKNOWN17584/17606/17622全部保留，无replay或第四条影片；上传ID修复上线与只读验收不构成真实归档成功，未恢复自动调度或自然轮观察。

16:46:35末次无代理宿主loopback直接GET中，5005/、9000/minio/health/live、18789/healthz均status0/RemoteDisconnected，原ops对应健康FAIL与独立三GET失败回执保留。16:51:12–16:51:14只读诊断确认quark容器loopback/eth均302（未跟随），MinIO同网peer直达容器eth健康200，OpenClaw loopback/eth健康200且Docker healthy/最近5次health exit0；本次所查4个原非backend代次与冻结基线一致。只证实宿主published-port访问路径在观察时异常，具体转发、relay、过滤或其他组件尚未定位，不归因代理。backend实际MinIO endpoint为 `host.docker.internal:9000`；现有frontend Node作为backend同gying-net的peer对 `http://host.docker.internal:9000/minio/health/live` 唯一GET为status0/ECONNRESET，同peer直达MinIO容器地址为200。该peer测试未由backend本进程执行，也未使用认证S3对象业务；配置依赖路径仍待修复与核验，内部health不能代替该路径或真实归档验收。此次无restart/config writes/cloudProviderRequests/新source，29helpers保持冻结，三个UNKNOWN与暂停/额度0边界继续保持。

## DBA 追加 SQL 与应用只读元数据

用户已明确同意共享账号 `gying_app@%` 的全局只读 `SHOW_ROUTINE` 权限范围，即可读取所有数据库的存储过程/函数定义，并报告已依次执行 `E:/gying-tools/releases/guard-metadata-20261110-dba-review/00-precheck-readonly.sql` 与 `01-apply-reviewed.sql`。本轮未再次要求授权、发送凭据或代替 DBA 执行追加 SQL。

应用实际身份为 `gying_app@%`、当前库 `gying`、角色 `NONE`。新增零参数过程 `gying.movie_merge_guard_metadata` 的完整正文、`READS SQL DATA`、`SQL SECURITY DEFINER`、非确定性声明及对象权限均严格一致；授权仅保留原 GYING 库 CRUD/EXECUTE，追加该过程 EXECUTE 和全局 SHOW_ROUTINE，无其他扩权。规范化过程正文 SHA-256 为 `968ab4ccec6644d4635e5c7630c6043c4c0cd86a2b2c1b67259d0b5b2fd4590c`。

原两份重复合并迁移的 34 个保护触发器正文完整。应用只读 CALL 返回 34 行、每行 5 列，各触发器全文匹配。外部 MySQL batch 读取器此前用通用行分割误切正文中的 CR；现仅用物理 LF 划分运输记录，保留正文 CR，7 个解析夹具通过。旧读取器、原失败和精确 diff 保留；未放宽正文、权限或历史保护判定。

## 恢复材料与宿主 80 入口修复

冻结 v3 的独立绑定复核为 29 PASS：1,356 项配置、884 项宿主材料全部哈希匹配，328 项旧输入保留原哈希或精确旧副本，9 项 helper 变化及固定源码来源准确。只读验收 helper 的离线模拟 42 项、恢复材料默认无 I/O 与授权/哈希拒绝 10 项通过。这些结果不代表执行生产恢复。

部署停止窗口最终检查点为 `G:/gying-backups/baidu-runtime-merge-metadata-20261110-20261110T050323.802905Z`，7 份 age 完整认证与离线内容验证通过；manifest SHA-256 为 `fd13981dca2b35355a80734636aa7514c1138798bc4cd1b84eda360a9eb6bfd8`。配置 1,362 members、宿主 886 members/1,374 ACL、SQL 31 表/1 过程/34 个完整触发器、当前队列及百度会话 GCM/UID/0600 与 0700 权限通过。未恢复数据库，不称跨服务原子快照。

backend 已完成一次激活后，原验证器因 Python 3.10 不接受 Docker 的九位小数时间而失败；新增验证器按显式时区 RFC3339 和整数纳秒读取已激活服务，未重复部署。宿主 `127.0.0.1:80` 空响应另经 13:13:37 单次正常重启 nginx 修复；同一 nginx 容器、镜像、网络/IP/别名、端口绑定及配置保持，Mounts 仅数组排列变化，并已复现原 SHA。其他 12 个容器相对该入口修复前保持，13:52 配置测试与进程内平滑重载及真实入口通过。

冻结原 v3 文件与原失败保持。追加 `acceptance-baseline-entry-repaired-20261110.json` 仅替换 nginx 的验收代次并绑定修复回执，保留 backend 部署前像；后续只读/浏览器副本在执行前先验证该谱系。17 项入口验收补充材料已独立完整认证，归档 `G:/gying-backups/baidu-entry-acceptance-materials-20261110-20261110T055726.410529Z` 的 manifest SHA-256 为 `e8f83a18d6ddef912cfd1a583759e40d82be4c6886302fd9e6721e285c941689`。原 Windows 路径分隔符与 tar member 归一化不同的验证失败保留，原加密包和生产者未改。

追加最终补材已完整认证：`G:/gying-backups/baidu-runtime-merge-final-materials-20261110-20261110T074733.814326Z` 单份age内230个member的大小/SHA全部匹配，manifest SHA-256 `19e1910acb6e10f695fd9230216564346bfbd4cd55a01f28ce28ebae8512fe3b`，生产写入0、未恢复数据。显式来源清单保留V2原178项、第三唯一失败/旧UNKNOWN、每日42、原离线及文档失败、只读GET和完整调用依赖源码；两份repo文档及未来R2事实未纳入旧补材。该补材完成不代表归档成功。

## 原发布宿主 pin 与 post-pin 检查点（历史）

14:25:02 的 pin 完成回执为 `pinned-local-verification-passed`；配置 CAS 已写入，唯一变化为预期 backend image。配置 SHA-256 `91958ebb2e13239aa67c41601c5e2a66df725230b41ecd03ce5073182e8ce95d` → `4b316a6c4438d9daa6898a5897db96cd5ac84cebf3f9a0029d96b1b9ce0a3346`，pinned installation SHA-256 `4eec8c7ccce70618a8024c725642248a5ae4391fae410279c1df2f5b774da96d`。任务始终 Enabled/Ready，taskChanges=0、browserOpened=false、remoteRequests=0，880 个宿主文件、ACL、XML 与四份受保护历史整行保持。回执无 startedAt，14:25:02 仅表示完成时刻；未自动 retry/restore。

首次 CAS 的 `Get-FileHash / CommandNotFoundException` 与 0 writes 失败回执、reservation 保留。新版 `HostConfigAtomicExplicitModules20261110.ps1` 只显式导入 Utility/Management 模块，原正文逐字保持；只读 prerequisites/final review 通过。8 个来源的补材在 `G:/gying-backups/baidu-host-fresh-recovery-final-materials-20261110-20261110T062300.871716Z` 完整认证，manifest SHA-256 `b094b9f0b7586fe1973b1615ea74fbc5ec9df91f07e2008fe77b49b663ca7e17`、archive SHA-256 `2341feded22eaa7c4dd2c235a88fa76430c7d1ad30cb71f24c937f802a49cbd7`。

14:27:57–14:28:35 post-pin 回执 `status=complete`；`G:/gying-backups/baidu-runtime-merge-metadata-post-pin-20261110-20261110T062757.593805Z` 中三份 age 完整认证，manifest SHA-256 `299c956c4016e361ec4702eff689aa4b8876032104a0887e4d1e492d2926379c`。880 个宿主文件/494 目录的哈希、结构、ACL/inventory、Enabled/Ready 任务 XML，以及 7 个 backend-data 文件的哈希/inventory/volume 均保持。百度会话 AES-GCM/同账号/当前 pair/UID-GID10001/0600/0700 通过；host/config/task/data writes=0、remoteRequests=0，未恢复数据，父 17583 允许正常进度变化。该 post-pin 阶段未验证日常覆盖或自然完整备份；日常覆盖随后于15:12更新，见下。

## 原发布目录日常备份覆盖

15:09:57–15:09:59 prepare 只对原 private daily config 完成单文件 age 加密包及全部成员认证、原字节核对与42项真实 dry-run；原配置与 owner/group/DACL 保持，未写生产配置。V2 helper 的独立静审确认只追加本轮发布目录这一条 paths 规则；失败回退同时要求本助手 proposal bytes 与原 owner/group/DACL 保持，atomic_replace 内再次核对，后续权限漂移不覆盖。V3 的10项离线测试、18个有效夹具场景和 Windows scratch 原生替换/CAS/权限保持通过；独审只复核原回执，没有重复执行生产操作。

15:12:52–15:12:56 唯一 apply 在 maintenance lock 内完成旧发布目录41→42覆盖，配置 SHA-256 `c3cde6c64670c8e5075ebc47e2d3827f7e5f238c56507a0f1aafcbe8b83f65d7` → `3930008bb7cd3c7a63b0ea7667fa0a3210b7421f14440ff7a70ba2d7b380740e`，仅增加 `baidu-runtime-merge-metadata-20261110`，原规则及其他字段/owner/group/DACL 保持。前后 dry-run 均42项/exit0，`configurationOutcomeUnknown=false`；SQL/service/host/cloud writes=0，`backupExecuted=false`、`naturalBackupGenerated=false`。旧release日常覆盖已闭环，自然生成的完整备份仍未验；后续字符校验修复R2 release已于16:28独立追加到43项，见R2实际验收；本段仍保留原15:12的42项历史回执。

## 已通过的生产只读与真实预览

13:56:41–13:57:56 的生产只读验收 `status=passed`：本机/公网 20 个入口 PASS、0 FAIL；本机与公网各 95 个静态资产与 2 个语言文件，共 194 次请求，SHA 全部通过；375 个实际前端产物无差异。公网管理入口为既有 Cloudflare Access HTTPS 302，原通用页面检查失败与精确重定向补证均保留。当前应用身份、过程元数据/零参数/精确授权以及 34 个触发器全文通过；真实 ADMIN backfill/history GET 均 200，复用已有有效设备，未新增设备，JWT 只留内存。该阶段相对入口修复后的基线保留其他 12 个容器，未合并、发布或云写。

13:59:08–13:59:29 的本机真实 ADMIN 浏览器完成 MobLand `tmdb_tv_247718 → 1YKW` 的 `zh-light-390` 一组、两张实际视口截图。扫描完整，15,647 条有效影片、144 个候选；`canMerge=false`，`SCHEMA_NOT_READY` 已消失。实际阻塞为 `quark_transfer_task / ACTIVE_OR_UNKNOWN_TASK / count=1` 和 `resource_hub_task / TASK_PAYLOAD_INVALID / count=2`。目标仍有 8 条资源；未点击 merge、未发云操作。

14:00:02–14:01:03 的 Troll `tmdb_movie_736526 → D0nk` 首组真实预览显示 `canMerge=true`、`eligible=true`、`blockers=[]`，来源 0/目标 3 条资源。但旧浏览器脚本要求 `[data-testid=...] input[type=checkbox]`，而实际 Ant Design 将 `data-testid` 放在原生 input 本身，确认步骤超时且无截图，不能计该组通过。新增版本化 visual 与 browserpy 副本采用精确 `input[type=checkbox][data-testid=...]`，在每次原生点击前 scrollIntoView，记录确认启用/取消的阶段与有限错误类型。原脚本、失败回执和全部安全请求白名单/禁止 merge、defer、run 的门禁保持；实际已安装 Ant Design 的 4 种 SSR 状态与抽取确认流程共 8 项、默认无 I/O/Entry 拒绝/精确 diff/38 个原 Python 守护及请求白名单共 8 项离线通过。首离线测试把守护数误写为 37 的失败脚本与回执也保留，仅在测试 v2 改为实测 38。14:10:30–14:10:46 使用该修复副本的 `zh-light-390` 单组真实验收通过，实际 confirmation 勾选后 merge 启用、取消后禁用，2 张视口截图齐全，未点击 merge。14:12:29–14:12:42 的八组连续验收首组在 `confirmation-enable` 即时报 `Error`，无截图；原回执只能支持“点击阶段失败”，不能断言坐标/动画竞态已实证。

新增 `CheckboxStable20261110` visual/browserpy 副本对精确原生 input 等待可见矩形、无 modal 动画及至少 3 个连续稳定帧（180 帧预算，每次 RAF 回调后检测 4 秒），再发送该 input 的原生 DOM click，随后 10 秒内核对 checked 与 merge 禁用状态联动。固定布尔、矩形/计数诊断和失败快照不读取页面文本或凭据；只输出固定错误码，未加重试、未点击 merge。离线动画/遮挡/隐藏/移动/断连/禁用及原生事件流程 9 项、全部默认无 I/O/Entry 拒绝/精确变化范围/原 38 个守护与完整请求白名单 8 项通过。已有单组 PASS、两次真实 FAIL 与第一版 helper 均保留。14:23:03–14:24:12 的后续回执整体 FAIL，但其中 `zh-light-390`、`zh-light-1280`、`zh-dark-390` 三组实际 PASS，确认启用/取消与每组两图齐全；第四中文组 input 位于 y=988、16×16，下缘超出视口 4px，中心 y=996 可见且命中。CenterVisible 副本仅把整框包含判断改为中心包含，14:30:22–14:30:38 的 `zh-dark-1280` 真实 PASS。原整体 FAIL 保留，中文四组共 8 张实际截图齐全。

14:30:40–14:30:54 的英文首组在 `confirmation-enable` 失败，固定错误码 `CHECKBOX_GEOMETRY_UNSTABLE`。只读失败快照 input x=32/y=1392/16×16、label x=32/y=1376/326×48、视口 390×1000，connected=true/checked=false/disabled=false/centerHitOwned=false/mergeDisabled=true。该证据确认 input 在屏外，不能单独证明动画或布局变化的具体原因。

新增 `CheckboxStableThenScroll20261110` 副本先等待无 modal 动画及至少 3 个连续稳定几何帧，第一阶段允许精确 input 在屏外；然后一次 native scrollIntoView，再等 3 帧稳定且中心在视口并命中该 input/label。每阶段 180 帧预算、每个 RAF 回调后检测 4 秒；RAF 停摆时不是严格 4 秒硬截止，原 Python 子进程最终上限仍 360 秒。尺寸、连接、隐藏样式、checked/disabled、精确 input/label、原 38 个 Python 守护和完整请求白名单保持，不重复 click、不点击 merge/defer/run。诊断仅新增 wrap scrollTop/clientHeight/scrollHeight 等数值，记录滚动前稳定、滚动后即时与中心稳定快照。实际抽取流程离线 23 项、保护边界 9 项、Node 语法与默认 no-I/O plan 通过。前两份边界测试脚本因引号/换行转义编译失败，原脚本与失败回执保留，V3 仅修测试引号后通过，产品 helper 未变化。

14:42:01–14:43:14 剩余英文四组分别真实 PASS。14:44:14 的八组完成汇总按实际 group 和截图 SHA 验证中文四组/英文四组，`passedGroups=8`、`actualScreenshots=16`；每组确认后 merge 启用、取消后禁用，精确 Troll 对均 canMerge/eligible=true、blockers=[]，merge/cloudMutationRequests=0。中文前三组来源仍是整体 FAIL 的原回执，汇总不改写其历史状态；原选择器、点击、第四中文整框与英文屏外失败全部保留。八组浏览器验收已完成，仍未执行影片合并。

## 百度真实归档授权与待验边界

用户允许本次最多 3 条全新影片逐条上传种子/磁力小文件并创建自有分享；每次失败后先停止、修复验证。两条旧 UNKNOWN `17584`、`17606` 完整保留，不重放；旧父 `17580` 与视频任务 `baidu_transfer_task:1` 的完整历史行也保持。仅真实归档成功并逐文件/自有分享只读对账后，才恢复原每 10 分钟 1 条，并观察一轮自然调度。

13:57 历史只读快照中新影片 run=0，父 `17583` 为 PAUSED/关闭10分钟1条/revision50/processed2/skipped2/succeeded0/failed0/deferred2/currentChildId=null。该历史快照保留，后续不再称本轮 run0。

14:16:45–14:16:56 已发出一次全新影片 manual run，settings PUT/defer/merge/delete/publish=0。原 caller 因 `EXACTLY_ONE_NEW_CHILD_FROM_SINGLE_RUN` 门禁停止，`operationOutcomeUnknown=true`、`sendOutcomeUnknown=false`，原回执未改写。14:26:25 只读 closure 核实新影片 `KYW1` / GYING identityId44 因有效已审核 GYING P2P torrent=0 正常跳过，持久化固定结果 `NO_ELIGIBLE_GYING_TORRENTS`，未创建 child、百度归档行0。新回执 `first-manual-cycle-readonly-closed-as-skip` / `closedOperationOutcomeUnknown=false` 闭合业务结果，保留原 caller 的历史判断。

14:26 历史核实父为 revision54/processed3/skipped3/succeeded0/failed0/deferred2，仍 PAUSED、enabled=false、10分钟1条、currentChildId=null、无 lease、identityCursor44。该时点授权测试已用1/3，最多还可2条全新影片；该 skip 不计上传/归档/自有分享成功，不恢复自动调度。closure SQL writes/cloud requests/settings PUT/manual runs=0，四份受保护历史整行保持。后续上传/自有分享、成功对账后恢复原调度与自然一轮仍待补。

14:54:28–14:54:47 第二条全新影片 `dYy2` / GYING identityId45 的唯一 manual POST 已完成，回执 `manual-cycle-skipped`、sendOutcomeUnknown=false、operationOutcomeUnknown=false，固定代码仍为 `NO_ELIGIBLE_GYING_TORRENTS`。父任务 revision58/processed4/skipped4/succeeded0/failed0/deferred2/identityCursor45，仍 PAUSED/关闭10分钟1条/currentChildId=null、归档0；settings PUT/defer/merge/delete/separatePublish=0、retryPerformed=false，known skipped cycle 云写0，四份受保护历史整行保持。该时点测试预算已用2/3、剩余1；第三条后续结果见下，本轮未恢复自动调度。

第二轮20项材料在 `G:/gying-backups/baidu-second-cycle-materials-20261110-20261110T065355.817005Z` 全部 size/SHA/完整认证通过，manifest SHA-256 `b07bb33f697eb373769a58097be40af46e3af7a2da5ae49151370aae04b78773`；productionWrites=0、未恢复生产数据。该补材认证不代替第三轮归档或自然调度验收。

15:02:20–15:02:45 第三条全新影片 `nArx` / GYING identityId46 唯一 manual POST 后，新子 `17622` 进入 `UNKNOWN`，stage=`UPLOAD_MAGNET_TEXT`、errorCode=`UPLOAD_PRECREATE_UPLOAD_ID_CHARACTERS_INVALID`、attempts=1；fileCount/totalBytes/resourceLinkId 均未记录。原 caller 在 `EXACT_THIRD_METADATA_SUCCESS_ONLY` 门禁停止，保留 `operationOutcomeUnknown=true`、`sendOutcomeUnknown=false`，不重写为成功或正常SKIP；settings PUT/defer/merge/delete/separatePublish=0、retryPerformed=false。两条旧UNKNOWN17584/17606及四份受保护历史整行保持。

15:02:44 实际父17583为 revision63/processed5/skipped5/succeeded0/failed0/deferred3，仍 PAUSED、enabled=false、10分钟1条、currentChildId=null、nextRunAt=null、无 lease。父skipped增加包含本次UNKNOWN延后，不等于第三条正常跳过，failed=0也不代表归档成功。测试额度已用3/3、剩余0，不恢复调度、不观察自然一轮；新17622也不自动重放。上传ID兼容修复/完整离线suite及16:12受影响backend部署已通过，见R2实际验收；修复后未再测试，不能据此把第三条UNKNOWN改为成功。

第三轮576项显式来源材料在 `G:/gying-backups/baidu-third-cycle-materials-20261110-20261110T070137.227343Z` 全部 size/SHA/完整认证通过，manifest SHA-256 `846bc9f25e76477d4fbc7d5441b0cab858703dbb14eaad75597d62c5813dca44`；productionWrites=0、未恢复生产数据。该材料认证发生于第三轮实跑前，不代表上传/分享成功或后续修复已验。

15:34:44–15:35:02 对第三条失败任务17622所属专用账号和精确归档目录完成真实只读观察：3次 GET、3个成功响应，AES-GCM及同账号核对通过，完整目录列表为0文件。四份任务整行（父17583及UNKNOWN17584/17606/17622）、会话vault和容器代次前后保持；cloud writes/manual runs/settings changes=0、retry=false、resolveUnknown=false、archiveSuccess=false。原回执 `verification/uploadid-fix-20261110/unknown17622-readonly-observations/unknown17622-20261110T073502.417407Z.json` SHA-256 `0035891ef4b30091c1b3ad8109d7d1286c34947b250637cd7dfe5a56e5f2accf`。空目录只说明当前未观察到文件，不能排除precreate或临时块，也不解除UNKNOWN；测试额度仍3/3用完，调度保持关闭。

安全既有 58 PASS/11 FAIL/1 UNKNOWN 与 G01–G11 未关闭，本轮未声称重跑安全审计或关闭风险。此前旧 idle/consumer FAIL、原启动/入口/浏览器失败保留其时点。未验证真实视频转存、临时目录自然彻底删除、配额释放、群内或 GYING 发布；本轮授权不延伸其他影片或外部发布。

## 外部证据索引

下表相对路径均基于 `E:/gying-tools/releases/baidu-runtime-merge-metadata-20261110/`，仓库仅存摘要，不复制凭据、SQL 正文或原始 provider 日志。

| 材料 | 路径（相对或绝对） | SHA-256 |
| --- | --- | --- |
| 已完成部署 | `deployment/deployment-completed-2026-11-10T055622170383+0000.json` | `bd9baf631e372c7c6e4ee2a44d1ffa657f7739da457e607209cac4b9c4f81b29` |
| 生产只读验收 | `verification/production-readonly-20261110T055641.317716Z.json` | `c958e2b81753bd22748b664b1674166f4e6b99d612ae322e7773337576b47335` |
| MobLand 真实浏览器 | `verification/production-browser-acceptance-20261110T055908.883536Z.json` | `e8cc774b46f15dea6d14900507ed0648d966576c975bc8d7579e5880ada6f82f` |
| 独立 v3 绑定复核 | `verification/metadata-independent-readonly-20261110/resumed-v3-bound-independent-review.json` | `497b4744c25904cfcc75e2bb1bc4211fce9bae2cd0e89ece68400061858b2ca8` |
| nginx 入口修复 | `verification/nginx-loopback-recovery-completed-20261110.json` | `4aa127bdef57448ef05cd13cf56d246bcf49e329b0b9a2cdf0e80d27eacb59e9` |
| 入口修复验收基线 | `acceptance-baseline-entry-repaired-20261110.json` | `7ae7fe1a09b3f8318adff208efd534239ba9e5634d6e3d0dca0921162bd1deab` |
| 入口材料来源 | `helper-provenance-entry-repaired-20261110.json` | `f3029a6f97214f6a1687c188199f7053728f61fd132d908a7d6f45dfaaba9609` |
| 入口材料认证 | `verification/entry-acceptance-materials-checkpoint-20261110.json` | `8bccdbb8adbc45fd58ef96ba6f2854fb5033ec8c7a2733820774e48556faf662` |
| 首版复选框修复 visual | `browser-mock/VerifyProductionScanPreviewVisualCheckboxRepaired20261110.cjs` | `c0bee71697a92fb9e35901d3e6fb9cebb38e1a46fe31d1376d751af09ff64a45` |
| 首版复选框浏览器副本 | `verification/VerifyProductionBrowserAcceptanceCheckboxRepaired20261110.py` | `7956fe38db4f6dba503ba7809563f809384438f9ad8b404dbc74dc15f2a299bd` |
| 实际 Ant Design 离线 fixture | `verification/browser-checkbox-offline-dom-20261110.json` | `e0b33d8b382fd2f0a6508e1baec2444d6275f286b950b28884195069e5c7f4c5` |
| 浏览器边界离线 v2 | `verification/browser-checkbox-offline-boundaries-v2-20261110.json` | `8a784db0560040e7627a9ecd5d460a7ed9ea59ab88128d513dcf138f0d48b4b4` |
| 首版修复后单组真实 PASS | `verification/production-browser-acceptance-20261110T061030.450668Z.json` | `067534679abe8980a77d7f22b8aecb9f634002c2d8f2ac7c2818506598c2fcbb` |
| 首版八组首组真实 FAIL | `verification/production-browser-acceptance-20261110T061229.877837Z.json` | `7f4e5809c496e67ac9447e74718d395f9e010ca8d32807a8014deb6203048469` |
| 新稳定点击 visual | `browser-mock/VerifyProductionScanPreviewVisualCheckboxStable20261110.cjs` | `249882d0cac0d5533f34d8b8196861a97ad1626a324c9fae3dc32a802cf45afd` |
| 新稳定点击浏览器副本 | `verification/VerifyProductionBrowserAcceptanceCheckboxStable20261110.py` | `160cafb0978803fbcb64fac3eb8f5da2c08fc6a1bcd4b08b36b4fb629880ec8d` |
| 稳定点击离线几何/事件 | `verification/browser-checkbox-stability-offline-dom-20261110.json` | `c896e4400d7fd470003ff1d82bec5f2436a4392441646faa6d0a73f08d28b2f1` |
| 稳定点击离线保护边界 | `verification/browser-checkbox-stability-offline-boundaries-20261110.json` | `4071928b0a46508d4563566605968516e6e3b8e213bfedf4a465bafe2f382e5b` |
| 固定backend构建 | `backend-build.json` | `fa0e7322a4d274be83e6689b667758996f7071ac4c073f4c483d1a78fb6a6b39` |
| 宿主CAS显式模块副本 | `host/HostConfigAtomicExplicitModules20261110.ps1` | `48b952f12cd5b15fee59cc125eb5e18b19722f4005d1f88fda0e02ea34213c70` |
| 宿主CAS只读prerequisites | `host/host-config-cas-readonly-prerequisite-pass-20261110.json` | `1d09e7b5af1c349f9f0a765724fd3d59dc7b6a6cb6465f7d1cceae2cd9593c96` |
| 宿主fresh pin final review | `host/fresh-host-pin-review-final-20261110.json` | `495931bf3ca755269993e7106b143643e415a7f6420f59daf8598bd0c313b1c3` |
| 宿主pin完成 | `host/pin-fresh-recovery-attempts/pin-583754d0b301422093262a5e65f146eb.json` | `eda098a6ba75d71c6e74b3cd9d06944578ae20a556c52df8cfd76c88931a236a` |
| post-pin三age认证 | `verification/post-pin-checkpoint/post-checkpoint-entry-primed-20261110.json` | `6000a535c482767c32f18ee033e1d4ecd1219ac3d1e4d0c35f4629facdf47517` |
| 中文前三组PASS/第四组原FAIL | `verification/production-browser-acceptance-20261110T062303.723115Z.json` | `117d36f1e25d0a5a2945ce85b6c5cdf3eaa7d70192483205c4d7d52918ae90ad` |
| 中文第四组真实PASS | `verification/production-browser-acceptance-20261110T063022.453435Z.json` | `c26c7abfbd45a3abb56056680372fafe000a855fda3f7920938efba25a366799` |
| 英文首组原FAIL | `verification/production-browser-acceptance-20261110T063040.198165Z.json` | `ac20680a7186b18088616da84b5f873c5dbaac344bce5f35fb0050ebfb4fce70` |
| 稳定后滚动visual | `browser-mock/VerifyProductionScanPreviewVisualCheckboxStableThenScroll20261110.cjs` | `f2422adea540a6184ef3998c3e58dd39b55040b3d984865afd422250d73d4547` |
| 稳定后滚动browserpy | `verification/VerifyProductionBrowserAcceptanceCheckboxStableThenScroll20261110.py` | `5e1987c4d80fa87d92bec147cd397967e00f692a1299132e3c331c5fe01451b2` |
| 稳定后滚动离线DOM23项 | `verification/browser-checkbox-stable-then-scroll-offline-dom-20261110.json` | `c0ce43ab6fa205d929ac366d74cadce4c0e155f358158dbceb75abd8d55bf65f` |
| 稳定后滚动离线边界v3九项 | `verification/browser-checkbox-stable-then-scroll-offline-boundaries-v3-20261110.json` | `3e7d1e5e6bbb6a2ff75d76c77b6600804e82f03d332c6781ddb53e32a11910ae` |
| 八组真实浏览器完成汇总 | `verification/browser-acceptance-eight-groups-completed-20261110.json` | `629c1e8b7654ad0d9cd41f1d07de668cbb7689548e80071a7832ea98b15aa2d6` |
| 首条新影片run原回执 | `verification/new-baidu-cycle/run-new-once-20261110T061645.637299Z.json` | `17e7b234980ca2112b806c1236a87e2393850a7132d14f10cff7ed83f413d2fa` |
| 首条新影片skip只读闭环 | `verification/first-cycle-readonly-close-20261110T062625.736338Z/first-cycle-closed.json` | `eeeb84f72d5c4577002a521fa55ea787065a1c874dd3adbc2bd27a391007be85` |
| 第二条新影片唯一POST/known SKIP | `verification/new-baidu-cycle-attempt2-20261110/run-second-skip-once-20261110T065428.084261Z.json` | `67c2e705b13d6c7cbdfecebdd53de78dc16f93799039901ce6efb715c065be7e` |
| 第二轮20项材料完整认证 | `verification/second-baidu-materials-checkpoint-20261110.json` | `80f796167f6efd46fc983bbbb09309e2bec5eb519f2ca71577c843f7df86ea90` |
| 第三条唯一POST原失败/新UNKNOWN17622 | `verification/new-baidu-cycle-attempt3-20261110/run-new-once-20261110T070220.337934Z.json` | `a05a97fcf3104e95dad2cf315df72feaa70a0178cfee48879034662583957e56` |
| 第三轮576项材料完整认证 | `verification/third-baidu-materials-checkpoint-20261110.json` | `85f46826027cb84952e4679eb4c05b72510d9a5ee438501b3a7e2dfd9654df03` |
| 日常覆盖V2助手 | `verification/UpdateDailyBackupCoverageReviewedV2_20261110.py` | `a1add64d9fdfbefb0a0ba973f7422da863de361cfbd6d28986cf7bf0fc29c806` |
| 日常覆盖V3离线/原生10tests18fixtures | `verification/daily-backup-coverage-20261110/offline-tests-v3.json` | `c5b5606ac6d69dc10a2b815831e9b6aaaa1a702223b83c541ecaeca0bc693e12` |
| 日常覆盖prepare/原config完整认证 | `verification/daily-backup-coverage-20261110/prepare-20261110T070959.524921Z.json` | `16c5a8e1d446c0598c0a5269f17d6e67ab2df33243029e8599319b145b2d543c` |
| 日常覆盖封存prepare计划 | `verification/daily-backup-coverage-20261110/coverage-prepared.json` | `6084f9e98501641f3b2afb30cb41cf43690ee7e954e303fcff3bb89eea8e2a67` |
| 原release日常41→42唯一apply | `verification/daily-backup-coverage-20261110/apply-20261110T071256.556950Z.json` | `57282d9e5c1ee48e8e9e7e772a832cdb771b11df79988a3d23a921f27fa4a1a2` |
| 日常V2独立静审/实际回执核对 | `verification/daily-backup-coverage-20261110/independent-static-review-metadata-20261110.json` | `9a131a0bd0e1b428aef5ac0040bc4536cabb721a9840532444bcf551d05cae2c` |
| UNKNOWN17622同账号精确目录实际3次只读GET | `verification/uploadid-fix-20261110/unknown17622-readonly-observations/unknown17622-20261110T073502.417407Z.json` | `0035891ef4b30091c1b3ad8109d7d1286c34947b250637cd7dfe5a56e5f2accf` |
| 最终230member追加来源清单 | `verification/final-supplement-source-manifest-reviewed-20261110.json` | `985a4000347b1e6fbcc531be501f98ddb50fa1ece9e876772add72c213434888` |
| 最终230member追加age完整认证 | `verification/final-supplement-materials-checkpoint-20261110.json` | `d898569e04e69a2f4edc84e4322eb672d5883677d018759156d204d003359c6f` |
| 修复候选本地固定image/JAR构建 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/backend-build-raw.json` | `ab4acbcaabfed716d2dd385992dbc6e6bdb9dd072795217a9f91ab9b183d7471` |
| R2实际backend部署 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/deployment/deployment-completed-2026-11-10T081208826636+0000.json` | `52928f4455fd64ba8cd86d9885a5ae8f931abf5ccf8426d5a10c8bbf7e3c771f` |
| R2实际23GET/375产物只读验收 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/production-readonly-uploadid-20261110T081248.581486Z.json` | `fee96b91717a63732f190d7a60f95c46e10398ea1418eed4264ebe2f2b8290b8` |
| R2冻结29source最终独立静审 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/final-operational-helper-independent-static-review-20261110.json` | `f3b57c7f2aa8098c6ee4460487b51a02496dae6f5bc60823ed512794c8993640` |
| R2 fresh v4-final检查点计划 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/backup-audit/checkpoint-plan-v4-final.json` | `30a52c2d6ededfdb1472894df8072fbbe89635e55632e6fba9a5c8ca9f7795cc` |
| R2部署前75member显式来源 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/predeployment-final-reviewed-materials-manifest-20261110.json` | `7857881771783c55d1ecd91d7266a1728ba1dae0747577840d5fa90a006cad17` |
| R2部署前75member完整认证 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/predeployment-final-reviewed-materials-checkpoint-20261110.json` | `aed2c1983aff2885328552c481575b4a23bfa2fa50d2a149b6b6e26946dd293a` |
| R2旧release230补材绑定 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/previous-release-supplemental-binding.json` | `c65808b814d0e51ced18f0ac95deae3d274178e0a5f947f9649b57b499e938a6` |
| R2宿主pin完成 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/host/pin-attempts/pin-8ff2fdccbcb544e7ba1a92f9e1dacdda.json` | `2a07ce0f4d7d867d943136b1de70c4a3d24aa2d442dc89dde9c1d0f977eba02b` |
| R2 pinned installation | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/host/installation-pinned.json` | `ca22d727b23a1f7ce6fc030512877e4c0cdd9d3b690a161611c13b9ee1a993e4` |
| R2 post-pin三age完整认证 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/post-pin-checkpoint/post-pin-completed-20261110.json` | `316eb62a7f3486f4b18defee61fe42e43008ffc35c50b3f19b79c43c33a9d096` |
| R2 post初始report范围拒绝 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/post-pin-checkpoint/rejected-report-scope-20261110.json` | `5be29819701e0ed1b66c49aca035b781d63b23e27dedbc49aada563235152a21` |
| R2日常42→43唯一CAS/dry-run | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/daily-backup-coverage-20261110/apply-20261110T082804.015799Z.json` | `abacd5d30928c1b78a7ec42608888b3acc88fcc6c44e055badfb2acdaeb39bdb` |
| R2 Docker双流限定日志窗口 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/backend-docker-log-window-20261110.json` | `043e268f2eab081d89fabbd489ddea6d5ba52ca10ee946a8bcc5e3de839dd971` |
| R2最后13代次/保护整行保持 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/final-runtime-invariants-20261110.json` | `8bd8c82e70dd59fb3a1aef21e296f30242bd85b8453ecbc89d7eb9a72e0f5fb6` |
| R2末次宿主三published端口直接GET失败 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/final-direct-local-dependency-probes-20261110.json` | `d2df7a412b6933a5c0da3f96fb30bd662e05c8842c3c83637cb894f2e527be7c` |
| R2内部健康/MinIO配置peer路径只读诊断 | `E:/gying-tools/releases/baidu-upload-id-compat-20261110/verification/local-dependency-paths-readonly-20261110T085114.915481Z.json` | `0e7dcc14af44989a932409424a4a2369df3746b7de60da9db86339a4f80e23c7` |

原完整 suite 外部路径为 `G:/gying-tools/builds/baidu-recovery-merge-20261110/query-boundaries-mysql-9911f58e51aa/result.json`，SHA-256 `4085cf8e04ca5b776d344e7bd4d1aa38da4841f2632e2c00040c6e8a66f760b1`；不归到本表 release 目录。

[当前状态](../../current-project-status.md) · [前一轮扫描上线](../baidu-search-duplicate-20261109/README.md) · [历史上传/分享兼容](../baidu-share-compat-fix-20261109/README.md)。
