# 重复影片合并与 TMDB 绑定（2026-11-11）

时间均为 Asia/Shanghai。11 月 11 日 13:24:08，仅 backend 上线固定代码 `65fd377de0f055c6377c623e763d245e991241f8`，13:31:03 部署终验通过，Compose23/frontend12 保持。73 对候选本轮第 1/3/4 批及新 201/202 共 66 对新 merge 正式精确 PASS：380 条迁移关联、512 条 reference history、7 条旧 `update_at` 单次合并窄窗；余七对运行态阻塞与原 71 对 DO_NOT_MERGE 理由保持。历史 MobLand 一次合并另计 19 关联/21 history/8 资源，本轮 canonical 两字段于 16:11:51 首次 UPDATE、16:12:27 独立只读精确增量通过，没有重 merge。17:10:12 本轮真实入口闭环：67 对详情、67 组列表和六个代表 SSR PASS，复用 V3 已过的两个电影 SSR，仅补四个 TV/MobLand SSR；完整行/百度/13 代次/永久阻塞保持。原 Entry V2/V3 STOP、MobLand V2/V3 未写失败和旧批次失败均保留；17:12:06 百度 fresh baseline 已 PASS，追加四手动/最多一实际归档及成功对账后的自然一轮问题已发送但未获回答，未批准或执行；百度真实归档及自然完整备份、安全门禁仍待，不将只读准备计为云归档成功。

## 部署与恢复

[部署终验](deployment-final.json)和[真实入口验收](deployment-acceptance.json)通过。backend 为 Compose 第 23 层，frontend 第 12 层及其余 12 个容器代次保持。旧 backend 正常 SIGTERM 退出，仅一次激活；完整旧 Config、Env 原始顺序和 labels 已认证保存，现役 17 个 Config 字段、153 项 Env 映射、HostConfig、网络和挂载核验一致。实际 Env 顺序不同，不声称原始序列 hash 相同。

| 固定产物 | 实际值 |
| --- | --- |
| backend image | `sha256:b7134d36c9eb8b36ef99471623ab4599ad837288b612d38ec04eb1a672dad2bb` |
| 实际 JAR SHA-256 | `d766f376c4c1b411b133632835b4eab327d0cfeeea11a590e5d49e4dd49fd3f9` |
| backend 启动 | 2026-11-11 13:24:08.901986 |
| 停止窗口检查点 | `G:/gying-backups/duplicate-tmdb-binding-20261111-20261111T052216.834247Z` |
| host pin 后检查点 | `G:/gying-backups/duplicate-tmdb-binding-post-pin-20261111-20261111T052748.133234Z` |

停止窗口七份 age 完整认证，75 项配置、882 个宿主材料、1374 项 ACL、MySQL 31 表/1 过程/34 触发器完整正文、队列和当前百度会话均验证；未导入 SQL，不称跨服务原子快照。13:28:29 host pin 唯一 CAS 只改 backend 镜像，post 三份 age 完整认证和权限/原任务保持。13:30:09 日常覆盖 45→46，仅增加本 release，唯一 CAS、原生 before/proposed/after dry-run 及 owner/group/DACL 通过；自然完整备份未验。原镜像、覆盖和检查点留在仓库外；应用回退保留当前 SQL/data/state/key，数据变更后须重新核对兼容性。

本机/经既有代理的公网 20 个匿名入口按预期通过，2 个既有 ADMIN device GET 通过，final 另核本机片库与 MobLand 详情 GET 200；零新登录/device，认证可能常规更新 `last_seen_at`。nginx 校验后进程内重载。当前 backend 配置的 DB/Redis/source/quark/publisher 只读链验证通过；MinIO 认证既存对象 GET 200 是 frontend Node peer 在 backend 网络的读取，不能代替 backend 本进程上传业务；PanSou 仅 TCP 可达。未新增 UI 测试，既有截图/静态资产为历史证据。

Docker 双流日志限 13:24:08–13:30:43、最多 tail 2000 行，stdout 1621 B/stderr 0 B，ERROR/Exception/OOM 匹配为 0；应用文件日志未验。部署阶段没有新扫描/preview/merge、云写或直接 SQL 写。安全 G01–G11 未关闭。

## MobLand 历史合并与两字段补齐

2026-11-10 23:56:11，当时旧 merge 实现完成一次 `tmdb_tv_247718 -> 1YKW` 合并，review `65efad8f-e6ea-4250-bfcf-15b824163e32`、status=SUCCEEDED、merged=1、replayed=false。23:58:26 独立只读核验 PASS：19 条关联迁移、21 条 reference history，源影片软删除并保留 canonical alias，8 条资源保留；夸克、P2P、discovery 历史整行及百度/sys_config/全部容器代次保持，favoriteDeletes=0、cloudActions=0、operatorDirectSqlWrites=0。不能再次合并同 source。

canonical `1YKW` 的 `tmdb_id`/`tmdb_type` 当时仍为空，已确认的 TMDB 身份已迁移。11 月 11 日首次窄字段 prepare 只读失败，原[失败回执](mobland-binding-original-prepare-failure.json)保留 `MYSQL_EOF_BEFORE_STATEMENT_COMPLETED`；没有生成 preimage/reservation，没有 UPDATE 或云动作。主任务已定位两张历史表没有 `id` 时默认排序引发 MySQL 1054；后续 V2 修复和实际锁读停止分别记录，不改写原失败。

原合并结果与独立验证仍在 `E:/gying-tools/releases/mobland-merge-apply-20261110/`：

| 原回执 | SHA-256 |
| --- | --- |
| `merge-result.json` | `8864f7723141e9f738a9b4363c8a33b952654b5695ca2a761fc846c5a2edf7e5` |
| `verification.json` | `f8bfcd5f1b8da18481cb2cb249d402ffab7e726f61d8b7372d238cfa8f6d779d` |

原 merge helper verifier failure 和后续只读闭合回执各自保留，不把旧失败覆盖为成功。

BindMergedV2 [原离线与 AST 复核](bind-merged-v2-offline-review.json)、[独立 11+2 项离线验证](bind-merged-v2-independent-review.json)通过，helper SHA `647f4c1a09a96c630ac5674112f680ab20fffde6e9be8853076e9cf1830c5b2c`。两历史表默认 `id` 排序的[原 1054 与完整复合主键排序只读成功](bind-merged-v2-readonly-schema-order.json)分别保留；20 个函数和原保护文件保持。它只允许 MobLand canonical 一行 `tmdb_id`/`tmdb_type`，同连接 SERIALIZABLE 全行/范围锁、一次 CAS/COMMIT，保留 `updated_at`、source/身份/原 merge history/百度/sys_config 与容器；UNKNOWN 禁重试。该独审仅本地 synthetic 子进程，无生产 I/O；下述实际回执另记，不能将离线保护等同于写入成功。

15:27:52 V2 实际只读 prepare 为 PREPARED，仓库外 `mobland-binding-prepared.json` SHA `53e3d3631b1f08a988f0cafdf5b9dbc6a683420419747c6a9634024f715cb696`；完整 preimage 加密保存。15:29:33 [实际 apply 失败](mobland-binding-v2-apply-failure.json)为 STOPPED，locking SELECT 触发 MySQL errno 1142/sqlstate 42000，保留 gate `MYSQL_EOF_BEFORE_STATEMENT_COMPLETED` 与原 SQL/stderr 摘要 hash；`sqlUpdateAttempted=false`、`commitAttempted=false`、`commitAcknowledged=false`、`transactionOutcomeUnknown=false`，metadata changed=0。15:31:17 [随后真实只读复验](mobland-binding-v2-not-applied-verification.json)为 `NOT_APPLIED_EXACT_PREIMAGE_VERIFIED`，当前完整投影 SHA 与 prepared 的 before SHA 同为 `f243970d6ea9a230fe5e8174ceae149ab9abc46a35a2e98f57f499b8ef95f752`。原 apply/预约保持且不重发；本次没有 UPDATE、COMMIT 尝试、身份写、merge 或云动作。canonical 两字段仍待补，最终 Entry V2 未执行；权限和锁读语句只读诊断中的候选修复不计实际成功。

V3 [独立窄锁审阅设计](bind-merged-v3-offline-review-plan.json)已形成，12 项纯 AST/synthetic 控制检查通过，未载入 Runtime、读取凭据、解密或启动子进程。方案保持两个独立 metadata 查询 FOR UPDATE，其余 23 个保护投影（含排除 source/canonical 的第三 owner JOIN）整条 FOR SHARE，仍同连接 SERIALIZABLE；25 个投影、空范围和原完整行保护均须保留。它冻结 V2 原文件及实际失败/复验，要求既有应用身份的独立只读权限证据、新 V3 generation 和与 V2 before 相等的 fresh 原像。该回执仅为离线设计，尚未审阅 V3 实现或真实权限、没有字段 UPDATE 或入口成功。

此前[初始本地诊断失败](mobland-lock-privileges-initial-local-failure.json)为文件安全模块与参考 schema 模块识别错误，databaseConnections=0，原 gate 保留。15:41:51 [实际维护身份只读权限](mobland-maintenance-privileges.json)为 READ_ONLY_FACTS，MySQL 8.0.28、无 role，但 metadata 两字段 UPDATE 不可用；单改后续保护表锁不能使该身份完成 CAS，未 GRANT 或换凭据。[首次应用身份只读停止](mobland-application-privileges-original-readonly-stop.json)保留 `EXISTING_BACKEND_DATASOURCE_KEYS_REQUIRED`，没有 UPDATE/COMMIT；新独立身份观察按实际 prod 配置来源读取 DB_USER/DB_PASSWORD 后才形成下述实际回执。

