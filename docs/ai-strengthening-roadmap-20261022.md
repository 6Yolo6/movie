# AI 强化路线与首轮交付

2026-10-22，Asia/Shanghai。目标：用 Java 后端经验构建可验证、可恢复的 AI 业务系统，以 GYing 为主项目，local-ai-agent 为模型执行层。本文是当前代码审查与后续实施路线；阶段计划不代表已部署。

## 当前起点

| 模块 | 代码与已记录能力 | 下一步缺口 |
| --- | --- | --- |
| GYing backend | Java17/Spring Boot；标题分页、自然语言条件解析、ICU/BM25、特征推荐、鉴权、有界问答队列 | 队列在内存中，重启丢任务；租约无续期/恢复；无持久调用审计 |
| local-ai-agent backend | Java17；Ollama/Qwen3原生tool_calls、参数/工具边界、总时限、调用预算、上下文裁剪、trace | 执行检查点/回执仅内存；跨重启恢复、运行身份与持久化审计待补 |
| Spring AI/MCP | local-ai-agent 已有 Spring AI MCP 1.0.0 与 SSE；自主助手使用原生 Ollama 执行循环 | 旧MCP有写文件/命令/外发工具，不能直接作为只读影视MCP服务 |
| 评测 | 已有40个实施阶段合成检索题；本轮增加真实Agent回归入口 | 独立人工留出集、完整回答正确性、实际网站账号多轮/点击评价待补 |

项目网页是 Next.js/React，本机助手页面是 Vue。贴文中的“Spring Boot统一AI应用层”“Vue项目前端”“重新接入工具与MCP”应按现有接线调整，避免重复建设。模型调用主要在另一仓库，不能给 GYing 加一个 starter 就认为完成迁移。

Harness 的学习拆成两部分：业务Agent的执行控制（状态、上下文、工具、预算、恢复）；辅助开发的工程环境（AGENTS、测试入口、文档、可重复反馈）。两者都需要证据，不能把会使用编码助手等同于已构建业务Agent Harness。

## 建议顺序与验收

按每周约8–12小时学习和实践估算，8周为参考投入量，遇到评测失败先修复再进入下一阶段。每一阶段结合源码讲清设计、运行一条正常路径和一条失败路径，再整理面试材料。

| 阶段 | 重点与实施内容 | 交付和通过条件 |
| --- | --- | --- |
| 本轮起点 | 统一Agent回归：实际工具发现、真实模型、实际片库、多轮详情、百科引用、无结果和参数拒绝 | 有版本题集、逐项检查与脱敏报告；程序校验和人工回答质量分开 |
| 第1–2周：执行可靠性 | 先设计任务/attempt/lease/checkpoint/审计模型；只读任务支持幂等提交、租约续期、超时释放、取消和终态回执幂等；本机保存未确认回执 | 隔离环境验证进程重启、过期lease、重复complete、回执丢失、跨用户读取、预算终止；证明确认回传重试不重复推理。持久化前确定敏感内容保留与删除规则 |
| 第3周：真正可比较的评测 | 先收20–30个用户独立编写的问题和失败例，冻结标签/数据快照，再扩100–150题；问题改写按同源组划分，避免相似题泄漏 | 区分工具路由、参数、检索结果、回答事实、拒绝、多轮；报告重复运行分布、成本/时延与未通过题。实施者编题只叫回归，不叫独立留出 |
| 第4–5周：检索与RAG | 同一片库快照、同一留出题比较BM25、embedding、混合召回，再考虑rerank；文档RAG作为独立小模块，演示导入→切分→索引→引用→删除 | 报告Recall@k/MRR或nDCG（按实际标注条件选择）、冷/温索引时延、无答案；硬条件独立核查。删除后所有检索路径不再返回原片段，引用能回到实际文档位置 |
| 第6周：Spring AI与影视MCP | 基于当前Java版本评估依赖兼容；用同一工具契约/题集对比现有执行循环与ChatClient/ToolCallback；建立单独只读影视MCP工具集 | Client初始化→tools/list→call→来源校验实际通过；write/转存/发布不出现在该工具集。采用框架后仍保留权限/预算/超时回归 |
| 第7周：服务化 | 只对明确需要的AI API做身份和配额：Key哈希、撤销、按用户/Key预算、429、审计、模型超时与降级 | 两个测试身份隔离、配额竞争/撤销生效、错误来源可追踪；token估算与提供方真实usage分开，无usage时不编成本 |
| 第8周：求职材料 | 完成3–5分钟演示、架构图、可靠性故障复盘、同题集优化报告和简历更新 | 能解释工具边界、为什么采用状态机、恢复语义、BM25/向量取舍、测量局限；简历每项能力有可复现入口 |

