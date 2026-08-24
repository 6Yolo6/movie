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

### 剧集自动追更

- `quark-auto-save` 的任务按 `savepath` 区分 GYing 剧集/动漫目录；正常任务配置 `runweek: [1]` 后，每周一由全局计划自动检查来源分享并转存新增剧集文件。
- `runweek: []` 表示明确禁用，`shareurl_ban` 表示平台封禁；这两类任务不在批量周更迁移范围内。
- `update_subdir`、`pattern: "$TV_MAGIC"` 等原任务字段继续保留，确保新增文件按剧集目录和集数规则命名。
- Resource Hub 的首次转存/手动重试直接传递任务列表，不等待周计划；来源更新后才由 `runweek` 负责周期追更。

## 恢复与去重

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
