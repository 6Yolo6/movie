# 发布与恢复台账

日期均为原交付/观测时点；文档精简没有重部署或重验生产。[现役配置/镜像](../current-project-status.md) · [问题台账](issues.md) · [完整Git原文](README.md#git-history)

## 近期交付

| 日期 | 范围与最终结果 | 验证/恢复入口 |
| --- | --- | --- |
| 11月23日百度搜索 | 自动采集保持仅种子，临时搜索在清理开启时允许视频；修复来源fragment/异常提取码及暂不可用误报。215项、入口/会话/QQ存活、启动ERROR0通过 | [机制](../cloud-drive-workflows.md#百度永久归档与临时搜索)；旧1b6220ae镜像保留，E:/gying-tools/releases/qq-baidu-temporary-video-20261123/rollback.py默认只检查；官方挑战及真实群内转存/自然删除待验 |
| 11月23日 | cloudflared升级2026.11.1，固定HTTP/2/IPv4及独立已验节点；排除443测速通过但7844失败的候选，持久/生成配置同步。303秒内HA持续4、入口与匿名拒绝通过、新增错误0；只有一条已验出站 | [现役与验收](../current-project-status.md)；回退 E:/gying-tools/releases/tunnel-stability-20261123/Rollback-Tunnel.ps1（默认只检查，-Apply恢复原路由与9.1旧任务，保留后续配置改动保护） |
| 11月19日 | TVmaze接入并改6小时10次；backend/frontend现役1b6220ae…/a8e00af6…，22:35上线。首轮9关联/1候选/0失败，真实6小时轮次待验 | [现役与两旧镜像/覆盖](../evaluation/tvmaze-frequency-20261119/README.md) · [首轮](../evaluation/tvmaze-metadata-20261119/README.md) |
| 11月18日 | source f24203be…修单个LF/CRLF种子尾缀；17761成功及官方两文件/目录/账号通过，首自然18125成功，百度父恢复10分钟1条 | [source旧镜像/三age检查点](../evaluation/baidu-torrent-recovery-20261118/README.md) |
| 11月17日 | frontend一键去重上线；单次6组，6来源/别名/18历史，23资源/12任务保持；17,967→17,961、87→81，7旧阻塞保持 | [SQL检查点与实际结果](../evaluation/duplicate-one-click-20261117/README.md) |
| 11月15日22:27 | backend b8ef54e增加TMDB→GYING严格资源跟进；334项含18真实SQL通过，真实入口/host/daily收尾通过，自然资源上传待验 | [检查点/覆盖](../evaluation/tmdb-gying-followup-20261115/README.md) |
| 11月15日17:17/17:18 | 两应用1adbc33上线UPDATES，榜单前2页/24小时10详情，旧RANGE暂停；首18064/18065各10成功 | [七age/覆盖与首轮边界](../evaluation/metadata-update-watch-20261115/README.md) |
| 11月14日 | 两应用0ce354e上线GYING去重及TIME/SCORE；自然20详情成功、夸克7/百度7归档成功，线上正向重复跳过未实证 | [七age/固定覆盖](../evaluation/gying-collection-20261114/README.md) |
| 11月13日21:07 | 仅迅雷六工作流关闭；20任务15空间不足，精确配额受captcha阻断，保持暂停 | [配置/SQL两age与限定回退](../evaluation/xunlei-workflow-pause-20261113/README.md) |
| 11月13日13:31 | 原nginx/backend/MinIO恢复；消除固定IP冲突，端口/SSR/海报/20入口通过，内存和代理信任待处理 | [DPAPI原配置与恢复限制](../evaluation/entrypoint-recovery-20261113/README.md) |
| 11月11日 | backend65fd377可信TMDB/canonical修复；66组/380关联/512history/7legacy、MobLand两字段及67详情/67列表/6SSR通过。百度17650手动/17655自然成功，官方V5对账通过 | [数据/七age/未决UNKNOWN](../evaluation/duplicate-tmdb-binding-20261111/README.md) |
| 11月11日23:03 | 迅雷官方/risk初始化拦截修复；原两小时任务自然续期、官方200/同账户/原子写回，0密码提交 | [脚本/DPAPI旧状态](../evaluation/xunlei-token-refresh-20261111/README.md) |
| 11月10日23:09 | backend2f282d2加上传后及自有分享后逐文件/fileId守护；1442过/1Redis跳过，真实入口通过，当时真实归档仍未成功 | [七age/旧镜像](../evaluation/baidu-archive-file-guard-20261110/README.md) |
| 11月10日20:06 | backend0bba123兼容严格已完成Quark/P2P凭证；MobLand真实预览通过、当次未合并，原rawhash/selector失败有补证且不改写 | [七age/固定覆盖](../evaluation/mobland-merge-compat-20261110/README.md) |
| 11月10日16:10 | backendf8b0653修opaque上传ID；1425过/1Redis跳过、23GET通过，三个UNKNOWN保留。当日DBA追加元数据过程/全局SHOW_ROUTINE | [权限范围/七age/旧镜像](../evaluation/baidu-runtime-merge-metadata-20261110/README.md) |
| 11月9日22:49 | 两应用048795f上线全库扫描/临时搜索清理和上传分享兼容；真实ADMIN扫描15,366/144待审，临时删除/配额未实测 | [七age/pin后三age](../evaluation/baidu-search-duplicate-20261109/README.md) |
| 11月9日21:21 | 百度扫码GUI启动器上线；三次自然0可见窗口、exit0，原任务参数/ACL保持 | [宿主原像/回退工具](../evaluation/console-window-fix-20261109/README.md) |
| 11月9日13:54 | 两应用b55080d按GYING ID补种子；17584首次上传UNKNOWN，当次自动关闭，未重放 | [七age/pin后三age与原结果](../evaluation/baidu-torrent-backfill-20261109/README.md) |
| 11月9日01:22 | 两应用1a9ff55慢速补全；真实入口/ADMIN通过，01:34原扫码任务恢复Enabled/Ready/exit0 | [七age及会话/权限](../evaluation/baidu-backfill-20261109/README.md) |
| 11月9日00:32 | 两应用90defca百度/文件类型上线；用户建表，00:48专用账号官方扫码并加密导入；当次未转存/分享 | [结构/七age/登录检查点](../evaluation/baidu-file-selection-20261108/deployment/README.md) |
| 11月8日22:34 | 两应用6b4104f网盘独立工作流上线；真实ADMIN/入口通过，维护journal认证备份后收尾recovered | [六age/回退与维护边界](../evaluation/cloud-drive-workflows-20261108/deployment/README.md) |
| 11月5日 | 官方可查3自动+1手动微博；用户已找到旧创建日期帖子，无UI修复，3历史UNKNOWN保留 | [只读核查](../evaluation/weibo-readonly-20261105/README.md) |
| 11月3日23:04 | backendd24e08f修QQ第二部目录；仅2177重试成功、资源NORMAL/回复通过，未群内实发 | [五age/真实自有分享](../evaluation/qq-prada-transfer-20261103/README.md) |
| 11月2日22:56 | OpenClaw搜索错误即结束插件处理；17回归/实际插件/白名单/UTF8/helper通过，群内端到端待验 | [源/唯一runtime与证据](../evaluation/qq-search-error-20261102/README.md) |

## 早期必要记录

<a id="identity-theme-20261104"></a>
### 11月4日：电影身份与主题

Movie d6641e7/Agent6c277a4于22:39/22:42上线，入口与各停止窗口五/三age认证通过；新增身份历史阻断不兼容旧Movie。7轮完成仅满足3/部分1/缺证2/未满足1、真人0，第一阶段未完成。[固定产物/检查点](../evaluation/identity-theme-20261104/deployment.md)。

<a id="query-quality-20261103"></a>
### 11月3日：查询条件与可信历史

Agent15a2b8a/Movie13660c9于21:12/21:47上线，三/五age及入口通过；v2请求出现后禁止旧Movie自动回退。网站4机械通过但模型仅满足2/未满足1/缺证1；临时test已禁用/设备撤销。[部署](../evaluation/query-quality-20261103/deployment.md)、[网站复核](../evaluation/query-quality-20261103/website-review.md)。

<a id="tmdb-empty-range-20261102"></a>
### 11月2日：TMDB空范围冷却

fe042692两应用修复104轮空跑；自然17301记录EMPTY_RANGE，24小时冷却已验，跨日/增长待验。六age检查点 G:/gying-backups/tmdb-empty-range-20261102-20261102T110716.404280Z；回退旧backend会恢复空跑。[结果](../evaluation/tmdb-empty-range-20261102/README.md)。后续UPDATES已替代旧RANGE运行。

<a id="agent-quality-final-20261101"></a>
<a id="agent-quality-rollback-20261101"></a>
<a id="quality-improvement-20261031"></a>
### 10月31日–11月1日：质量改进与Agent回退

Movie22:58/Agent11月1日17:12最终上线；Agent上午候选核验失败已确认应用回退，后续成功单列。四问完成但满足2/未满足1/部分1、真人0。Movie检查点 G:/gying-backups/movie-quality-20261031-20261031T145725.979995Z；最终Agent三age G:/gying-backups/ai-agent-quality-20261031-20261101T091153.915087Z。原82条审计、三轮开发回答/评分和失败字节保持，[最终部署](../evaluation/quality-improvement-20261031/deployment-final-20261101.md)。

<a id="metadata-source-pause-20261029"></a>
### 10月29日：目录独立暂停

两应用支持六目录分别暂停，范围/游标/草稿保持，真实管理视口和入口通过；完整图片与直连fake-IP仍有边界。六age G:/gying-backups/metadata-source-pause-20261029T153345.685852Z；旧Worker回退先保护pause意图。[报告](../evaluation/metadata-source-pause-20261029/README.md)。

<a id="resource-recovery-20261029"></a>
### 10月29日：内存与入口恢复

backendc4b8955缓存懒加载/复用，heap768MiB/容器1280MiB，QQ/冷暖TTL/网页通过；Agent9cd1d2b当晚上线，评分排序/剧情遗漏保留。检查点 backend-recovery-20261029T095350.591530Z、ai-agent-final-20261029T125645.591658Z均在G:/gying-backups/；覆盖/旧镜像 E:/gying-tools/releases/ai-recovery-20261029-1750/。[结果](../evaluation/resource-recovery-20261029/README.md)。

<a id="restart-window-20261026"></a>
### 10月26日–28日：重启窗口与恢复

Python3.10不兼容.NET七位小数窗口，安装器改秒级+08:00并由实际Python预验；原09:30失败未确认同因。10月27日20:03真实重启、原登录恢复失败；10月28日迁Ollama至21434并手动恢复，原Worker/任务类型还原。脚本/回退 E:/gying-tools/releases/restart-window-20261026/，恢复材料 G:/gying-tools/restart-20261018/recovery-20261028/port-21434-recovery/；完整自然维护仍待验。[手册](../weekly-graceful-restart.md)。

<a id="ai-availability-20261025"></a>
### 10月25日：AI服务恢复

cd79797修独立WMI隐藏启动、Windows Job拒绝及有界30秒片库读取；真实只读队列/9入口通过，唯一终止原因和人物/年份偏差未闭环。Agent三age G:/gying-backups/ai-agent-20261025T091941.886931Z，旧JAR/脚本 E:/gying-tools/releases/ai-assistant-unavailable-20261025/；保留当前state/key。

<a id="ai-query-20261025"></a>
### 10月25日：结构化查询/语义索引

Movief209e87/Agent8a65287上线Schema/Bing/BGE-M3，23入口/游客页通过；8轮开发对照不等于全答对。15age G:/gying-backups/20261025T080048.799654Z，固定覆盖/旧镜像 E:/gying-tools/releases/ai-query-plan-v2-20261025/；原10,432留出、11,220对照和11,272索引分别保留。

<a id="ai-reliability-20261023"></a>
### 10月23日：加密队列与回执

Movie3052f2c/Agente11c631启用独立state/key、45秒lease和幂等回执；两终态跨平滑重启保持，真实在途故障未注入。G:/gying-backups/20261023T044352.387300Z、20261023T045302.843250Z各14age认证；旧包 E:/gying-tools/releases/ai-reliability-20261023/，保留新state/key。

<a id="manual-completion-20261023"></a>
### 10月23日：手动资源补齐

2b1809b两应用上线provider分流/原位修复/P2P同步，入口通过、当次未真实转存/发布。11age G:/gying-backups/20261023T132303.117468Z；覆盖/旧镜像 E:/gying-tools/releases/gying-manual-completion-20261023/。[机器摘要](../evaluation/gying-manual-completion-20261023/validation-summary.json)。

<a id="metadata-weekly-20261005"></a>
### 10月5日：元数据/三个周榜分离

e6dead5/1aaa401三服务上线，紧凑JSON适配既有列，无DDL；三类型真实GET和本机通过，实际周榜/完整公网/视频转存未全验。五age G:/gying-backups/20261005T032935.839103Z；旧backend/frontend gying-metadata-crawl-*:20261004a、source gying-series-search-source:20260929a，旧Worker可能恢复视频消费，先核兼容。

<a id="earlier-releases"></a>
## 更早来源

| 日期/事项 | 必要材料/限制 |
| --- | --- |
| 10月22采集进度/资源链接 | E:/gying-tools/releases/crawl-resource-links-20261022/；G:/gying-backups/20261022T132212.039731Z，9age，范围/游标保留 |
| 10月22 AI资料 | E:/gying-tools/releases/ai-research-20261022/；G:/gying-backups/20261022T053706.234649Z，7age，摘录与全文边界保留 |
| 10月21/22特征/BM25 | E:/gying-tools/releases/catalog-features-search-20261021/；G:/gying-backups/20261021T160733.115483Z，7age，合成题非独立泛化/CTR |
| 10月19/20桥接 | E:/gying-tools/releases/ai-assistant-20261019/；G:/gying-backups/ai-assistant-config-20261019-232528，旧Agent G:/local-ai-agent/backups/ai-catalog-20261019/before.jar |
| 10月19资源/P2P | 原resource-search-p2p-deployment-20261019.md从Git读取；账号/dbt不公开，NapCat不恢复 |
| 10月14访问身份 | E:/gying-tools/releases/access-identity-20261014/；G:/gying-backups/20261014T125228.430856Z，8age，旧NULL与新增列保留，回退不DROP |
| 10月13 Access | G:/gying-tools/security-20260926/access-enable-20261013-205517-925b9d；回退核精确归属，OTP/角色/origin JWT未全验 |
| 10月11优化/监控 | 原code-optimization-deployment-20261011.md、monitoring-resource-counts-20261011.md；UNKNOWN/认领/计数与UI机制已进现行手册 |
| 10月8来源身份/P2P | E:/gying-tools/releases/p2p-workflow-recovery-20261008/；utf8mb4_bin迁移2983行保留，旧58耗尽失败不重置 |
| 9月25恢复样本 | G:/gying-backups/20260925T030328.719395Z，19age/25表与MinIO隔离单图通过，完整异机恢复未验 |
| 9月14旧迁移 | migration-data/20260914-081539，旧回退E:/gying-data/gying-pre-deploy-20260914.sql；DPAPI限原主机账户，操作按现行[MIGRATION](../../MIGRATION.md) |

上述路径是原记录位置，本次未逐一验证存在或恢复可用性。已认证检查点与dry-run不等于跨服务原子快照、完整自然备份或异机恢复。所有应用回退保留当前SQL/data/state/key/上传意图，不重放UNKNOWN；G01–G11和许可/外部副作用限制持续有效。
