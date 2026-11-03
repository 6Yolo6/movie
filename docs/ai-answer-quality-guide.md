# 从真实检索到用户可读回答：AI 质量闭环

2026-11-03。本文讲解已上线的查询质量修复，以及促成本轮修复的旧版本网站诊断。此前正文与卡片候选的验证保留在[原证据](evaluation/answer-quality-20261103/README.md)，本轮构建与模型观测单独保存在[查询质量证据](evaluation/query-quality-20261103/README.md)，交付进度按[路线](ai-career-roadmap.md)和[评测手册](ai-evaluation.md)分别记录。已有网页通过 evidence 展示候选卡片；正文遗漏与检索偏差需要在各自出现的位置修复。第一阶段仍未完成。

## 一次回答经过哪些边界

Agent 的 `MovieQueryPlanner` 用 JSON Schema 抽取年份、评分来源、时长等条件，再由确定性规则核对原句。Schema 限制格式和字段，不能保证理解正确；模型输出不是 SQL。

[CatalogResearchService](../backend/src/main/java/com/gying/movie/service/impl/CatalogResearchService.java)用 SQL 取得真实公开元数据，`eligible` 在查询过程中检查硬条件；BM25、向量和 RRF 负责找到、排列候选。相似分高不代表年份、人物或主题满足要求，硬条件和简介依据还要分别核对。

Agent 的 `GyingResearchService` 对语义候选核对实际简介摘录，再用 `catalogRows` 将保留候选按原顺序写入正文，最多20项。确定性正文只展示已取得的字段，不额外请求模型补片名或分数；它解决呈现遗漏，不能补齐错误检索。

[队列完成处理](../backend/src/main/java/com/gying/movie/service/impl/AiAssistantService.java)将成功工具 trace 转成 evidence；[网页](../frontend/src/app/ai-assistant/page.tsx)据此渲染片名、评分和时长卡片。正文和卡片都是交付的一部分：trace 有影片，仍需检查用户是否能看懂答案。Agent 源码位于另一仓库 `D:/ai/local-ai-agent/backend/src/main/java/com/localai/agent/assistant/`，重点阅读上述两个类和 `ExplicitMetadataQuery`。

## 零值、缺失和来源不能混用

JavaScript 的 `0`、`null`、`undefined` 都会在真值判断中走假分支，但业务含义不同：0 是已知数值，null 表示显式缺失，undefined 通常表示字段未提供。

```ts
// rating ? rating.toFixed(1) : '暂无' 会隐藏真实的 0。
const text = typeof rating === 'number' && Number.isFinite(rating)
    ? rating.toFixed(1) : '暂无';
```

同理，“0个可用资源”不能变成空白；类型字段缺失不应阻止其他真实字段展示。时长还需验证大于0，不能只把“是数字”当作有效。

例一：用户要求豆瓣排序，某片 `doubanScore=null`、`imdbScore=8.8`。正文应写“豆瓣评分：暂无”；不能拿IMDb补成豆瓣8.8。没有最低分条件时，缺分候选可以按既定规则放最后；要求豆瓣至少8分时，缺分不能通过硬过滤。

例二：片库语言为“国语”，只能说明该字段的记录。它未区分原始对白和发行配音，所以要求“原始对白中文”时仍写“未核验”。联网返回空结果也不会把未知变成满足；保留候选和局限，供用户继续核查。

## 人物姓名必须带着角色理解

“村上春树原著改编”指原作关系，不能因为片库里有一个相近姓名，就新增演员条件。此前网站实际计划加入了“明基·村上”；即使查询成功且诚实返回无结果，错误的硬过滤仍会漏掉可能相关的影片。这是条件层偏差，不是正文展示问题。

候选在两处守住这个边界：Agent 的 `MovieQueryPlanner.otherPersonRole` 根据原句区分作者、作家、编剧与演员、导演；Movie 的 `CatalogResearchService.explicitPeople` 在恢复片库标准姓名时再次检查角色。只修 Planner 不够，因为后端人物恢复可能把已经清掉的姓名重新加回。原作改编缺少专用字段时仍标为待核验；删除错误演员条件不会自动证明改编关系。

姓名匹配还需要数字边界。“150分钟”含有字符“50分”，但前者是时长，不能据此匹配演员“50分”。反过来，同一句后面若明确说“50分主演”，也应保留真实的出演要求。这说明正确匹配不是发现一个子串就结束，还要看它在原句中的位置与角色。同一人物同时被明确要求主演或执导时，作者身份也不应覆盖另一个真实条件。

## 主题、硬条件与软偏好分别更新

“野外徒步纪录片，但必须没有动物受伤镜头”包含正向主题和排除要求。候选 Planner 从正向修饰语恢复 `semanticText`，把可核对的类型留在类型字段，内容排除无证据时仍提示未知；不能把“动物受伤”当成搜索主题，也不能把简介没写受伤理解为确认不存在。

