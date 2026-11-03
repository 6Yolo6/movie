# 当前项目状态

更新时间：2026-11-03

11月3日23:04:50 QQ电影目录识别修复仅backend上线6bee2a1d…/代码d24e08f，保留21:47查询改进；940项939过/1 Redis跳过、真实SQL/compile及35项部署回归通过。23:06仅原失败2177重试成功，正确第二部自有分享/资源1579 NORMAL/QQ LIBRARY_RESOURCE含新分享通过；2176未重试、未发群消息。最终五age与两日常覆盖验证通过；此前公网传输失败回退已实证、原失败保留，安全58/11/1未关闭。[修复与验收](evaluation/qq-prada-transfer-20261103/README.md)。

Asia/Shanghai。11月3日21:12:14 Agent055f…/15a2b8a、21:47:47 Movie固定f2b7361b…/JAR1b8a09d5…/13660c9按用户“可部署”限定授权上线；固定349文件来源、自然空闲、正常停止、三/五份age及补充助手认证、实际配置及其他容器保持通过，日常备份新覆盖apply/dry-run通过。最终Movie902项872过/30环境跳过、Agent173/173与compile；原15轮未重跑，模型满足5/部分2/澄清2/未满足4/缺证2，三维完整12、真人0。新增网站4提交/4机械通过/0重试，首轮身份/矛盾澄清通过，模型满足2/未满足1/缺证1、真人0；test禁用/有效设备0/401/密码USER不变/临时凭据删除通过。本机/经既有代理公网14入口分次通过，UA1010及URLError原失败保留，非浏览器验收。音乐电影身份和原主题错判继续保留，第一阶段未完成；安全58/11/1未关闭。[查询修复与上线](evaluation/query-quality-20261103/README.md)。11月3日早先正文/卡片候选完成：Agent143/143与compile、前端隔离构建/固定镜像及中英/明暗/390与1280八组40卡片通过；最终四问4 completed，模型复核满足3/部分有用1，真人0。原声未核验；另完成现役网站USER15/15轮，机械15通过/0失败，非密码登录/非浏览器验收，模型诊断满足3/部分1/澄清2/未满足5/缺证4，三维完整13、真人0。授权临时旧test已恢复disabled/有效设备0/本轮会话401，密码与USER角色不变；安全本次58 PASS/11 FAIL/1 UNKNOWN，G01–G11未关闭，当时生产替换停止，后续查询双后端上线另记。[本轮候选与知识](evaluation/answer-quality-20261103/README.md)。11月2日22:56 QQ搜索错误回退修复已上线OpenClaw：发送失败后结束插件处理，避免误入缺key的AI分支；17项回归、实际插件13场景/白名单/UTF8及真实helper搜索通过，群内端到端仍待实际使用观察。[修复证据](evaluation/qq-search-error-20261102/README.md)。11月2日微博真实登录有效但账号发博权限需平台身份验证，历史12条当前4准备失败/8待发布；TMDB高分剧集范围360–500超过实测131页，19:08空跑修复已上线，保留范围并按24小时复查；19:15:57自然首轮已记录EMPTY_RANGE，11月3日19:15:59复查，高分电影原next252保持。[空跑修复](evaluation/tmdb-empty-range-20261102/README.md)。迅雷旧token自然过期已用环境变量在独立Edge一次登录恢复；18:18已启用两小时独立浏览器续期，原任务exit0、官方200/同账户/600通过，后续自然失效与验证码仍按实际运行观察。[本轮核查](evaluation/platform-diagnosis-20261102/README.md)。10月31日已核对82条历史未满足回答的原因并落实共性改进；已保留三轮隔离15轮观测：首轮无完全满足；第二轮14条答案复核为满足4/未满足4/证据不足4/有效澄清2，另1次500；最终轮13条正常答案模型复核为满足3/有效澄清2/未满足4/证据不足4，另2次联网500且质量三维null。10月31日22:58已上线Movie backend；11月1日17:12最终Agent候选1f9d…/代码e19b6b16上线，管理员身份、自然空闲、三份age认证、旧9516正常停止、新7700/instancea9366577…独立运行和root绑定复核通过。四条实际回答均HTTP/API200 completed、程序0失败，总耗时39.0秒；第1与第4正文未列片名，第4原始对白仍未核验，模型复核为满足2/未满足1/有用但不完全1，有效澄清0、真人新增0，不能计四条全部满足。当时网站USER15轮未执行。10月29日23:34上线GYING六目录独立暂停，现有范围/选择/游标保持；后续1280/390浏览器视口与公网片目数据经现有代理验收按原UTC证据记录；当前可见海报通过、全页图片覆盖未计完整通过；本机Edge直连fake-IP超时单独待处理，日常备份已纳入新覆盖。10月29日用户恢复上线授权，17:55恢复后端、18:02部署片库缓存修复及768MiB heap/1280MiB容器；18:13复验QQ回复、公网数据与完整页面通过。本机Agent20:56–20:57通过管理员确认上线最终候选，21:00两条真实查询的类别/评分来源否定条件通过，评分排序和剧情类型仍有遗漏。离线质量复核/开发runner已完成，整体质量提升尚未实证。10月28日18:21手动恢复完成，18:22–18:29本机/公网及真实AI复验：主机已于10月27日20:03:15重启，原登录恢复因Ollama端口被Windows保留而失败。现已迁至loopback21434，维护recovered、Worker还原true；其余历史事实保留观测日期。

