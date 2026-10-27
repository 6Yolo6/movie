# 当前项目状态

更新时间：2026-10-27，Asia/Shanghai。重启前处理及取消后只读检查截至15:45；主机未重启。用户指定今天17:30，15:30 Windows管理员授权被取消，17:15准备任务与17:30单次任务均未注册。AI业务部署/入口证据仍为10月25日17:26，其他事实保留观测日期。

[知识导读](project-guide.md) · [AI架构](ai-architecture.md) · [求职路线](ai-career-roadmap.md) · [发布证据](history/releases.md)

## 当前目标

维护统一片库、自有资源和可追溯采集/归档/发布；提供网页/QQ检索、可解释推荐与依据明确的AI回答。工程知识、实现和验证入口由知识导读与专项手册维护。

## 运行架构

| 层 | 当前接线与约束 |
| --- | --- |
| 公网 | `gyinghub.dpdns.org` → Cloudflare Tunnel → `127.0.0.1:80` nginx → Next.js frontend / Spring backend |
| 业务 | Java17 backend、Next.js/React网页、Python gying-source、Node social-publisher |
| AI | 网页业务分流/加密队列 → 本机Agent5000 → Ollama11434；Router5002、本机Vue3000仅本机入口 |
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
| 采集和治理 | TMDB/GYING独立1–500页范围/页内游标，范围调整保进度、失败项目隔离、季/合集消歧 | [Resource Hub](resource-hub.md) |
| 网盘与P2P | 三个独立GYING周榜、手动补齐/按id修复、双盘原位分享与有效性判断；P2P小文件归档，账号/dbt隔离 | [周榜](metadata-weekly-transfer.md)、[P2P](p2p-cloud-archive.md) |
| 账号与监控 | 注册/邀请、昵称/邮箱登录、验证码与设备吊销、原IP/UA、API日志和分类资源计数 | [API](api.md)、[数据库](database.md) |
| 主机维护 | 迅雷同账户Edge会话验证/非root原子写回；每周优雅重启和登录恢复任务已注册 | [迅雷](xunlei-token-sync.md)、[重启](weekly-graceful-restart.md) |

资源绑定使用bindingVersion，更新同组/新增绑定但不删除取消勾选的历史；来源身份区分canonical与外部id，保留utf8mb4_bin。自动视频资源先验证自有分享再入库，不确定匹配待审。高频Worker只处理元数据，不消费旧视频/发布队列；旧夸克追更与三个周榜分开。

## 当前部署与恢复基线

| 服务/产物 | 当前记录 |
| --- | --- |
| Movie backend | `sha256:fa06d609e0d04ecaf790f0965ba7b0a4ecfe5030e2b7a25f60b1814e4227221a`，10月25查询业务代码f209e87 |
| Movie frontend | `sha256:9b4eb2377a1ce3608b9f501c6ef8c4c8394e086684ae3c931486d5f7838ae177`，同日16:00–16:01上线 |
| 本机Agent | 代码cd79797，17:19–17:20补丁；JAR SHA `33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`，对应hash不可变运行副本 |
| 模型/索引 | 影视qwen3指令4B、工作区1.7B；11,272条BGE-M3索引，SHA `f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4` |
| gying-source | `gying-resource-p2p-source:20261019b` |
| social-publisher | `gying-code-optimization-social-publisher:20261010a` |
| Node | 10月23本机24.22.1/npm11.19.1、frontend容器22.23.4；旧版本保留 |

10月25三应用检查点 `G:/gying-backups/20261025T080048.799654Z` 的15份age，以及17:19 Agent专用 `G:/gying-backups/ai-agent-20261025T091941.886931Z` 的3份age，hash/完整认证解密通过。原有效覆盖、旧包/镜像和失败回退在对应外部release目录，见[台账](history/releases.md)。兼容应用回退保留state/key与新数据，不恢复旧SQL。

9月25完整数据检查点 `G:/gying-backups/20260925T030328.719395Z` 的19文件/25表校验与MinIO隔离单图通过；系统账号/完整应用/异机/离线私钥仍未验。现役日常备份契约已补状态、覆盖、本机配置与索引，dry-run通过不等于下一次日常完整备份已完成。

## 配置与安全