15:51:17 [既有应用身份真实只读权限](mobland-application-privileges-v2.json)为 READ_ONLY_FACTS，MySQL 8.0.28、无活动 role、与维护身份同 server/schema、固定 TLS transport 保持；既有 prod profile 的 DB_USER/DB_PASSWORD 名称已核，22 表完整 SELECT 和 movie_metadata 表 UPDATE 可用。未保存凭据值/用户名/原 grants/完整行，实际 UPDATE/COMMIT 尝试均为 0，不修改授权。V3 helper `e8e2c2a056102eed1ad64c80d7a09c534b136bde0380c2797ee31fff5e709304` 的[16 项独立 AST/完整查询与失败路径 mock](bind-merged-v3-independent-review.json)全部通过：25 个 AST 函数与类保持，25 个无锁查询完整列/谓词与 V2 相等；2 个独立 metadata FOR UPDATE、23 个保护 FOR SHARE（含完整第三 owner JOIN），错误 row count/前后漂移/EOF/未知 COMMIT 均停止且原预约拒重，真实 readonly verify 的 before/approved-after/漂移规则保留。原 11 项 self-test 已通过，此独审未重复，0 Runtime/凭据读取/decrypt/子进程/生产 SQL。

15:55:43 [实际 V3 只读 prepare 安全摘要](mobland-binding-v3-prepared-summary.json)为 PREPARED，完整 prepared/runtime 继续仓库外保存，SHA `e132d72f7c719bcf9cf82a87e34b79dfb8d105ecc1c37857f29512a631f9253f`。两身份 fresh 全行投影均与原认证 age/preimage 相等、运行态保持；此次按主任务明确方案安全重用原加密 preimage，设计中的新 ciphertext 是建议而非新增条件。V3 独立 prepare/预约/result/verify，原 V2 文件、失败/预约保持且不重发。此时尚未实际 apply，canonical 两字段和最终入口仍待验。

16:00:14 [V3 实际 apply 前置失败](mobland-binding-v3-apply-failure.json)为 STOPPED/RuntimeError，原 gate 为 `SAFE_FAILURE_SEE_HASHED_PHASE_EVIDENCE`。`sqlUpdateAttempted=false`、`commitAttempted=false`、`commitAcknowledged=false`、`transactionOutcomeUnknown=false`，且未生成 V3 apply reservation；本次没有 UPDATE/COMMIT 尝试，原结果/脚本/prepare 保持，不重跑 V3。16:01:20 [立即只读复验](mobland-binding-v3-not-applied-verification.json)为 `NOT_APPLIED_EXACT_PREIMAGE_VERIFIED`，当前完整 before SHA 仍为 `f243970d6ea9a230fe5e8174ceae149ab9abc46a35a2e98f57f499b8ef95f752`，V3 prepared SHA 仍为 `e132d72f7c719bcf9cf82a87e34b79dfb8d105ecc1c37857f29512a631f9253f`；本轮 canonical 两字段仍未更新。16:04:17 [后续独立只读门禁观察](mobland-binding-v3-later-readonly-preflight.json)为 IDLE_PASS/maintenanceLock PASS，生产 UPDATE/云动作 0；该时点观察不能倒推原前置 RuntimeError 原因。新 generation 候选只读诊断/审阅另记，不视为字段更新或 Entry 成功。

V3 实际未预约停止暴露一条同代拒重保护缺口：原脚本仅检查 reservation，既有失败 result 存在但无 reservation 时仍可进入后续 preflight。旧 V3 不修改也不重跑。独立 V4 helper `9b8e68fe63049302ec032bdd33d039c2166f468c00499188f6b6fd83044a2b3f` 同时拒绝既有本代 result/reservation，追加原 V3 helper/prepare/未写失败/正式原像复验四项 SHA 到八项 prior 链；UPDATE、TLS/现有应用身份、完整列/谓词、2/23 锁和物理保护逻辑保持。其[7 项最小差异与新增未预约失败拒重检查](bind-merged-v4-independent-review.json)全部通过，29 AST 函数与类保持；去除本代命名/version、拒重和 safe phase/hash 诊断后，apply/prepare/prepared/verify AST 与 V3 相同。原 11 self-test 和 16 独审结果复用，未重复 suite；新增 mock 的私有 sentinel 只保存 hash，stack 仅文件名/行号/函数，不含变量值。

16:07:35 [V4 实际只读 prepare 安全摘要](mobland-binding-v4-prepared-summary.json)为 PREPARED，完整 prepared/runtime 仍在仓库外，SHA `f37655c3f3b01fcc8b784379f63643474b39aa894ff1c9e11d0202a8d7d0c398`；两身份 fresh 完整原像与原 age/before、现役运行态及八项旧证据保持。此时尚未发送本轮字段 UPDATE，scope 未扩大、未 GRANT/改凭据，canonical 两字段与最终 Entry 仍待实际回执。

16:11:51 [V4 本轮首次字段 UPDATE 正式结果](mobland-binding-v4-applied-verification.json)为 APPLIED_VERIFIED，唯一 canonical `1YKW` 的 `tmdb_id=247718`、`tmdb_type=tv` 已提交，metadataRowsChanged=1、commitAttempted/commitAcknowledged=true、transactionOutcomeUnknown=false。完整锁定 before SHA 为 `f243970d6ea9a230fe5e8174ceae149ab9abc46a35a2e98f57f499b8ef95f752`，仅两字段 after SHA 为 `6fef56de4af629686d00f1d04660f7c000b43125e6df475e69c6bce3620bba65`；`updated_at`、source/身份/原 21 条 merge history、全部其他相关行、百度/sys_config 和 13 个容器代次保持。16:12:27 [独立真实只读复验](mobland-binding-v4-committed-readonly-verification.json)为 COMMITTED_EXACT_DELTA_VERIFIED，current SHA 精确等于 approved-after，复验新增 SQL 写为 0。本轮为一次直接 SQL 两字段 UPDATE/COMMIT；mergeRequests/identityWrites/cloudActions/schemaChanges=0，禁止自动重试/恢复，没有重 merge MobLand。原 V2/V3 失败和原像保持回执均按时点保留，V3 后续 idle PASS 不倒推其原 RuntimeError 原因。新合并仍为 66 对/380 关联/512 history/7 legacy，MobLand 历史一次 merge 与本轮一行字段修复分开计；最终 Entry 尚待实际回执。

## 候选复核与保留理由

[candidate-scan.json](candidate-scan.json) 为完整 dry-run scan：15788 部影片、145 对候选。[candidate-review.json](candidate-review.json) 按真实 GYING 来源身份和 TMDB 身份逐对独立复核，确认 74 对直接方向，其中 1 对为已合并 MobLand、复核时 73 对待处理；另 71 对全部为 DO_NOT_MERGE。没有使用多记录链条推断身份，复核 operatorProductionWrites=0。确认身份不等于已通过运行态 preview 或已执行合并。

71 对保留理由按原始 reason 码计数如下，具体标题与方向见原字节 review 文件：

| 原始保留理由 | 对数 |
| --- | ---: |
| `BOTH_SOURCE_IDENTITIES_MISSING` | 1 |
| `RETAINED_GYING_IDENTITY_MISSING` | 4 |
| `DIFFERENT_DEADPOOL_CUT_AND_TMDB_ID` | 1 |
| `ANIME_SECOND_COUR_SCOPE` | 1 |
| `ANIME_PART2_VS_WHOLE_SEASON` | 4 |
| `INFERNAL_AFFAIRS_II_VS_III` | 1 |
| `MATRIX_RELOADED_VS_REVOLUTIONS` | 1 |
| `THE_DEER_AND_CAULDRON_I_VS_II` | 1 |
| `BILINGUAL_PERSON_IDENTITY_MAPPING_UNPROVED` | 1 |
| `SOURCE_HAS_CONFLICTING_CONFIRMED_TMDB_IDENTITIES` | 1 |
| `SERIES_VS_STORY_ARC_SCOPE_UNPROVED` | 1 |
| `SPY_FAMILY_PART2_VS_WHOLE_SEASON` | 1 |
| `DIFFERENT_WORK_PART_SEQUEL_COLLECTION_OR_CONFLICTING_TMDB_ID` | 51 |
| `LORO_2_VS_LORO1_AND_TARGET_IDENTITY_CONFLICT` | 1 |
| `FILM_FESTIVAL_TAG_NOT_WORK_TITLE` | 1 |

## 批次 helper 修复与当前阻塞

13:41:43 第一批原 helper 在已收到一次 20 对 preview 后因 `ValueError/LOCAL_PHASE_FAILURE` 停止，[原失败](batch-1-original-failure.json)保留，merge POST 未预留。该原 preview 有 18 对 eligible，人生切割术 `tmdb_tv_95396 -> B2kG`、暗影蜘蛛侠 `tmdb_tv_220102 -> EVrv` 各被一条 `quark_transfer_task/ACTIVE_OR_UNKNOWN_TASK` 阻断，保留原任务和审核，不重发原 preview 或合并该阻塞审核。

`E:/gying-tools/releases/duplicate-tmdb-binding-20261111-data/` 中 V2 helper 已通过[39 项离线测试](batch-helper-v2-tests.json)，[固定绑定](batch-helper-v2-binding.json) SHA 为 `edb8540f998a22eac3ca8723f1f949b14ace6fe87f2ee3d048920c638d63dfab`。原 helper、失败、primary preview 和 17 项原输入均固定；18 对子集须使用新的加密 preimage 和新的 preview。该 V2 离线阶段尚未合并；后续实际结果分别记录，不改写原失败。

