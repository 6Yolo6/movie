# 当前项目状态

更新时间：2026-10-25

时区：Asia/Shanghai。2026-10-25 17:19–17:20仅替换本机Agent，修复独立后台启动与冷片库读取超时；[服务恢复](ai-assistant-availability-20261025.md)。同日16:00–16:01获本轮“可部署”授权后切换AI查询backend/frontend/本机Agent，启用指令4B与语义索引；详见[部署报告](ai-query-plan-v2-20261025.md)。前序生产变更为2026-10-23 21:22–21:23：仅部署GYING手动资源补齐backend/frontend，入口/页面验收截至21:25；本机nvm Node升级24.22.1并重启两个Node服务，frontend容器补至22.23.4。晚间AI评测/题集/链路文档及USER账号网站验收保留独立证据，测试账号已禁用；12:43–12:54启用AI加密状态，10月22日采集与其他事实保留原日期。

[导航](README.md) · [API](api.md) · [部署](deployment.md) · [安全](security/README.md) · [历史](history/README.md)

## 当前目标

维护统一片库与自有资源，提供网页/QQ 检索、个性化推荐、可追溯采集/归档/发布；AI 查询只使用授权业务数据和实际依据。

## 运行架构

| 层级 | 当前接线与约束 |
| --- | --- |
| 公网入口 | `gyinghub.dpdns.org` → Cloudflare Tunnel → `127.0.0.1:80` nginx → frontend/backend |
| 应用 | Next.js frontend、Spring backend、Python gying-source、Node social-publisher |
| 数据与集成 | Windows MySQL、Redis、MinIO、PanSou、quark-auto-save、独立 OpenClaw |
| 宿主任务 | Tunnel、原腾讯频道 CLI、迅雷 Edge 会话同步；QQ 每日推荐属于 backend QQBot |
| 生产配置 | 显式 `-p gying-movie -f docker-compose.prod.yml` 加已审查覆盖；JVM/容器时区 Asia/Shanghai |
| 主机路径 | 仓库 `D:/gying-movie/movie`；Docker 数据 `G:/dockerdesktop/wsl/DockerDesktopWSL`；敏感运行配置在仓库外 |

NapCat 已停用；旧 `secondary`、`qq-v3` 频道目标保留历史且 `enabled=0`。2026-09-15 开关观察为 Resource Hub/QQ/频道/微博/网盘计划启用；高频 Worker 在 10 月 5 日之后只处理元数据，不消费旧视频队列。恢复环境先关闭自动化，再按原配置和逐项验收恢复。

Tunnel 使用 HTTP/2、原任务 `GYing Cloudflare Tunnel` 和 `E:/gying-tools/cloudflared/config.yml`。2026-10-14 已将边缘出网接入 Clash 的 `GYing-Tunnel` fallback（代理 → DIRECT），配置保存在订阅增强文件；ready 入口 `127.0.0.1:20241/ready`。故障与回退见[排障](../.agents/skills/gying-project-ops/references/troubleshooting.md)，不直接编辑生成的 `clash-verge.yaml`。

## 已具备能力