[知识导读](project-guide.md) · [AI架构](ai-architecture.md) · [求职路线](ai-career-roadmap.md) · [发布证据](history/releases.md)

## 当前目标

维护统一片库、自有资源和可追溯采集/归档/发布；提供网页/QQ检索、可解释推荐与依据明确的AI回答。工程知识、实现和验证入口由知识导读与专项手册维护。

## 运行架构

| 层 | 当前接线与约束 |
| --- | --- |
| 公网 | `gyinghub.dpdns.org` → Cloudflare Tunnel → `127.0.0.1:80` nginx → Next.js frontend / Spring backend |
| 业务 | Java17 backend、Next.js/React网页、Python gying-source、Node social-publisher |
| AI | 网页业务分流/加密队列 → 本机Agent5000 → Ollama21434；Router5002、本机Vue3000仅本机入口 |
| 数据 | Windows MySQL、Redis、MinIO；PanSou、quark-auto-save、独立OpenClaw与网盘任务 |
| 主机 | 仓库 `D:/gying-movie/movie`；Agent `D:/ai/local-ai-agent`；Docker数据 `G:/dockerdesktop/wsl/DockerDesktopWSL`；私有配置在仓库外 |
| 运维 | 显式 `-p gying-movie -f docker-compose.prod.yml` 加现役审查覆盖；MySQL/JVM/容器时区一起核验 |

NapCat已停用，旧secondary/qq-v3频道目标保留为enabled=0。QQBot每日推荐、原腾讯频道CLI与social-publisher分别维护。Tunnel为HTTP/2、原 `GYing Cloudflare Tunnel` 任务及 `E:/gying-tools/cloudflared/config.yml`，10月14已部署 `GYing-Tunnel` 代理→DIRECT兜底；ready为loopback20241。[排障](../.agents/skills/gying-project-ops/references/troubleshooting.md)解释旧Docker转发/fake-IP事故，不能直接改客户端生成配置。

## 已具备能力