14:00:58，V2 子集恢复在实际发出 subset preview 前被 `C.idle` 门禁阻断（`New in-flight tasks or maintenance; no stop or replay`），[预发送失败](batch-1-v2-presend-failure.json)保留。已有子集 preimage/auth 保存，但没有 subset request、merge intent 或 merge request；该次新增 preview/merge 均为 0。14:02:58 后续只读活动计数回到 0，不改写原失败，也不自动重发。

随后 V3 预发送恢复入口通过[60 项离线测试](batch-helper-v3-tests.json)及[独立静审与冻结链核验](batch-helper-v3-independent-review.json)，独立复跑亦为 60/60。新[绑定](batch-helper-v3-binding.json) SHA `379d2d90dd78761c885b886312d14457aa33fac834bde356d0396524af68f5c9` 固定 V2 的 17 项旧输入和 7 项预发送失败材料，原字节保持。仅允许原 18 对方向、一个新 preview 和一次 merge，总批预算仍为两次 preview/一次 merge；采用新加密 preimage、同 actor/既有 device 的 300 秒认证和发送前两次无新请求检查。自然 idle 只读观察共享最多 120 秒，其他门禁立即停止，UNKNOWN 只读核验且禁止重发；旧阻塞审核不可合并。离线通过不计新合并完成。

14:14:12，首批 V3 merge 返回 SUCCEEDED、merged=18、replayed=false 后，精确 verifier 报 `UNEXPECTED_ROW_DELTA_resource_link`，原[合并后核验失败](batch-1-v3-postmerge-verification-failure.json)保留。该失败已预留 subset/merge POST，不允许重发或自动恢复，当时其余批次尚未推进。14:15:38，两条原阻塞候选[完整相关投影的独立只读核验](batch-1-blocked-retention.json)通过，原任务和审核保留。

后续只读诊断确认：生产 `resource_link` 同时有 `updated_at` 和旧 `update_at` 两列，均为秒精度自动更新时间；合并 SQL 只显式保持前者，4 条实际 source 迁移行的旧列自动刷新。V4 [42 项离线测试](batch-helper-v4-tests.json)、[独立静审与冻结链](batch-helper-v4-independent-review.json)通过，[固定绑定](batch-helper-v4-binding.json) SHA `de37382a454a06b5d2b24b62b0b95d3406405f40571e1c750045f26b2bebdb33` 保留 V2/V3 和 46 项操作证据原字节。V4 只允许实际 source 迁移资源的旧列变化，绑定完整资源表结构/触发器/数据库本地时区，按 14:13:55 请求至 14:14:04 数据库完成的秒级窄窗核验；其他整行/列及 history 仍须精确一致，B1 禁止再次 run，UNKNOWN 只能只读核验。

[已认证存储快照核验](batch-helper-v4-stored-snapshot-verification.json)和 14:30:36 [正式生产只读终验](batch-1-final-verification.json)分别通过：18 对、184 条迁移关联、220 条 reference history、4 条旧时间列窄窗，favoriteDeletes=0；source metadata/身份 provenance/其他资源列保持。百度、配置和 13 个容器代次保持，正式恢复核验新增 POST=0、直接 SQL 写=0、云动作=0；原三个 helper 失败不改写。其余批次待按原单次预算处理，MobLand 窄字段尚未补。

14:33:25，第 2 批已收到原 20 对 primary 与 19 对 subset preview，但在 immediate-before-merge 的自然 idle 门禁停止；[原预发送失败](batch-2-original-presend-failure.json)记录 mergePostReserved=false。唯一阻塞为 `tmdb_movie_11647 -> gying_mv_XpJM` 的 `PART_NUMBER_CONFLICT`。原 subset review/key 和 intent 保留，原 API expiry 为 14:43:05.795209697；发送前须余期严格大于 90 秒，因此门禁截止严格早于 14:41:35.795209697。V5 [9 项修正后离线测试](batch-helper-v5-tests-pass.json)、[独立源码审查](batch-helper-v5-offline-review.json)与[冻结链核验](batch-helper-v5-independent-review.json)通过，[早期两项 fixture error](batch-helper-v5-tests.json)原样保留。V5 完成封存时窗口已过，未执行、没有 merge request，也没有新 preview；[秒级时点精度说明](batch-helper-v5-expiry-clarification.json)保留原独审回执。新只读 Observer 的 [31 项离线测试](unused-batch2-observer-offline-tests.json)及[独立审查](unused-batch2-observer-independent-review.json)通过，15:02:19 [实际只读退出观察](batch-2-unused-expired-observation.json)通过：两份 age 完整认证，原 20/19 全部状态（含一条阻塞）保持，两份完整 review 未使用且自然过期；原 request/response/authentication/intent 和 key 字节保留，V5 未执行。观察新增 POST/直接 SQL/云动作均为 0，百度/配置/13 容器代次保持。

V6 [12 项离线测试](batch-helper-v6-tests.json)、[独立源码审查](batch-helper-v6-offline-review.json)与[冻结链核验](batch-helper-v6-independent-review.json)通过，仅允许未预留的第 3/4 批，复用原 V4 run/精确 verifier，native idle 的精确原门禁只作共享最多 120 秒自然观察。原每批最多两次 preview/一次 merge、发送前余期大于 90 秒、整行/来源身份/表结构/时区/容器/百度配置保护及 UNKNOWN 禁重发均保持。

| 实际批次精确终验 | 合并对数 | 迁移关联 | reference history | 旧时间窄窗行 | 未合并阻塞 |
| --- | ---: | ---: | ---: | ---: | ---: |
| [第 1 批 14:30:36](batch-1-final-verification.json) | 18 | 184 | 220 | 4 | 2 |
| [第 3 批 14:53:34](batch-3-final-verification.json) | 16 | 73 | 105 | 0 | 4 |
| [第 4 批 14:57:50](batch-4-final-verification.json) | 13 | 66 | 92 | 2 | 0 |
| [新 201 子批 15:20:52](batch-2-split-201-final-verification.json) | 10 | 36 | 56 | 0 | 0 |
| [新 202 子批 15:24:01](batch-2-split-202-final-verification.json) | 9 | 21 | 39 | 1 | 0 |
| [原第 2 批过期未发送](batch-2-unused-expired-observation.json) | 0 | 0 | 0 | 0 | 1 |
| 本轮已完成 | 66 | 380 | 512 | 7 | 7 |

第 3/4 批各实际一次 merge，replayed=false，favoriteDeletes/favoriteHistoryRows=0，精确行 delta/history coverage、source metadata/身份及保护百度/配置/13 容器保持。第 3 批用了两次 preview，第 4 批用了一次 preview；其实际 API 写属于候选合并，不将其记成纯只读过程。[33 项 mock 与独立输入认证](blocked-inputs-v2-independent-review.json)只证明只读 wrapper 的七份 primary 输入绑定、严格布尔和原 journal 数据 hash。14:54:15 第 3 批四条阻塞的[完整相关投影](batch-3-blocked-retention.json)与[输入封存前后核验](batch-3-blocked-inputs-retention.json)真实通过；第 1 批两条阻塞[后续完整保持](batch-1-blocked-retention-current.json)亦通过。第 2 批一条阻塞计入总剩余七条，15:02:19 Observer 已真实验证其原审核与完整业务状态保持。该 15:02:19 退出时 73−47=26 对未合并；新 201 的后续实际结果另记，仍不重新发送第 2 批旧请求。

V7 新 generation 的 [19 项离线测试](batch-helper-v7-tests-final.json)、[独立源码审查](batch-helper-v7-offline-review.json)和[最终冻结链核验](batch-helper-v7-independent-review.json)通过。其 [binding](batch-helper-v7-binding.json) 精确固定 15:02:19 的真实 Observer 回执，保留原 ROOT、原 20/19 请求、review/key 与加密材料；仅将原已确认的 19 对按顺序拆为 201/202 两个独立 10/9 子批，永久排除原一条阻塞。执行须再核新的只读 preparation 和完整 plan 双 SHA；201 前仍须原 20/19 完整状态相等，202 前须 201 实际 V4 精确 PASS 加余下九对/阻塞的原完整投影保持，各子批 fresh age、既有 actor/device、全 eligible 的新 body/review/key 和余期大于 90 秒，最多一次首次 merge。UNKNOWN 不重发，只有 root 显式只读精确 PASS 后才可对账推进下一子批。15:15:21 [实际只读 preparation](batch-2-split-v7-preparation.json)通过，原 20/19 full state 与 15:02:19 Observer 的 state SHA 一致，0 新 POST/直接 SQL/云动作；[新 plan](batch-2-split-v7-plan.json) SHA `61c351ddc6dd384caf2998a28c280b65a633edc8b1e6161dd3437040c295f889`、preparation SHA `38de6438b959b26ba6dc2751dda16ff96bed22e74c073d970c58f5a266ccacca` 经[独立完整 plan 校验](batch-2-split-v7-plan-independent-review.json)通过。[201 实际精确终验](batch-2-split-201-final-verification.json)已 PASS：10 对/36 关联/56 history/0 legacy，一次新 preview/一次首次 merge，replayed=false，原永久阻塞一条全部状态保持。15:24:01 [202 实际精确终验](batch-2-split-202-final-verification.json)亦 PASS：九对/21 关联/39 history/一条 legacy 窄窗，一次新 preview/一次首次 merge，replayed=false；执行前 fresh 核 201 的真实 V4 exact PASS 与剩余九对/永久阻塞原完整状态，执行后永久阻塞全部状态保持。新 19 对已完成，本轮合计 66 对/380 关联/512 history/7 legacy 窄窗，favoriteDeletes/favoriteHistoryRows=0；原 B2 旧请求、review/key 和材料保留，不计一次新原 batch2 merge。