| 领域 | 已实现/部署能力 | 专项说明 |
| --- | --- | --- |
| 片库 | 电影/剧集/动漫分页筛选、热搜、收藏评论通知、资源审核、软删除、来源归一；资源搜索二维码和可点击链接，网盘/磁力/种子分组 | [API](api.md)、[数据库](database.md) |
| 页面 | 中英品牌/明暗主题/响应式、竞态保护；AI/相关推荐新标签页；磁力/种子分别计数 | [详情验收](movie-detail-enhancements-20261021.md) |
| AI | 登录片库助手、标题完整分页；收藏/衰减浏览稀疏特征与混合推荐；结构化条件/原文人物核验、BM25+BGE-M3融合、指令4B与固定Bing搜索轨迹；多轮候选/序号走受控本机桥接；任务/回执加密持久化与可续期租约已启用 | [当前成果](catalog-features-search-20261021.md)、[前序桥接](ai-assistant-20261019.md) |
| 采集与治理 | TMDB/GYING 每来源独立起止页 1–500、修改范围保留范围内页码/游标、独立失败元数据重试；电影合集/剧集季严格消歧；失效云盘分享原位修复 | [资源中心](resource-hub.md) |
| 网盘视频 | 三个GYING周榜独立计划；手动确保/按ID修复共用双盘有效性检查、失效原位修复及P2P同步；有效才跳过，不确定待核验；QQ按需与旧夸克追更独立 | [采集/周榜](metadata-weekly-transfer.md)、[手动补齐](gying-manual-completion-20261023.md) |
| P2P | 来源磁力/种子解析、1080P/4K 小文件归档；账号 `/dbt/` 下载 URL 从公开结果及新任务隔离，旧引用保留 | [采集](gying-p2p-collection.md)、[归档](p2p-cloud-archive.md) |
| QQ/发布 | 搜片→候选→用户选择单资源才转存；backend 每日推荐/原频道 CLI/独立 QQ 微博发布器分开；不确定发布阻断重试 | [发布](social-publishing.md)、[多盘正文](qq-channel-multi-drive.md) |
| 用户/监控 | 注册上限/邀请、昵称、用户名或邮箱登录、验证码重置与设备吊销；API 日志原 IP/UA、服务端日志分页、热搜缓存回退 | [API](api.md)、[身份验收](access-log-client-identity-20261014.md) |
| 主机维护 | 迅雷同账户 Edge 刷新/验证/非 root 原子写回；每周优雅重启任务已注册 | [迅雷](xunlei-token-sync.md)、[重启](weekly-graceful-restart.md) |

资源绑定编辑使用 `bindingVersion`，更新已有同组记录、只新增新绑定，不删除取消勾选的历史行；系列补全只在有效合集明确季范围内复用分享，电影续集只补元数据。临时/后台转存补配图的代码已部署，真实复制链路仍按下节待验收跟踪。

## 配置与安全

- 2026-10-25本轮复检 **58 PASS / 11 FAIL / 1 UNKNOWN**，G01–G11保留；UNKNOWN为退出的独立旧迅雷探针root_processes。用户本轮明确“可部署”仅授权本次三应用业务例外；未启动探针或实施依赖加固。
- 2026-10-23 安全复检 **59 PASS / 11 FAIL / 0 UNKNOWN**，G01–G11原失败全保留。用户在获知原失败后对本次GYING手动补齐候选明确“部署”；仅用于本候选，不代表风险关闭或其他发布授权。[机器台账](security/hardening-gates.md)、[风险](security/risk-register.md)。
- nginx/backend 为 loopback，应用非 root；internal/QQ 公网路径 404，源站匿名管理 API 401，公网管理路径由Access拦截。Quark/MinIO 仍非 loopback，五依赖缺 NNP、三依赖有 root 进程，Redis 仍在共享网络。
- 2026-09-28 MySQL 8.0.28、25 InnoDB 表；三应用共用 `gying_app@%`，库级 SELECT/INSERT/UPDATE/DELETE/EXECUTE；TLS 强制关闭、local_infile 关闭、secure_file_priv=NULL。专用 localhost 备份账号已验证，分服务身份/grants 尚未完成。
- 2026-09-25/28 Public/Private Firewall 与物理网卡六敏感端口拒绝规则已验证；同网有线客户端报告不可连接。公网直连/IPv6 未验收，不重复 Apply。Quark config 父目录 root `0700` 已收紧，文件自身权限/非 root/旧 Cookie 撤销未完成。
- 2026-10-13 账号侧 Access 已启用：四类管理路径、单邮箱 OTP、应用/策略 1 小时；回读/匿名拦截通过。本人登录、网站 ADMIN/USER、origin JWT/WAF 待验收，不重复创建或录入 Token。
- 2026-10-25 Movie与Agent工作区密钥扫描均为0；历史扫描的 1,278 Blob/105 重复规则命中属于旧证据，轮换和历史重写未闭环。真实 `.env`、Cookie、JWT、API Key、备份私钥、完整 inspect 只在外部受限存储，不写入文档。

