# 开发验收题 v3：第一阶段质量闭环

2026-10-28，Asia/Shanghai。10 个场景、15 轮、10 个来源组，全部是 `development`。题目与验收口径由实施者编写，用于暴露结构化查询的共性问题，**不是新的独立盲测，也没有人工相关性标签或已完成的评分**。

[题目及逐轮口径](cases.json) · [冻结清单](freeze.json) · [评测边界](../../ai-evaluation.md) · [路线](../../ai-career-roadmap.md)

## 本轮交付与范围

| 场景 | 要验证的问题 | 主要失败分类 |
| --- | --- | --- |
| 旧胶片修复过程 | 区分主题描述与精确片名，以实际简介解释候选相关性 | 分流、候选、事实 |
| 语言并集与时长 | 原始对白与配音信息不能混用；OR 与严格时长上下界 | 条件、候选、事实 |
| 同源评分及缺分 | 明确豆瓣/IMDb 来源，缺失不补零；换排序不换候选 | 条件、事实、澄清 |
| 同名版本辨认 | 年份、导演、演员与同一版本身份绑定 | 事实、澄清、多轮 |
| 动物受伤镜头排除 | 简介未提及不等于证实不存在，未知必须保留 | 条件、候选、事实、澄清 |
| 跨轮条件替换 | 替换原年份；第三轮指代第一轮的实际列表 | 条件、多轮、澄清 |
| 原作者改编关系 | 原作者不能当演员/导演字段，改编关系要有依据 | 分流、条件、事实 |
| 官方重映公告 | 区分查询日、旧公告、转载时间和版本身份 | 分流、事实、澄清 |
| 冲突的片长范围 | 两个硬条件无交集时有效解释并澄清 | 条件、澄清、分流 |
| 可放宽的结尾偏好 | 用户允许放宽的软偏好不影响题材和影片类型硬条件 | 候选、条件、多轮 |

每轮 `reviewRubric` 分开列出 `hardConditions`、`softPreferences`、`expectedBehavior` 与 `usefulApproximationBoundary`。事实、依据、需求三项仍由实际观测进入复核，不能仅因题目列出某条件就把回答标成通过。明确排除未核实、错误身份、评分来源混用都不能偷偷算作近似满足。没有结果、证据不足或请求本身冲突时，有效说明与澄清可以满足相应口径。

所有 `expect` 都保留空对象：此处未给旧原生工具协议伪造 `requiredTools`、片库 ID 或 Recall 标签。`schemaVersion=2` 和 `provenance=agent-authored-regression` 是为了符合现有题集校验器允许值；实际用途由全量 `split=development` 与 `intendedUse` 明确限定。

## 与历史题集的关系

`sourceComparisons` 固定了旧 `human-holdout-v1/cases.json` 与 `development_cases_v2.json` 的 canonical SHA-256。每个场景的 `relatedSourceGroups` 明示沿用的共性意图，`lineageNote` 说明本轮要观察的新组合。旧 v2 开发题没有 `sourceGroup`，因此关联键使用该文件的 `id`，并在清单里注明 `referenceKey=id`。

本套没有迁移、改写或重新分组旧 30 道 holdout 题。旧题和新题共享筛选、来源、澄清、多轮等问题类型，因此不能把新来源组名当成语义独立的证据。测试会核对引用存在、历史文件 hash、旧 holdout freeze，以及跨文件归一化原文重复；这些检查**无法证明没有语义相近题**。

## 使用方式与验收状态

先以新结构化查询 runner 或人工逐轮执行方式采集实际观测，保持同一场景历史；真实执行需遵守既有凭据、只读入口、串行模型和不重试推理约束。10月28日准备时尚无专用runner；10月29日已新增 `tools/ai/evaluate_development.py`，默认只输出计划。尚未使用现成专用USER会话运行本套，因此仍无真实报告、回答或分数。

不要把本套直接交给旧网站 `evaluate_website.py` 后宣称可运行：它只接受特定 holdout 协议，字段及工具 trace 均不同。旧 `comparable_evaluation.py` 能校验本套的版本/来源组及 freeze，不代表它能判定 `reviewRubric`；运行旧回归器即使程序通过，也不能替代这些口径的质量验收。

离线验证：

```powershell
python -X utf8 -m unittest discover -s tools/tests -p test_development_v3.py
```

`freeze.json` 绑定整套题目及口径，不绑定未来回答。后续修改题目、标签或来源关系时应另建版本和清单，保留本版本；真实模型输出只作一次运行的观测。有了观测后，仍需分别报告失败轮数、待复核数、三项评分，以及硬条件和有用近似的复核记录。本套本轮状态为 **未执行 / 未评分**。

## 2026-10-29 runner已适配，真实采集待执行

新[runner](../../../tools/ai/evaluate_development.py)绑定本套freeze，全development且逐轮expect保持空；不改旧网站runner。对齐网页requestKey、14分钟前轮job引用、最近4对问答和UTF-16预算；提交串行且只发一次，轮询身份变化、未知完成状态、无效终态答案会停止后续请求并保留已观察记录。程序失败但有答案仍可复核。

观测保存queryPlan、原样null评分、候选公开字段、检索/联网/未核验条件及实际发送历史（均脱敏）；报告只保留hash、执行元数据和程序检查。复核页现在展示结构化证据。37项新runner测试通过，但不是37条真实模型问答。

```powershell
# 仅计划：10场景 / 15轮 / 0网络请求
python -X utf8 tools/ai/evaluate_development.py
# 先创建并保护外部观测目录；使用既有专用USER会话。这里路径仅为需替换的示例。
python -X utf8 tools/ai/evaluate_development.py --run --session-file E:/private/website-session.json --output E:/private/development-report-new.json --observations E:/private/development-observations-new.json
```

工具不登录、不启用账号，目录ACL由操作者先核验。人工质量分与相关性标签不自动产生；本套仍是 **未执行 / 未评分**，不能把同日两项Agent专项探针混入15轮网站评测。