追问“结尾不用温暖也行，但电影院必须是核心场景，电影类型和最多三部都别改”只撤回结尾偏好，同时加强核心场景并声明类型、数量继续有效。`groundTheme` 移除撤回的旧偏好，保留或加强主题；`preserveDeclaredType` 从上一轮计划保留声明不变的类型。年份、数量等明确字段继续由 `ExplicitQueryConstraints` 处理。继承需要逐字段理解，不能整份复制旧计划，也不能全部清空。

候选简介核对中的核心要求由业务代码从 `semanticText` 固定下来，通用的“最好、尽量、优先”子句单列为软偏好。软偏好之后的其他核心子句继续保留。例如“小镇电影院，最好轻松，围绕放映员与观众关系”，核心仍包含电影院和人物关系；分离“轻松”不能顺带删掉后半句。明确要求核心、主要或重要场景的当前强化条件还会参与必需核对。

## 历史文字与可信影片身份有什么区别

客户端的 `history` 是对话文字，能帮助理解，却不是影片身份的可信来源。用户可以编辑文字，也可以写一个没有出现在真实候选里的 ID。候选后端只通过经过归属校验的 `previousJobId` 回溯同一用户的成功任务，保存服务端链路，不从客户端文字提取 ID 来构造可信历史。

最新 `queryPlan/candidateIds/candidates` 保留上一轮含义；新增 `candidateHistory` 只放更早轮次，按旧到新排序，最多三轮，加上最新轮共四轮。每轮最多30个候选，仅保留 `id/titleCn/titleEn/year`，片名最多120个 Java 字符，整个 context 最多10000个 Java 字符。最新候选超预算时从尾部裁剪，必要时移除计划；更早轮次必须整轮加入，否则停止回溯。`historyComplete=false` 表示无法证明已经回溯到起点；旧快照缺少链路标记时也不会假定它就是第一轮。

每轮 `expiresAt` 来自原任务完成时间加900秒。新追问不会重新给旧轮续15分钟。后端在领取和内部查询时重新读取当前任务与租约对应的上下文，Agent 在定位旧列表时也检查有效期。完整链不存在、某轮过期或无法确定起点时，应要求片名与版本信息，而不是把目前最早保留的一轮称作“最开始”。

Agent 的 `TrustedCandidateHistory.ground` 先确定第一轮，再按序号、片名或 ID 定位；多个指示必须一致。同名版本和本轮明确年份还需要与该身份相符。定位后使用 `details/inheritContext=false/scope=catalog`，清除最新列表中本轮没有再要求的筛选条件，避免“第一轮2001年的首片”被最新轮“2015年以后”过滤掉。受租约约束的早期列表详情在查询前还要核对实时链路。

这里有两个独立的时间规则：旧候选是否仍有效，决定这次执行能使用哪些身份；同一 `requestKey` 是否对应同一输入，决定能否复用已有任务。候选幂等指纹绑定校验后的原输入与 `previousJobId`，而不是随时间裁剪的 context。否则祖先恰好过期时，相同输入的重试可能被误判成换了问题。终态恢复保存链路信息，但恢复不会延长原任务保留时间。

## 引文真实还不够，还要支持完整要求

此前电影院题引用《存身》简介里的小镇、梦境和避难所。句子确实来自原文，却没有证明“电影院是核心场景”。来源身份与语义支持是两个问题：程序可以核对句子在哪里出现，模型仍可能误解句子证明了什么。

候选 `GyingResearchService` 固定核心和软偏好要求后，模型只按要求索引逐项返回 `supported/unknown/contradicted` 及该项引文，不能重新输出一份更窄的要求。必需项只有 supported 且引文长4–60个 Java 字符、逐字来自本次实际展示的简介前300个 Java 字符，才可通过。不能拿未展示的全文或模型记忆补证。缺项、重复项、核心未知或冲突都会使该候选不能进入已核对清单；软偏好的 supported/contradicted 也需要独立有效引文，否则降为未知。unknown 可以没有引文，软偏好未知或不一致可保留，并在正文逐项说明。

核对最多覆盖八个候选、每批最多四个，保留片库原排序和总截止时间。它延续原相关性核对阶段，没有另加第二次模型裁判。确定性合同减少遗漏要求、错配身份和伪造引文的空间，**仍不能确定性证明语义蕴含**：模型可能把不相关的真实句子误判为 supported，召回不足或简介截断也可能遗漏合适影片。没有足够依据时，少给、说明未知或受限联网核对，比用不相关候选凑数量更符合当前契约。

## 怎样证明本轮改进

| 验证层 | 能说明什么 |
| --- | --- |
| 行为测试 | 在指定输入下检查角色、字段继承、可信历史、逐项引文与正文契约；模拟资料不是实际观影事实 |
| 隔离真实模型 | 保存该候选代码、模型、语料和问题的实际回答与错误；fixture 历史不代替生产归属、队列和租约验证 |
| 网站USER API | 旧版本15轮诊断另记；新版本4轮验证真实USER/队列/previousJobId及首轮身份，机械4通过；不覆盖密码登录、浏览器、跨owner或自然TTL |
| 浏览器UI | 此前正文与卡片候选只验证8组fixture卡片；真实网站登录与推理后的页面仍待验 |
| 逐条质量复核 | 逐条核对事实、证据和需求；模型复核与真人判断分别记录 |

