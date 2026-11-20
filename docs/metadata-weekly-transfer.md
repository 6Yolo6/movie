# 元数据采集与周榜转存

[当前配置](current-project-status.md#当前采集配置) · [Resource Hub](resource-hub.md) · [网盘工作流](cloud-drive-workflows.md) · [来源研究](evaluation/metadata-update-watch-20261115/sources/metadata-sources-research.md)

## 元数据增量

| 来源 | 现役策略 |
| --- | --- |
| TMDB/GYING | UPDATES，所选榜前2页，24小时/最多10次详情；旧RANGE范围/游标和暂停图保留 |
| TVmaze | 剧集变更ID/时间戳，6小时/最多10次详情；已绑定优先，漏跑用重叠窗口补查 |
| TMDB→GYING | 新元数据同事务保存独立资源意图，按名称、类型、年份、季数与唯一canonical严格匹配；每24小时10次资源尝试，单项3次、失败至少24小时再试 |

Worker每60秒检查到期。可信身份/完成凭证和语义指纹跳过重复，待处理项持久保留；榜单排序/热度/评分变化不重抓详情，仅集数/清晰度/资源变化仍需手动刷新。请求页数上限或前2页重复不代表上游全库已采完。

TVmaze使用精确IMDb/TVDB→TMDB剧集身份，不近似猜片；保留已有中文/人工资料，记录上游版本、来源和CC BY-SA 4.0署名。只补元数据，不自动排视频转存。

GYING元数据沿可信来源读取resources并保存P2P，种子下载/上传由归档任务完成；TMDB新增资源跟进也复用该链路，资源失败不重抓元数据。P2P_ARCHIVE只上传磁力文本/种子，不启动BT视频下载；旧视频、发现/发布队列不因Worker启用而恢复。ARCHIVE_QUEUED只表示入队，云端成功需独立任务结果。

## 三个独立周榜

| 参数 | 约束 |
| --- | --- |
| 来源 | /hits/mv/week、/hits/tv/week、/hits/ac/week；缺失/错类型/非week失败，不回退综合榜 |
| enabled | 各榜独立，默认true；受Resource Hub/Worker及对应网盘阶段控制 |
| intervalDays | 默认7，范围1–365 |
| maxItems | 默认5部，范围1–20 |
| 时间 | 初始化/改频率或开关等待完整间隔；改数量不改下次时间 |

副作用前持久化nextRunAt，每次最多领一榜，失败/中断不每分钟重放。当前使用单实例锁，多实例需DB租约。优先复用自有分享，夸克即时执行不登记每日追更，不自动排PanSou fallback。已有quark-auto-save追更独立运行，本页不改其周期。

ADMIN config GET/PUT的weeklyTransferSchedules须完整包含mv/tv/ac；客户端运行时间、状态和任务ID忽略。sys_config键resource.hub.gying.weekly_transfer_schedules使用版本化紧凑JSON，任务WEEKLY_TRANSFER/GYING_WEEKLY不由普通Worker消费。source认证GET /weekly-popular?typeCode=mv&limit=5。

## 保存与历史清理

迅雷视频保存到配置根下的影片/来源子目录，逐文件确认完整预期集合与目标父目录；部分/不同视频失败，计划和文件ID持久保存，失败保留WAITING_SHARE。仅移动本轮尚未落位文件，不重转存；未知结果先远端核对。

历史目录、追更和已发布文件不批量清理。迁移前核ID/父目录/分享、保存清单并按批准小批执行。旧目录容量和失败流水从[Git原文](history/README.md#git-history)追溯，不能作为今天删除依据。

## 验收与回退

10月5日三周榜真实GET及本机入口通过，实际首轮/完整公网与真实视频转存未全验；初次预计10月12日已过，仍按待验记录处理。[发布台账](history/releases.md#metadata-weekly-20261005)保留原检查点/回退边界。

11月15日TMDB/GYING首轮各10详情成功；11月19日TVmaze首轮9关联/1候选/0失败、现役6小时配置已验。真实6小时轮次、后续新TMDB资源上传和长期积压待自然观测。应用回退暂停相应写入并保留当前SQL、游标、身份/上传检查点；旧Worker兼容性先核，不清队列或恢复旧dump。