2026-10-22 本轮已部署短语/演员分流、对话无资源搜索入口、联网百科及安全调用轨迹；[验收](ai-search-web-20261022.md)。59/11/0再次确认，用户本轮明确批准先业务部署，原失败继续保留。

### 当前部署与恢复基线

| 服务 | 当前镜像 |
| --- | --- |
| backend | `sha256:fa06d609e0d04ecaf790f0965ba7b0a4ecfe5030e2b7a25f60b1814e4227221a` |
| frontend | `sha256:9b4eb2377a1ce3608b9f501c6ef8c4c8394e086684ae3c931486d5f7838ae177` |
| gying-source | `gying-resource-p2p-source:20261019b` |
| social-publisher | `gying-code-optimization-social-publisher:20261010a` |

2026-10-25 17:19–17:20本机Agent补丁代码`cd79797`，当前JAR SHA`33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`。四服务消失与冷片库超过旧10秒read上限分别修复；独立WMI启动和read/call30秒仍受总体deadline约束。91项verify、6项真实Job/9项配置、完整JAR隔离启动通过；首次公网403验收已平滑回退，第二次成功。三份age认证Agent检查点`G:/gying-backups/ai-agent-20261025T091941.886931Z`保留；Movie容器、其他本机实例、配置和索引hash保持，真实条件/语义/Bing/生产只读队列及9入口通过，但人物/年份解析偏差仍保留，网站登录条件查询未复验。见[恢复报告](ai-assistant-availability-20261025.md)。

2026-10-25 16:00–16:01仅替换backend/frontend/本机Agent，业务代码Movie`f209e87`/Agent`8a65287`；当时Agent实际SHA`b7bd517cbe6f7c5b8cdf01ce0d8872460958fc8b108653d7c8882fa74ff7f399`。生产Compose加本轮外部`deploy.json`，15份age认证检查点`G:\gying-backups\20261025T080048.799654Z`与原镜像/包回退保留。首次Agent装配失败已回退，修复、87项verify和完整JAR启动通过后第二次成功；23入口、95静态、3依赖与四组真实游客页面通过，实际8880恢复，其余容器身份、启动时间、重启数以及转存/发布审计保持。当前索引11,272条，原11,220开发对照/10,432留出快照分别冻结；网站登录条件查询未复验。见[报告](ai-query-plan-v2-20261025.md)。

2026-10-23 21:22–21:23仅切换backend/frontend，功能提交 `2b1809b`；有效覆盖和旧镜像回退材料在 `E:/gying-tools/releases/gying-manual-completion-20261023/`，检查点 `G:/gying-backups/20261023T132303.117468Z` 的11份age哈希/完整认证通过。其余9个容器、有效配置、调度及发布转存审计不变；26真实入口、3内部依赖、线上JAR/95静态文件与三组模拟API页面通过。见[部署验收](gying-manual-completion-20261023.md)。

本机nvm活跃Node由20.17.0（已EOL、存在官方披露漏洞）升级24.22.1，npm11.19.1；官方包哈希一致，旧版本保留。AI frontend/Ollama启动器已用新版本重启且健康/生命周期通过，Java实例未变。生产frontend容器Node22.23.2补到22.23.4，其他容器运行时未改；[官方安全公告](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases)。

前序2026-10-23仅替换backend与本机Agent，源码分别`3052f2c`/`e11c631`；持久化两侧已启用，独立状态key受限保存并加密备份；日常备份已补state/key/部署覆盖，dry-run通过。初次/重启检查点`G:/gying-backups/20261023T044352.387300Z`和`G:/gying-backups/20261023T045302.843250Z`各14份age哈希/完整认证通过；回退及旧包在`E:/gying-tools/releases/ai-reliability-20261023/`。真实入口16项、新包模型8/8、两条终态跨平滑重启状态/attempt不变；[部署验收](ai-reliability-deployment-20261023.md)。