HTTP200、completed、测试通过都不能直接换算成答对率。重复开发题也不能证明独立质量提升。[评测手册](ai-evaluation.md)保留原回答与评分，新观测另存。此前旧版本网站15轮的模型诊断为满足3、部分有用1、有效澄清2、未满足5、证据不足4，三维完整13、真人0；电影院两条事实维度保留null。这是本轮修复的问题来源，不是新候选的质量结论。有效澄清是否满足任务要看原请求，不能一律当完成影片推荐；未取得事实不能靠猜分补齐。

模型观测所用候选的完整构建为Movie899项中869通过、30项环境跳过，Agent173项全部通过，两侧verify/compile均exit0。随后一次隔离真实模型观测完成10场景15轮、各轮一次请求、15次completed，使用2026-10-31公开分页快照12822条，非数据库原子快照。dev-v3-01和dev-v3-10第二轮仍出现核心主题错误：程序核对到真实引文，模型却把它当作更强主题的依据。逐条诊断见[本轮报告](evaluation/query-quality-20261103/README.md)，这些结果不能写成15条全部满足或独立质量提升。

原15轮绑定Movie4f73846f…/Agent055f1bdc…。审查后Movie补充“作者甲原著改编同时作者甲主演”这类无标点句的逐次姓名匹配边界，新增确定性回归与[审查后构建](evaluation/query-quality-20261103/movie-reviewed-build.json)单独保存；模型没有重跑，不能把原15轮观测绑定到后来的Movie源码。

旧网站观测、正文候选和本轮查询修复的构建与模型报告分别绑定各自版本。生产安全门禁尚未关闭；用户获知后明确授权本轮双后端，21:12 Agent055f…与21:47 Movie1b8a09d5…已上线。[新网站四轮](evaluation/query-quality-20261103/website-review.md)机械4通过，模型满足2/未满足1/缺证1，事实null2、真人0；首轮导演指代与矛盾澄清满足，现场录像电影身份缺证、电视特辑充作音乐电影未满足。原15轮自定义v1字节hash与新网站v2规范JSON协议分别保存，不算独立提升。test新设备已撤销，disabled/有效设备0/401/密码角色不变及临时凭据删除见[网站运行](evaluation/query-quality-20261103/website-runtime.json)；真实浏览器、跨owner与自然TTL未在本轮额外验收。部署检查点和新v2请求限制旧版回退见[上线验收](evaluation/query-quality-20261103/deployment.md)。

## 阅读与练习

先读本仓库 [CatalogResearchService](../backend/src/main/java/com/gying/movie/service/impl/CatalogResearchService.java) 的 `explicitPeople/query`，再读 [AiAssistantService](../backend/src/main/java/com/gying/movie/service/impl/AiAssistantService.java) 的 `prepareRequest/conversationContext/runningContext/requestFingerprint`。对应行为测试在 [人物与查询测试](../backend/src/test/java/com/gying/movie/service/impl/CatalogResearchServiceTest.java) 和 [可信历史测试](../backend/src/test/java/com/gying/movie/service/impl/AiTrustedCandidateHistoryTest.java)。

Agent 另一仓库的阅读顺序是 `MovieQueryPlanner → ExplicitQueryConstraints → TrustedCandidateHistory → GyingResearchService`，测试分别阅读同名 `*Test`。本机检出在 `D:/ai/local-ai-agent/backend/src/`，这是本机定位信息，通用安装路径由环境配置提供。最后对照 [网页](../frontend/src/app/ai-assistant/page.tsx) 查看正文与 evidence 卡片怎样交付给用户。

1. 给出“作者甲原著改编”与“作者甲同时主演”两句，分别写出哪些人物过滤可以保留，哪些关系仍待核验。再解释“150分钟”与“50分主演”为什么不能只用子串匹配。
2. 建立两轮有不同年份的候选，用“最开始列表第一部”追问导演；再把第一轮过期或移除，说明为什么不能直接使用目前最早保留的轮次。检查客户端伪写 ID 是否会进入服务端候选链。
3. 将“最好温暖”撤回，保留电影类型并加强核心场景，逐字段比较新旧 queryPlan。不要只比较两份计划是否完全相等。
4. 对一条真实引文分别问“来自哪段资料”和“支持完整核心主题吗”，指出程序与模型各自能核对什么。再给卡片输入0、null、缺失类型与缺失时长，说明怎样展示。

面试可解释：“我把模型理解、确定性过滤、证据呈现和质量评价分开验收。业务代码固定身份、时间和要求，模型在有限范围内抽取条件与判断相关性；即使执行成功，我仍检查答案是否满足原要求，并保留无法核验的事实。”这属于AI应用工程，没有进行本项目模型训练。
