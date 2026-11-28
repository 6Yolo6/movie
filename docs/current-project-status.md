# 当前项目状态

更新时间：2026-11-26（Asia/Shanghai；本次复核QQ频道与群机器人，其余内容保留各自观测日期）。

[文档导航](README.md) · [部署与回退](deployment.md) · [发布台账](history/releases.md) · [问题台账](history/issues.md)

## 当前目标

维护统一片库、自有资源及可追溯的采集、归档和发布，提供网页/QQ检索、推荐和有依据的AI回答。机制与操作步骤见专项手册；本文只保留当前配置、版本及未决事项。

## 运行架构

| 层 | 接线与位置 |
| --- | --- |
| 公网 | gyinghub.dpdns.org → Cloudflare Tunnel → 127.0.0.1:80 nginx → frontend/backend |
| 业务 | Java17 backend、Next.js frontend、Python gying-source、Node social-publisher |
| AI | 网页分流/加密队列 → 本机Agent 5000 → Ollama 21434；Router 5002、Vue 3000仅本机 |
| 数据与集成 | Windows MySQL、Redis、MinIO、PanSou、quark-auto-save、独立OpenClaw |
| 主机 | 仓库 D:/gying-movie/movie；Agent D:/ai/local-ai-agent；Docker数据 G:/dockerdesktop/wsl/DockerDesktopWSL |
| 部署 | 显式 -p gying-movie -f docker-compose.prod.yml，加各服务现役覆盖；私有配置在仓库外 |

QQBot每日推荐、原腾讯频道CLI、social-publisher分别维护。NapCat停用，旧secondary/qq-v3目标为enabled=0。Tunnel配置仍在 E:/gying-tools/cloudflared/config.yml；原计划任务运行cloudflared 2026.11.1，产物在 E:/gying-tools/releases/tunnel-stability-20261123/，固定HTTP/2、IPv4与loopback 20241。

11月26日QQ链路恢复：频道CLI因nvm切换留在旧Node全局目录，现使用LOCALAPPDATA下GYing/tools/tencent-channel-cli独立安装的1.0.10；扫码重新授权后登录及官方帖子读取通过，原每日09:00、3篇、间隔300秒计划启用，下一自然发帖待验。群机器人原连接期间有持续token刷新网络失败，12:57仅重启原OpenClaw恢复新连接；13:02及13:18后自然群消息/回复有官方200和消息ID，搜索后端认证、原插件/白名单/UTF-8文案保持，重连后错误0；30项离线发布回归通过。

规则模式下，GYing-Tunnel独立固定到已验美国V6666，30秒主动健康检查；订阅增强、生成配置与运行态一致，普通代理选点不再牵连此出口。DIRECT及其他候选未通过实际7844握手/完整HA验收，只有一条已验出站，有效备用线路待补；回退见[发布台账](history/releases.md)。

11月28日21:51夸克账号切换：旧账号被上游限制新建分享，已将已验证的备用账号移到首位。1,364条旧追更已停用，记录和旧账号保留。认证配置、夸克管理页及QQ后端健康检查返回200，22:11群内首笔新请求因新账号根目录缺少 `救星小窝基地.jpg` 未生成分享；22:53补入原图并读回确认，重试分享待验证。

## 已具备能力

