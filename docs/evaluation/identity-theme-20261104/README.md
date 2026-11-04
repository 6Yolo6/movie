# 2026-11-04 电影身份与主题：第一阶段（双后端已上线）

Asia/Shanghai。本轮继续修复11月3日实际观察到的电视特辑误作音乐电影、胶片拍摄数据误作修复过程、小镇人物误作电影院核心场景，以及徒步查询误限动画。两仓代码与构建已完成，按本轮“可上线”限定授权已先Movie后Agent上线，**第一阶段未完成**。当前线上版本见[状态手册](../../current-project-status.md)，部署回执及独立只读验收见[本轮上线](deployment.md)。

## 代码与证据边界

Movie公开元数据增加既有数据库字段`tmdbType`及`mediaIdentity`，身份状态区分`supported/uncertain/contradicted/conflict`，证据包含来源字段和逐字的局部原文。`mv/ac`本身不能证明电影身份；明确电视特辑、电视类别或来源类型冲突优先剔除。裸`season`仅作待核验线索，不独证剧集，也不覆盖明确电影来源或电影自述；旧快照有1414条mv/season1，不擅自修改其真实数据。录音棚现场录像没有充分电影自述时保留未知，不据未知断言作品不是电影。简介中的故事人物提及电视节目不当作该作品自述。没有修改真实片目或数据库架构。

原句决定媒体类别与独立`filmIdentityRequired`；普通动漫与动画电影分开，同身份的动画/真人电影替代仍要求电影，电影/剧集替代不强行限定。“是不是有多个电影版本”不再被误当作“不是电影”的否定要求。字段随同用户成功任务的既有服务端`queryPlan`继承。Agent解析继承后第二次检索使用已经校准的计划，避免把无媒体词的年份追问当新抽取再次清空身份条件。

Agent先保守核对电影身份，再对核心主题加独立反证审查。第二审查只见固定完整要求及本次简介前300个Java字符，不见片名、类型、评分或第一审结论；检查主体、行为、对象、地点及故事作用。各项必须supported、无缺失要素并给逐字引文；未知、否定、缺项和伪引文剔除。软偏好仍可未知或不一致。最多审8候选、每批4，缩批时调用数可能增加，保持原截止时间。两次模型判断仍可能同错，**不是确定性语义证明**。

联网识别片名后的回查继续保持原年份、时长、主题、电影身份和数量，不能以标题命中绕过复核。额外召回最多20项，最终清单保持用户limit。直接详情保留受限的`tmdbType/season`并计算身份资料，可查看未知或冲突记录并显示身份疑点。有效本地身份或主题核对已完成却缺证时，补充联网失败保留`completed+partial`和诚实的未知答复、错误trace，不把它计为需求满足；未完成本地核对或核心调用失败维持失败。完全泛化的快速规则推荐链未在本轮改动，不声称所有网站推荐均验证电影身份。

## 验证

| 记录 | 结果与范围 |
| --- | --- |
| [Movie构建](movie-build.json) | 离线verify和独立compile均0；973项942通过、31环境跳过，固定JAR `2c1301be…`；来源354文件逐字hash绑定 |
| [Agent构建](agent-build.json) | 离线verify和独立compile均0；202/202，无跳过，固定JAR `1d1ad180…`；来源50文件绑定 |
| [真实SQL](catalog-mysql.json) | `CatalogAiMySqlTest`12/12，无跳过；固定已存在镜像、network=none、无宿主端口/生产挂载；两个自有测试容器已清理；[源码清单](catalog-mysql-source-manifest.json) |
| [实际候选harness](catalog-harness.json) | 真实Movie类与身份后处理，Java编译0；旧公开快照12822项，没有`tmdbType`，缺失仍缺失，未伪造来源类型 |
| 首轮模型观测 | 6场景10计划/9提交/8完成，影院WEB_TIMEOUT1、后续1未执行；[首轮原报告](initial-report.json)/[模型复核](initial-review.md)保留，不能绑定到后来的源码 |
| 最终模型观测 | 冻结的Movie `2c1301be…`/Agent `1d1ad180…`完成3场景7/7；[原报告](final-report.json)、[实际回答](final-answers.md)、[模型复核](final-review.md)；每轮一次、零推理重试，双进程正常exit0 |

