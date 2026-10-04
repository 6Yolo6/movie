# Resource Hub 架构

Resource Hub 在现有片库模型上补充元数据采集、资源发现、转存和发布，不另建影视主模型。

## 数据规则

- `movie_metadata` 保存影视元数据；`resource_link` 保存可用链接。
- `popularity` 是站内收藏热度，TMDB 热度和评分使用 `tmdb_*` 字段。
- TMDB 数据先按 TMDB ID、剧集名、标题、别名和年份匹配 canonical 影片。
- 自动资源使用 `resource_discovery_result`、`quark_transfer_task` 和 `xunlei_transfer_task` 保留发现与转存轨迹。
- 自动发布资源标记来源，核心记录只软删除。

## 流水线

1. TMDB 自动同步按配置间隔轮询一个来源，使多个来源分散到全天执行；管理员也可手动选择来源。
2. 跳过已有可用资源、可发布发现或近期重复任务。
3. 合并本地 PanSou 与外部 Panso API 结果并按 URL 去重。
4. 校验标题相关性和源链接状态，保存发现结果。
5. 为夸克或迅雷结果创建对应转存任务；夸克运行 quark-auto-save，迅雷调用当前账号的 Drive API。
6. 确认保存目录有内容并创建自有分享；迅雷公开分享接口未验证时停在 `WAITING_SHARE`。
7. 仅将可用自有分享发布到 `resource_link`，不得回退发布第三方迅雷或夸克源链接。
8. 无资源影片保持 `TRAILER`，可从缺资源页重新处理。

### 元数据页码范围与断点续采

- 采集设置中的 TMDB / GYING 分别支持「起始页」「结束页（含）」，合法范围为 `1 ≤ 起始页 ≤ 结束页 ≤ 500`。旧的 `auto_sync_page` / `*AutoSyncPage` 字段保留，含义变为起始页；新增 `resource.hub.{tmdb,gying}.auto_sync_end_page` 和 `tmdbAutoSyncEndPage` / `gyingAutoSyncEndPage`。
- 缺少结束页时按原页码初始化为单页范围，不自动扩大已有配置。后台保存会校验完整范围，非法范围返回 HTTP 400；只修改旧单页字段的旧客户端仍保持单页行为。
- 每个 provider + 榜单独立保存页码和页内偏移。每个调度间隔仍只轮换一个榜单，沿用每轮数量上限；一页尚未处理完时续采余下条目，再进入下一页。TMDB 单批实际最多 20 条，GYING 单批最多 20 条。
- 完成结束页或遇到空页后回到起始页。榜单顺序可能随时间改变，位置不是上游内容快照；片库写入仍按已有 canonical / 来源身份规则去重。
- 自动任务在 `resource_hub_task.payload.crawl` 保存范围、批次偏移和成功后的下一位置；与任务最终状态一并持久化。失败（含部分条目失败）保留当前批次，下次该来源轮到时幂等重试，不跳过失败项。手动单页任务不带 `crawl`，不会改动自动位置。
- 修改范围后从新起始页重新开始；已排队或运行的旧任务按原范围完成。停用、重新启用或进程重启保留历史进度。当前单实例 backend 启动时仅将本进程启动之前遗留的 **自动范围元数据** RUNNING 任务标记为 FAILED，让正常调度按开关和间隔续采；不自动重置旧式手动任务、资源发现或转存任务。多 backend 实例需先实现分布式锁/租约，不能直接复用此恢复方式。
- 管理概览的 `config.metadataCrawlProgress` 返回各来源的范围、下次页码、起始条目、最近任务状态和任务 ID；后台「自动采集进度」表展示已保存配置的状态。运行中显示当前批次，`RETRY` 表示部分失败且位置保留。
- 不引入表结构迁移。回滚旧镜像后，旧代码会忽略新增结束页与任务 JSON 字段，退回固定单页采集；保留新增配置与任务审计即可，不删除数据。

### 剧集自动追更

- `quark-auto-save` 的任务按 `savepath` 区分 GYing 剧集/动漫目录；正常任务配置 `runweek: [1]` 后，每周一由全局计划自动检查来源分享并转存新增剧集文件。
- `runweek: []` 表示明确禁用，`shareurl_ban` 表示平台封禁；这两类任务不在批量周更迁移范围内。
- `update_subdir`、`pattern: "$TV_MAGIC"` 等原任务字段继续保留，确保新增文件按剧集目录和集数规则命名。
- `update_subdir: ".*"` 会匹配每一级已存在的源/目标同名目录并递归检查新增文件；若某一级目录名不匹配，递归在该级停止。新集直接位于当前目录时仍按文件 `pattern` 检查。
- 候选标题明确标注多季合集、全季或全集时，夸克跳过当前季子目录收窄并递归保存整个来源到剧名根目录；迅雷沿用整份来源的视频遍历转存。普通单季候选仍只保存对应季。
- Resource Hub 的首次转存/手动重试直接传递任务列表，不等待周计划；来源更新后才由 `runweek` 负责周期追更。

## 恢复与去重

### 失效资源单条修复

管理员可调用 `POST /api/resources/admin/{id}/repair-invalid` 修复单条 `INVALID` 或 `SUSPECTED_INVALID` 的云盘资源。后端按资源 `provider` 分流夸克和迅雷，优先复用已有转存目录/任务原位生成新分享，更新原 `resource_link`，并在存在明确 GYING 影片映射时继续同步发布。

修复验收至少检查：新 URL 可访问、`url_hash` 已更新、`link_status=NORMAL`、对应转存任务状态和 GYING 返回结果。没有 GYING 映射时允许资源修复成功但 `gyingUpdated=false`；不得把第三方原始链接直接写入正式资源。批量 `POST /api/resources/admin/repair-invalid` 仍返回 `jobId`，必须轮询 `/api/resources/admin/repair-invalid/jobs/{jobId}`。

- 空目录或分享失败先重跑原转存，仍失败时重新搜索并创建替代发现。
- 同一影片避免重复原始 URL、自有分享和并行转存任务。
- 失效资源优先原位更新；替代资源成功后归并关系并软停用重复行。
- 历史影片合并先 dry-run，优先保留非 `tmdb_*` 的片库记录。

## 管理界面

`/admin/resource-hub` 提供：

- 服务和 Worker 状态、批量限制与运行配置。
- 近 24 小时 TMDB 同步、任务失败、资源发现和入库统计，以及最近/下次采集时间。
- TMDB 手动同步、单片搜索和任务列表。
- 发现结果筛选、时间排序、可点击链接与批量发布/重试/发 QQ。
- 缺网盘资源影片检查，以及 GYING/PanSou 补全。
- 失效检测、重分享和重复数据 dry-run 清理。

GYING 站点工作流单独位于 `/admin/gying-source`，接口细节见 [API 文档](api.md)。