前序2026-10-22 21:22仅切换backend/frontend，功能提交 `706ae3e`；实际覆盖和旧镜像回退材料 `E:/gying-tools/releases/crawl-resource-links-20261022/`，检查点 `G:/gying-backups/20261022T132212.039731Z` 9份age的哈希/完整认证解密通过（含两个发布状态卷）。其余9个容器运行身份/启动/重启不变，应用有效配置摘要不变；[本轮验收](crawl-resource-links-20261022.md)。前序13:37切换backend/frontend，本地AI最终包13:47切换；回退材料 `E:/gying-tools/releases/ai-research-20261022/`，检查点 `G:/gying-backups/20261022T053706.234649Z` 7份age认证通过。前序功能提交 `add5d78`，文档校正 `dc13094`；2026-10-22 00:07–00:08 只替换 backend/frontend，9 个其余容器不变。有效覆盖及旧镜像回退文件在 `E:/gying-tools/releases/catalog-features-search-20261021/`；检查点 `G:/gying-backups/20261021T160733.115483Z` 的 7 份 age 产物 hash/完整认证解密通过。[前序验收](catalog-features-search-20261021.md)。

2026-09-25 完整逻辑/持久数据检查点 `G:/gying-backups/20260925T030328.719395Z`：19 文件约 372 MiB、冻结约 31 秒；25 表行数/CHECKSUM 和隔离 MinIO 单图验证通过。应用/系统账号/异机灾难恢复及私钥离线保管仍未完成。当前发布检查点与完整恢复范围不同，见[备份方案](security/backup-plan.md)。旧镜像、迁移资料和前序检查点见[历史索引](history/README.md)，兼容应用回退不覆盖新 SQL。

## 仍需处理

| 事项 | 必须补的证据/工作 |
| --- | --- |
| 安全整改 | G01–G11、公网/IPv6、分服务 DB、MinIO policy/root key、Quark 单文件/身份/Cookie、OpenClaw 内网、WAF/origin JWT、异机恢复与离线私钥 |
| AI/求职 | 2026-10-25[查询修复](ai-query-plan-v2-20261025.md)已上线分流/原文人物/多轮/真实Bing/语义检索；当前Agent91项及6项独立启动测试通过，原查询部署Agent87项、后端全套731（730通过/1跳过）及后续29项、构建器9项通过。两组8轮实际开发对照与海上误召回如实保留，未独立盲评；原30题/44轮与10,432快照冻结、旧测试账号禁用。独立评分/真实网站登录查询/点击、持续误召回、增量刷新和usage审计仍待；文档RAG/API Key/配额未交付 |
| AI下一阶段 | [可靠性部署](ai-reliability-deployment-20261023.md)已启用加密任务/回执、可续期租约、有限重试、取消/幂等；真实内部模型链路及空闲平滑重启2条终态恢复通过。真实USER网站API验收见[评测](evaluation/human-holdout-v1/README.md)；在途回执故障、中间步骤与长期审计待验收；[路线](ai-strengthening-roadmap-20261022.md) |
| 每周重启 | 2026-10-25 09:30:30触发结果1，原preflight未留失败原因；P2P在途可能阻挡，非唯一确认。已补尝试日志并登记/回读今晚2026-10-25 20:00 +08单次优雅重启：Ready、20:00–20:10开始/21:00截止，失败/过期不补跑。真实重启/ASUS登录恢复未验收；下周2026-11-01 09:30:30 |
| QQ/周榜/发布 | 原频道多盘正文自然出站、三周榜首轮结果、QQ 每日推荐官方消息权限/群内出站、临时转存到期清理；未手动试发补验收 |
| 失败与历史队列 | 自然元数据失败隔离分支仍缺线上证据；2026-10-08 的 58 条耗尽归档失败、2026-09-28 旧 RUNNING 保留，重试须精确任务范围 |
| 上游写入与配图 | GYING publish 的两个 RuntimeError 尚不能区分重复/拒绝/回执失败；双盘源图已只读存在，真实复制/分享未验收 |
| 迅雷与数据质量 | 自然失效后的已保存密码/验证码、backend refresh-token 独立续期与长期写回；根目录历史文件、乱码/重复/空目录逐批 dry-run 后确认 |
| 浏览器与入口 | Headless Edge 视口不等于 iOS/Android 真机；真实已读/编辑事务未全验收；Tunnel 兜底需观察故障再现；跨版本同片去重/海报缓冲仍待优化 |
| 日志调查 | 10 月 18 日 QQ 空值 500 与 GYING 503 保留独立问题；10 月 19 日短窗口 5xx=0 不等于全天或持续恢复，见[调查](api-4xx-investigation-20261018.md) |
| 邮件 | Resend 域验证与 163 delivered 有旧证据，Gmail 对免费域拒收仍待解决；不从一次投递推导所有邮箱可用 |