入口 V2 [18 项离线测试](entry-acceptance-v2-tests-complete.json)、[独立源码审查](entry-acceptance-v2-offline-review.json)及[冻结链](entry-acceptance-v2-independent-review.json)通过，仍要求所有真实批次/子批及 MobLand 窄字段完成。它保留原匿名 HTTP、完整行、百度状态、运行态和最终检查，另核 10/9 的实际请求/V4 PASS/current actor-bound SUCCEEDED 结果，并在入口结束时再次验证原永久阻塞一条全部行保持。该离线阶段尚未执行实际入口；正常 VIEW/search/Redis/nginx 日志变化按原验收规则单列，不称零业务日志写。

16:13:09–16:14:06 [Entry V2 实际停止回执](entry-acceptance-v2-actual-stopped.json)为 STOPPED，gate `ACTUAL_TARGET_REVIEWED_TMDB_BINDING`。实际待验为 67 对（本轮 66 对加历史 MobLand），仅七对详情和七组列表 PASS、21 个显式 nginx GET，SSR 检查尚未执行；`ssrInternalDetailGetsMayOccur=6` 为潜在内部请求额度，不代表实际内部 GET 数。全部 staging terminal 及新 201/202 current DB review 已验证，退出 finally 的 metadata134/identity136/resource461/alias67 完整选定行 count/SHA 与 before 精确相等，百度完整状态、13 容器代次及原第 2 批永久阻塞完整行保持。直接 SQL 写/POST/云动作均为 0，全程匿名，无认证 HTTP 凭据读取或 HTTP 正文/provider URL 落盘；正常 VIEW/site_search_log/Redis/nginx 日志可能变化，不称零业务日志写。七对通过不能计作全部 67 对入口完成，旧停止回执与 helper 保留。

16:16:13 [全 67 canonical 只读 TMDB/season 核验](entry-season-readonly-observation.json)为 READ_ONLY_67_TARGET_BINDING_OBSERVATION：TMDB 两字段 mismatch=0，严格 season mismatch=39，全部为 movie 的原 metadata 部数或 NULL；第八对 `tmdb_movie_14310 -> gying_mv_0YK0` 原 season 实为 3。Entry V2 将电影身份命名空间的 reviewed season 0 与 metadata 原部数等同，造成上述门禁停止，不需要生产数据修复。下一代入口须保持 movie review 与 CONFIRMED 身份 season 0、metadata 原 season 与 DTO 原 season 精确一致，tv 则严格匹配正 reviewed season；完整行、百度、代次及永久阻塞保护继续保留。该只读观察 SQL UPDATE/云动作 0；新 Entry 实现和实际回执另记，不能提前写成功，也不能在正常 VIEW 之后重用 MobLand V4 的原完整 preimage 验收。

16:26:57 [全 67 canonical CONFIRMED 命名空间只读证据](entry-namespace-readonly-observation.json)为 READ_ONLY_67_CONFIRMED_NAMESPACE_PASS：67 条 TMDB identity 均为 CONFIRMED，所有 canonical 的 type/ID/命名空间 season 与原 review 精确一致，issues=[]；一条 SELECT，UPDATE/云动作 0，原始身份行未落盘。该证据补齐新 Entry season 语义所依赖的真实身份事实，不代表新入口已执行。

Entry V3 新代仅修正上述验收语义：Core SHA `af5618beaf19427cd7e354a28898ba9e4f1a6616c803c1b86269fa45dda2c04f`，wrapper SHA `b02d75746158c96c49f1adf143a5a8775d360699bd9aece0be0ebf3b01e54f91`。[13 项新增 pure/真实 Core mock 测试](entry-acceptance-v3-tests-complete.json)、[七类 45 断言独立语义与最小 AST 审查](entry-acceptance-v3-independent-semantics-review.json)和[新增测试源码独立静审](entry-acceptance-v3-offline-review.json)均通过。movie review/全部保留的 canonical CONFIRMED TMDB identity 要求命名空间整数 0，metadata 原部数/NULL 与 DTO、列表原 season 精确一致；tv 要求 reviewed/metadata/identity 正整数季号精确一致，Boolean/类型漂移、缺失或冲突 identity、API/list 误归零均停止。仅新增三个 pure 函数；规范四处允许的 execute 差异后，Core 全 AST 与原版本相等，wrapper 十个无关函数及阻塞 finally 尾保持，原资源/HTTP/SSR/完整行/百度/13 代次/V7/current review 保护保留。旧 18 项已通过测试复用未重跑；新增 mock 不作生产证据，独审仅编译抽取的 pure AST，无完整 helper import/Runtime/凭据/子进程/生产 SQL 或 HTTP。该语义离线审阅时完整 V3 binding 链与实际入口回执仍待，旧 Entry/V2 及实际停止证据不修改。

随后 [Entry V3 binding](entry-acceptance-v3-binding.json) SHA `515fdb2615a3cd7ced1b4afeabaa5c2c10f33509985932292bf5089e489e8305`、[derivation](entry-acceptance-v3-derivation.json)及[完整冻结链独审](entry-acceptance-v3-independent-review.json)通过：新 helper/13 mock/offline review、原 V2 binding/两 helper/继承 V7 链和旧真实 STOP/67 season/67 CONFIRMED 命名空间回执逐项 SHA 精确保持。独审仅执行本地 revision_binding，网络/子进程/凭据/越界写 audit 事件 0，未调用生产 execute/SQL/HTTP 或解密，不重跑旧测试。其后根代理唯一启动 Entry V3 实际匿名入口验收，启动时等待最终回执；未追加数据修改、云动作或 commit/push，不能提前计作入口 PASS。

16:39:46–16:47:38 [Entry V3 真实部分通过后停止](entry-acceptance-v3-actual-stopped.json)为 STOPPED，gate `SSR_RENDERED_ACTUAL_TITLE_AND_YEAR`。全部 67 对 source/canonical 详情完整 DTO、TMDB/命名空间身份/原 metadata season/公开资源 ID 与数量精确通过，67 组列表均 PASS；两个电影 SSR（`tmdb_movie_14310` 与 `gying_mv_0YK0`）PASS。随后首个 batch TV SSR `/movie/tmdb_tv_93740` GET 200，在标题/年份门禁停止，共 204 个显式 nginx GET；只记录两个已通过的电影 SSR，TV 与 MobLand 的剩余 SSR 未完成，不能写为最终入口 PASS。finally 三项 `selectedCompleteRowsFinallyRetained`/`oldBaiduStateFinallyRetained`/`runtimeFinallyRetained` 和 `permanentOriginalBlockedPairCompleteRowsRetained` 均为 true，metadata134/identity136/resource461/alias67 完整行 count/SHA、百度完整状态、13 容器代次及原 B2 永久阻塞完整行与 before 精确相等，无 finally 异常记录。全部 staging terminal 和 201/202 current DB review 仍已验证，全程匿名、直接 SQL 写/POST/云动作 0，未读取认证 HTTP 凭据或保存 HTTP 正文/provider URL；正常 VIEW/site_search_log/Redis/nginx 日志可能变化，内部 SSR GET 潜在额度 6 不代表实际计数。当前仅待标题/年份门禁诊断和剩余 SSR 入口验收，本轮 66 对合并与 MobLand 首次两字段提交结论不变；百度尚未创建 fresh baseline，旧 Entry V2/V3 实际 STOP 都保留。

16:52:16 [首个 TV SSR 标题年份真实只读观察](entry-ssr-header-readonly-observation.json)为 READ_ONLY_OBSERVED，仅一次匿名 nginx GET 200，正文 SHA `7b662ccf2ab8243272ce2e98033a0f730b1466cb219d19008738c9cff996db6b` 与 V3 STOP 的最后一次响应精确相同，HTTP 正文未保存。actualTitleInH1=true、oldBoundedYearRegexMatches=false、yearSubstringInH2/titleEnEndsDigit/exactRenderedYearSpanPresent 均 true；canonical `4YNV` 的 TMDB 93740/tv、season1、year2021 及五个资源投影已记录。根只读诊断结合原 Headlines 的无分隔文本拼接确认：h2 内英文 `Season 1` 的末尾 1 与单独 year span 的 `2021` 连成 `12021`，旧年份数字边界检查误阻塞，实际 h1/年份 span 正确。没有修改网页或生产元数据，直接 SQL 写/POST/云动作 0，正常 VIEW 日志仍可发生；百度未建立 fresh baseline 或新增写。原 V3/STOP 不改，新 helper 仅补剩余四个 TV/MobLand SSR，须继续保持原 67 详情/67 列表/两个电影 SSR 的真实通过和完整行/百度/13 代次/永久阻塞保护，尚未实际补验成功。