| 领域 | 当前能力 | 说明 |
| --- | --- | --- |
| 片库与页面 | 分类、收藏/评论/通知、审核、软删除、国际化/主题/响应式 | [API](api.md)、[知识导读](project-guide.md) |
| AI与推荐 | 复杂查询计划、人物核对、BM25+BGE-M3/RRF、指令4B、Bing来源、多轮候选和推荐理由 | [架构](ai-architecture.md)、[评测](ai-evaluation.md)；质量与点击收益尚无完整结论 |
| 可靠性 | 加密任务/回执、requestKey、45秒可续期lease、有限重试、取消和幂等恢复 | [恢复契约](ai-architecture.md#7-租约回执与恢复) |
| 元数据 | TMDB/GYING榜单增量、TVmaze剧集变更、来源身份和语义指纹去重 | [采集手册](metadata-weekly-transfer.md) |
| 网盘/P2P | 按盘独立工作流、自有分享、磁力文本/种子归档、百度逐文件校验 | [网盘手册](cloud-drive-workflows.md)、[P2P](p2p-cloud-archive.md) |
| 重复治理 | 全库扫描、预览审核、分批合并、永久旧ID别名和关联历史 | [合并规则](duplicate-movie-merge.md) |
| 主机维护 | 独立后台启动、迅雷会话续期、百度无控制台扫码、优雅重启/登录恢复 | [迅雷](xunlei-token-sync.md)、[重启](weekly-graceful-restart.md) |

11月25日周日重启已修复并实测完成：11月15/22任务被夸克2262遗留RUNNING阻塞，认证SQL检查点后单行CAS标UNKNOWN待远端对账，原请求/响应、次数及身份保持。控制器补锁前日志、限时锁等待、百度在途检查及Worker执行回执守护，126项维护测试、8项时间校验及管理员计划参数诊断通过。18:55启动维护，49项加密备份完整认证，19:03:48系统接受重启，主机19:04:21.5启动；19:09:20自然登录恢复完成，19:09:23 Worker恢复true，两个恢复任务均返回0。10个原容器同ID/镜像、4个本机AI服务、5个本机/公网入口及启动日志通过，无未完恢复；原周日计划启用，下一次11月29日09:30:30。

11月28日22:57发布迅雷和百度备用账号：每盘三个加密栏位，主账号仍执行任务。六个状态接口、公网新页面资源、匿名拦截和百度扫码助手检查通过，真实录入及扫码待用户操作。

## 配置与安全

### 当前采集配置

| 来源/任务 | 保存的频率与额度 | 范围 |
| --- | --- | --- |
| TMDB | 24小时一轮，最多10次详情尝试 | UPDATES；每日电影/剧集趋势及即将上映，各所选榜前2页 |
| GYING | 24小时一轮，最多10次详情尝试 | UPDATES；时间顺序电影/剧集/动漫，各所选榜前2页 |
| TVmaze | 6小时一轮，最多10次详情尝试 | 剧集变更ID/上游版本；可配置1–720小时，代码默认24小时 |
| TMDB→GYING资源跟进 | 独立预算每24小时10次，单项最多3次，失败至少24小时后再试 | 新TMDB元数据严格匹配GYING，再交既有P2P归档；未上映延后 |
| 百度存量种子补全 | enabled=true，每10分钟1条 | 父17583；11月18日阻塞恢复，原deferred UNKNOWN保留 |
| 迅雷网盘工作流 | 六开关全部false | 11月13日暂停，保持关闭 |
| GYING三个周榜 | 各榜独立，默认7天/5部 | 与元数据采集及已有夸克追更分开，实际自然结果仍需核对 |

Worker=true，每60秒检查到期；TMDB/GYING旧RANGE游标和CSCORE暂停图保留，当前使用UPDATES。榜单前2页和请求上限不代表全库已采完；仅集数/清晰度/资源变化仍需手动刷新。TVmaze只补剧集元数据，按精确跨库ID关联，不近似猜片；保留中文/人工字段及TVmaze、CC BY-SA 4.0署名。

- 私有.env、Cookie、JWT/API key、备份私钥和完整inspect只保存在受限外部位置。
- 安全最近记录仍为58 PASS/11 FAIL/1 UNKNOWN，G01–G11未关闭；专用DB/MinIO身份、IPv6、轮换、Access/origin JWT和异机恢复见[风险表](security/risk-register.md)。
- 三应用仍共用gying_app@%；11月10日获准追加全局只读SHOW_ROUTINE，可读取所有库的过程/函数定义，不能描述为仅本库权限。
- 应用非root、nginx/backend为loopback；Quark/MinIO绑定和共享网络等遗留风险保留。Access管理路径策略已启用，本人OTP、角色及origin JWT验收未闭环。

## 当前部署与恢复基线

前后端与百度扫码助手为11月28日发布观测，其余保留各自的交付记录。

| 服务 | 固定产物 | 最后记录的上线时间 |
| --- | --- | --- |
| backend | sha256:0314e7430025d5dd64338ed0deb017ae6a209e8fc01997ea6a59668bc1d0f4d1 | 2026-11-28 22:57:24；Compose30层 |
| frontend | sha256:958e2e174073fb78a91062bb6e5713ef5086db8ea74b9bfbd3d043aeb42c0998 | 2026-11-28 22:57:47；Compose19层 |
| gying-source | sha256:f24203be8b93f2d64b0bfeec6069e13a1b072cd03ae667edc98f45f60bb083ed | 2026-11-18 13:05:58 |
| 本机Agent | JAR 1d1ad1800339f94ffb4b85fa3d93bd6a495e37bd8e1dad5e2dbb59383aaa8a64；代码6c277a4 | 2026-11-04 22:42:34 |
| social-publisher | sha256:8b6dd6e77fa93f012add7fb7cc6f17082d13fa937a9d5b21bfb62697185381cb；代码7152edb | 2026-11-01登录/身份预检修复 |
| 模型/索引 | 影视qwen3指令4B、工作区1.7B；BGE-M3索引11,272条 | 索引SHA f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4 |

| 回退对象 | 保留材料 |
| --- | --- |
| backend | sha256:47b8c6e2d110f045a3dd3d7e830e4af24b7b384a7369ccfa223f9def08254a90 |
| frontend | sha256:a8e00af6b8c2e90f45a6211c1a85df8cb9108f6b540ba5d410c070cbc9ef39a6 |
| 两应用覆盖 | `E:/gying-tools/releases/drive-backup-accounts-20261128/release.py rollback` 恢复两镜像和百度助手；有备用扫码票据时先做兼容性核对，保留加密凭证及密钥 |
| gying-source | 旧镜像76fe318b24b1…；覆盖及三age认证检查点见[百度恢复](evaluation/baidu-torrent-recovery-20261118/README.md) |
| Agent | G:/gying-backups/ai-agent-identity-theme-20261104-20261104T144159.678847Z；旧包及配置/state/key认证检查点 |
| 日常/灾备 | 沿用[加密备份计划](security/backup-plan.md)；已记录的dry-run不等于完整自然备份或异机恢复 |

当前SQL、来源身份、别名、任务审计、上传文件及state/key在应用回退时保留；有新身份历史、百度未决意图或不兼容进度时禁止直接启动旧消费者。早期检查点和旧版本从[发布台账](history/releases.md)及[Git原文](history/README.md#git-history)追溯。

## 仍需处理

| 事项 | 下一步与边界 |
| --- | --- |
| 自然元数据轮次 | TVmaze真实6小时间隔、版本变化/积压，以及TMDB→GYING新资源上传仍待自然观测；11月19日22:37最后回读仍是18190，不能据预计时间补记次轮成功 |
| 百度 | 自动采集仅种子；临时搜索视频在清理开启时允许，成功后10分钟清理。22日QQ7/8仍CHALLENGE_REQUIRED需官方验证；来源格式误报已修。17584/17606/17622等deferred UNKNOWN保留 |
| 重复影片 | 11月11日66组、11月17日新增6组已成功；7组运行态/清理/身份阻塞及排除理由保留，候选不等于已确认重复 |
| 主机/依赖 | 11月13日入口已恢复；内存压力、Quark2262远端结果及APP_TRUSTED_PROXIES精确地址失配未解决，MinIO/backend本进程完整业务链仍有验收边界 |
| AI | 电影身份/核心主题与联网相关性仍缺证；最新7轮仅完成，模型满足3/部分1/缺证2/未满足1、真人0，第一阶段未完成 |
| 安全/维护 | G01–G11、完整自然备份/重启/登录恢复、异机与离线私钥验证未闭环 |
| QQ/发布/UI | 真实群内端到端、主动消息权限、周榜、临时删除/配额、外部发布、真机/部分真实事务、Gmail免费域拒收仍待处理 |

工具中间步骤、长期usage/成本和增量索引等计划见[AI路线](ai-career-roadmap.md)；完整未决清单见[问题台账](history/issues.md)。Wikidata、IMDb、Trakt尚未接入，许可与选型见[来源研究](evaluation/metadata-update-watch-20261115/sources/metadata-sources-research.md)。

## 长期不变量

- movie_metadata/resource_link/movie_source_identity为主模型；外部ID与canonical分开，utf8mb4_bin及关联历史保持。
- 分享验证后入库；未知身份待审，合并先预览/授权，优先软删除和关联迁移。
- UNKNOWN/PUBLISHING/旧FAILED不批量重置；恢复先关自动化，不用旧dump覆盖在线数据、不删除卷或恢复NapCat。
- 任务注册/实际运行、HTTP/业务完成、程序/人工质量、mock/真实、备份/恢复分别成立。

## 验收

| 日期 | 已验结果 | 仍有的边界 |
| --- | --- | --- |
| 11月23日隧道 | 官方SHA-256/签名及唯一进程通过；09:53–09:58共303秒，56次HA均4，主页/API/图片及匿名拒绝通过，新增请求/连接错误0 | context canceled表示请求取消，不能单独判掉线；有效备用线路待补 |
| 11月19日TVmaze | 接入156项/频率调整108项后端定向，前端11项、构建/隔离Linux/视口/本机公网入口通过；首18190为10尝试、9关联、1候选、0失败 | 90待后续预算；6小时自然轮次未复验 |
| 11月18日百度恢复 | source61项回归；17761两文件45,961B及官方内容/目录/账号核验通过，首自然18125四文件255,864B成功 | 后者未再次逐文件独立官方核验；父103处理/84成功/19跳过/2历史失败为当时快照 |
| 11月17日去重 | frontend37项回归及入口通过；单次6组、6来源/别名/18历史，23资源/12目标任务保持，12新旧详情通过 | 全库17,967→17,961、候选87→81仅当次观测，7阻塞未合并 |

各轮只保留最终结果和必要限制，详细失败、原始报告与回退位置见[发布台账](history/releases.md)；本次文档整理只检查编码、链接和事实一致性。