2026-10-08 的 QQ 多盘“下一次 10 月 16 日”、周榜“首次 10 月 12 日”等已过去，仍是未验收记录，不当作未来调度。历史目录数量/磁盘余量不代表现在；原清单及回退线索完整保留在[整理前快照](history/snapshots/20261022/docs/current-project-status.md)。

## 长期不变量

- `movie_metadata`/`resource_link`/`movie_source_identity` 是主模型；外部 GYING ID 与本地 canonical 分开，大小写身份保留 `utf8mb4_bin`。
- 自动资源先验证自有分享，再入库；不确定匹配进人工候选，删除合并优先 dry-run/软删除。
- UNKNOWN/PUBLISHING/历史 FAILED 不批量回 PENDING，状态机版本协调部署/回退。
- 恢复先关自动化、保留数据与审计；不删除卷、不重放旧队列、不用旧 dump 自动回滚在线数据。
- MySQL/JVM/容器时区一并核对；任务注册/运行、HTTP/业务成功、模拟/真实、备份/恢复分别记录。

## 验收

2026-10-25 17:19–17:24服务恢复：Agent91/91、合成Job6/6、研究配置9/9与新完整JAR隔离健康/握手/优雅退出通过；真实条件27.98秒、语义62.50秒、Bing17.42秒、只读生产队列7.52秒及9入口符合预期。当前Agent包与索引hash、配置/未改实例核验通过，短窗口ERROR/Exception=0；小模型人物/年份偏差如实保留，未测试网站登录或真实外发。20点任务只读回读全部绑定通过、尚未重启；[恢复报告](ai-assistant-availability-20261025.md)。

2026-10-25查询修复已部署：原88轮诊断定位分流/否定/多轮/检索与1.7B理解共同问题。后端全套731（730通过/1跳过）与后续29项、Agent87项、构建器9项、重启36项、启动配置9项通过；首次装配失败回退并补完整JAR验证。两组开发8轮完成但未记为全正确，真实Bing来源/同名排除可追溯，目标页未逐页访问。部署23入口/95静态/3依赖/四组真实游客UI通过；完整与增量索引、认证检查点及安全58/11/1见[报告](ai-query-plan-v2-20261025.md)。

2026-10-23 21:22–21:25 GYING手动补齐部署：ensure与按影片/资源ID修复共用双盘有效性检查、失效原位修复及P2P同步；后端706项（705通过/1依赖跳过/0失败）、工作流58项、真实MySQL14项与独立compile/JAR通过；前端tsc/lint和Node24完整webpack构建通过，候选/线上三组浏览器模拟API验收通过。生产JAR/95静态文件、26真实入口/3内部依赖匹配，其他9容器/有效配置/调度/发布转存审计不变，四应用本次日志窗口ERROR/Exception=0。11份新加密备份认证通过；安全59/11/0保留，本候选用户明确“部署”，未做真实转存/发布。本机Node24.22.1及frontend容器22.23.4升级完成。[部署验收](gying-manual-completion-20261023.md)、[机器摘要](evaluation/gying-manual-completion-20261023/validation-summary.json)。

2026-10-23晚间评测交付：57项离线行为/完整性测试通过；9场景10轮本机回归重复2次20/20程序检查、16回答待人工复核。30用户独立题保持原文拆44轮，普通USER网站实际88轮全部执行、84程序通过/4任务失败/0未执行，84回答待人工复核；详见[独立验收](evaluation/human-holdout-v1/README.md)与[机器摘要](evaluation/human-holdout-v1/validation-summary.json)。10,432条有效元数据只读事务快照不含用户/资源URL；线上仍用活库。首段本机/网站运行重叠存在负载干扰，不做SLA或无负载性能结论；未使用留出题调prompt，未冻结模型回答。该评测交付未修改应用源码/镜像，随后GYING部署另述；逐题人工评分、同数据retriever对比/真实UI仍待；[完整链路](ai-system-chain-20261023.md)。

