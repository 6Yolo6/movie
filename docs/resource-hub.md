# Resource Hub 架构

[导航](README.md) · [接口](api.md#resource-hub) · [数据库](database.md) · [现状](current-project-status.md)

## 数据规则

canonical 主模型为 movie_metadata/resource_link；外部身份独立保存在 movie_source_identity。站内 popularity 与 tmdb_* 分开；发现/双盘任务保留轨迹。自动视频资源先验证自有分享，核心记录软删除，不确定匹配进入人工候选。

## 流水线

| 触发 | 处理范围 |
| --- | --- |
| 高频 TMDB/GYING、手动元数据 | 元数据/海报/原始 P2P；独立小文件 P2P_ARCHIVE，不发现或转存视频 |
| 普通 Worker | METADATA_SYNC；不消费旧发现/转存/发布，定时 discovered retry 不执行 |
| 三个 GYING 周榜 | 独立 WEEKLY_TRANSFER 视频计划，默认各 7 天/前 5 部；不新增夸克每日追更 |
| 手动发现/修复、QQ 按需 | 候选→确认→按 provider 转存→完整落位→自有分享→resource_link；外部源 URL 不直接入库 |
| 历史夸克追更 | 独立上游任务继续，不能因新策略推断已停用 |

[周榜/迅雷落位](metadata-weekly-transfer.md)、[P2P](p2p-cloud-archive.md)。分享未确认停在 WAITING_SHARE，排队/HTTP 200 不算完成。

### 元数据页码范围与断点续采

- `1 ≤ start ≤ end ≤ 500`；旧 auto_sync_page/*AutoSyncPage 为起始页，新 end_page/*AutoSyncEndPage 为结束页。缺 end 保持单页；旧客户端单页更新不扩大范围；非法配置 400。
- provider+榜单独立页码/偏移；每间隔轮换一榜，单批 TMDB/GYING 各最多 20。完成页翻页，到 end/空页回 start；榜单变化不是一致性快照，写入仍幂等。
- payload.crawl 保存批次/范围/下一位置并与最终状态一起落库。TMDB 失败留原位；GYING 仅在失败条目都持久化 METADATA_ITEM_RETRY 后推进，记录 itemRetryTaskIds、failed/deferred。全失败/缺身份/隔离保存失败不推进；手动单页不带 crawl，不改自动位置。
- `tmdbAutoSyncRanges` / `gyingAutoSyncRanges` 按来源配置 `{startPage,endPage}`，对应 `resource.hub.*.auto_sync_ranges` JSON；局部更新只合并指定来源，缺配置沿用旧共用范围。改范围保留范围内的页码/偏移，越界才回新 start；延长已完成的旧 end 后继续下一页，空页仍回 start。旧在途按旧范围完成；停用/重启保留进度。单实例启动只恢复本进程前遗留的自动范围元数据 RUNNING 为 FAILED，不动旧手动/发现/转存。多实例先做分布式租约。
- overview `config.metadataCrawlProgress` 展示范围/下页/偏移/任务状态；RETRY 为位置保留。无新增表；旧版本忽略 end，回滚须核对 Worker 行为，来源 utf8mb4_bin 不撤销。

2026-10-22 独立范围与进度保留已完成代码和隔离验证，尚未部署；[验收](crawl-resource-links-20261022.md)。

### 剧集自动追更

历史 quark-auto-save 任务按 savepath，runweek=[1] 周一；[] 明确禁用，shareurl_ban 封禁，不批量改这些任务。保留 pattern/$TV_MAGIC/update_subdir；`.*` 递归只进入同名层级，目录不匹配则停止。明确全季合集可保存完整来源到剧名根，普通单季仅保存目标季；迅雷遍历整来源视频。首次转存/手动重试即时执行，来源后续新增才由历史周期处理。

## 恢复与去重

### 失效资源单条修复

`POST /api/resources/admin/{id}/repair-invalid` 仅 ADMIN、INVALID/SUSPECTED_INVALID，分 QUARK/XUNLEI，优先复用保存目录原位重分享。验收新 URL 可访问、url_hash 更新、link_status=NORMAL、任务状态与 GYING 返回；无明确映射可修成功但 gyingUpdated=false。批量返回 jobId，轮询 `/api/resources/admin/repair-invalid/jobs/{jobId}`。

空目录/分享失败先查原任务与远端结果再重跑；替代资源成功后迁移关系/软停重复。按影片+URL/自有分享/在途任务去重。影片合并先 dry-run，优先保留非 tmdb_* canonical；禁止批量重置 UNKNOWN/历史失败。

## 管理界面

/admin/resource-hub：配置/采集进度、任务/发现筛选、批量发布/重跑/频道、缺资源补全、失效修复和 dry-run 清理。/admin/gying-source 独立；真实发帖/转存不是只读验收。

搜索资源自有结果按网盘、磁力、种子和在线资源分组；网盘托管的种子归档标明不含视频。网页保留二维码并显示可点击链接，长地址仍可点击；历史地址经过协议校验。2026-10-22 已实现，待部署。

### GYING 单条隔离重试

- METADATA_ITEM_RETRY 仅元数据/P2P，每分钟最多一条到期项；30 分钟退避、最多 3 次，大小写身份 SHA-256 去重。耗尽保留，不重置次数；自然目录轮回仍会再检查影片。
- 重试读取精确 ID 的最新主创/标题/年份/季，保留原失败阶段。Resource Hub/Worker/GYING 自动采集任一关闭不运行，启动恢复仅本类型。
- SUCCEEDED 且 crawl.deferred>0 仅代表失败已隔离；通过 itemRetryTaskIds 追踪。严格匹配不降阈值；同名差一年须演员/导演独立证据；季冲突待审，四位片名数字不当季锚点。
- 10 月 8 日已部署；自然失败隔离尚未实证，不制造生产失败。[历史](history/operations-through-20261008.md)、[完整原文](history/snapshots/20261022/docs/resource-hub.md)。