后端基础与AI补强一起做：事务/唯一约束对应幂等，线程池/取消对应调用预算，SQL索引对应任务审计，HTTP/鉴权对应工具边界。Python用于评测和数据脚本，主要业务继续用Java。当前4GB显存环境先保持单模型串行；embedding性能及额外模型内存先测量，不预设需要更换硬件。

推荐先学清楚 JSON Schema、原生tool_calls及工具结果回传，再映射到 Spring AI 的工具抽象；用固定任务练上下文裁剪、截止时间、attempt与checkpoint。MCP连接与授权、工作流执行与恢复分别验收。网盘转存和外部发布留到只读任务闭环后，真实副作用仍需明确授权。

## 本轮评测工具

工具：[evaluate_agent.py](../tools/ai/evaluate_agent.py)；测试：[test_agent_evaluation.py](../tools/tests/test_agent_evaluation.py)；题集：[cases.json](evaluation/agent-harness-20261022/cases.json)。仅访问已有本机助手，不启动应用/数据库/调度器。默认计划模式无网络、无报告写入。

在仓库根目录执行：

```powershell
python -X utf8 tools/ai/evaluate_agent.py
python -X utf8 -m unittest discover -s tools/tests -p test_agent_evaluation.py -v
python -X utf8 tools/ai/evaluate_agent.py --run --repeat 2 --output tmp/agent-evaluation/report.json
```

`--case exact-title`可选择单个场景。只允许显式HTTP回环端口，禁代理/重定向；执行前实际发现工具必须恰好为searchMovies/getMovieDetails/searchMovieWeb。逐条执行，不重试推理；传输或响应契约失败立即停止。报告非零退出明确表示失败或未完成，不能仅观察进程已退出。

判分检查实际业务code、工具执行结果、工具预算、预期片名/年份、查询时间、空结果、多轮来源ID、联网原文摘录及URL。拒绝例要求模型/工具未执行。回答摘要哈希、调用名/状态/耗时用于复现；报告不保留问题历史、原始参数、资源URL、响应正文或凭据。

题集为7个场景/8轮，**由实施者编写的回归题**。程序校验通过不表示回答整体正确：除百科摘录检查外，片库回答自由文本可能仍补写事实；成功回答保留人工复核待办。该入口直连本地模型执行层，未覆盖GYing网页登录、Redis限流、站内队列、QQ群消息或MCP调用。

本轮结果与安全脱敏证据见[交付摘要](evaluation/agent-harness-20261022/README.md)。旧判分兼容问题的报告仅保留tmp，不作为模型失败或有效基线；最终报告使用修正后的Java纳秒时间兼容和校验前拒绝契约。

## 技术资料依据

本轮核查的官方资料：OpenAI Docs 的 Build an Agent Improvement Loop with Traces, Evals, and Codex、Evaluate agent workflows，以及 Spring AI Reference 的 Tool Calling、Getting Started with Model Context Protocol。前者用于组织trace→反馈→评测→工程改进；后者用于工具抽象与MCP互操作。架构与优先级仍由本项目的真实缺口决定，不依赖贴文中未单独核验的产品发布时间或行业调研。

## 求职表达

当前可补充：“为Java影视Agent建立版本化真实调用回归，验证工具选择、来源证据、多轮详情、引用一致性及请求拒绝，输出脱敏逐项报告；区分程序回归与人工留出评测。”

持久化恢复、向量混合RAG、Spring AI模型执行迁移、只读影视MCP和API Key平台完成后再加入简历。既有稀疏特征推荐不是embedding；百科搜索不等于已交付文档RAG；16次程序校验通过不能写成‘生产准确率100%’。
