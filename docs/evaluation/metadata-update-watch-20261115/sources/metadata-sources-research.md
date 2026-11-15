# 可用影视元数据来源与接入建议（2026-11-15）

建议先完成本次 TMDB/GYING 每日 UPDATES 优化；新增来源首选 **TVmaze 的剧集增量接口**，用 **Wikidata 补充跨库 ID 与中文名称**。当前只整理可用来源和建议，没有接入新 provider、申请 API key、改生产配置或下载全量数据。

TVmaze 无需 key、直接返回变更 ID，符合“重复影片不再反复爬详情”的目标；电影覆盖继续依靠 TMDB。IMDb 免费 datasets 不适合直接导入公开影视网站，其官方许可限制重新发布影视数据库。Trakt 有应用注册与数据使用条件，当前优先级较低。

## 现役来源与本次候选代码的真实行为

代码基准为已提交的 `1adbc336cdadefa48be40ff37082a6de840fbd41`。这里描述已审查代码与配置目标，不代表新版本在本报告完成时已经部署。

| 来源 | 本次 UPDATES 选择 | 资源行为 |
| --- | --- | --- |
| TMDB（现役） | TRENDING_MOVIE_DAY、TRENDING_TV_DAY、UPCOMING_MOVIE；每轮跨所有已选榜单共享 10 次详情预算 | 当前 `runUpdates` 只抓元数据并 `upsertMovie(..., true)`，不会自动去 GYING 搜索种子，也不会自动入 P2P。 |
| GYING（现役） | TIME_MOVIE、TIME_TV、TIME_ANIME；前两页、每日一轮、每轮跨所有已选榜单共享 10 次详情预算 | 已有可信 GYING mid 的更新走 `GET /resources/{typeCode}/{mid}`，必要时创建 P2P 归档，下载走 `GET /torrent-file/{type}/{mid}/{ref}`；已有稳定 source_ref、资源 hash 与归档 fingerprint 去重。 |

TMDB 入口见 [TmdbMetadataSyncServiceImpl.java](D:/gying-movie/movie/backend/src/main/java/com/gying/movie/service/impl/TmdbMetadataSyncServiceImpl.java:190)。完整资源链与准确代码位置见 [metadata-resource-chain-review.md](E:/gying-tools/releases/metadata-update-watch-20261115/metadata-resource-chain-review.md)。

GYING 每榜最多 50 页是列表上限，不能据此判断全站已采完。11 月 14 日已记录最新三类首页 144 条中有 118 条没有可信 GYING 绑定；这只是当时的覆盖观察，不是今日全量统计。最近没有夸克新增也不能单独说明源站没有更新：元数据、资源归档与云盘上传是不同环节。Movie 项目的公网入口不是 GYING 上游地址，本报告没有将两者混用。

## 候选来源比较

| 来源 | 覆盖与中文情况 | ID 关联 | 增量与请求条件 | 使用条件及建议 |
| --- | --- | --- | --- | --- |
| **TVmaze** | 剧集、季、集、动画；不覆盖独立电影。实测中文“三体”能找到国产电视剧、国产动画和 Netflix 版本；这证明存在中文作品，不能推出完整中文覆盖。展示名称/简介仍可能为英文。 | TVmaze ID，externals.imdb、externals.thetvdb；没有直接 TMDB ID。可在未来通过现有 TMDB 的外部 ID 查询补齐。 | 无 key；`/updates/shows?since=day` 返回 ID→更新 epoch。since 支持 day/week/month，不能直接传任意高水位时间。官方当前限额为每 IP **至少 20 次/10 秒**，常规缓存约 60 分钟，show index 最长 24 小时。429 退避。 | API 数据为 **CC BY-SA 4.0**，需署名并遵守 ShareAlike。**首选新增剧集增量候选**。 |
| **Wikidata** | 电影、剧集及中文标签；覆盖稀疏，缺简介/季集信息，不适合作为完整影视详情库。 | QID、P345 IMDb、P4947 TMDB movie、P4983 TMDB TV。实测流浪地球/国产三体均可精确跨库关联。 | 无 key；SPARQL 小量 ID 查询及 EntityData 可用。修订号/modified 适合追踪已知实体；全球变更流需自行筛影视实体，本轮未测试长连接。WDQS 单次查询上限 60 秒；每客户端每 60 秒限 60 秒处理时间及每分钟 30 次错误查询，429 按 Retry-After。 | 结构化数据 **CC0**。其他页面文本和图片各有许可。**首选 ID/中文名称补充**，不做全站 SPARQL 扫描。 |
| **IMDb non-commercial datasets** | 电影、剧集、集、评分；title.akas 有地区/语言别名。无海报、剧情简介，不是完整详情 API。 | 稳定 tconst（tt...）；title.episode 可关联剧集/季集。 | 无 key、每日 UTF-8 TSV.gz 快照；没有逐影片更新游标，通常需本地全量快照比对。实测仅取 title.basics 前 4096 个压缩字节，完整文件当时约 230 MB。 | 官方许可不仅要求个人/非商业，还明确限制 altered/republished/resold/repurposed 形成影视数据库（个人使用例外）。**不建议直接导入公开网站**；商业许可另议。 |
| **Trakt** | 电影/剧集、热榜、观看热度；当前合约有 `translations?language=zh`，但中文文本可为空，尚未抽样验证覆盖。 | 合约 movie IDs 有 trakt/slug/imdb/tmdb；show 再有 tvdb；外部 ID 可缺失/为空，movie 没有 TVDB 字段。 | 需自有 client ID；默认 Development 限本人及16邀请者，公众服务需申请独立 Production ID。Standard GET **500次/5分钟**（2026-10-13官方文档）；实际授权响应/限流头未验证。更新接口支持 UTC updated_at 分页或 Trakt ID 列表。 | 当前API政策允许商业/非商业，但禁止 bulk harvesting、catalog mirroring 和可复用目录副本。**仅按需发现后备，不作为全目录采集源**。 |

