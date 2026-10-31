# 当前项目状态

更新时间：2026-10-31

Asia/Shanghai。10月31日已核对82条历史未满足回答的原因并落实共性候选修复；已保留三轮隔离15轮观测：首轮无完全满足；第二轮14条答案复核为满足4/未满足4/证据不足4/有效澄清2，另1次500；最终轮13条正常答案模型复核为满足3/有效澄清2/未满足4/证据不足4，另2次联网500且质量三维null。生产仍为10月29日版本，本候选因安全门禁和Agent身份预检未通过而未部署，网站USER新15轮未执行。10月29日23:34上线GYING六目录独立暂停，现有范围/选择/游标保持；后续1280/390浏览器视口与公网片目数据经现有代理验收按原UTC证据记录；当前可见海报通过、全页图片覆盖未计完整通过；本机Edge直连fake-IP超时单独待处理，日常备份已纳入新覆盖。10月29日用户恢复上线授权，17:55恢复后端、18:02部署片库缓存修复及768MiB heap/1280MiB容器；18:13复验QQ回复、公网数据与完整页面通过。本机Agent20:56–20:57通过管理员确认上线最终候选，21:00两条真实查询的类别/评分来源否定条件通过，评分排序和剧情类型仍有遗漏。离线质量复核/开发runner已完成，整体质量提升尚未实证。10月28日18:21手动恢复完成，18:22–18:29本机/公网及真实AI复验：主机已于10月27日20:03:15重启，原登录恢复因Ollama端口被Windows保留而失败。现已迁至loopback21434，维护recovered、Worker还原true；其余历史事实保留观测日期。

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
| 采集和治理 | TMDB/GYING独立范围/页内游标、GYING六目录独立暂停；范围调整保进度、失败项目隔离、季/合集消歧 | [Resource Hub](resource-hub.md) |
| 网盘与P2P | 三个独立GYING周榜、手动补齐/按id修复、双盘原位分享与有效性判断；P2P小文件归档，账号/dbt隔离 | [周榜](metadata-weekly-transfer.md)、[P2P](p2p-cloud-archive.md) |
| 账号与监控 | 注册/邀请、昵称/邮箱登录、验证码与设备吊销、原IP/UA、API日志和分类资源计数 | [API](api.md)、[数据库](database.md) |
| 主机维护 | 迅雷同账户Edge会话验证/非root原子写回；每周优雅重启和登录恢复任务已注册 | [迅雷](xunlei-token-sync.md)、[重启](weekly-graceful-restart.md) |

资源绑定使用bindingVersion，更新同组/新增绑定但不删除取消勾选的历史；来源身份区分canonical与外部id，保留utf8mb4_bin。自动视频资源先验证自有分享再入库，不确定匹配待审。高频Worker只处理元数据，不消费旧视频/发布队列；旧夸克追更与三个周榜分开。

## 当前部署与恢复基线

| 服务/产物 | 当前记录 |
| --- | --- |
| Movie backend | `sha256:95bbb204b4d01a246dfe5218d8318655c59edc471774526b1c73f1a5a836b893`，10月29 GYING独立暂停；沿用缓存修复和Xmx768MiB/容器1280MiB/含swap2560MiB |
| Movie frontend | `sha256:db1cc643cb8dad1c10071ac1b9b9c8ed14aea7ffe0188b308aeacfdf535b31a6`，10月29日23:34上线独立暂停按钮 |
| 本机Agent | 代码9cd1d2b，10月29日20:56–20:57条件否定补丁；JAR SHA `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`，对应hash不可变运行副本 |
| 模型/索引 | 影视qwen3指令4B、工作区1.7B；11,272条BGE-M3索引，SHA `f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4` |
| gying-source | `gying-resource-p2p-source:20261019b` |
| social-publisher | `gying-code-optimization-social-publisher:20261010a` |
| Node | 10月23本机24.22.1/npm11.19.1、frontend容器22.23.4；旧版本保留 |

10月29日GYING暂停检查点 `G:/gying-backups/metadata-source-pause-20261029T153345.685852Z` 六份age hash/完整认证通过；仅backend/frontend正常TERM退出143及固定镜像替换/nginx重载，其他容器/Agent/配置保持。有效第五覆盖为 `E:/gying-tools/releases/metadata-source-pause-20261029/candidate.json`，已纳入日常备份并dry-run；完整日常备份自然执行仍待验。回退旧worker须保护新暂停意图，保留当前数据/state/key。详见[本轮验收](evaluation/metadata-source-pause-20261029/README.md)。

10月29日20:56 Agent检查点 `G:/gying-backups/ai-agent-final-20261029T125645.591658Z` 的旧JAR、私有配置/当前state/key/token、启动脚本三份age，SHA/完整认证解密通过；旧进程匹配stopped回执，新不可变runtime ready、独立hostInJob=false，其他本机服务/全部Movie容器与配置/索引保持。失败回退只恢复旧JAR，保留当前state/key。

10月29本次后端检查点 `G:/gying-backups/backend-recovery-20261029T095350.591530Z` 的旧镜像、有效配置、backend-data与ai-state-key四份age，SHA/完整认证解密通过；两次TERM均正常exit143，仅backend替换/nginx重载。原镜像与救援/最终覆盖在 `E:/gying-tools/releases/ai-recovery-20261029-1750/`；应用回退保留当前数据/state/key。

