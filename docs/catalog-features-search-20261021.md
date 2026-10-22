# 推荐特征、自然语言影视搜索与求职成果验收

2026-10-21实现，2026-10-22 00:07–00:08部署；add5d78/dc13094。[原路线](ai-career-project-analysis-20261016.md)、[完整原报告](history/snapshots/20261022/docs/catalog-features-search-20261021.md)。

## 1. 交付能力

### 用户行为转为推荐特征

FeatureIndex预计算不可变稀疏影片向量，UserFeatures聚合收藏3+90天浏览 exp(-days/30)，30天剩约36.8%（不是半衰期）；最多各100，收藏/已看排除。用户ID仅鉴权/查本人，不入特征；无持久Feature Store。字段权重类型.50/导演.15/演员.10/分类.10/年代地区语言各.05，类型IDF/字段归一；排序内容.55/共同收藏.20/评分.15/热度.10，冷评分.75/热度.25。共同收藏独立用户去重≥2支持，多样性重排/实际理由，featureVersion=content-features-v2。规则变更非结果等价/质量收益证明。

### 自然语言检索

登录GET /api/ai/natural-search，与标题搜索共30次/分钟；query已识别描述直查，精确已有片名优先标题、详情/资源追问沿用模型桥接。固定解析年份/范围/近几年（2026为2022–2026）、类型/排除/评分/时长/有限主创地区语言，filters才是实际执行条件；高分≥8取存量评分最大值非统一口径，缺失/单集/多段时长不通过最大限制。

ICU/BM25倒排+显式中英主题映射，片名4/别名系列3/类型导演2/演员1.5/简介1，概念分×.65；非空文本覆盖≥60%后硬条件过滤，检索分/评分/ID排序。原文片段/命中理由、indexVersion=icu-bm25-concepts-v1。进程快照TTL60秒/最多50000、达到上限catalogTruncated，文本索引按需重建；无DDL/embedding/模型训练/文档RAG。太空/烧脑等概念较宽，混合否定/任意口语/跨语言泛化未充分支持。

### 网页结果

完整分页、失败留旧结果、原文/理由、新标签保留对话，中英/明暗/手机桌面与原路线示例；模型离线仍直查。

## 2. 可复现证据与测量边界

| 验证 | 结果与范围 |
| --- | --- |
| 后端 | Windows672：645通过/27POSIX或opt-in skip/0失败，compile；最终隔离MySQL8/8无skip/两容器清理 |
| 前端/镜像 | typegen/tsc/lint/build（7旧warning）；backend341、frontend375文件hash匹配 |
| 候选/生产 | 候选17场景；本机/公网各13页面场景（AI/API身份fixture）；32真实入口/依赖/匿名边界通过，不代网站实际用户登录 |
| 观察 | 00:07:22–00:14:04三服务无ERROR/Exception；9未改容器身份/镜像/启动/重启及转存/发布审计不变；00:26推送/入口复核 |

40合成题，development/evaluation各20（19正+1无答案）：旧Top5各2/19，新各19/19，2负例空。题目/标签实施时编写，**不是独立盲测/未调参留出**；条件零违规仅同解析对象程序回查，非独立理解准确率，不写生产准确率100%。

2000合成片/60种子、预热2/采样5：旧推荐中位415.7289ms→特征81.0494ms（少80.5%），索引构建57.7854ms另列。固定顺序/小样本，不含DB/HTTP/5万共同收藏/并发，不是线上p95/QPS/CTR。

真实库独立只读fixture：9615活动片/9220简介/8531时长；原“近几年烧脑≤两小时科幻”1，2010–2020高分科幻排恐怖33，英文时间旅行≤两小时18，“量子海豚火山”0；前三页21唯一ID，个人/冷各12含理由。模拟鉴权/只读DB、不启调度。镜像复制并行负载时冷查询14.640s、温31–62ms、个人2.703s/冷启.891s；TTL重建仍待优化，仅一次本机样本非网站SLA。

可随仓库复现：

- [题集](../backend/src/test/resources/catalog-evaluation/search-v1.json)、[评测实现](../backend/src/test/java/com/gying/movie/service/impl/CatalogEvaluationTest.java)。
- [逐题报告](evaluation/catalog-20261021/search-report.json)、[计算基准](evaluation/catalog-20261021/recommendation-benchmark.json)、[真实库摘要](evaluation/catalog-20261021/real-catalog-readonly.json)。
- [验证摘要](evaluation/catalog-20261021/validation-summary.json)、[源hash](evaluation/catalog-20261021/source-sha256.json)；hash为构建Windows原始字节，Git换行规范化后可能不同，应先比较规范化内容。
- 隔离backend `mvn -Dtest=CatalogEvaluationTest,NaturalMovieQueryTest,CatalogRecommendationEngineTest test`，输出target/catalog-evaluation；[完整隔离/页面步骤](development.md)。

## 3. 部署、风险与回滚

仅backend/frontend、显式-p gying-movie/-f生产文件+ `E:/gying-tools/releases/catalog-features-search-20261021/deploy.json`；nginx校验/重载。精确镜像：

| 服务 | 本轮 | 旧镜像 |
| --- | --- | --- |
| backend | sha256:3b5fe68645457f4333c12a36608312f4d3c6c7f500c265b9063347cba7d3af4d | sha256:8555dfc569644f7b03d5d597fec3a7b59145c70fe9b1cfa8eac7e23d3384028e |
| frontend | sha256:e032edbdd5f0e7007bd4429f36de8145e1aa480e3c75ec59ba9a23817ec66000 | sha256:97bf2757d4d08057551dcb1a0d7c8980297adcec29da41afcc58fdd032e0f066 |

检查点 `G:/gying-backups/20261021T160733.115483Z` 七age hash/认证解密通过，非灾难恢复；同目录rollback-deploy.json先审新写入/在途，仅两应用+nginx，不恢复SQL。切换守护曾因已清测试容器快照退出、未停生产，刷新基线后成功；测试鉴权替身错误修后最终SQL通过，失败证据完整保留。

上线前后安全59PASS/11FAIL/0UNKNOWN；本轮用户知悉后“通过可部署”仅此次业务例外，未关闭门禁/改安全配置。无手工转存/重放/QQ出站/外部发布。

## 4. 原求职路线的进度

已完成：规则检索/推荐特征/同合成基线/SQL与页面/可回退生产交付。优先补冷索引和误召回、100–150独立人工留出题/真实错例、统一调用轨迹/越权超时/真实用户多轮，再同数据比较BM25/embedding/混合。文档RAG导入删除引用、API Key哈希撤销/配额审计/预算、简历更新/演示/复盘、企业合成评分未完成。企业资料不用公司人员/源码；另一仓库全面审计未重做。

## 5. 当前可用于简历的表述

> 开发并部署影视检索与解释推荐，将收藏和时间衰减浏览聚合为类型、主创、年代等稀疏特征，结合共同收藏、评分热度和多样性重排；实现条件解析与ICU/BM25资料检索、原文依据和分页，提供隔离MySQL/页面及可回退上线证据。
>
> 固定2000合成影片/60种子场景，通过预计算特征与聚合，将推荐排序中位415.7ms降至81.0ms，另列索引构建与旧实现基线。

面试说明权重规则、合成评测非独立泛化、未有真实点击效果；不写“大模型训练”“100%生产语义准确率”“CTR提升80%”“已交付文档RAG/API Key平台”。
