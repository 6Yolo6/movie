# 2026-11-03 查询条件、主题与可信历史候选

本轮继续质量闭环，修复作者被当作演员、数字姓名误匹配、正向主题遗漏、撤回偏好仍保留，以及较早列表详情指代。完整验证与两仓源码已分别记录；第一阶段仍未完成。

## 实现与运行范围

Movie只在原句有实际主演/执导要求时恢复人物条件，数字姓名不从更长数字内部恢复，例如不将150分钟中的50分片段识别为演员。Agent保留正向主题，撤回软偏好时保留类型并加强本轮核心要求。主题复核由服务端固定完整core与soft顺序，模型逐项返回状态和本次300字简介中的引文；core未证实则剔除，soft未证实保留并说明。真实引文仍可能不支持要求，语义判断继续依赖模型。

多轮上下文由Movie沿同用户、成功且未过期的previousJobId链生成；最新加最多三个旧轮，每轮最多30项公开身份，总预算10000字符，期限按原完成时刻加900秒。较早列表序号、片名、ID和本轮年份相互核对，链不完整或过期时澄清。新请求幂等指纹与动态候选上下文分离，并兼容旧检查点指纹。候选上下文和客户端历史文本承担不同职责。

## 构建和审查

| 记录 | 实际范围 |
| --- | --- |
| [模型观测Movie构建](movie-build.json) | 离线verify/compile通过；899项，869通过、30环境跳过；JAR `4f73846fd722f1c6d33baa2ee9df31d0a1745cf87c53ba48dd38904b1dc51112` |
| [Agent构建](agent-build.json) | 离线verify/compile通过；173/173，无跳过；JAR `055f1bdc4bc8d52b8e9c2330d8a3937085d6043f96323cc2461db16f71d75eda` |
| [审查后Movie构建](movie-reviewed-build.json) | 另补同一无标点句作者与同人主演/执导的出现位置边界；修前新增两项失败、修后48项专项通过；完整902项中872通过、30环境跳过，compile通过。最终构建与源码hash另记，不覆盖原模型候选 |

完整构建使用仓库外源码副本、offline Maven和allowlist环境，未加载生产env、数据库或生产任务。跳过类别见各构建记录，不能计全量零跳过。修后Movie的额外边界未重跑模型；下面15轮仍仅绑定原4f73846f…候选。

## 实际候选观测和质量

11月3日20:24–20:35（Asia/Shanghai）执行已观察过的开发题v3，10场景/15轮，各轮一次、无推理重试。真实候选Agent和Movie检索类使用现役qwen3:4b、BGE-M3索引及10月31日12822条固定公开快照；快照非原子、非实时片库。较早候选上下文来自此前实际响应的fixture，生产同owner/TTL链另由Java回归验证。

15 executed/15 completed、执行失败0，两临时进程正常exit0；原协议没有mechanical passed字段，该计数为null。[逐轮模型复核](review.md)为满足5、部分有用2、有效澄清2、未满足4、证据不足2；三维完整12、事实维度null3、真人新增0。不据重复开发题、不同运行链路或耗时计算独立质量提升。

仍有明确不足：胶片修复题引用《迁徙的鸟》的胶片里程却没有修复过程依据；电影院核心场景题引用《沼泽深处的女孩》中的小镇人物片段，不能支持电影院要求。野外徒步题类别仍错为ac且无候选；一条音乐候选片库标mv但简介为电视特辑，电影身份不足。原始对白、当前官方发行公告和片库原作关系的核验范围继续保留未知。模型状态与引文身份通过，不能代替需求蕴含核对。

## 证据协议

- [candidate-report.json](candidate-report.json)：原报告完整字节归档；[candidate-answers.md](candidate-answers.md)：逐轮原文。
- [candidate-review.json](candidate-review.json)：独立自定义v1，reportSha256为原文件字节hash，answerSha256为UTF8原文hash；不冒充quality_review.py的v2规范JSON hash。
- [review-validation.json](review-validation.json)：15轮身份、原回答hash、评分数量和未知边界；[validation-summary.json](validation-summary.json)：构建、源码、清理与门禁综合记录。
- [model-and-corpus.json](model-and-corpus.json)、[runner-manifest.json](runner-manifest.json)：模型/语料与运行脚本来源。
- [runtime-before.json](runtime-before.json)、[runtime-after.json](runtime-after.json)：只读入口、容器和stored Agent身份；未复验生产argv，非候选上线证据。

无网站USER会话、生产state/queue领取、DB连接/写入、浏览器页面或真实外发。未再次启用test。临时15001/18883监听已清理；旧网站15轮属于此前现役版本的[问题来源](../answer-quality-20261103/README.md)，不能复用作本候选上线验收。

## 部署与回退边界

20:46只读[安全复检](security-gates.json)仍为58 PASS/11 FAIL/1 UNKNOWN，G01–G11全FAIL；就绪检查11通过/1既有schema警告/0失败。按[gying-project-ops技能](../../../.agents/skills/gying-project-ops/SKILL.md)的[部署规则](../../../.agents/skills/gying-project-ops/references/deployment-recovery.md)，“门禁 FAIL、测试失败或目标不清时停止相应动作并汇报”。20:46检查时尚无本次例外，生产替换暂停；随后用户明确“可部署”，授权本轮Movie后端与本机Agent。按新Agent兼容旧后端的顺序进入部署，结果由[部署记录](deployment-result.json)和[部署验收](deployment.md)另记。

当前Movie容器和Agent stored身份保持；本轮未建立应用部署加密检查点、未执行应用回退。已有原生产恢复材料仍按[当前状态](../../current-project-status.md)维护，上一轮test行备份不能替代部署备份。最终上线须重新核验受影响服务、自然空闲、配置/密钥/状态检查点和实际入口，不沿用旧模型报告冒称上线通过。