| 领域 | 已实现/部署能力 | 手册 |
| --- | --- | --- |
| 片库和页面 | 分类分页、收藏/评论/通知、资源审核与软删除、国际化/主题/响应式、详情相关作品和新标签页 | [API](api.md)、[知识导读](project-guide.md) |
| AI检索 | 明确片名/泛推荐直返；复杂Schema计划、原文人物核对、BM25+BGE-M3/RRF、指令4B、真实Bing轨迹与服务端多轮候选 | [架构](ai-architecture.md)、[评测](ai-evaluation.md) |
| 推荐 | 收藏+衰减浏览、稀疏内容特征/共同收藏/评分热度/多样性与理由，暂无点击收益结论 | [架构](ai-architecture.md#5-推荐如何利用用户行为) |
| 可靠性 | 加密任务/回执、requestKey、45秒可续期lease、有限attempt/取消/幂等；本机独立后台启动与30秒有界片库读取 | [恢复契约](ai-architecture.md#7-租约回执与恢复) |
| 采集和治理 | TMDB/GYING独立范围/页内游标、GYING六目录独立暂停；TMDB实测末页/空范围24小时复查/其他榜轮转；范围调整保进度、失败项目隔离、季/合集消歧 | [Resource Hub](resource-hub.md) |
| 网盘与P2P | 三个独立GYING周榜、手动补齐/按id修复、双盘原位分享与有效性判断；P2P小文件归档，账号/dbt隔离 | [周榜](metadata-weekly-transfer.md)、[P2P](p2p-cloud-archive.md) |
| 账号与监控 | 注册/邀请、昵称/邮箱登录、验证码与设备吊销、原IP/UA、API日志和分类资源计数 | [API](api.md)、[数据库](database.md) |
| 主机维护 | 迅雷独立Edge自动续期/同账户验证/非root原子写回；每周优雅重启和登录恢复任务已注册 | [迅雷](xunlei-token-sync.md)、[重启](weekly-graceful-restart.md) |

资源绑定使用bindingVersion，更新同组/新增绑定但不删除取消勾选的历史；来源身份区分canonical与外部id，保留utf8mb4_bin。自动视频资源先验证自有分享再入库，不确定匹配待审。高频Worker只处理元数据，不消费旧视频/发布队列；旧夸克追更与三个周榜分开。

## 当前部署与恢复基线

| 服务/产物 | 当前记录 |
| --- | --- |
| Movie backend | `sha256:6bee2a1d65a7114c05dcffed559bab9bbf1791751b855ba5c44a75ef2dcd471e`，代码d24e08f/完整源绑定065b7ce、实际JARf805f065…；11月3日23:04:50夸克电影目录AI兜底上线，保留21:47查询条件/可信历史及原TMDB/微博/QQ/GYING能力，Xmx768MiB/容器1280MiB/含swap2560MiB保持 |
| Movie frontend | `sha256:f16cb8f1440681ef2fd2f3e2d508f7745b8c509bb6e96ce36592e9221291c841`，代码fe042692；11月2日19:08上线TMDB实测末页、范围无数据及北京时间复查提示 |
| 本机Agent | 11月3日21:12:14上线JAR `055f1bdc4bc8d52b8e9c2330d8a3937085d6043f96323cc2461db16f71d75eda`，代码15a2b8a；PID38460/instance7d5364c2…，不可变runtime/受管身份/ready/hostInJob=false通过；部署期管理员argv证明已绑定，普通权限实时argv UNKNOWN |
| OpenClaw QQ插件 | 11月2日22:56源/唯一运行时gateway错误回退修复上线，SHA `94922920…`；原固定镜像/config/白名单保持，其他9容器保持，真实群内端到端待验 |
| 模型/索引 | 影视qwen3指令4B、工作区1.7B；11,272条BGE-M3索引，SHA `f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4` |
| gying-source | `gying-resource-p2p-source:20261019b` |
| social-publisher | `sha256:8b6dd6e77fa93f012add7fb7cc6f17082d13fa937a9d5b21bfb62697185381cb`，代码7152edb；11月1日21:45微博发送前登录/身份验证检查上线 |
| Node | 10月23本机24.22.1/npm11.19.1、frontend容器22.23.4；旧版本保留 |

11月3日QQ目录修复最终五age检查点 `G:/gying-backups/qq-prada-transfer-20261103-20261103T150327.259056Z`：旧f2b镜像、完整有效配置、单事务MySQL、backend-data与ai-state-key的size/SHA/完整认证及OCI/配置/SQL对象/当前队列key恢复核对通过。仅backend正常TERM143替换与nginx测试/重载，其他容器/现役参数保持；实际第11层为 `E:/gying-tools/releases/qq-prada-transfer-20261103/candidate.json`，第10层为本次已生效rollback覆盖。两文件已CAS纳入日常备份并dry-run28份通过，自然完整备份未验。先前公网传输失败已应用回退f2b并验证；最终重新部署成功，未恢复SQL/state/key，非跨服务原子快照。23:03:46–23:12:33双日志流ERROR/Exception/OOM0、restarts0，AI目录CONFIRMED1。[完整证据](evaluation/qq-prada-transfer-20261103/README.md)。

11月3日双后端部署检查点：Agent三age `G:/gying-backups/ai-agent-query-quality-20261103-20261103T131135.560714Z`，Movie最终五age `G:/gying-backups/ai-query-quality-backend-20261103-20261103T134656.321321Z`，固定助手单age补充 `G:/gying-backups/ai-query-quality-pinned-helper-20261103-20261103T134458.791036Z`；size/SHA/完整认证与适用内容核对通过。只替换backend、nginx测试重载，原8层Compose追加`E:/gying-tools/releases/ai-query-quality-20261103/backend-candidate.json`第9层，并纳入日常备份dry-run，自然完整备份未验。共享工作树夸克改动保留，349文件副本绑定已测试提交13660c9。回退保留当前SQL/state/key，新requestHashVersion2身份写入后禁止旧Movie自动回退，前向修复优先；未实际回退或恢复SQL，非跨服务原子快照。[部署验收](evaluation/query-quality-20261103/deployment.md)。

11月2日19:08 TMDB空跑修复仅backend/frontend正常TERM退出143并替换固定镜像，实际JAR/配置/挂载及其他容器保持。最终停止窗口检查点 `G:/gying-backups/tmdb-empty-range-20261102-20261102T110716.404280Z` 含两旧镜像、有效配置、单事务MySQL、backend-data、ai-state-key六份age，SHA/完整认证/离线内容核验通过；SQL未导入，不称跨服务原子快照。现役新覆盖 `E:/gying-tools/releases/tmdb-empty-range-20261102/candidate.json` 分别追加原backend七层/frontend五层；已纳入日常备份且dry-run通过，自然完整备份待验。应用回退保留当前SQL/state/key，旧backend会恢复原空跑；实际回退未执行。[本轮验收](evaluation/tmdb-empty-range-20261102/README.md)。

10月31日Movie检查点 `G:/gying-backups/movie-quality-20261031-20261031T145725.979995Z` 的旧镜像、完整有效配置、backend-data与ai-state-key四份age，大小/SHA-256及完整认证解密通过；旧容器正常TERM退出143，新固定镜像和实际JAR/挂载/配置一致，其他容器保持。现役追加第六覆盖 `E:/gying-tools/releases/ai-quality-improvement-20261031/backend-quality-final.json`。11月1日已加密认证备份原日常配置并纳入该覆盖，dry-run通过，下一次自然完整备份仍待验。11月1日17:12 Agent再次部署通过，新检查点 `G:/gying-backups/ai-agent-quality-20261031-20261101T091153.915087Z` 三份age hash/完整认证解密通过，manifest533d9247…；旧9516正常受管停止、新1f9d…/7700独立ready，Movie、其他12容器、Ollama/Router/frontend/config/key/token/index保持。回退只恢复应用并保留当前data/state/key，不宣称跨服务原子快照。最新见[最终部署验收](evaluation/quality-improvement-20261031/deployment-final-20261101.md)；[16:59尝试](evaluation/quality-improvement-20261031/continuation-20261101-attempt.md)和[上午回退](evaluation/quality-improvement-20261031/deployment-20261101-summary.md)保留历史时点。

10月29日GYING暂停检查点 `G:/gying-backups/metadata-source-pause-20261029T153345.685852Z` 六份age hash/完整认证通过；仅backend/frontend正常TERM退出143及固定镜像替换/nginx重载，其他容器/Agent/配置保持。有效第五覆盖为 `E:/gying-tools/releases/metadata-source-pause-20261029/candidate.json`，已纳入日常备份并dry-run；完整日常备份自然执行仍待验。回退旧worker须保护新暂停意图，保留当前数据/state/key。详见[本轮验收](evaluation/metadata-source-pause-20261029/README.md)。

10月29日20:56 Agent检查点 `G:/gying-backups/ai-agent-final-20261029T125645.591658Z` 的旧JAR、私有配置/当前state/key/token、启动脚本三份age，SHA/完整认证解密通过；旧进程匹配stopped回执，新不可变runtime ready、独立hostInJob=false，其他本机服务/全部Movie容器与配置/索引保持。失败回退只恢复旧JAR，保留当前state/key。

10月29本次后端检查点 `G:/gying-backups/backend-recovery-20261029T095350.591530Z` 的旧镜像、有效配置、backend-data与ai-state-key四份age，SHA/完整认证解密通过；两次TERM均正常exit143，仅backend替换/nginx重载。原镜像与救援/最终覆盖在 `E:/gying-tools/releases/ai-recovery-20261029-1750/`；应用回退保留当前数据/state/key。

10月25三应用检查点 `G:/gying-backups/20261025T080048.799654Z` 的15份age，以及17:19 Agent专用 `G:/gying-backups/ai-agent-20261025T091941.886931Z` 的3份age，hash/完整认证解密通过。原有效覆盖、旧包/镜像和失败回退在对应外部release目录，见[台账](history/releases.md)。兼容应用回退保留state/key与新数据，不恢复旧SQL。

9月25完整数据检查点 `G:/gying-backups/20260925T030328.719395Z` 的19文件/25表校验与MinIO隔离单图通过；系统账号/完整应用/异机/离线私钥仍未验。现役日常备份契约已补状态、覆盖、本机配置与索引，dry-run通过不等于下一次日常完整备份已完成。

## 配置与安全

- 11月3日本次QQ目录修复按当前用户“可授权部署”仅backend限定授权上线；58 PASS/11 FAIL/1 UNKNOWN保持，未加固/轮换凭据，不延伸未来授权。[本次范围](evaluation/qq-prada-transfer-20261103/validation.json)。
- 11月3日用户获知58 PASS/11 FAIL/1 UNKNOWN后明确“可部署”，本轮限定Agent+Movie双后端已上线；未加固或轮换凭据，不作为未来部署授权。[本次部署](evaluation/query-quality-20261103/deployment.md)。
- 11月3日本次只读复检 **58 PASS / 11 FAIL / 1 UNKNOWN**；G01–G11未关闭，UNKNOWN为退出的旧迅雷探针root_processes。10月23历史为59/11/0，业务部署例外批准不等于安全加固或未来授权。[门禁](security/hardening-gates.md)、[风险](security/risk-register.md)。
- nginx/backend为loopback，应用非root；internal/QQ公网404、源站匿名管理401。Quark/MinIO仍非loopback，五依赖缺NNP/三依赖有root进程，Redis仍在共享网络。
- 10月13账号侧Access四类管理路径/单邮箱OTP/1小时策略已启用；本人OTP、网站角色、origin JWT/WAF验收未闭环。[Cloudflare](security/cloudflare-security.md)。
- 9月28 MySQL8.0.28/25 InnoDB表；三应用共用gying_app@%、库级DML/EXECUTE，TLS未强制、local_infile关闭。分服务账号/grants未完成；schema23表缺两QQ日志表，[数据库](database.md)保留24迁移库存。
- 9月25/28防火墙及同网拒绝报告、Quark父目录0700有证据，公网IPv6/单文件权限/身份/旧Cookie撤销未闭环。10月25两仓工作树秘密扫描为0，历史凭据轮换/清理不因当前零命中完成。
- 真实.env、Cookie、JWT、API key、备份私钥和完整inspect只在受限外部存储；恢复环境先关闭自动化，凭据变更与真实副作用按当前明确范围执行。
- 10月31日本候选有限两后端部署例外和管理员预检已获明确批准；Movie于10月31日22:58上线，Agent于11月1日17:12通过新管理员身份/argv、自然空闲、新加密检查点、受管平滑停止与独立runtime核验完成上线，root绑定复核passed。上午候选核对失败/确认回退和16:59提权取消均保留原时点。G01–G11未关闭，例外不表示加固完成。[最终部署验收](evaluation/quality-improvement-20261031/deployment-final-20261101.md)。

## 仍需处理

| 事项 | 保留的待验/工作 |
| --- | --- |
| AI质量 | 11月3日作者/数字、固定core+引文、可信四轮/TTL与幂等候选完成；15 completed，模型满足5/部分2/澄清2/未满足4/缺证2，三维完整12、真人0；原始对白/原作/官方公告仍有未知。胶片修复及电影院核心主题错判、徒步ac/空、mv与电视特辑冲突继续待修；同句作者兼主演/执导审查补丁另验，未重跑模型。21:12 Agent/21:47 Movie已上线，网站4机械通过而模型满足2/未满足1/缺证1，电影身份问题未解、真人0；第一阶段未完成。[查询修复](evaluation/query-quality-20261103/README.md)；此前正文/卡片、现役USER15轮/test清理和非浏览器/密码登录边界保留[原记录](evaluation/answer-quality-20261103/README.md) |
| 片库内存观察 | 10月29日18:02缓存修复上线；768MiB heap/1280MiB容器，实机硬条件、语义、人物及超过60秒刷新通过，测得容器约461–944MiB，短窗口无新OOM。仍有全库快照和跨服务独立索引，持续观察刷新/并发峰值；[本次恢复](evaluation/resource-recovery-20261029/README.md)及[早先诊断](evaluation/quality-fix-20261029/memory-diagnosis.md) |
| AI服务与求职 | 10月28日本机1.7B读取/Router与4B片库查询通过，一次简短历史追问未正确利用上下文；网站登录条件/点击本次未复验；工具中间步骤、长期usage/成本审计、定期增量索引、文档RAG/API Key/影视MCP待补，[路线](ai-career-roadmap.md) |
| 安全/灾备 | G01–G11、专用DB/MinIO身份、IPv6、Cookie/key轮换、WAF/origin JWT、异机/系统身份/离线私钥 |
| 每周重启 | 10月27日20:03:15真实重启；原登录恢复失败，10月28日18:21已按原journal手动恢复，0 pending、Worker=true、原任务开关/原生启动类型还原。Ollama端口21434与维护监听校验共用私有配置，111维护测试通过；11月1日上午journal已recovered；本轮仅核投影，完整自然维护/备份/恢复验收仍待独立核对。证据/回退见[重启手册](weekly-graceful-restart.md) |
| QQ/周榜/发布 | 10月29日QQ生成回复5条本库链接/PanSou及GYING纯搜索通过；真实群消息/资源选择转存未测试。公网普通USER有效设备会话为0（仅1 ADMIN），登录后资源job未验，旧测试账号仍disabled。原频道多盘正文自然出站、官方主动权限、三周榜首轮及清理/去重保留 11月2日22:56 OpenClaw错误回退修复与真实helper认证搜索通过，群内端到端待验，见[证据](evaluation/qq-search-error-20261102/README.md) ；11月3日仅2177真实转存/9339本库入库及QQ生成新自有分享已验，2176只读确认保持FAILED历史；群内实发/本次自然临时清理未验。[目录修复](evaluation/qq-prada-transfer-20261103/README.md) |
| 微博账号与QQ链路 | 11月1日微博/QQ修复已上线；12条经用户确认恢复后，11月2日17:39为4 PREPARE_FAILED/8 PENDING，原3篇/500秒计划保持。登录有效，微博明确限制发博并要求本人身份验证，发送前阻断；不再次重放。QQ同词内部回复0.360/0.250秒保留原验收，群内端到端未另测。[本轮核查](evaluation/platform-diagnosis-20261102/README.md) |
| TMDB高分剧集 | 11月2日17:43上游131页/2610条、所设360–500；10月23日21:05后104轮空跑历史保留。19:08 fe042692两应用已上线实测末页/24小时复查/跳过冷却榜，范围保持，电影任务17300原next252保持。19:15:57创建/19:15:59完成的自然任务17301保存EMPTY_RANGE、131页/2610条，11月3日19:15:59复查；本机/公网入口与新bundle通过，短日志无新异常。下一电影自然执行、跨重启冷却、次日复查与上游增长待发生时验。配置更新时间upsert问题另保留，未在本次修改。[修复](evaluation/tmdb-empty-range-20261102/README.md)、[历史](evaluation/platform-diagnosis-20261102/README.md) |
| 历史任务 | 10月8旧58条耗尽归档失败、两条精确旧RUNNING和自然元数据失败隔离未实证；不批量重置 |
| 上游/配图 | GYING两个发布RuntimeError原因不明；源图只读存在、实际复制/分享未验 |
| 迅雷 | 11月2日环境变量独立密码登录一次及同账户新token同步通过，到期11月3日05:51:24。18:18两小时独立profile续期启用，51 Node/21 Windows/5隔离写回、正式任务exit0/官方200/600通过。当前token复用与独立profile只读真实验证通过，下一次自然失效/真实验证码及异常残留仍待发生时验；backend请求触发续期未代验，旧目录/重复/乱码须逐批dry-run。[证据](evaluation/platform-diagnosis-20261102/README.md) |
| UI/入口/邮件 | 真机/真实已读编辑事务、Tunnel故障再现、同片跨版本/海报缓存；Gmail免费域拒收，其他邮箱旧投递不代全部可用 |
| 日志 | 10月18 QQ空值500和GYING503独立保留；后续短窗口正常不证明原边界已修，[问题台账](history/issues.md) |

10月12周榜首轮/10月16旧频道“下一次”已过期，属于待验记录，不当未来计划。历史容量、内存和计数不代表当前机器；资料全文按[Git来源](history/README.md#git-history)核查。

## 长期不变量

- movie_metadata/resource_link/movie_source_identity是主模型；外部id与本地canonical分开，来源大小写和历史关系保持。
- 自动分享先验证；未知候选待审，删除/合并先dry-run，优先软删除/关联迁移。
- UNKNOWN/PUBLISHING/历史FAILED不批量重置，状态机变更协调应用部署/回退；不删除卷或重放队列。
- 恢复先关自动化、核身份/配置/备份；不以旧dump自动覆盖在线数据，不恢复NapCat。
- 任务注册与运行、HTTP与业务、程序与人工质量、mock与真实、备份与恢复分别记录，不把一次成功推广为全天保证。

## 验收

11月3日QQ目录修复：940项中939通过/1 Redis环境跳过，真实SQL与独立compile、35项部署/回退/只读网络回归通过；最终JAR真实只读确认两份源叶目录。23:04:50固定6bee backend上线，五age/配置/其他容器与15入口通过，公网经既有7897代理且失败attempts保留。23:06仅2177一次重试：submitted1/failed0，自有分享含1个46,357,645,187字节目标视频、源名称/size相同且无2006第一部；9339 SAVED/原资源1579 ACTIVE+NORMAL+approved，QQ455字符回复含新分享、LIBRARY_RESOURCE，未发送。2176/任务数/QQ清理marker与配置保持；群内实发、自然清理与下一次完整日常备份未代验。[结果](evaluation/qq-prada-transfer-20261103/validation.json)。

11月3日实际上线：最终Movie902项中872通过/30跳过、Agent173/173及compile，21:12 Agent先/21:47 Movie后上线，实际JAR/固定源码/运行配置、三/五age与补充助手完整认证、其他容器保持、日常备份新覆盖通过。本机/经既有代理公网14入口分次通过，默认UA1010及三个URLError后定向复验原证据保留；backend21:47:19–21:54:42限定日志ERROR/Exception/OOM0、restarts0。21:53–21:55网站4提交/4成功/4机械通过/0重试，46项契约/链/清理核对通过；模型满足2/未满足1/缺证1、事实null2、三维完整2/4、真人0。test恢复disabled/有效设备0/token401/密码USER不变/临时凭据删除；不覆盖密码登录/浏览器/自然900秒TTL或跨owner生产重测。原15轮未重跑，电影身份/主题误判仍待修。安全例外仅本轮双后端，不等于G01–G11完成；新v2请求阻断旧Movie自动回退，保留SQL/state/key。[完整上线记录](evaluation/query-quality-20261103/deployment.md)。

11月3日20:24–20:46查询修复隔离阶段：模型观测Movie4f73846f…/Agent055f1bdc…离线verify与compile通过，Movie869过/30环境跳过、Agent173过；15候选请求全部completed，两进程正常exit0。模型复核满足5/部分2/澄清2/未满足4/缺证2、三维完整12/真人0，不等于全满足。随后同句作者兼主演/执导边界修前两新增回归失败、修后专项通过，独立[Movie审查后构建](evaluation/query-quality-20261103/movie-reviewed-build.json)与源码绑定另记，原模型15轮未重跑。固定公开快照和旧轮fixture非网站/生产owner与TTL验收；该隔离阶段未启用test、写生产DB、领取生产队列或外发。20:44–20:46入口、容器/stored身份与临时端口清理通过，未复验生产argv；安全58 PASS/11 FAIL/1 UNKNOWN保持，当时未部署/建立新应用检查点/回退；后续上线另记。[本轮完整证据](evaluation/query-quality-20261103/README.md)。

11月3日早先正文/卡片候选验收：143项Java、前端构建/固定镜像network=none和8组40卡片fixture，最终四问模型满足3/部分有用1。另于18:29–18:36通过真实nginx入口采集现役Agent1f9d…/frontendf16c…的15轮USER开发题，15机械通过/0失败，p50 15.296秒/p95 68.594秒；不是候选上线验收或独立提升对照。运行前后镜像/JAR/受管身份记录一致及三入口200；本次独立进程argv未复验。test两表限定行age认证备份完成，临时USER设备会话撤销、disabled/有效设备0/401/密码角色保持及临时凭据文件删除通过；不是密码登录或浏览器验收。当时新应用部署加密检查点尚未建立，账号备份不能替代；后续查询双后端上线检查点另记，未执行应用回退。[证据及hash](evaluation/answer-quality-20261103/validation-summary.json)、[网站边界](evaluation/answer-quality-20261103/website-runtime.json)、[逐轮模型复核](evaluation/answer-quality-20261103/website-review.md)。

| 记录 | 经过验证的范围 |
| --- | --- |
| 11月2日TMDB空跑修复 | 19:08仅backend/frontend固定镜像上线，代码fe042692；881项880通过/1 Redis跳过，111定向、必需SQL/独立compile与临时容器清理通过。前端Offline React报告15通过（14子场景与父套件）/i18n5、TS/lint/build及6组真实Next/AntD资产模拟API浏览器场景通过；全量lint7既有warning/0error。六age认证、实际JAR/配置及其他容器/日常备份新覆盖通过；19:12:39本机五health200。本机七入口与经现有代理的公网七入口复核通过，新bundle公网/本机SHA一致；原脚本Access302误判及传输中断单列保留。19:15:57创建/19:15:59完成的自然任务17301保存EMPTY_RANGE、nextCheckAt为11月3日19:15:59；19:18:02只读回读配置保持/无活跃/提前TV重排0，电影next252保持而新电影尚未执行。短日志至19:15:59 ERROR/Exception/OOM=0、restarts0；次日复查/上游增长/跨重启生产行为未代验。[报告](evaluation/tmdb-empty-range-20261102/README.md) |
| 11月2日平台核查与迅雷恢复 | 微博17:39登录200/ok1、权限200/confirm要求身份验证，4准备失败/8待发布；TMDB17:43高分剧集131页，现360空跑。迅雷官方旧401→独立env登录一次→DPAPI认证/UID10001 CAS全字段回读/600/新官方200。18:18主机脚本启用，原任务手动启动exit0、有效token复用、独立profile禁止密码仍官方200；51 Node/21 Windows/5隔离检查通过，保留原任务及应用。自然失效/真实挑战未代验。[本轮证据](evaluation/platform-diagnosis-20261102/README.md) |
| 11月2日QQ错误回退修复 | 22:56仅OpenClaw正常退出0/同步源与唯一runtime两个gateway/健康恢复，配置、slash和其他9容器保持。17项回归、actual13场景/11提取/白名单/拒绝/UTF8通过，QQ发送异常模型入队0；真实helper搜索279ms正常回复，未发送测试群消息。单份age的源/runtime/config大小/hash/完整认证及成员hash通过，回退在外部release目录；[证据](evaluation/qq-search-error-20261102/README.md) |
| 11月1日微博/QQ修复 | 21:45仅backend/publisher固定镜像上线，代码7152edb；806项后端776通过/30环境跳过、Node40/40、隔离发布SQL7/7。自然空闲、两个旧应用TERM143、八age完整认证、实际程序hash/参数和其余11容器通过；第七backend覆盖/第四publisher覆盖为publishing-recovery-20261101/candidate.json，已纳入日常备份dry-run。内部QQ同词0.360/0.250秒、仅本库阶段；微博会话保持但需身份验证。随后经用户确认，新的MySQL认证检查点和行锁/CAS恢复12条PENDING，新连接回读；未手工群发/转存/发博。58 PASS/11 FAIL/1 UNKNOWN保持，本次应用限定例外；[报告](evaluation/publishing-recovery-20261101/README.md) |
| 11月1日17:12 Agent最终上线 | 管理员身份/argv、自然空闲、新三age hash/完整认证解密、旧9516匹配正常stopped、新1f9d…/代码e19b6b16/7700独立ready及root绑定复核passed。Movie实际image/JAR/config、其他12容器和Ollama/Router/frontend/config/key/token/index保持。四条HTTP/API200 completed、程序0失败、总耗时39.0秒；第1与第4正文未列片名，第4原始对白仍未核验，模型复核为满足2/未满足1/有用但不完全1，有效澄清0、真人新增0，网站USER15轮仍未执行。[本次证据](evaluation/quality-improvement-20261031/deployment-final-20261101.md) |
| 11月1日16:59继续部署 | 11月1日16:59触发Windows提权返回取消，助手未启动；上午维护已改变Agent实例，当时stored PID9516/instance5e9e0e80…，进程/监听及四本机health200，新管理员argv与运行版本当时待证明。新16项离线检查和候选绑定通过，当时4条实际回答为0。Movie实际image/JAR/config保持；当时Windows令牌待系统确认，既有有限授权有效。[历史证据](evaluation/quality-improvement-20261031/continuation-20261101-attempt.md) |
| 11月1日Agent尝试与回退 | 08:34新身份/空闲通过，旧36916正常停止，三age完整认证；1f9候选35532 ready/hostInJob=false后最终核对PermissionError，候选正常停止，08:35–08:36已确认应用回退旧01eab8…/40148/instance0ad13b71…，当时状态保留。08:55新79dc…/9161…修正助手提权取消，7项离线/静态审查通过但未执行；新4问0。08:59普通权限stored record/进程/监听/health200与Movie及其他12容器保持，不冒充新提权argv证明；11安全FAIL未关闭。[本阶段](evaluation/quality-improvement-20261031/deployment-20261101-summary.md) |
| 10月31 Movie改进上线阶段 | 22:58仅Movie backend上线，782项中752通过/30跳过；四age hash/完整认证解密，旧TERM143，新实际image8d65…/JARd0149…，资源参数/挂载及其他容器保持。23:23三个直接bridge只读契约通过：数量/时长/同源排序、null上限、非法limit400；无模型推理/队列写入，缺分显示分支未覆盖，不证明多轮继承。本机及经现有代理的公网片库、首页HTML/同ID详情SSR、internal/QQ404通过；公网匿名admin超时未验，源站匿名401。11月1日afterMovie快照/就绪exit0，11通过/1原QQschema警告/0失败；日常备份已纳入新覆盖并dry-run，自然完整备份待验。Agent于11月1日00:04受管身份通过，随后在途门禁阻断且未停；11月1日01:06 Windows提权再次返回操作取消、助手未启动，仍01eab8…/36916，新4条回答未执行。5项离线有界等待检查通过，改进助手仅准备；安全11 FAIL未关闭。[阶段报告](evaluation/quality-improvement-20261031/deployment-summary.md) |
| 10月31 AI原因审计与候选观测 | 82条历史原因及原报告/回答hash绑定核对完成，冻结材料不改；首轮15/15 completed，模型满足0/部分1/未满足8/证据不足4/有效澄清2。第二轮14 completed/1 API500，14答案满足4/未满足4/证据不足4/有效澄清2，执行失败三维null。最终冻结候选Movie782项中752通过/30跳过、Agent135/135及compile；最终15轮13 completed/2 API500（01 WEB_UNAVAILABLE、07 WEB_TIMEOUT），两进程正常exit0；13条答案模型满足3/有效澄清2/未满足4/证据不足4，另2次失败三维null。固定12822条公开快照非原子，真实模型/现役索引/Movie候选类联动，未领取生产队列或启用生产state，未登录网站USER、未外发；[最终原始报告](evaluation/quality-improvement-20261031/development-final-report.json)、[本轮交付](evaluation/quality-improvement-20261031/README.md) |
| GYING六目录暂停 | 10月29日23:34两应用固定镜像上线；后端765项中735通过/30环境跳过，79定向及compile，前端8新增/15原场景与lint/TS通过。实际ADMIN匿名401/13项400、暂停/继续保持范围/其他五标记/所比游标；1280/390真页面、刷新持久化和未保存草稿通过。实际现役三个CSCORE按钮，六目录合成另验；QQ5本库链接、公网API及经现有代理片目数据通过，当前可见海报通过、全页图片覆盖仍partial；本机Edge直连fake-IP超时另记。六age认证，其他容器/Agent保持；[报告](evaluation/metadata-source-pause-20261029/README.md)含UTC原时点及研究限制 |
| 10月29 Agent上线 | 20:56–20:57经用户重试及管理员确认，现役01eab8…/代码9cd1d2b；104项Java、14套报告/JAR/源码hash绑定。旧实例正常stopped、新PID36916/ready/独立hostInJob=false，三age认证；其他服务/容器、config/key/token/index与当前state保持。21:00两条模型查询HTTP/API200/completed，23.50/2.25秒，类别/评分来源均正确；两条都缺rating_desc、第二条缺剧情类型，只计专项通过，不计完整需求满足；公网片库仍200，18:02–21:01日志无新OOM。[报告](evaluation/resource-recovery-20261029/README.md) |
| 10月29资源恢复 | Movie c4b8955/固定0b3498…镜像，742项Maven中712通过/30跳过（25 SQL、1 Redis、4权限）；42定向及compile通过。18:07/18:09内库冷/暖/TTL评分/语义/人物通过，12,400有效片目；18:13 QQ生成5条本库链接0.20秒，日志LIBRARY_RESOURCE；本机/公网片名API200、internal/QQ404；公网1280/390视口各100片目链接/98海报已加载，无loading/脚本错误。PanSou200/夸克10迅雷3、GYING实际search200/1片。四age认证、其他容器保持，未转存/发送/发布；[报告](evaluation/resource-recovery-20261029/README.md) |
| 10月29质量续推 | 139项Python与17组Edge合成检查通过；真实离线页面88记录/84条模型需求评分，0外网/错误；25个历史文件不变。Agent最终候选SHA01eab8…，104项Java/完整JAR隔离启动退出通过；早先尚未部署，20:57上线另见本表。首次UAC取消，第二次前用户明确暂缓；后续用户恢复授权，Movie后端已另行恢复/上线，Agent首次再次UAC取消后，用户20:56明确重试并完成20:57替换，21:00两条类别/评分来源通过；排序与剧情类型遗漏另记，早先两条片库OOM失败不能当修复前后质量对照。[验收](evaluation/quality-fix-20261029/validation-summary.json) |
| 10月28重启后恢复 | 系统事件/OS启动时间证实10月27日20:02:39发正常重启、20:03:15启动；18:20–18:21受限管理员脚本正常退出无子进程的旧Ollama宿主，迁移21434并同步维护runtime SHA `3223c5c1cfe9eb53e5db2854a4dc1abc397d447ead8a7c27013ed48c7eff2de9`。现役recover/readiness均0，journal=recovered、0 pending，原guard CAS还原Worker=true；10原容器ID/镜像不变。九入口200、Tunnel HA4；真实1.7B读取/Router及4B片库查询通过，简短追问限制另记。备份、执行结果和回退说明在 `G:/gying-tools/restart-20261018/recovery-20261028/port-21434-recovery/` |
| 10月27重启准备 | 15:45复检主机最后启动仍9月24日21:51:41，10容器身份/镜像/启动状态相对13:03不变，六本机/公网入口正常、0待恢复维护、worker原true。实际应用账号sys_config SELECT/UPDATE及12:31新mysql.sql.age SHA-256/完整age解密复验通过；107维护与8时间测试通过，现役weekly SHA41a527…不变。旧17:30候选/UAC取消证据保留。20:00候选与登记/执行/前后验收在 `G:/gying-tools/restart-20261018/scheduled-20261027-2000/`；19:31:55登记准备任务NextRunTime19:45:45，19:45:57helper部署/Worker true→false及20:00单次任务注册成功，guard Running。19:59十原容器身份/镜像/启动不变、六入口正常、0待恢复维护；当时真实重启/登录恢复待验，10月28结果见上行 |
| 10月26重启排查 | 17:30计划无任务/无注册成功结果；系统最后启动仍9月24日21:51:41。13:05/19:59失败均有恢复证据，20:48无在途/无未完恢复，20:54基线出现一条新元数据RUNNING；自然等空闲由控制器校验，10容器信号接收通过；62维护+8时间测试，以及Agent退出竞态7项/实际子进程停止0退出并恢复通过。20:48新单次注册并回读NextRunTime20:55:55，现役runtime SHA `41a527965dbe98ae5cf1d1cbb18fd4aa3cc422a070332e53339f2d9007948683`，Agent脚本e3d97f6。基线/回退/诊断外部保留；20:55/22:03后续取消及恢复见[重启手册](weekly-graceful-restart.md)，该轮未重启，10月27实际重启结果见上行 |
| 10月25服务补丁 | Agent91/91、合成Job6/6、配置9/9、完整JAR隔离启动退出；真实条件/语义/Bing/只读队列及9入口正常，17:19–17:26短日志无ERROR/Exception；人物/年份偏差保留 |
| 10月25查询上线 | 后端全套731（730通过/1跳过）及后续29项、Agent87项、构建器9项、重启36项；23入口/95静态/3依赖/四组真实游客UI；两组开发对照各8轮完成但非全正确 |
| 10月23独立网站题 | 原30题/44轮重复2次88轮、84程序通过/4失败；不等于84答对，用户复核发现大量需求未命中。[冻结报告](evaluation/human-holdout-v1/README.md) |
| 10月23可靠性 | 新包模型8/8程序检查；2条终态跨平滑重启state/attempt保持，真实在途故障未注入。[机器摘要](evaluation/ai-reliability-20261023/validation-summary.json) |
| 其余上线与测量 | 范围/检查点/限制统一见[发布台账](history/releases.md)，原始JSON保留；不重复堆历史测试数量 |

实际操作遵循[开发](development.md)/[部署](deployment.md)/[运维技能](../.agents/skills/gying-project-ops/SKILL.md)。文档整理只验证编码、链接、契约、证据保留与diff，不触发服务或外部业务。