10月25三应用检查点 `G:/gying-backups/20261025T080048.799654Z` 的15份age，以及17:19 Agent专用 `G:/gying-backups/ai-agent-20261025T091941.886931Z` 的3份age，hash/完整认证解密通过。原有效覆盖、旧包/镜像和失败回退在对应外部release目录，见[台账](history/releases.md)。兼容应用回退保留state/key与新数据，不恢复旧SQL。

9月25完整数据检查点 `G:/gying-backups/20260925T030328.719395Z` 的19文件/25表校验与MinIO隔离单图通过；系统账号/完整应用/异机/离线私钥仍未验。现役日常备份契约已补状态、覆盖、本机配置与索引，dry-run通过不等于下一次日常完整备份已完成。

## 配置与安全

- 10月29复检 **58 PASS / 11 FAIL / 1 UNKNOWN**；G01–G11未关闭，UNKNOWN为退出的旧迅雷探针root_processes。10月23历史为59/11/0，业务部署例外批准不等于安全加固或未来授权。[门禁](security/hardening-gates.md)、[风险](security/risk-register.md)。
- nginx/backend为loopback，应用非root；internal/QQ公网404、源站匿名管理401。Quark/MinIO仍非loopback，五依赖缺NNP/三依赖有root进程，Redis仍在共享网络。
- 10月13账号侧Access四类管理路径/单邮箱OTP/1小时策略已启用；本人OTP、网站角色、origin JWT/WAF验收未闭环。[Cloudflare](security/cloudflare-security.md)。
- 9月28 MySQL8.0.28/25 InnoDB表；三应用共用gying_app@%、库级DML/EXECUTE，TLS未强制、local_infile关闭。分服务账号/grants未完成；schema23表缺两QQ日志表，[数据库](database.md)保留24迁移库存。
- 9月25/28防火墙及同网拒绝报告、Quark父目录0700有证据，公网IPv6/单文件权限/身份/旧Cookie撤销未闭环。10月25两仓工作树秘密扫描为0，历史凭据轮换/清理不因当前零命中完成。
- 真实.env、Cookie、JWT、API key、备份私钥和完整inspect只在受限外部存储；恢复环境先关闭自动化，凭据变更与真实副作用按当前明确范围执行。
- 10月31日质量候选部署已停止：G01–G11仍未关闭，普通权限Agent只读预检无法证明现役受管身份；没有发送停止请求或替换服务。10月29日例外不作为本候选授权；部署/回退计划在仓库外release，须按[部署门禁规则](../.agents/skills/gying-project-ops/references/deployment-recovery.md)完成本次预检。

## 仍需处理

| 事项 | 保留的待验/工作 |
| --- | --- |
| AI质量 | 10月31日82条唯一历史未满足记录/42个case-turns原因审计完成，真人新增0；数量/严格数字/同源排序/范围替换/缺分未知/版本资料/公告检索/语言并集/纪录别名及未知限制提示已形成冻结候选。首轮15答案模型复核满足0/部分1/未满足8/证据不足4/有效澄清2；第二轮14答案满足4/未满足4/证据不足4/有效澄清2，另1次执行失败不赋质量分；最终轮13 completed，模型满足3/有效澄清2/未满足4/证据不足4，另2次联网500且质量三维null。Movie782项中752通过/30环境跳过，Agent135/135及compile通过。生产仍10月29日版本，网站USER15轮未执行，不称82条全部解决或独立质量提升。[本轮证据](evaluation/quality-improvement-20261031/README.md) |
| 片库内存观察 | 10月29日18:02缓存修复上线；768MiB heap/1280MiB容器，实机硬条件、语义、人物及超过60秒刷新通过，测得容器约461–944MiB，短窗口无新OOM。仍有全库快照和跨服务独立索引，持续观察刷新/并发峰值；[本次恢复](evaluation/resource-recovery-20261029/README.md)及[早先诊断](evaluation/quality-fix-20261029/memory-diagnosis.md) |
| AI服务与求职 | 10月28日本机1.7B读取/Router与4B片库查询通过，一次简短历史追问未正确利用上下文；网站登录条件/点击本次未复验；工具中间步骤、长期usage/成本审计、定期增量索引、文档RAG/API Key/影视MCP待补，[路线](ai-career-roadmap.md) |
| 安全/灾备 | G01–G11、专用DB/MinIO身份、IPv6、Cookie/key轮换、WAF/origin JWT、异机/系统身份/离线私钥 |
| 每周重启 | 10月27日20:03:15真实重启；原登录恢复失败，10月28日18:21已按原journal手动恢复，0 pending、Worker=true、原任务开关/原生启动类型还原。Ollama端口21434与维护监听校验共用私有配置，111维护测试通过；下一次自然ASUS登录恢复仍待验，每周任务下次11月1日09:30:30。证据/回退见[重启手册](weekly-graceful-restart.md) |
| QQ/周榜/发布 | 10月29日QQ生成回复5条本库链接/PanSou及GYING纯搜索通过；真实群消息/资源选择转存未测试。公网普通USER有效设备会话为0（仅1 ADMIN），登录后资源job未验，旧测试账号仍disabled。原频道多盘正文自然出站、官方主动权限、三周榜首轮及清理/去重保留 |
| 历史任务 | 10月8旧58条耗尽归档失败、两条精确旧RUNNING和自然元数据失败隔离未实证；不批量重置 |
| 上游/配图 | GYING两个发布RuntimeError原因不明；源图只读存在、实际复制/分享未验 |
| 迅雷 | 自然失效后密码/验证码与backend独立续期、异常browser.close残留；旧目录/重复/乱码须逐批dry-run |
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

| 记录 | 经过验证的范围 |
| --- | --- |
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