最终观测在11月4日21:36:59–21:42:02（Asia/Shanghai）执行，使用实际qwen3:4b指令模型、现役BGE-M3索引和10月31日12822条公开快照。快照没有tmdbType，缺失保持缺失；bare season并不直接判剧集。详情也来自18883的同一快照，2次detailGets、0数据库连接/写入/队列领取，不是生产详情验收。题目已用于开发，历史是实际前轮答复的fixture，不替代生产owner/TTL、加密队列、网页或真人验收。

| 最终场景 | 实际结果 | 模型诊断 |
| --- | --- | --- |
| 同名版本2轮 | 首轮只给2019/Greta网页线索，未列未经身份核定的多个版本；次轮准确返回2019主演并保留电影身份未知 | 证据不足1、部分有用1；事实维度均null |
| 音乐电影3轮 | 首轮《曾经》2007/86分钟；次轮保留≥2015/音乐/≤110分钟/limit2，列《坂本龙一：杰作》2023/103及《冷战》2018/84；第三轮回到第一轮《曾经》/约翰·卡尼，未把电视特辑当电影 | 满足3；依据是实际片库资料支持，不是独立外部电影身份认证 |
| 小镇影院2轮 | 没有经身份与完整主题核定的候选，保留诚实空结果和搜索资料；加强核心场景后仍无候选，第三条《蛋镇电影院》为文学摘录 | 首轮证据不足、次轮未满足；真实摘录不足以证明剧情电影身份或核心场景，事实null |

最终模型复核共满足3、部分有用1、证据不足2、未满足1，三维完整3/7、事实null4、真人0。最终7轮中3次补充联网调用均成功，补充联网超时后保留partial-completed的降级分支仅由Java回归验证，最终真实推理未触发，不把回归计入真实模型验收。**执行7/7不等于需求7/7满足；第一阶段仍未完成。**初轮为满足1/部分1/有效澄清2/未满足3/证据不足1，另执行失败1与未执行1，三维完整6/10；两轮开发观测不合并为质量提升率，原失败不覆盖。原文件字节SHA与逐条回答UTF8 SHA、自定义v1复核协议见[最终复核校验](final-review-validation.json)和[验证汇总](validation-summary.json)；[源码清单](movie-source-manifest.json)/[Agent清单](agent-source-manifest.json)、[实际脚本hash](script-manifest.json)及[材料hash](artifact-manifest.json)分别绑定。仍需补身份与核心主题资料、联网摘录的文学形态偏题，以及正式入口和真人验收。冻结复核原文保留外部文件名：candidate-review.json对应本目录[final-review.json](final-review.json)，review-validation.json对应[final-review-validation.json](final-review-validation.json)；原report.json对应[final-report.json](final-report.json)。

Movie环境跳过为SQL26、Redis1、Windows权限/文件4；本轮SQL12另由真实隔离MySQL验收，不把其余跳过计通过。构建环境采用allowlist并复制源码，不加载生产env。独立审查发现的多轮丢身份、冲突升级、回填limit覆盖和混合电影类型四个P2均已补回归。

本段“生产保持”只描述上述部署前隔离观测，不描述后续替换。 [运行前入口](runtime-before.json)为Agent/Movie/真实nginx片库/Ollama均200、生产Agent仍PID38460；普通权限Get-Process无法读取其Path，生命周期CheckOnly未能证明完整身份，argv/JAR运行态未另验。两份最初探针使用不存在的health路径而404的原结果保留，不当作实际服务故障；后续正确片库入口复验通过。[运行后入口](runtime-after.json)四项也均200，[前后核对](runtime-validation.json)确认10个生产容器ID/镜像、Agent受管身份和索引SHA `f74cb9c6…`保持；15001/18883临时监听已清除。推理后4B模型驻留内存，模型配置与索引没有替换，不声称加载状态前后相同。