SSR 补充 helper [归档源码](EntrySsrCompletion.py) SHA `4561ba6cd314e2b3dfe39597649ee4b652b7d57d4a5998702f31bee86ef32f05` 只允许 `tmdb_tv_93740`/`4YNV`/`tmdb_tv_247718`/`1YKW` 四个匿名 SSR GET，API/JSON/第五个 GET 均拒绝；h2 内独立 span 的完整文本必须唯一精确等于 metadata year，不使用 substring。新增[八项解析器/default 回归](entry-ssr-completion-parser-tests.json)与[四项实际 helper 流程 mock](entry-ssr-completion-flow-tests.json)均 PASS，[组合 12 项回执](entry-ssr-completion-tests-complete.json)、[独立静态 AST/测试源码审查](entry-ssr-completion-offline-review.json)、[binding](entry-ssr-completion-binding.json) SHA `404db6d1b3a75ed37d59f968bcd28f281e62a2e910523aa03e2b4996d3931218`、[derivation](entry-ssr-completion-derivation.json)及[完整冻结链独审](entry-ssr-completion-independent-review.json)通过。严格绑定真实 V3 STOP 的 67 详情/67 列表/两个电影 SSR PASS，fresh current review/pair/blocked 与旧 finally 相等，before/after/finally 全选定行/百度/13 代次/永久阻塞保持；RSC 完整 DTO 投影含 fullDtoSHA 与已过 API、当前 metadata/CONFIRMED identity/资源精确一致。错年份首 GET 即 STOP，最终全行漂移覆盖已有四项 PASS；只复用新增已过回执和旧 suite，没有重复测试。源码归档仅作证据，实际执行位置仍为仓库外 release。

17:09:46–17:10:12 [唯一四 SSR 补验正式结果](entry-ssr-completion-actual-pass.json)为 ENTRY_ACCEPTANCE_SSR_COMPLETION_PASS：四个 GET/四个 SSR 全 PASS，batch TV 两页 year2021、MobLand 两页 year2025 均有唯一精确 year span，实际标题和完整 canonical DTO/资源投影与已过 API 精确一致。apiDetailGets/apiListGets/POST/直接 SQL 写/云动作均 0，anonymousOnly=true、HTTP 正文/provider URL 未保存；正常 VIEW/nginx 等监控日志可能写，不称零业务日志写。before/after/finally 的 metadata134/identity136/resource461/alias67 完整 count/SHA 与旧 V3 finally 精确相同，百度完整状态、13 容器代次和原 B2 永久阻塞完整行保持，四个 finally Retained 均 true、currentSplitDatabaseReviewsVerified=true。与 V3 真实 67 详情/67 列表及两个电影 SSR 合并，本轮六个代表 SSR 入口闭环完成；不重跑旧 67/67/2，不改原 V2/V3 STOP，不修改网页/元数据。本轮新 merge 66/380/512/7 legacy 和 MobLand 首次两字段提交统计不变。根随后仅准备百度 fresh baseline，真实归档须另记具体追加额度授权与实际结果；此 SSR 通过不代云归档、自然完整备份或安全门禁。

七条运行态阻塞的[原 preview 理由与实际保持证据](runtime-blocked-seven-summary.json)如下；未重新 preview、合并或恢复原任务。

| 原批次 | source → canonical | 原 blocker code / reference |
| --- | --- | --- |
| 1 | `tmdb_tv_95396 -> B2kG` | `ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task` |
| 1 | `tmdb_tv_220102 -> EVrv` | `ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task` |
| 2 | `tmdb_movie_11647 -> gying_mv_XpJM` | `PART_NUMBER_CONFLICT` / `movie_metadata` |
| 3 | `tmdb_movie_986056 -> gying_mv_onzr` | `TEMPORARY_SCOPE` / `qq_transfer_cleanup_job`；`ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task` |
| 3 | `tmdb_tv_73375 -> gying_tv_6lx7` | `TEMPORARY_SCOPE` / `qq_transfer_cleanup_job`；`ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task`；`ACTIVE_OR_UNKNOWN_TASK` / `resource_hub_task` |
| 3 | `tmdb_tv_50878 -> gying_tv_M9de` | `SERIES_ONLY_MATCH` / `movie_metadata`；`TEMPORARY_SCOPE` / `qq_transfer_cleanup_job`；`ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task` |
| 3 | `tmdb_tv_96102 -> gying_tv_W7Mr` | `TEMPORARY_SCOPE` / `qq_transfer_cleanup_job`；`ACTIVE_OR_UNKNOWN_TASK` / `quark_transfer_task` |

## 本轮 backend 修复与测试范围

两个 service 与三个 test 文件构成本轮代码变更。TMDB 的 TMDB-ID、剧集、标题/年份三种查找命中均先解析 canonical，仅允许 ACTIVE 且未软删除的保留记录；没有有效 canonical 时拒绝更新或重新创建原记录。合并事务仅在真实 GYING AUTO/CONFIRMED provenance、source matching CONFIRMED TMDB 身份和明确同季成立时补 canonical 的 `tmdb_id`/`tmdb_type`，preview 显示拟绑定；source/target 已确认身份、已有 metadata 或第三 active owner 冲突均阻断。目标及源原 metadata 指纹先存 history，身份 provenance 原样迁移，canonical `updated_at` 保持；不新增表结构或接口路径。

新增 consumer 9 用例；绑定规则 4 单元测试；真实 MySQL 6 测试覆盖绑定、缺可信身份、source/target 冲突、第三 owner/季号、完整行漂移、事务回滚与幂等重放。两名代理独立静态复核通过；`git diff --check` 通过（仅既有 CRLF 提示）。以下 SHA 是冻结源码，不代表部署事实：

| 文件 | 最终冻结 SHA-256 |
| --- | --- |
| `TmdbMetadataSyncServiceImpl.java` | `531bec482cd2658a242d5d762a7487cc16c21b7a3ad291faf89549479052193a` |
| `MovieDuplicateMergeService.java` | `03117ce17b20610961ec3650e6b96415625214e8e7419207c18aeb2c028b90e2` |
| `TmdbMetadataSyncServiceImplTest.java` | `ca3430c4fce6becf3abd6aa67c6537cc0a2dc9ccb89dd2c68521e9fa179bd9f1` |
| `MovieDuplicateMergeServiceTest.java` | `f71601b8a729fecd53444e1afdf28139374340eb0f5f9e943e07948ad656cf41` |
| `MovieDuplicateMergeMySqlTest.java` | `83dcd38d228ffb89c2727789131f59631e32eb12517a2655ec843c937b72a20f` |

首轮完整 suite 来自旧冻结版本：`tmp/query-boundaries-mysql-984b184284b6/`，1461 tests / 0 failures / 1 error / 1 既有 Redis skip，BUILD FAILURE。唯一 error 是 `bindingPreservesAlreadyBoundTargetAndRequiresExplicitSameTelevisionSeason` 在 `plannedTmdbBinding:265` 缺季时的 nullable Long 三元拆箱 NPE。旧 merge SHA `b5a4c2fbc6f36a4d189c7e7b3e0643496bab302c8c493ddbc66edae5e2be4a1e`；当前已将 `?0L:number(...)` 改为 `?Long.valueOf(0):number(...)`。原失败目录与日志保留；最终版本已通过以下新 suite；原失败不改写。

2026-11-11 00:19:09–00:26:23，最终冻结版本完成[完整 suite](suite-final.json)：1462 tests / 1461 passed / 0 failures / 0 errors / 1 既有 Redis skip。`MovieDuplicateMergeMySqlTest` 真实隔离 MySQL 30/30，`MovieDuplicateMergeServiceTest` 7/7，consumer `TmdbMetadataSyncServiceImplTest` 9/9；required_sql_verified=true、compile_verified=true、exit=0。MySQL fixture 为 network=none，Maven 加入该隔离 fixture 的网络 namespace；结束时两容器 removed=true，production_changed=false。417 份源码 manifest 与当前仓库逐项 SHA 完全相同。

本轮仅 5 个 backend 文件的代码提交为 `65fd377de0f055c6377c623e763d245e991241f8`。固定源码构建复用完整 suite，`testsRerun=false`；417 份源码、441 份类/资源、固定 image/JAR 及实际运行产物一致，见[构建回执](backend-build.json)。

## 百度只读准备与待验

13:26:01 的[只读准备](baidu-readonly-teahouse.json)对茶馆 identity50/M9m6 的 1080P/4K 磁力文本和配对种子共 4 文件、33,254 B 完成真实来源 GET、原始 info 字节 BTIH/配对磁力/size/SHA 核验。霸王别姬当时被旧 helper 的 100 条来源上限阻断，原失败保留；13:30:33 [独立选择](baidu-readonly-first-selection.json)确认 146 条来源并选中四文件，仅改只读库存上限 100→500，未改生产代码。

13:40:57 的[首48完整文件核验](baidu-readonly-first-all-files.json)完成 identity48/x3xX 霸王别姬四文件、143,163 B 的原始 torrent infoHash、配对磁力、size/SHA/MD5 核验。[固定清单](baidu-first48-final-manifest.json) SHA 为 `07069079bff2d774b242f5a66c3f2e8f4da3e78727c00345831dc63d279a447b`；预期精确目录是 `/GYing Resource Hub/mv/霸王别姬（x3xX）/磁力种子`。文件内容、来源 URL 和凭据未落盘；实际只有来源/torrent GET，没有上传、分享或归档 POST。

`E:/gying-tools/releases/baidu-workflow-continuation-20261111/plan.json` SHA 为 `666d2cf7ae8ddeed5fc77e5d280c74c413a7b0abadfdcfbaca788ad4e2ddfa22`；13:56:19 的[sealed 计划](baidu-sealed-plan.json) SHA 为 `4842b6a0895d38f01071fc42c890cd98ea543d09e8a0b2a1e4890dc5fac5f15a`，状态 `PREPARATION_SEALED_NOT_AUTHORIZED`。[37 项离线测试](baidu-cycle-offline-tests.json)、[独立静态复核](baidu-cycle-static-review.json)、隔离编译与默认无 I/O 校验通过。sealed 不代表追加云授权或实际运行；数据阶段结束后仍需 fresh 加密 baseline 和绑定到具体范围的确认。