TVmaze 官方说明：[API、增量、缓存与限额](https://www.tvmaze.com/api)、[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。

Wikidata 官方说明：[结构化数据许可](https://www.wikidata.org/wiki/Wikidata:Licensing)、[数据访问方式](https://www.wikidata.org/wiki/Wikidata:Data_access)、[WDQS 查询限制](https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual#Query_limits)。

IMDb 官方说明：[每日 datasets 与字段](https://developer.imdb.com/non-commercial-datasets/)、[Non-Commercial Licensing](https://help.imdb.com/article/imdb/general-information/can-i-use-imdb-data-in-my-software/G5JTRESSHJBBHTGX)。

Trakt 官方说明：[开发者入口](https://developer.trakt.tv/)、[官方 API repo](https://github.com/trakt/trakt-api)、[服务条款](https://trakt.tv/terms)。当前官方指南/合约的小量 GET 均为200；核对依据如下，属于文档与源码验证，不属于业务 API 验证：

- [创建应用](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/developer/src/lib/guides/create-an-app.md)（2026-10-01）：verified GitHub account；公众应用申请独立 Production client ID；可申请 read-only Production，默认无 Client Secret。
- [请求头](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/developer/src/lib/guides/required-headers.md)：`trakt-api-key` 是自有 client_id，另需 `trakt-api-version: 2`；公共数据无需 OAuth不等于无需应用 ID。
- [限流](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/developer/src/lib/guides/rate-limiting.md)（2026-10-13）：Standard GET500/5分钟；非OAuth按client ID+公网IP共用桶，OAuth按用户桶。429按Retry-After退避，X-Ratelimit/Retry-After可能缺失。
- [API使用政策](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/developer/src/lib/guides/api-use-policy.md)（2026-10-20）：Standard API可商业或非商业使用，但禁止bulk harvesting/catalog mirroring/可复用目录副本/向其他服务提供数据集；允许应用内按实际需要搜索、发现、缓存和后台更新。更新接口存在不代表可镜像全库。
- [媒体对象](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/developer/src/lib/guides/standard-media-objects.md)、[movie合约](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/api/src/contracts/movies/index.ts)、[show合约](https://raw.githubusercontent.com/trakt/trakt-api/master/projects/api/src/contracts/shows/index.ts)：translations是`?language=zh`查询参数，返回language/country/title/overview/tagline，文本可空。`updates/{start_date}`返回updated_at与媒体对象并分页；`updates/id/{start_date}`返回Trakt整数ID数组，不带更新时间，也不是IMDb/TMDB ID。

尚未验证自有client ID下的成功响应、实际配额头、中文覆盖和更新回溯窗口。 另见 [metadata-sources-trakt-doc-evidence.json](E:/gying-tools/releases/metadata-update-watch-20261115/metadata-sources-trakt-doc-evidence.json)。

## 小量 GET 验证

验证时间为北京时间 2026-11-15 16:48–16:53，均未使用账户、key 或生产配置。HTTP 可达只说明本次网络观察，不是可用性承诺。

| 请求 | 实际结果 | 验证意义 |
| --- | --- | --- |
| TVmaze `/updates/shows?since=day` | 200，4399 字节，235 个 ID→epoch | 有真正的廉价增量入口；更新也可能来自演员/集等子对象变动。 |
| TVmaze `/search/shows?q=三体` | 200，3 项 | 65038=国产 Three-Body，Chinese/CN，IMDb tt20242042；56716=国产 San Ti 动画，Chinese/CN，TVDB426655、IMDb为空；53277=Netflix版本。必须区分年份/类型，不能只按同名下载。 |
| Wikidata 两个 IMDb ID 精确 SPARQL | 200，693 字节，2 行 | tt7605074→Q57966215→TMDB movie535167；tt20242042→Q109788700→TMDB TV204541。 |
| Wikidata Q57966215 EntityData | 200，83745 字节 | 中文标签“流浪地球”、英文名、IMDb/TMDB ID，以及 lastrevid/modified 字段均存在。 |
| Wikidata wbsearchentities + maxlag=5 | **HTTP200，但业务 error.code=maxlag，lag=11.53** | 必须检查 JSON 业务错误，按延迟退避；不能把 200 当搜索成功或“没有匹配”。 |
| IMDb title.basics Range0–4095 | 206，4096 字节；Content-Range 全长230354426 | 验证 gzip 可达和头字段 tconst/titleType/primaryTitle/originalTitle/isAdult/startYear/endYear/runtimeMinutes/genres；没有全量下载。 |
| Trakt trending 无 key | 403 | 未借用其他应用 key，没有注册新应用；不能声称已验证授权业务数据。 |

完整安全观测（HTTP 状态、字节数、公开字段样本和官方条款摘录）见 [metadata-sources-network-evidence.json](E:/gying-tools/releases/metadata-update-watch-20261115/metadata-sources-network-evidence.json)。全量 datasets 下载数为 0；生产请求数为 0；申请 key 数为 0。

## 最小接入建议：先 TVmaze，种子继续优先 GYING

这部分是后续方案，不属于本次冻结的十字段配置和上线代码。

1. 新增剧集元数据 provider，每天只读 `/updates/shows?since=day`。保存 TVmaze ID 与最近成功 upstream updated；列表 ID 去重，未变化的 ID 跳过详情。漏跑超过 24 小时按 week/month 重叠窗口补齐，不从列表第一页重新扫全库。
2. 优先取本地已绑定 TVmaze ID 的变更；未知 ID 仅放入有数量上限与过期规则的发现候选池，不能把全球所有变更长期无限排队。待处理详情使用独立持久检查点，每 provider 每轮共享小预算，失败最多三次，成功后推进。先试 10 次/天，观察中文命中、无变化比例与积压，再调整。235 个日变更不等于必须当日下载235部，也不代表这一预算能覆盖全部新增。
3. 元数据身份优先精确 IMDb/TVDB→TMDB ID；Wikidata 可补中文标签或跨库 ID。电影/剧集类型、年份、季号都参与匹配。没有稳定外部 ID 时，只留下候选与置信证据，不能按近似同名自动绑定。
4. **未来资源入口仍先 GYING 搜索匹配。** 已有可信 mid 优先复用；否则仅按 exact ID 或“标题+年份+类型/季”执行严格 search-only 匹配，不确定就不下载。可复用已审查的 `discoverGyingIdentity(movie,0)`/`discoverGyingIdentityBySearch` 搜索入口，禁止自动扩成逐影片详情扫站。
5. 确认唯一 GYING mid 后才进入现有 resources→P2P→torrent-file 流程。继续用稳定 source_ref、torrent infoHash/SHA 与 archive fingerprint 跳过已采资源；新来源提供的是发现/元数据，不直接提供或替代种子。

**当前 TMDB UPDATES 没有步骤4/5的自动逻辑。** 若用户后续要求新增来源自动找种，应单独实施、验证错绑和重复下载防护，再部署；本次仅建议。

## 可在同一现役 provider 内继续优化

TMDB 本身已有 [Tracking Changes](https://developer.themoviedb.org/docs/tracking-content-changes)：movie/person/TV changed IDs 默认最近24小时，可扩到14天，再按ID查询字段级变更。与盯热榜前两页相比，这是更完整的“已入库影片是否改资料”入口；但需要现有授权及独立检查点、语言/无关字段过滤。可作为后续低成本优化，不必新增第三方来源。

这不改变本次选择：先上线已验证的独立 UPDATES 模式，观察真实首轮/每日新增与重复跳过数，再决定是否接入 TVmaze 或 TMDB change feed。