## 部署门禁与回退

本轮获用户“可上线”限定授权，Movie backend于2026-11-04 22:39:17、Agent backend于2026-11-04 22:42:34（Asia/Shanghai）按先Movie后Agent顺序上线；实际image/JAR、受管身份、真实入口与独立只读复核通过。安全58 PASS/11 FAIL/1 UNKNOWN及G01–G11仍未关闭，第一阶段未完成。

普通权限前置检查因不可读进程Path与bridge.bin访问拒绝而停止；实际管理员回执确认bridge.bin归BUILTIN\Administrators所有且只有一条ACL规则，不是共享冲突。零字节.lock的真实FileLock占用与该ACL拒绝分别记录。随后Windows实际确认的受限管理员路径完成读取/空闲/身份门禁，未修改ACL或绕过state。

Movie正常TERM143后完成五份age检查点 `G:\gying-backups\ai-identity-theme-backend-20261104-20261104T143825.278661Z`，Agent正常停止后完成三份age检查点 `G:\gying-backups\ai-agent-identity-theme-20261104-20261104T144159.678847Z`；manifest/size/hash/完整认证检查通过，二者是各自停止窗口检查点，非跨服务原子快照。两份静态应用/回退覆盖已纳入日常备份，dry-run30通过，自然完整备份未验。现役Compose在原11层末尾追加第12层 `E:\gying-tools\releases\ai-identity-theme-20261104\deployment-backend\candidate.json`；前11份文件及参数保持，11月3日第10层rollback和第11层candidate仍保留。有效配置、当前数据/state/key/token/模型/索引及无关容器和本机服务保持。

应用回退保持当前SQL/state/key；回退旧Movie前先比较持久化filmIdentityRequired历史，并在候选写入者正常停止后再次核对。新增或竞态历史阻断旧版启动，必要时恢复当前候选保持运行。这些保护由已审查助手与回归验证，不称生产竞态注入；不恢复旧dump或数据。旧Agent055f…直接拷贝queryPlan，传输能保留新字段，恢复旧Agent应用通常无需回退Movie，但会失去本轮电影身份与第二主题审查机制。

独立扩展只读验收初轮误按size200分页，实际ApiSecurityFilter对internal非管理员入口限size100，因此返回400，身份投影schema/完整性检查失败；这是核验脚本请求错误，未据此改生产或回退。原失败报告raw SHA ea813a3d…保留；修正版按100分页重新只读复验通过，不称全部入口一次过。Movie部署期原15入口一次通过与该扩展验收分开记录。

原七轮仍为部署前隔离开发观测，7/7 completed不等于7条满足；模型诊断满足3/部分有用1/证据不足2/未满足1、事实null4、真人0保持。本次上线后只读GET与入口检查没有模型推理或新USER任务，网站USER、跨owner、自然TTL、真实浏览器、主题回答和真人质量仍未验。

此前门禁失败暂停、普通权限失败、UAC及入口失败attempts按原时点保留，旧授权未自动复用。当前用户仅批准本轮两后端；没有数据库迁移、账户改动、旧数据恢复或真实转存/群发/发博。原[validation-summary.json](validation-summary.json)是部署前冻结观测，其deploymentPerformed=false不改写；[部署汇总](deployment/deployment-summary.json)记录后续运行事实，[上线与回退](deployment.md)列出回执绑定及验收限制。更新README前已按原字节保存[旧README](deployment/README-before-deployment.md)和[旧材料manifest](deployment/artifact-manifest-before-deployment.json)；根[artifact-manifest](artifact-manifest.json)重新递归覆盖部署材料，final-report原字节保持。