前三条新影片额度 3/3 已耗尽，父 17583 仍 PAUSED、关闭、10 分钟/1 条、rev63，17584/17606/17622 UNKNOWN 保持；真实小文件上传、自有永久分享和官方逐文件对账尚未执行。追加云验收范围待确认，不重放、不恢复调度，不把只读文件核验当作归档成功。13:34:06 的[隔离编译连续性](baidu-readonly-compiler-continuity.json)只证明该窗口 13 个现役/历史容器代次及当前 image/JAR 保持；旧 identity47 无资源与旧 helper 上限失败均保留历史。

17:12:06 [百度 fresh baseline 安全摘要](baidu-fresh-baseline-prepared-summary.json)为 FRESH_BASELINE_VERIFIED_ADDITIONAL_AUTHORIZATION_PENDING。仓库外原 `E:/gying-tools/releases/baidu-workflow-continuation-20261111/execution-ledger/baseline.json` 为 FRESH_BAIDU_BASELINE_VERIFIED，createdAt=`2026-11-11T09:12:06.588278+00:00`、11,300 B、SHA `24717bfee5ccd446609e8578cefded672fe9a082700087ba77d67d0aa60afee1`；plan SHA `666d2cf7ae8ddeed5fc77e5d280c74c413a7b0abadfdcfbaca788ad4e2ddfa22`、Cycle SHA `da752867e57b3c79c5ffa1b3ecc8e9a12da276de46a96720bf36a9e03caf7a47` 已核。父 17583 rev63/settingsRevision 保持 PAUSED、enabled=false、10 分钟/1 条、cursor46/max18820、processed5/skipped5/succeeded0/failed0/deferred3；17584/17606/17622 均 UNKNOWN、attempts1。完整加密 preimage authenticatedExactContent=true/plaintextWritten=false，保护百度 task5/transfer1/sys_config89/identity5/movie5/resource9 完整行 count/SHA 和 13 容器/配置/host/daily hash；仓库仅保存安全摘要，原 baseline/runtime 与加密材料仍在仓库外。此次直接 SQL 写/云写 0，既有 ADMIN 只读认证可能正常更新 last_seen；没有归档、重放 UNKNOWN 或恢复自然调度。

追加问题已仅向用户发送一次，当前尚无回答，不能当作批准：拟追加 identity47–50 四个手动候选槽位、最多一次实际归档，成功官方对账后才恢复 enabled=true/10 分钟1条并观察自然一轮。fresh baseline 完成只消除准备缺口；用户授权、实际归档/对账及自然一轮仍未执行，旧 3/3 额度用完及暂停/UNKNOWN 保持。

## 原字节证据与边界

以下文件经结构和值检查后原字节保存，未包含凭据值、完整业务行、provider 正文、原 inspect、SQL 或解密归档。身份/关联完整 preimage、认证材料与失败原日志留在仓库外；路径、计数、hash 和测试结果不能替代真实云副作用验收。

