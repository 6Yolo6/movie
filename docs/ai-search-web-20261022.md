# AI 查询、联网资料与资源入口（2026-10-22）

已部署 GYing backend/frontend 和本地 AI backend，其他容器身份、镜像、配置不变。本轮用户在知悉 59 PASS / 11 FAIL 后授权先部署业务，未加固依赖。

## 用户行为

- “想看几部高分悬疑片”解析悬疑 / 电影 / 评分≥8；数词不参与片名搜索。
- “小罗伯特唐尼演的片”及“帮我找小罗伯特·唐尼主演的电影”解析演员条件；姓名中间点、标点、空格归一化，返回片库实际演员匹配。
- 描述走简介与条件检索；完整已知片名保留精确查询，历史指代进入本地工具链。
- 对话卡片资源数为 0 时显示暂无资源及“搜索这部的资源”；新标签打开带 q / auto=1 的现有资源搜索，用户仍选择候选后才触发原有转存流程。没有人工测试真实转存。
- 明确“联网 / 百科 / 维基”进入本地 AI 的 searchMovieWeb；固定 zh/en Wikipedia API，只传简短影视关键词，无任意 URL、跳转跟随或工作区文件发送。网页显示来源链接、摘录及查询记录。

## 可靠性与求职路线

调用轨迹贯通站内规则查询与模型桥接；公开输出只留步骤名、状态、耗时、时间和 requestId，丢弃原始参数与内部地址。仍是限量内存记录，无持久审计平台。

本地 Qwen 实测能真实调用联网工具。复验发现自由概括可能补出来源外结局，已增加联网摘录确定性输出和回归：只展示实际源文片段 / 原始来源，不能把有工具调用等同于回答完全正确。百科搜索是公开资料检索，不是全网搜索，片库分数 / 资源以站内记录为准。

60秒片库刷新后内容一致时复用文本索引；indexGeneration 用于核对重建。此变更没有 embedding、向量数据库、模型训练、文档RAG或API Key平台。

独立人工留出题、真实点击 / 网站账号多轮、embedding混合对比、文档导入删除引用、API Key哈希撤销 / 配额审计 / token预算、简历和演示仍待后续，不能将实施者编写的合成回归称为人工独立评测。

## 验收与恢复

- GYing 677 项：676通过 / 1依赖跳过 / 0失败；独立MySQL片库9项和compile通过。两个测试类冲突修复只在随机标记 network-none 临时库清理本类表，未修改生产schema或数据。
- 本地 AI Maven verify 53 项全部通过；13:48 真实模型请求完成，一次 searchMovieWeb，回答内摘录及来源 URL 逐一匹配实际结果。
- 前端 typegen / tsc / lint / webpack生产构建通过；7条既有lint提示。Turbopack Windows缓存EXDEV未作为业务失败；未升级依赖。
- 隔离、镜像、公网四组中英/明暗390/1440及游客通过；API为fixture。生产32入口和匿名/internal拒绝通过。真实库查询为只读fixture，非网站真实登录。
- JAR341文件 / 前端385产物hash一致。13:36:59–13:42:38 backend/frontend/source错误与Exception计数为0。
- backend `sha256:570dcf70e0e6c08e045cc671725d29dc9152046866fcd9748f7b257197bb742c`；frontend `sha256:355aa60994a34af082f9ee12cd9376078dfa544f26f3ca31df1693dd88aebb8e`。
- 发布与回退 `E:/gying-tools/releases/ai-research-20261022/`；新检查点 `G:/gying-backups/20261022T053706.234649Z` 7份age hash/完整认证解密通过，应用回退不恢复在线数据库。
- 本地AI旧包 `G:/local-ai-agent/backups/ai-research-20261022/before.jar`；平滑退出握手、不可变新运行包验证。真实模型请求和联网来源另存外部报告。

[机器摘要](evaluation/ai-research-20261022/validation-summary.json) · [当前状态](current-project-status.md) · [求职路线](ai-career-project-analysis-20261016.md)
