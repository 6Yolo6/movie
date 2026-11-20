# 元数据来源建议与资源链路审查

2026-11-19：已按用户选择接入并启用TVmaze剧集增量，首轮10条详情尝试成功关联9部剧集，1条保留候选、0失败。当前TMDB→GYING自动种子跟进已上线，TVmaze本次只补剧集元数据；Wikidata、IMDb、Trakt仍未接入。[实际接入与交付](../../tvmaze-metadata-20261119/README.md)。 后续已按用户要求调整为每6小时10条，[当前频率与部署](../../tvmaze-frequency-20261119/README.md)。

2026-11-15 的只读研究快照。用户选择先整理来源与建议，TVmaze、Wikidata、IMDb、Trakt 均未接入。候选代码基准为 `1adbc336cdadefa48be40ff37082a6de840fbd41`；这些研究文件不证明该版本已部署或新来源已可运行。

建议优先用 **TVmaze 做剧集增量候选**（无 API key，CC BY-SA 4.0 需署名及遵守 ShareAlike），用 **Wikidata 补跨库 ID 与中文名称**（结构化数据 CC0）。电影继续依靠 TMDB。IMDb 免费 datasets 的非商业及数据库再发布限制不适合直接导入公开网站；Trakt 需要自有应用 ID，当前政策禁止全目录镜像和批量收割，适合作为按需发现后备。

当时审查的 **TMDB UPDATES 仅更新元数据**，不会自动搜索 GYING 种子。**GYING UPDATES 才沿可信 mid 读取 resources、保存 P2P 并按配置排入归档**；torrent-file 下载由归档阶段执行。归档入队、下载与夸克上传成功是不同结果。未来新来源若要自动找种，需要另行实施可信身份匹配、限额补种、测试与部署。

11月15日四份原文件按字节复制。11月19日仅在来源研究正文前补充现役接入说明，原调查正文完整保留；原研究 SHA-256 为 `6f0d08383347476f2a78ef22bd27e77bcd1a0c589f11d341a46c1ce6820d594f`，其余三份观测快照未修改。当前副本校验值见 [SHA256SUMS](SHA256SUMS)，原报告的外部绝对链接继续保留。仓库副本如下：

| 原文件名 | 仓库副本 |
| --- | --- |
| metadata-sources-research.md | [来源比较与接入建议](metadata-sources-research.md) |
| metadata-resource-chain-review.md | [元数据与 GYING 种子链路](metadata-resource-chain-review.md) |
| metadata-sources-network-evidence.json | [公开网站小量 GET 观测](metadata-sources-network-evidence.json) |
| metadata-sources-trakt-doc-evidence.json | [Trakt 官方指南与合约审查](metadata-sources-trakt-doc-evidence.json) |

链路报告记录于 2026-11-15 16:49（北京时间），其中“未提交的 UPDATES 分支”描述当时状态；上述候选代码随后已提交。代码链接位于原工作区 `D:/gying-movie/movie`。

审查未发现凭据、认证头、Cookie 值、签名 URL、完整响应正文或网盘 provider 响应。证据保留 HTTP 状态、字节数、少量公开字段、条款摘录及文档审查结论。公开文档里的 API key/Cookie 字样不含真实值；IMDb 页面中的跟踪参数不是鉴权凭据。

11月15日的复制没有新增网络请求、账户注册、全量下载或生产操作。网络证据仅代表记录时间的可达性和有限样本：Wikidata 搜索曾 HTTP 200 但返回 maxlag；Trakt 未验证自有 key 的业务响应、中文覆盖或实际限流头。条款摘录与官方文档审查不是完整历史页面归档，后续接入需重新核对使用条件。