| 原字节文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| [EntrySsrCompletion.py](EntrySsrCompletion.py) | 14033 | `4561ba6cd314e2b3dfe39597649ee4b652b7d57d4a5998702f31bee86ef32f05` |
| [backend-build.json](backend-build.json) | 2203 | `b8f7c31a838b2846ae711aecada4f4a1a08bf04d68f710c5c7206bb4ab9eeb9d` |
| [baidu-cycle-offline-tests.json](baidu-cycle-offline-tests.json) | 5351 | `86cb0581c77b129b311f34cdfa9025b1db2d6ce5972845f0c24ff1945fcbae22` |
| [baidu-cycle-static-review.json](baidu-cycle-static-review.json) | 5997 | `81e0755abbc2808ff195438b5dc7359e0ffc8b942be94af8ceb98af0eae366fa` |
| [baidu-first48-final-manifest.json](baidu-first48-final-manifest.json) | 2586 | `07069079bff2d774b242f5a66c3f2e8f4da3e78727c00345831dc63d279a447b` |
| [baidu-fresh-baseline-prepared-summary.json](baidu-fresh-baseline-prepared-summary.json) | 5673 | `e86e978e11e3eff1a910999942a253c7b939dc9de57abbfd77bdb74a3c9f0578` |
| [baidu-readonly-compiler-continuity.json](baidu-readonly-compiler-continuity.json) | 9868 | `847b03cec67213b4fb6df047369fd0d185804eaec704b989a71a5df1acc868b5` |
| [baidu-readonly-first-all-files.json](baidu-readonly-first-all-files.json) | 6381 | `078384920cc6df497f4590cadb74ede4bb1d31739578e3e4573b1c41140ce014` |
| [baidu-readonly-first-selection.json](baidu-readonly-first-selection.json) | 5633 | `3dbf103e1ef9dfa7266fb3939167392c53133fcd3426b0b5c107eab50bf1101a` |
| [baidu-readonly-teahouse.json](baidu-readonly-teahouse.json) | 15178 | `0e6530b5cd003c4dc4e3cbe7a8cda998f698ff1be8bf5ae6650cf8b6566477d0` |
| [baidu-sealed-plan.json](baidu-sealed-plan.json) | 2904 | `4842b6a0895d38f01071fc42c890cd98ea543d09e8a0b2a1e4890dc5fac5f15a` |
| [batch-1-blocked-retention-current.json](batch-1-blocked-retention-current.json) | 1623 | `920c6010dab156e6f4c4f918f0f9b97e1a4f3f2b7fab234bb48a65aeb8ddb368` |
| [batch-1-blocked-retention.json](batch-1-blocked-retention.json) | 1709 | `fbeda3f2964ec592dc4849553318bea23465804d1c76a918af7eead7044e481a` |
| [batch-1-final-verification.json](batch-1-final-verification.json) | 1560 | `b5c03c78ca7eaf562451700a67b70b06463a6a1d26e1bbb1f020619282f98ec3` |
| [batch-1-original-failure.json](batch-1-original-failure.json) | 373 | `7e428c122573adbd3583d2adbdecfa95e2c07df2d649f788b9e420757b642ee0` |
| [batch-1-v2-presend-failure.json](batch-1-v2-presend-failure.json) | 456 | `5c1165874e384675fe373283f59629b4506c4c67a8888e00f7da2fb006c7f640` |
| [batch-1-v3-postmerge-verification-failure.json](batch-1-v3-postmerge-verification-failure.json) | 475 | `c558d294874f48dbe296a9a1cfb4ab0e8dcb1e66ee3ad89c77b0fe17700513fa` |
| [batch-2-original-presend-failure.json](batch-2-original-presend-failure.json) | 406 | `dc45833176720358a91a4ca06abdbddce23e9646aa5ca8adb8af440b37f0129b` |
| [batch-2-split-201-final-verification.json](batch-2-split-201-final-verification.json) | 2779 | `b9bb33d90ebfcaacf8ad63aacc912893a7bed449491a198c813fb8b458aa85fa` |
| [batch-2-split-202-final-verification.json](batch-2-split-202-final-verification.json) | 2778 | `5b0fe3a72d1c82f996b3995dec17d1601c6685ae598e50c3263a8979361dfb0d` |
| [batch-2-split-v7-plan-independent-review.json](batch-2-split-v7-plan-independent-review.json) | 3158 | `1a2f8927f92c665e614660667865653c60d5b473c0f20f94bf8d4c52e2e196d4` |
| [batch-2-split-v7-plan.json](batch-2-split-v7-plan.json) | 34847 | `61c351ddc6dd384caf2998a28c280b65a633edc8b1e6161dd3437040c295f889` |
| [batch-2-split-v7-preparation.json](batch-2-split-v7-preparation.json) | 1623 | `38de6438b959b26ba6dc2751dda16ff96bed22e74c073d970c58f5a266ccacca` |
| [batch-2-unused-expired-observation.json](batch-2-unused-expired-observation.json) | 3024 | `dfe60a595c51553d3d67857ce89588a13438c40d67a2d583b0f04f246c17c9d3` |
| [batch-3-blocked-inputs-retention.json](batch-3-blocked-inputs-retention.json) | 1167 | `a48ff3d687f3c490dc62a0a3e67323d2674a03ffda89ab4fdbac9e6c052e4860` |
| [batch-3-blocked-retention.json](batch-3-blocked-retention.json) | 1908 | `f5c04e57f8147ba5430569fc366a010c64c3e398e51f758464b6281597d7f481` |
| [batch-3-final-verification.json](batch-3-final-verification.json) | 1884 | `0dd344a82a7e747077c781bc7621ce5ef131a28c6fdd4702958a5d4ef0399aed` |
| [batch-4-final-verification.json](batch-4-final-verification.json) | 1883 | `0ca74e509714af1c9af34b72f57d83febeabb59b5c6b59785b8baceb84729af6` |
| [batch-helper-v2-binding.json](batch-helper-v2-binding.json) | 5210 | `edb8540f998a22eac3ca8723f1f949b14ace6fe87f2ee3d048920c638d63dfab` |
| [batch-helper-v2-tests.json](batch-helper-v2-tests.json) | 846 | `341edde44851afd1461dc6e21eb5673915343589e3a11462e338a88a447d4f4d` |
| [batch-helper-v3-binding.json](batch-helper-v3-binding.json) | 3344 | `379d2d90dd78761c885b886312d14457aa33fac834bde356d0396524af68f5c9` |
| [batch-helper-v3-derivation.json](batch-helper-v3-derivation.json) | 10581 | `9d8a5f5e9a1c3d5c39c3bc129c8ee269e1f62ea2430a63dd05cb2fbc0a7118be` |
| [batch-helper-v3-independent-review.json](batch-helper-v3-independent-review.json) | 4429 | `896cee0263e37b99395de8564553376196baf055a1d99696e53cc16c042c477b` |
| [batch-helper-v3-offline-review.json](batch-helper-v3-offline-review.json) | 3754 | `79121f0cbe89cb89ad10c4c0e23617f23d130e3eec388a29a0fffeb058bf3719` |
| [batch-helper-v3-tests.json](batch-helper-v3-tests.json) | 1271 | `054ab5f510e9bd7d5357d6db46108e0af6d45fd50ee3cb46905300fe2b12aafa` |
| [batch-helper-v4-binding.json](batch-helper-v4-binding.json) | 13409 | `de37382a454a06b5d2b24b62b0b95d3406405f40571e1c750045f26b2bebdb33` |
| [batch-helper-v4-derivation.json](batch-helper-v4-derivation.json) | 15037 | `e53b5ea08d1746678c5562d60abe5cddf1fb3dfcbb921615fc2273b7166b62b4` |
| [batch-helper-v4-independent-review.json](batch-helper-v4-independent-review.json) | 3247 | `81b642e82654a1a48d9cb92380f4016e61f08f98e9cae70c55f862e791cb2c5a` |
| [batch-helper-v4-offline-review.json](batch-helper-v4-offline-review.json) | 13741 | `ea24dcae9dbd884d5ad64bada08840be6bc8b247cc8a335a70f901c6caf48ad9` |
| [batch-helper-v4-schema.json](batch-helper-v4-schema.json) | 8892 | `58e16e67b3973eb9b00dc68a0f808c8f7c5ea9f4c4835b2c6a1e15b010474ce1` |
| [batch-helper-v4-stored-snapshot-verification.json](batch-helper-v4-stored-snapshot-verification.json) | 3390 | `b841356607ba21024735f8df0755246e9f07be2fd6b05ca01ad46a43318f89b7` |
| [batch-helper-v4-tests.json](batch-helper-v4-tests.json) | 1051 | `08fda28cf0fa6e77189df4efa2eb61686ff5dbbf94da04d9b232dd97ee1314a6` |
| [batch-helper-v5-binding.json](batch-helper-v5-binding.json) | 6816 | `0f96f542a7a21575498e2cecb36aa9c50afcf1a358392f92d5190278f6563297` |
| [batch-helper-v5-derivation.json](batch-helper-v5-derivation.json) | 7154 | `e9218c5ff907538888e279c76ea6ce2c162aebfb03381f1aae9515a54b6c3f80` |
| [batch-helper-v5-expiry-clarification.json](batch-helper-v5-expiry-clarification.json) | 1056 | `308e5b0e797eba9748b2ee64cc93432e63dd849a0862313a99dda95615e45fae` |
| [batch-helper-v5-independent-review.json](batch-helper-v5-independent-review.json) | 2403 | `068ba30c7524a4c6fec496ad3e7673dbe015a303209bd99dde9b8d0abaaf0d1b` |
| [batch-helper-v5-offline-review.json](batch-helper-v5-offline-review.json) | 2846 | `21e6c250c9bcc7cde00d85a2f5d39e17bbff63440edae60ebce69cb44ca36dcc` |
| [batch-helper-v5-tests-pass.json](batch-helper-v5-tests-pass.json) | 1161 | `62e4a1f4a45dde45daa817e59f524a41af7f0da0151cf1aff3b15beef87768ad` |
| [batch-helper-v5-tests.json](batch-helper-v5-tests.json) | 1162 | `879bbc42bb9fcf77ac1baf0e6119d017b4feb48de65cf630bc9bea0e5e4357b3` |
| [batch-helper-v6-binding.json](batch-helper-v6-binding.json) | 2158 | `5406cf761032ef90b42add9a1257d15ac1b97631bef3cf5bbda20b625e2b9573` |
| [batch-helper-v6-derivation.json](batch-helper-v6-derivation.json) | 2402 | `fe537bdb2fb37b16e5e89dcccb6f9ce529026b31506b3fabd29ff753e6e6cb15` |
| [batch-helper-v6-independent-review.json](batch-helper-v6-independent-review.json) | 2518 | `de674478771b56b48de2d9ab3e5caa31cde7259fe2b37fb1982322a819dd9e6c` |
| [batch-helper-v6-offline-review.json](batch-helper-v6-offline-review.json) | 2708 | `e3625079180c3d93a68c5d9c65969d91ea80b945ef3603e637b3c71f4a9e85b7` |
| [batch-helper-v6-tests.json](batch-helper-v6-tests.json) | 1255 | `b03ea716ccc6d73ed72e49d7c2c6c4ab1c2f569eff3ecfc2fb02524fab0ce1ca` |
| [batch-helper-v7-binding.json](batch-helper-v7-binding.json) | 4016 | `3d6dc081547fcc0694aee8f658c71afe1d8f0f4a1479b43093e29efb74bda00e` |
| [batch-helper-v7-derivation.json](batch-helper-v7-derivation.json) | 5496 | `80abaf8b0f38edc45dc6d8846f8feae93d42ecfca733c167252886cc9d78da1b` |
| [batch-helper-v7-independent-review.json](batch-helper-v7-independent-review.json) | 4731 | `f0072e2eebb3b9e71f7a0cf0758c7f6282056266dba53c4596fd600a76204b59` |
| [batch-helper-v7-offline-review.json](batch-helper-v7-offline-review.json) | 4219 | `9beb0a5404d91afa7f123eb6505806e42436d84fc041f8796e44c13146935523` |
| [batch-helper-v7-tests-final.json](batch-helper-v7-tests-final.json) | 2055 | `4b62fe2b6af11cb6ce0f90f8e2a20c697469fd8e5ecdfa4b7740732f1dff6a53` |
| [bind-merged-v2-derivation.json](bind-merged-v2-derivation.json) | 1830 | `74653e645ab86e0d65111670084278c4381f2ea1a6fa751271c6c376fadafafe` |
| [bind-merged-v2-independent-review.json](bind-merged-v2-independent-review.json) | 3096 | `69335765c30261e8e64438f257a463397a4643e47a09e4f22aaf84cb99b07757` |
| [bind-merged-v2-offline-review.json](bind-merged-v2-offline-review.json) | 2495 | `4b75b567c7c72e3fdb9c86a79bccecb1130311c215188479611b5d3e070e061e` |
| [bind-merged-v2-readonly-schema-order.json](bind-merged-v2-readonly-schema-order.json) | 1922 | `81c9596e904199a524706d848b808671a78dc185cb90ab5839e77629a78887bd` |
| [bind-merged-v3-independent-review.json](bind-merged-v3-independent-review.json) | 3036 | `971f20cf2fbb14bd915e664e0d149ef0426d2ca06f35598dc6a37ec7845348b3` |
| [bind-merged-v3-offline-review-plan.json](bind-merged-v3-offline-review-plan.json) | 15667 | `6d258261dd4f769fa6f494654b2fef1a5017a7b0a25f0a05ba48f79f97df53b1` |
| [bind-merged-v4-independent-review.json](bind-merged-v4-independent-review.json) | 2711 | `bdb4214545c590c63b6d17e1e8bcea31f3366086aa7dea9922c32feb9dfc5bb8` |
| [blocked-inputs-v2-binding.json](blocked-inputs-v2-binding.json) | 1987 | `a2d2dbf5281736a2f0fa0c64a36497ef515891c37f852dc167c675ced66963a9` |
| [blocked-inputs-v2-independent-review.json](blocked-inputs-v2-independent-review.json) | 7138 | `3fd8d0e952c1590f85da6dc8fddf6182a0a124d06597d34822eb21ed71d0c6e7` |
| [blocked-inputs-v2-local-authentication.json](blocked-inputs-v2-local-authentication.json) | 4318 | `9bce911197cb4f628b0e2fe89cab2be95fafd0e44091f0b7429d86b06682d355` |
| [blocked-inputs-v2-offline-tests.json](blocked-inputs-v2-offline-tests.json) | 561 | `fa001f320d447a81b4cc7025d3875f6c7d4523793064db30a7386ead49f380c2` |
| [candidate-review.json](candidate-review.json) | 82498 | `5fb28364a03a93655edfa71334a9fcf65f4b72b6619b0cd6676dbb826932e63d` |
| [candidate-scan.json](candidate-scan.json) | 64004 | `ea30de01c6e83b73e16a84c52bd47a25f356d5b7700344d2a25e6b161d2c9914` |
| [deployment-acceptance.json](deployment-acceptance.json) | 20426 | `1fb299bcd31b47b757f6e1bb3b060a1bdb8e613f1d96802de1a167739995d3ff` |
| [deployment-final.json](deployment-final.json) | 5707 | `60faee4410c579789b522cf966be5be6f1b0d3b07aebbcb242d4299e3d65c913` |
| [entry-acceptance-v2-actual-stopped.json](entry-acceptance-v2-actual-stopped.json) | 34508 | `e9a889865c80b56585329bb2508917f9babd89df387ac28371246b96f50641df` |
| [entry-acceptance-v2-binding.json](entry-acceptance-v2-binding.json) | 1805 | `7447eeee0b7111d227b3f3dce6343acbdb2978e7b71cfc680e3d7162b5f5e012` |
| [entry-acceptance-v2-derivation.json](entry-acceptance-v2-derivation.json) | 2362 | `9302f0704cb887a43bd54000493f7992166e7c808f5dac4e3c28ff54db9ba039` |
| [entry-acceptance-v2-final-derivation-inputs.json](entry-acceptance-v2-final-derivation-inputs.json) | 2109 | `4c9b2bd7f87220e85c408935f306c3b38e42459e1f03106a17cf5363c14d9de1` |
| [entry-acceptance-v2-independent-review.json](entry-acceptance-v2-independent-review.json) | 2510 | `4804bf66116356d64b3b8f8f286be202d3b09facc2f2db8080a28921ffff5424` |
| [entry-acceptance-v2-offline-review.json](entry-acceptance-v2-offline-review.json) | 2881 | `c4d056294be69cecd6c4f56637e4982993fef758c439c8a1faecda3e4d16e7db` |
| [entry-acceptance-v2-tests-complete.json](entry-acceptance-v2-tests-complete.json) | 1396 | `4c686bb06c7ff72305315fdd53b251f642d5e78cbd6d678669a35d6d6dc0c966` |
| [entry-acceptance-v3-actual-stopped.json](entry-acceptance-v3-actual-stopped.json) | 190083 | `5250df4151fd79272cabefcc4238c2b52f6bae2ad9f7908adbea4371582c9d25` |
| [entry-acceptance-v3-binding.json](entry-acceptance-v3-binding.json) | 3417 | `515fdb2615a3cd7ced1b4afeabaa5c2c10f33509985932292bf5089e489e8305` |
| [entry-acceptance-v3-derivation.json](entry-acceptance-v3-derivation.json) | 5541 | `f93c208b7006fcdb9f537490d38d56ea55f2defce1529ba6bb4809088bde5eaf` |
| [entry-acceptance-v3-independent-review.json](entry-acceptance-v3-independent-review.json) | 4673 | `587823736c3d8bf30b48c70a16e65f008118402bf24d36fc6270720d8e613d1e` |
| [entry-acceptance-v3-independent-semantics-review.json](entry-acceptance-v3-independent-semantics-review.json) | 2936 | `e84959397019254116ffc19102bcee576982c88b198457769e7f78ea21d0f087` |
| [entry-acceptance-v3-offline-review.json](entry-acceptance-v3-offline-review.json) | 2955 | `12744c9ab3ff055e3a94abbb6cf74188f0d19f9157c614ccad1194eba001cd10` |
| [entry-acceptance-v3-tests-complete.json](entry-acceptance-v3-tests-complete.json) | 2353 | `c87167981cd939ce371775a241f9b10ea719d1266a6ce4a55d6f891f9cdd9348` |
| [entry-namespace-readonly-observation.json](entry-namespace-readonly-observation.json) | 374 | `8f40cc87a83891279c956861881df4d9297605e6af9277ab5cdd0938a80fcdaa` |
| [entry-season-readonly-observation.json](entry-season-readonly-observation.json) | 4170 | `70e13ffdab5923081ee98b3bd69c6aedb69d4dc5150e2ca1e94cb5bec1847479` |
| [entry-ssr-completion-actual-pass.json](entry-ssr-completion-actual-pass.json) | 26980 | `4a41acaaca4f05f4095c685b55d2059262f2cbe17638dd312bb73389e7bb7008` |
| [entry-ssr-completion-binding.json](entry-ssr-completion-binding.json) | 2117 | `404db6d1b3a75ed37d59f968bcd28f281e62a2e910523aa03e2b4996d3931218` |
| [entry-ssr-completion-derivation.json](entry-ssr-completion-derivation.json) | 3485 | `ddf4d356bfcd5e487c1a5bce7b029d9f5bbdfd13df54fad51e0593b0cad0ed04` |
| [entry-ssr-completion-flow-tests.json](entry-ssr-completion-flow-tests.json) | 1318 | `00f4287d73d5f98317e82cb2dcb2feba5c02ad1c5d118b8e12442703ee3bcd21` |
| [entry-ssr-completion-independent-review.json](entry-ssr-completion-independent-review.json) | 3141 | `d1f2001e53ec0ed6acdd0b011a05efd38be3b31d5fd164a60941c9f3f5b767a0` |
| [entry-ssr-completion-offline-review.json](entry-ssr-completion-offline-review.json) | 3309 | `3750fbbff501a62d238f88daab91e0467ec784a5d858ec48db33c71ab527e2ea` |
| [entry-ssr-completion-parser-tests.json](entry-ssr-completion-parser-tests.json) | 1395 | `e6baccf4f9f191601d2dce486efcb41faa0358cc36d1dba2bc96c7b6de62a1f9` |
| [entry-ssr-completion-tests-complete.json](entry-ssr-completion-tests-complete.json) | 1455 | `f4281d4beaeb092458373c113c10e0ef1240c41f77147f5d44c050f865f7c6a0` |
| [entry-ssr-header-readonly-observation.json](entry-ssr-header-readonly-observation.json) | 1744 | `3a04e6ea924672f5d135a0aa2561d27e59a96910dc5d41d9322c546a6bced2a6` |
| [mobland-application-privileges-original-readonly-stop.json](mobland-application-privileges-original-readonly-stop.json) | 316 | `ee861c06a6de9e45e80c6c4aa67419430284efedcbb3c923606b2096b86cebd4` |
| [mobland-application-privileges-v2.json](mobland-application-privileges-v2.json) | 15128 | `45331a57a2da17dabe163c25f85c35035e18637e1a34502f11077eaece6b445d` |
| [mobland-binding-original-prepare-failure.json](mobland-binding-original-prepare-failure.json) | 564 | `7d44a7953ff0efcc71bd822431af5a5f1edc65f14619c91d9f25ba611e5e2b03` |
| [mobland-binding-v2-apply-failure.json](mobland-binding-v2-apply-failure.json) | 969 | `df142f531c54a5be4f891f4313a8bd94833e67cb66b42abd9489b957069678db` |
| [mobland-binding-v2-not-applied-verification.json](mobland-binding-v2-not-applied-verification.json) | 372 | `743f4bfcd6e9409a9eb2fdbbf90ef379c3cd0f22bd04be81d94676d3a3b08041` |
| [mobland-binding-v3-apply-failure.json](mobland-binding-v3-apply-failure.json) | 621 | `233fe2e30515fb5d7446f8e3edbc012181ccc19848490f9f2811457cd49ee709` |
| [mobland-binding-v3-later-readonly-preflight.json](mobland-binding-v3-later-readonly-preflight.json) | 322 | `8ea7fb1d2eef15621464adfa0d0e7981bbde8a6c3f222ac70d38c61173e4c580` |
| [mobland-binding-v3-not-applied-verification.json](mobland-binding-v3-not-applied-verification.json) | 458 | `15f9d47fcfb11a0fb113879f06e0d0045d320d1fab3bf8aa69015dee4002742a` |
| [mobland-binding-v3-prepared-summary.json](mobland-binding-v3-prepared-summary.json) | 3055 | `ca0b81846d8562d285b0d49fe5d13bbd08bbb634add65940f1af4f4776932888` |
| [mobland-binding-v4-applied-verification.json](mobland-binding-v4-applied-verification.json) | 940 | `7e9996c1994d72e296573f85ad98b797be27b9d001765577c3ef8f217427ef22` |
| [mobland-binding-v4-committed-readonly-verification.json](mobland-binding-v4-committed-readonly-verification.json) | 453 | `7c7397a343e3faaf484ab379ac9ac803170cab2a3f3f0796dce6240e3678df2a` |
| [mobland-binding-v4-prepared-summary.json](mobland-binding-v4-prepared-summary.json) | 3989 | `d85cd239ae2a6cd21cf239c0fd8ce62823926420782d11b142794cc4eba56316` |
| [mobland-lock-privileges-initial-local-failure.json](mobland-lock-privileges-initial-local-failure.json) | 363 | `1993a2b7a2814297b6eb07ccf955cb8afe9687f65db548627d932d88712d2d63` |
| [mobland-maintenance-privileges.json](mobland-maintenance-privileges.json) | 14150 | `5967cd2fa956e291de92bc79724bfb500c902f955dfb9b088f069f7ea74c78bf` |
| [runtime-blocked-seven-summary.json](runtime-blocked-seven-summary.json) | 5718 | `530c357e5b9d7c8f7906d05eed3060a47280b88890800c5d912365c9e7c4fc61` |
| [suite-final.json](suite-final.json) | 21252 | `da88d8242d208bb3c5e06caee49da4449978a93ae53333bde699d975ccd873a1` |
| [unused-batch2-observer-binding.json](unused-batch2-observer-binding.json) | 7471 | `60ac7e9fb02f232b8d85c720f7951383bdc5db655a10049f0957a8aceccaf9e3` |
| [unused-batch2-observer-independent-review.json](unused-batch2-observer-independent-review.json) | 3270 | `b30601a0c0ca844879fa140cfdcc82deb77e4f31af8e8d457f99848be8564fb9` |
| [unused-batch2-observer-local-authentication.json](unused-batch2-observer-local-authentication.json) | 634 | `181052df8f350d3bc44c29c3363768397db7dbe191c138653b43c57cc528c048` |
| [unused-batch2-observer-offline-tests.json](unused-batch2-observer-offline-tests.json) | 558 | `29c51ab337e26c7189462f3283bbb9729132682c13918c1bcd5566cf866fc57a` |


当前待办：等待已发送的追加额度问题得到用户明确回答后，才可能按绑定范围执行百度真实归档、官方对账及条件满足后的自然一轮；当前未批准、未归档、未恢复调度，旧 3/3 和 UNKNOWN 不重放。fresh baseline 已完成并仅安全摘要入库。本轮 66 对新 merge、MobLand canonical 两字段首次 UPDATE/独立精确复验、67 详情/67 列表/六个代表 SSR 真实入口已完成。七条运行态阻塞与 71 对排除理由保持，旧 Entry V2/V3 STOP、原 helper/批次失败按时点保留；自然完整备份、安全门禁和群内出站等既有待验项没有被本轮代验。
