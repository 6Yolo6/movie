# 发布与恢复证据台账

本文汇总保留必要的部署范围、检查点、原始证据与验收限制。日期均为原观测时点；文档整理未重新部署、查询生产库或执行恢复。旧全文从[Git来源](README.md#git-history)读取，实际操作按[部署](../deployment.md)/[迁移](../../MIGRATION.md)执行。

<a id="resource-recovery-20261029"></a>
## 2026-10-29 17:55–18:13：后端内存与QQ/公网恢复

用户恢复上线授权。旧heap512MiB/容器768MiB出现Java heap OOM与公网片库504；先以原fa06…镜像增加内存并正常TERM恢复，再18:02替换为c4b8955/固定0b349813…镜像，768MiB heap/1280MiB容器。BM25与人物字典按快照懒加载/复用，不降目录上限、不恢复旧数据。742项测试712通过/30条件跳过，冷/暖/TTL内库、QQ生成回复、本机/公网数据和完整桌面/手机页面通过；PanSou及GYING纯搜索有结果。普通USER会话缺失，登录后资源job与真实群消息/转存未验。

检查点 `G:/gying-backups/backend-recovery-20261029T095350.591530Z` 四age hash/认证通过，旧镜像/有效覆盖/脚本与结果在 `E:/gying-tools/releases/ai-recovery-20261029-1750/`。仅后端替换/nginx重载，其他容器和数据/state/key保持。Agent最终01eab8…候选仍未上线，本窗口管理员UAC取消，旧33d9…继续运行，待系统确认重试；安全58/11/1未关闭。详见[报告](../evaluation/resource-recovery-20261029/README.md)。

<a id="restart-window-20261026"></a>
## 2026-10-26 单次重启失败排查与维护修复

10月25日20:00单次计划实际触发，20:00:02在window阶段失败（`Invalid bounded one-off window`），未停服务或创建维护journal；主机最后启动仍为9月24日21:51:41。绑定Python3.10.6无法解析安装器.NET roundtrip七位小数，真实permit和实际版本复现通过。原每周09:30:30结果1仍无完整原因，不能归因本次格式错误。

安装器改秒级+08:00窗口，写permit后、注册前以配置Python调用已安装runtime验证；失败保原回退。维护HTTP Request显式发送已实证的浏览器UA，避免Cloudflare默认urllib403/1010误判恢复失败。实际Python3.10.6维护42/42、PowerShell时间8/8及ops-readiness无FAIL；本机API、Tunnel ready、公网主页/API通过。证据与旧脚本回退在`E:/gying-tools/releases/restart-window-20261026/`，昨天过期permit/attempt保留。12:56回读新单次 `GYing One-off Graceful Restart 20261026-1305` Ready，13:05–13:15开始、14:05截止（Windows NextRunTime13:05:05）；15项验证全通过，10个原运行容器及主机启动时间不变。现役runtime SHA `aa7fb8b56658965fbbf5309c6f8ea852de47b448166f6951e62bec4a0c66e6b8`，原子回退/旧runtime/任务XML在 `G:/gying-tools/restart-20261018/oneoff-installation-20261026-125553/`。新permit未消费，实际重启与ASUS登录恢复仍待验，不以测试或注册代实际运行。

<a id="ai-availability-20261025"></a>
## 2026-10-25 17:19–17:26：AI可用性恢复

16:49四个本机服务均无监听，旧PID/ready残留，没有匹配正常停止确认。16:36 CodexSandboxService事件可能相关但完整消息不可读，唯一终止原因未确认。16:53 WMI独立恢复四服务，生产只读搜《星际穿越》7.53秒完成。另有复杂查询HTTP200/API500；有效片库冷读取15.05秒超过原10秒read上限。

代码`cd79797`仅修本机独立WMI隐藏启动、ServiceHost在Job中拒绝及read/call30秒（connect2秒、总体deadline保留）。91/91 Maven verify，6项合成Windows Job、9项配置、完整JAR15000隔离健康/握手/优雅退出通过。17:17首次公网403验收失败已平滑回退；17:19–17:20第二次使用浏览器标识验收成功。

| 材料 | 原记录 |
| --- | --- |
| 当前Agent JAR | `33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`，对应hash不可变运行副本 |
| Agent检查点 | `G:/gying-backups/ai-agent-20261025T091941.886931Z`；旧JAR、私有state/key/token/config、旧启动器三份age，hash/完整认证解密通过 |
| 修复证据 | `E:/gying-tools/releases/ai-assistant-unavailable-20261025/`，失败/第二次部署报告及真实查询分开保留 |
| 验收 | 条件27.98秒、语义62.50秒、Bing17.42秒、生产只读队列7.52秒；9入口符合200/401/404预期，17:19:45–17:26:18 ERROR/Exception=0 |
| 保持与限制 | Movie容器/其他本机实例/配置及索引hash保持；不重启电脑、未登录网站复验、未转存/发消息/发帖；短窗口非长期可用性结论 |

条件查询额外排除“比利·克里斯托”、包含2010年；相似查询额外排除“雷金纳德·丹尼”。实际BGE-M3返回100候选，最终保留《E.T.外星人》；Bing《莫扎特传》原始2条/同名TV排除1/IMDb保留1，未逐页核实剧情。人物/年份偏差未掩盖，completed不等于全部正确。

<a id="ai-query-20261025"></a>
## 2026-10-25 16:00–16:01：结构化查询与语义索引

原网站88轮中78走直接业务分流，仅10到模型，4失败；单个“推荐”、人物否定、缺历史/空结果升级与1.7B理解共同影响。旧searchMovieWeb实际调用为0，不能把百科联网当成已做全网搜索。原30题/10,432快照冻结，不据逐题回答调规则。

Movie业务代码`f209e87`/Agent`8a65287`；Movie backend镜像`sha256:fa06d609e0d04ecaf790f0965ba7b0a4ecfe5030e2b7a25f60b1814e4227221a`，frontend`sha256:9b4eb2377a1ce3608b9f501c6ef8c4c8394e086684ae3c931486d5f7838ae177`。当时Agent SHA`b7bd517cbe6f7c5b8cdf01ce0d8872460958fc8b108653d7c8882fa74ff7f399`，已由上节补丁替换。

仅backend/frontend/本机Agent上线。首次15:51–15:53装配失败已回退，补MovieSearchTools构造器标记、87项verify和完整JAR验证后第二次成功。23入口、95静态、3依赖与四组真实游客页面通过；登录条件查询未复验。检查点`G:/gying-backups/20261025T080048.799654Z`的15份age hash/完整认证通过；覆盖、旧镜像/包及失败报告在`E:/gying-tools/releases/ai-query-plan-v2-20261025/`。

生效索引11,272条、SHA`f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4`、modelDigest`7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`。原11,220开发对照/provenance和10,432留出快照不改写。构建器100项分页/1秒节奏/有界Retry-After行为9项通过，定期增量调度未登记。

两组同一11,220快照的开发对照各8轮completed，包含海上场景明显误召回；不是8/8答对或独立盲评。关键词11.8–39.8秒/中位22.6秒、混合12.9–56.6秒/中位21.8秒仅一次小样本，含加载/排队/动态搜索。可交互对照原文件`E:/gying-tools/releases/ai-query-plan-v2-20261025/comparison.html`保留。

小模型调查基于当时官方材料与实际1.7B/普通4B/指令4B开发对照；最终部署的是Qwen3指令4B，不是本项目训练或蒸馏。替代候选和硬件兼容边界在原Git报告，文档整理没有重新调查最新模型。

<a id="ai-reliability-20261023"></a>
## 2026-10-23 12:43–12:54：加密任务与回执启用

仅Movie/backend与本机Agent替换，代码`3052f2c`/`e11c631`。两套独立state/key启用，45秒可续期lease与幂等回执生效；16真实入口、新包模型8/8程序检查、2条终态跨平滑重启state/attempt不变。本轮没有生产崩溃注入，在途回执、工具中间步骤与真实网站用户恢复未代验。

检查点`G:/gying-backups/20261023T044352.387300Z`、`G:/gying-backups/20261023T045302.843250Z`各14份age认证，含SQL、两个发布状态卷、AI state/key/配置与旧包。恢复范围是应用回退，不是全栈/异机灾备。材料`E:/gying-tools/releases/ai-reliability-20261023/`；回退保留新state/key及来源二进制排序规则，不恢复旧SQL。

<a id="manual-completion-20261023"></a>
## 2026-10-23 21:22–21:25：手动资源补齐与Node升级

Movie代码`2b1809b`仅backend/frontend部署，ensure和按id修复共用双盘有效性判断/原位修复/P2P同步。有效覆盖、旧镜像在`E:/gying-tools/releases/gying-manual-completion-20261023/`，检查点`G:/gying-backups/20261023T132303.117468Z`的11份age认证；26入口、3依赖、线上JAR/95静态与三组模拟API页面通过，未真实转存/发布。机器摘要保留在[evaluation](../evaluation/gying-manual-completion-20261023/validation-summary.json)。

本机Node20.17.0升级24.22.1，npm11.19.1，旧版本保留；生产frontend容器Node补至22.23.4。包hash核对是当时证据；本整理未升级运行时或重新核查外部公告。

<a id="earlier-releases"></a>
## 更早的必要来源

| 日期/变更 | 原证据及恢复材料 | 必须保留的边界 |
| --- | --- | --- |
| 10月22独立采集进度/资源链接 | `E:/gying-tools/releases/crawl-resource-links-20261022/`；`G:/gying-backups/20261022T132212.039731Z`，9份age | 元数据范围/游标保留，链接协议校验；mock页面不代真实转存 |
| 10月22 AI资料工具 | `E:/gying-tools/releases/ai-research-20261022/`；`G:/gying-backups/20261022T053706.234649Z`，7份age | 原Wikipedia工具与摘录边界；现役联网另见结构化研究 |
| 10月8来源身份/P2P恢复 | `E:/gying-tools/releases/p2p-workflow-recovery-20261008/`；代码457673a/8182cda，旧镜像见[P2P手册](../p2p-cloud-archive.md) | utf8mb4_bin迁移2983行保持，双盘任务4305/4306成功；58耗尽失败不重置，自然失败隔离未实证 |
| 10月21实现/22部署特征与BM25 | `E:/gying-tools/releases/catalog-features-search-20261021/`；`G:/gying-backups/20261021T160733.115483Z`，7份age | 检查点曾核25表；40合成题与2000片计算基准非独立泛化/线上CTR；[原始JSON](../evaluation/catalog-20261021/validation-summary.json)保持 |
| 10月19/20桥接与标题分页 | `E:/gying-tools/releases/ai-assistant-20261019/`；`G:/gying-backups/ai-assistant-config-20261019-232528`，4份age；旧Agent `G:/local-ai-agent/backups/ai-catalog-20261019/before.jar` | 后端/前端/桥接与OpenClaw独立接线，配置检查点不是一致性DB灾备 |
| 10月19资源/P2P隔离 | 原 `docs/resource-search-p2p-deployment-20261019.md`，按[Git索引](README.md)追溯 | 账号 `/dbt/` 不公开；保留磁力与种子归档，不恢复NapCat |
| 10月14访问身份与查询边界 | `E:/gying-tools/releases/access-identity-20261014/`；`G:/gying-backups/20261014T125228.430856Z`，8份age | 先INSTANT追加client_ip/user_agent再切换，80467旧行保持NULL；回退不DROP列；可信代理与伪造头边界保留 |
| 10月11监控/分类计数/发布认领/手机适配 | 原 `docs/code-optimization-deployment-20261011.md`、`docs/monitoring-resource-counts-20261011.md` | 条件认领/UNKNOWN、统计口径、UI竞态提炼入当前手册；完整镜像/检查点按Git原文，不套用旧命令 |
| 10月13 Access启用 | `G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d` | verify只读、rollback须核精确归属；本人OTP/角色/origin JWT另验，[现行流程](../security/cloudflare-security.md) |
| 9月25完整加密恢复样本 | `G:/gying-backups/20260925T030328.719395Z`，19份age约372MiB | 25表行数/CHECKSUM与MinIO隔离单图通过；系统账号/异机/离线私钥未闭环 |
| 9月14旧迁移 | `migration-data/20260914-081539`，4832文件hash核对；旧回退`E:/gying-data/gying-pre-deploy-20260914.sql` | 1631影片/2165资源仅历史容量，DPAPI限定原机器账户；按现行迁移替代旧明文导出 |

上述外部路径是原记录位置，本轮没有逐一重新验证文件存在性或恢复可用性。安全10月23为59/11/0、10月25为58/11/1；G01–G11未关闭，UNKNOWN为退出旧迅雷探针。历史业务例外批准不能代替后续安全门禁。