2026-10-23 12:43–12:54：同候选获批后仅替换两个后端，启用私有加密状态；16真实源站/公网入口与新Agent模型8/8通过。两条终态在平滑重启后状态/attempt不变，两侧密文认证通过；真实内部自然查询/批量拒绝、10个未改容器和转存/发布审计通过。五服务短窗口ERROR/Exception为0；安全59/11/0保持。网站真实登录及生产崩溃/回执丢失未验收，见[部署](ai-reliability-deployment-20261023.md)。

2026-10-22 23:22–23:53 AI可靠性候选：隔离MySQL全量695项（694通过/1依赖跳过/0失败），含8项恢复测试及7查询/7监控SQL；最后回执续期边界与鉴权调整另跑16项全部通过并打包。本机Agent verify 60/60（含7项恢复测试），评测器18/18；真实独立JVM退出仅合成fixture/替身模型。两个候选JAR及后端镜像存 `E:/gying-tools/releases/ai-reliability-20261022/`；安全复检59/11/0，依规则停止生产替换，未创建密钥或启用持久化；现役镜像/包保持前序，本机与源站存活复查通过。恢复语义和证据见[候选报告](ai-execution-reliability-20261022.md)、[机器摘要](evaluation/ai-reliability-20261022/validation-summary.json)。

2026-10-22 23:07 AI强化首轮：独立评测工具18项离线行为测试通过；实际loopback助手/只读片库与百科工具7场景8轮重复两次，16/16程序校验通过。题集为实施者回归，12次成功回答仍待人工复核；未覆盖网站登录/站内队列/MCP或独立人工质量。仅脚本和文档交付，无应用镜像/配置/部署变更；[证据](evaluation/agent-harness-20261022/README.md)。

前序应用部署验收（2026-10-22 21:22–21:34）：后端687项/686通过/1依赖跳过/0失败，真实独立MySQL查询边界7项、监控7项及compile通过；前端typegen/tsc/lint/独立webpack生产构建通过，离线资源中心4组/链接3组、资源UI单测9项通过。生产jar与已审查镜像一致（342类/资源），95个前端静态文件匹配；本机/公网22入口及3内部依赖检查通过。线上HTML/静态配合mock API的公网资源链接/源站资源中心390px验收通过，真实登录业务API、真机、下载/转存/发布未执行。21:22–21:34四应用ERROR/Exception均0，5条原自动任务/独立范围配置及发布转存审计不变；[验收](crawl-resource-links-20261022.md)、[机器摘要](evaluation/crawl-resource-links-20261022/validation-summary.json)。

前序AI验收：677项/676通过/1依赖跳过/0失败，独立MySQL9项及compile通过；前端构建与四组页面、32入口通过，真实库为只读fixture。本地 AI 53 项 verify 通过，13:48 实际模型回答的摘录和 URL 与来源逐一一致；独立人工留出未完成。[本轮验收](ai-search-web-20261022.md)。

前序：后端 672 项（645 通过/27 跳过/0 失败），独立 MySQL 8/8，compile、前端 typegen/tsc/lint/build 通过；341 包内和 375 前端文件匹配；候选 17、本机/公网各 13 页面场景，32 真实入口通过。AI 页面 API 为 fixture；真实库只读、匿名鉴权与实际网站登录的边界分别记录。00:07:22–00:14:04 三服务无 ERROR/Exception，审计不变；00:26 复核远端与入口通过。[完整结果](catalog-features-search-20261021.md)、[可随仓库复现的摘要](evaluation/catalog-20261021/validation-summary.json)。

前序部署、测试数量和检查点集中在[历史索引](history/README.md)。按任务运行[开发测试](development.md)、[运维快照/就绪](../.agents/skills/gying-project-ops/SKILL.md)、[安全只读扫描](security/hardening-gates.md)；文档整理只检查链接、契约、迁移库存与脱敏，不重建服务。
