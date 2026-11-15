# 元数据与 GYING 种子链路只读审查

记录时间：2026-11-15T08:49:01.654485+00:00

审查范围：本次未提交的 UPDATES 分支与现有资源采集/归档入口。依据为工作区源码静态阅读；没有新增元数据来源代码，没有触发网站、生产 API、云盘动作或浏览器。用户选择先获取来源建议，因此以下接入部分是未来方案，不是已实现能力。

## 当前两条日更链路

**TMDB 新元数据不会自动搜索或下载 GYING 种子。** `TmdbMetadataSyncServiceImpl.runUpdates` 的详情阶段调用 `fetchDetails` → `upsertMovie(item, details, true)`，保存影片与 TMDB 来源身份后结束。它没有调用 GYING 搜索、资源发现或 P2P 下载。旧 `enqueueDiscoveryTask` 方法仍在文件中，但当前没有调用点；Worker 的计划采集查询只消费 `METADATA_SYNC`。

依据：

- `backend/src/main/java/com/gying/movie/service/impl/TmdbMetadataSyncServiceImpl.java:190`：UPDATES 分支。
- 同文件 `:213`：每个候选只取 TMDB 详情并严格验证持久化。
- 同文件 `:375`：元数据保存与身份绑定。
- 同文件 `:490`：旧发现入队方法，目前没有调用点。
- `backend/src/main/java/com/gying/movie/service/impl/ResourceHubWorkerServiceImpl.java:530`：计划查询只处理元数据任务。

**GYING 新片目沿用 GYING 自身的 P2P 链路。** 日更榜单已经提供 GYING `mid`，无需再按片名搜索。`GyingMetadataSyncServiceImpl.runUpdates` 调用 `collectCatalogUpdate`，解析本地 canonical 影片、读取 GYING `/resources/{typeCode}/{mid}`、保存符合选择规则的 P2P 条目、排入网盘元数据归档任务，最后记录目录 COMPLETE 凭证。COMPLETE 是该采集/归档入队路径完成的凭证；它不表示远端上传或分享已经成功。

依据：

- `backend/src/main/java/com/gying/movie/service/impl/GyingMetadataSyncServiceImpl.java:180`、`:200`：GYING 日更采集。
- `backend/src/main/java/com/gying/movie/service/impl/GyingSourceWorkflowService.java:388`：`collectCatalogUpdate`。
- 同文件 `:629`：保存 P2P 后按已启用 provider 排入归档。
- 同文件 `:399`：可信目录完成凭证及旧自有归档证据。
- `backend/src/main/java/com/gying/movie/service/impl/P2pArchiveTaskService.java:576`：归档执行时才下载 GYING `/torrent-file/{typeCode}/{mid}/{ref}`，校验 infoHash 与内容 SHA-256；磁力链接保存为文本，种子为 `.torrent` 元数据文件。

因此，“TMDB 已新增影片”和“夸克新增上传种子”目前是两种不同结果。只有 GYING 采集找到合规 P2P 或另行触发现有补种入口，才会进入后者。

## 已有匹配与去重能力

GYING 身份匹配先使用已确认来源身份；缺失时可通过 `discoverGyingIdentity(movie, 0)` 只按片名搜索，不扫描历史榜单。其 `discoverGyingIdentityBySearch` 使用名称、年份、类别、季数及可用的导演/演员证据，要求自动匹配阈值，前两候选分差不足 10 时保留待审；还要求来源候选解析到同一个本地 canonical 影片。

- `GyingSourceWorkflowService.java:3028`：搜索优先，`maxPages=0` 禁止榜单回退。
- 同文件 `:3109`：严格 GYING 搜索匹配。
- 同文件 `:3212`：来源身份按来源/类型/外部 ID/季数保存，检查大小写与 canonical 所有权冲突。
- 同文件 `:611`：`collectMovieP2pResources(movieId, identityId)` 使用指定的可信 GYING 身份；不会搜索身份、导入元数据、排 provider 任务或发布内容。

P2P 保存复用以下去重顺序：本地影片与 URL/URL hash、稳定 GYING BT `source_ref`、已有 1080P/4K 分辨率槽位。归档再按影片、provider 与所选资源 fingerprint 避免重复入队。

- `GyingSourceWorkflowService.java:702`、`:710`、`:718`：URL、稳定 BT 身份与清晰度槽位。
- `P2pArchiveTaskService.java:54`、`:624`：provider 独立的集合 fingerprint 与入队去重。
- 同文件 `:603`：归档选项明确要求 `source=GYING`、`provider=P2P`，限定 1080P/4K 和中文字幕，最多两种版本/四份磁力与种子元数据文件。

可复用的现有入口：

- `POST /api/internal/resource-hub/p2p/{movieId}/sync`：内部鉴权，仅适合已有可信 GYING 身份的影片；会保存 P2P 并按配置排归档。
- GYING Source 管理员控制器的 `/movies/{movieId}/metadata/sync`：可执行受限搜索、绑定 GYING 身份及刷新元数据；不会自动补种。
- Java `collectMovieP2pResources(movieId, identityId)` + `P2pArchiveTaskService.enqueue(...)`：未来独立后台补种任务可复用，能够显式绑定身份。

`ResourceDiscoveryServiceImpl` 的 `AUTO` 路径虽然先查 GYING，但之后会补查 PanSou 的 provider 分享并进入转存链路。它不是保持“种子始终来自 GYING”所需的最小入口。

## 未来接入 TVMaze / Wikidata 的最小方案

1. 只新增元数据适配器和独立更新检查点。用该来源的稳定外部 ID、作品类型与季数定位本地 canonical；记录来源身份与证据。可以补充别名、年份、剧情、演职员或外部映射，不覆盖已维护的中文字段。不能只按同名直接创建第二条影片。
2. 有已验证的 TMDB 映射时，复用当前 TMDB 详情入库逻辑及 canonical 校验。现有公开 `syncSelectedCandidate` 可供选定身份导入参考；自动任务需要沿用本次 UPDATES 的严格持久化与身份所有权检查，不能把旧手动入口的宽松保存结果当成完成凭证。TVMaze 的剧集和作品级身份也要明确拆分，避免把整部剧与单季混用。
3. 将 GYING 匹配/补种设为独立、预算受限的阶段。已有可信身份直接复用；没有身份时，把现有 `discoverGyingIdentity(movie, 0)` 抽成受限公共 resolver，保留严格匹配及冲突待审规则。
4. 匹配成功后调用指定身份的 `collectMovieP2pResources`，再复用归档入队、资源去重、字幕/清晰度选择及 GYING torrent 下载校验。不为 TVMaze/Wikidata 增加种子下载源。
5. 没有唯一可信 GYING 匹配时保留元数据，记录 `NO_GYING_MATCH` 供后续限频复查。元数据成功、GYING 身份成功、资源获取成功、归档入队与远端上传成功分别保存；补种失败不重做已完成元数据，也不将没有匹配误报为没有网站更新。

这个方案保留 GYING 作为种子来源，并让其他网站只扩大元数据候选覆盖。当前日更上线保持冻结；后续实施需要用户选择来源和范围后另行完成测试、部署及验收。