- 10月25复检 **58 PASS / 11 FAIL / 1 UNKNOWN**；G01–G11未关闭，UNKNOWN为退出的旧迅雷探针root_processes。10月23历史为59/11/0，业务部署例外批准不等于安全加固或未来授权。[门禁](security/hardening-gates.md)、[风险](security/risk-register.md)。
- nginx/backend为loopback，应用非root；internal/QQ公网404、源站匿名管理401。Quark/MinIO仍非loopback，五依赖缺NNP/三依赖有root进程，Redis仍在共享网络。
- 10月13账号侧Access四类管理路径/单邮箱OTP/1小时策略已启用；本人OTP、网站角色、origin JWT/WAF验收未闭环。[Cloudflare](security/cloudflare-security.md)。
- 9月28 MySQL8.0.28/25 InnoDB表；三应用共用gying_app@%、库级DML/EXECUTE，TLS未强制、local_infile关闭。分服务账号/grants未完成；schema23表缺两QQ日志表，[数据库](database.md)保留24迁移库存。
- 9月25/28防火墙及同网拒绝报告、Quark父目录0700有证据，公网IPv6/单文件权限/身份/旧Cookie撤销未闭环。10月25两仓工作树秘密扫描为0，历史凭据轮换/清理不因当前零命中完成。
- 真实.env、Cookie、JWT、API key、备份私钥和完整inspect只在受限外部存储；恢复环境先关闭自动化，凭据变更与真实副作用按当前明确范围执行。

## 仍需处理

| 事项 | 保留的待验/工作 |
| --- | --- |
| AI质量 | 人物/年份误解析、海上场景偏题、独立人工评分、同冻结语料检索实验；原30题/44轮与10,432快照不改写 |
| AI服务与求职 | 网站登录条件/多轮/点击本次未复验；工具中间步骤、长期usage/成本审计、定期增量索引、文档RAG/API Key/影视MCP待补，[路线](ai-career-roadmap.md) |
| 安全/灾备 | G01–G11、专用DB/MinIO身份、IPv6、Cookie/key轮换、WAF/origin JWT、异机/系统身份/离线私钥 |
| 每周重启 | 10月26的17:30未注册；13:05/19:59/20:55失败均已恢复，22:03暂停误用只读备份账号失败、22:04恢复，22:11任务已禁用。AI退出修复e3d97f6已推送并真实停止/恢复验证。10月27暂停权限、恢复任务完整绑定及失败回退代码完成，107维护+8时间测试通过；17:30方案为17:15先预检/暂停再注册单次窗口，15:30 UAC被取消，计划未注册。受限脚本入口见[重启手册](weekly-graceful-restart.md)。真实重启/ASUS登录恢复待验，下次每周11月1日09:30:30 |
| QQ/周榜/发布 | 原频道多盘正文自然出站、官方主动消息/群内结果、三周榜首轮、临时清理与跨版本去重 |
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
| 10月27重启准备 | 15:45复检主机最后启动仍9月24日21:51:41，10容器身份/镜像/启动状态相对13:03不变，六本机/公网入口正常、0待恢复维护、worker原true。实际应用账号sys_config SELECT/UPDATE及12:31新mysql.sql.age SHA-256/完整age解密复验通过；107维护与8时间测试通过，现役weekly SHA41a527…不变。冻结候选/17:30调度脚本/取消证据在 `G:/gying-tools/restart-20261018/scheduled-20261027-1730/`；UAC取消，helper及未来任务未部署 |
| 10月26重启排查 | 17:30计划无任务/无注册成功结果；系统最后启动仍9月24日21:51:41。13:05/19:59失败均有恢复证据，20:48无在途/无未完恢复，20:54基线出现一条新元数据RUNNING；自然等空闲由控制器校验，10容器信号接收通过；62维护+8时间测试，以及Agent退出竞态7项/实际子进程停止0退出并恢复通过。20:48新单次注册并回读NextRunTime20:55:55，现役runtime SHA `41a527965dbe98ae5cf1d1cbb18fd4aa3cc422a070332e53339f2d9007948683`，Agent脚本e3d97f6。基线/回退/诊断外部保留；20:55/22:03后续取消及恢复见[重启手册](weekly-graceful-restart.md)，实际整机重启待验 |
| 10月25服务补丁 | Agent91/91、合成Job6/6、配置9/9、完整JAR隔离启动退出；真实条件/语义/Bing/只读队列及9入口正常，17:19–17:26短日志无ERROR/Exception；人物/年份偏差保留 |
| 10月25查询上线 | 后端全套731（730通过/1跳过）及后续29项、Agent87项、构建器9项、重启36项；23入口/95静态/3依赖/四组真实游客UI；两组开发对照各8轮完成但非全正确 |
| 10月23独立网站题 | 原30题/44轮重复2次88轮、84程序通过/4失败；不等于84答对，用户复核发现大量需求未命中。[冻结报告](evaluation/human-holdout-v1/README.md) |
| 10月23可靠性 | 新包模型8/8程序检查；2条终态跨平滑重启state/attempt保持，真实在途故障未注入。[机器摘要](evaluation/ai-reliability-20261023/validation-summary.json) |
| 其余上线与测量 | 范围/检查点/限制统一见[发布台账](history/releases.md)，原始JSON保留；不重复堆历史测试数量 |

实际操作遵循[开发](development.md)/[部署](deployment.md)/[运维技能](../.agents/skills/gying-project-ops/SKILL.md)。文档整理只验证编码、链接、契约、证据保留与diff，不触发服务或外部业务。
