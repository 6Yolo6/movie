# 每日少量更新检查（2026-11-15）

已按用户授权仅部署 backend/frontend，代码 `1adbc336cdadefa48be40ff37082a6de840fbd41`，分支 `codex/cloud-drive-workflows`。后端于17:17:54、前端于17:18:25（北京时间）各启动一次；17:40启用UPDATES并恢复Worker，17:47最终只读审计通过。旧全量RANGE游标没有恢复。

16:16曾先通过一次后台配置暂停TMDB/GYING全量自动翻页，Worker保持开启，16:20独立只读复核无在途/无新增元数据。两份暂停回退材料在 `G:/gying-backups/metadata-bulk-pause-20261115-20261115T081647.394767Z`；旧范围、CSCORE暂停图及其他配置保持。TMDB电影旧范围曾到500后回绕，电视剧上游实测132页；500是本项目请求上限，不能据此认定整个TMDB片库采完。

`tmdbAutoSyncUpdatesOnly` / `gyingAutoSyncUpdatesOnly` 使用独立 `crawl.mode=UPDATES`、`keyword=UPDATES` 的METADATA_SYNC任务。每个平台聚合所选榜单前2页，检查间隔至少24小时，每轮累计最多10次详情尝试；已完成且语义摘要未变的影片跳过，热度、评分和排序变化不触发详情重采。首次以可信canonical TMDB身份或精确GYING完成凭证建立基线。待处理、离榜和失败项跨日保留，单项最多3次尝试，耗尽需复查。

已选TMDB每日电影/剧集趋势及即将上映，GYING时间顺序电影/剧集/动漫。旧范围仍可见，在更新模式下禁用；两个自动开关关闭时也暂停更新检查。仅集数、清晰度或资源变化而列表语义字段不变的影片，仍需手动刷新/追更。

| 首轮自然任务 | 结果 | 可信已完成基线 | 详情尝试/成功/失败 | 留待后续每日小批量 |
| --- | --- | --- | --- | --- |
| TMDB 18064 | SUCCEEDED，attempt1 | 34 | 10 / 10 / 0 | 61 |
| GYING 18065 | SUCCEEDED，attempt1 | 259 | 10 / 10 / 0 | 19 |

初始293条基线体现了首次去重，`lastRound.skipped=0`不能解释为未去重，也不代表另做了第二天同指纹正向跳过实验。跨日冷却、列表更新和待处理保留由本轮测试覆盖，生产次日实际行为尚未代验。

首轮GYING自然创建6项P2P归档：夸克18066/18068/18070各首次SUCCEEDED，8份上传文件检查点中含4个`.torrent`；百度18067/18071首次SUCCEEDED，4份中含2个`.torrent`。百度18069在UPLOAD_TORRENT被API_REJECTED，intent=REJECTED，FAILED1/3，保留2份非torrent上传检查点，原策略排到2026-11-15 18:12:48重试。未手工重试或重放；这些是任务行及上传检查点观测，未另逐个核验远端内容hash或分享目录。

当前TMDB UPDATES只更新元数据，不自动搜索GYING种子；GYING UPDATES沿可信mid读取resources、保存P2P并按配置归档，torrent下载发生在归档阶段。其他元数据源按用户选择只整理，未接入；推荐TVmaze剧集增量与Wikidata中文/跨库ID补全，电影继续依靠TMDB。IMDb/Trakt许可边界和未来GYING可信匹配要求见[来源建议](sources/README.md)。

本轮后端232/232定向测试通过，零失败/错误/跳过，包含5项生产同类型JSON字段的真实MyBatis/MySQL CAS检查；package未重跑测试，JAR446文件/412class与同stage产物逐字节一致。首轮旧文本fixture及3处测试期望失败材料保留，未作最终门禁。前端8项JSX/config-save及定向ESLint通过；最初Turbopack native Zone Allocation OOM保留，采用子进程内串行Webpack后TS/build通过，源码不改。固定镜像保持原Linux Node依赖，375产物SHA/size/UID核验及network-none/read-only smoke通过。

上线前后各4组真实ADMIN页面验收（中英、明暗、1440/390、刷新）通过，每阶段98次本机/公网资产GET一致，合计196次；全部API非GET在网络前拦截，页面验收本身生产配置写0、新登录/device0，正常last_seen可能更新。新模式生效后两开关、24小时/10次额度及旧范围/暂停图保持；独立运行配置/JAR/375前端文件、其他11服务代次和真实本机/公网入口通过。公网匿名管理为指定Cloudflare Access302，源站401，内部QQ404；同影片详情SSR实际H1片名匹配。至17:49的有界Docker日志ERROR/Exception/OOM匹配0、两服务重启0，未读取应用文件日志。就绪11PASS/1既有QQschemaWARN/0FAIL。

回退材料：完整预部署4age位于 `G:/gying-backups/metadata-updates-completed-predeploy-20261115-20261115T091532.990934Z`；停机SQL/data/key3age位于 `G:/gying-backups/metadata-updates-stopped-backend-20261115-20261115T091737.485175Z`。49项运行配置及全部密文hash/size/完整认证已核验。旧镜像保留，宿主expectedBackendImage仅一次CAS改新镜像，原像认证/ACL及任务XML保持；日常备份47→48仅追加本release路径，原像认证/原规则与权限、native dry-run通过。完整自然日常备份未代验。应用回退需先暂停更新并审查本轮新UPDATES/P2P状态，不直接恢复SQL或重放任务。

初次备份在宿主材料阶段停止：原密文认证有效却仅47/49项，缺host snapshot和prepared；原incomplete检查点及两失败报告保留，具体原Host错误未留存。后续仅复制两份已认证旧镜像密文，新建完整runtime和缺失SQL，不重导出旧镜像。实际各一次激活及nginx一次reload后，原入口脚本把正常公网Access302误判为401；停止结果保留，后续只读补齐，没有再次激活/reload/备份。旧TERM原始局部证明未持久化，回执明确只由冻结控制流及reservation证明已接受SIGTERM和0/143退出域，不填写实际退出码。原UI包装器因Windows路径分隔符的字符串比较在会话/浏览器前停止；V2仅改一行实际SHA比较，原文件保留，成功两阶段另记。

旧Quark2262/discovery9904、三个百度UNKNOWN、耗尽child17761全行保持；父17583仍BLOCKED/CHILD_FAILED，只有既有惰性调度的revision/time/error投影变化。迅雷工作流仍关闭。未修复、删除或重放这些历史，安全G01–G11和完整灾备边界未被代验。

[部署证据、失败材料及SHA](deployment/README.md)；[定向测试](backend-targeted.json)；[源码清单](backend-sources.json)；[暂停结果](pause-result.json)；[独立暂停复核](pause-independent.json)。
