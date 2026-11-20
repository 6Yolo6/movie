# 本机可比较回归 v1

2026-10-23，Asia/Shanghai。工具与统计口径见[评测说明](../../ai-evaluation.md)。

题集为实施者编写9场景10轮，3个sourceGroup。真实既有loopback Agent、实际片库/百科，重复2次共20轮。

- 程序检查：20/20通过，0失败，0未执行。
- 六维applicable/pass：routing20/20、arguments6/6、retrieval16/16、answer16/16、rejection4/4、multiTurn2/2。
- 8个有相关性标签的首屏轮次Recall@k=1、截断MRR@k=1；主要同源星际穿越，不推断全片库指标。
- 32次模型调用、16次工具；无提供方usage，token/美元成本null。
- p50=5109.0ms，p95=21187.0ms；本轮前段与网站验收重叠，负载未控制，不是SLA或无负载基线。
- 16条成功回答待人工复核，0条人工评分；非整体回答正确率。

[题集](cases.json)与[freeze](freeze.json)绑定题目、标签与分组。 [结果](report.json)、[评分模板](review.json)、[评分摘要](review-summary.json)脱敏保留，不含原始回答/工具参数/资源URL/凭据。回答hash绑定一次观测，不是逐字标准答案。

前聊天的initial-baseline仅在tmp保留；evaluator源码hash与本轮不同，不生成受控版本提升结论。当前报告源码hash复核通过；已修正评测器web参数标签为真实query/language，不修改应用prompt。

冻结live返回证据hash能检测部分漂移，不能冻结整个片库；下一阶段使用独立元数据快照和相关性标注完成检索对比。

冻结回归题集里的“human questions pending”是19:40创建时的历史metadata，保持hash不改写；用户题已独立存human-holdout-v1，不以改旧manifest替代新题集。
