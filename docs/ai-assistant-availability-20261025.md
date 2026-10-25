# AI 助手服务不可用修复（2026-10-25）

2026-10-25 17:19–17:20 仅平滑替换本机 Agent backend 并启用独立 Windows 启动入口。服务与实际查询已恢复；本次可用性回归不代表小模型回答全部正确。16:00 的 Movie backend/frontend/Agent 查询部署与开发对照保留在[原报告](ai-query-plan-v2-20261025.md)。

## 故障依据

16:49 调查时，Agent 5000、Ollama 11434、Router 5002、本机前端 3000 均无监听；旧 PID/ready 文件仍在，没有匹配的正常停止请求及完成确认。Movie 后端、网站 HTML 正常，匿名 AI 状态接口返回预期 401。Agent 最后启动日志为 16:01，未发现这次的 OOM/正常退出记录。

16:36 的 CodexSandboxService Windows 1000/1001/1002 事件可能相关，但完整消息无法读取，不能断言唯一终止原因。真实测试确认本工具的启动壳处于带 kill-on-close 的 Windows Job，普通后台子进程存在随宿主结束的风险；单层 CREATE_BREAKAWAY 仍可能留在外层 Job，未采用该原型。

16:53 使用 WMI 独立启动恢复原四服务。随后真实生产只读队列查询《星际穿越》完成，7.53 秒，没有发送消息。恢复进程后，复杂条件查询仍以 HTTP 200/API 500、CATALOG_RESEARCH_UNAVAILABLE 失败，总 30.64 秒，只有 planning 成功；有效结构化片库 POST 单独耗时 15.05 秒才成功。旧客户端 connect=2 秒、call=15 秒，未配置 read 上限，实际为 10 秒。这是第二处独立故障。空 job/lease 的受控 loopback 调用由服务端允许，不把本次失败归因为缺少任务上下文。

## 修复与回退

- Agent 代码提交 `cd79797`。`start-local.ps1` 默认通过 WMI 创建隐藏独立启动壳；仅恢复指定服务，并等待健康及实例握手。内部 ServiceHost 入口若仍处于 Job 就拒绝。仅转交审查过的非敏感启动设置，凭据继续读取既有私有文件，临时启动设置读取后删除。没有新增宿主计划任务。
- GyingCatalogClient read/call 上限改为 30 秒，connect 仍 2 秒；所有研究片库请求同时受原总体 deadline 约束，不无限等待。
- Maven verify **91/91，0 失败/错误/跳过**，完整 JAR 打包成功；6 项真实合成 Windows Job 测试与 9 项启动研究配置验证通过。关闭合成父 Job 后独立子进程仍持续心跳；不通过停止生产或整机重启模拟。完整新 JAR 在 15000 隔离健康/握手/优雅退出通过。
- 17:17 首次补丁切换因公网探针 403 平滑回退旧包，记录 `agent-deployment-result.json` 保留。浏览器标识探针同一公网页面 200，调整验证方式后 17:19–17:20 第二次成功，记录 `agent-deployment-result-attempt2.json`。
- 当前 JAR SHA-256 `33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`，使用对应 hash 的不可变运行副本。检查点 `G:/gying-backups/ai-agent-20261025T091941.886931Z` 的旧 JAR、Agent 私有 state/key/token/config、旧启动器三份 age 产物均通过 hash 与完整认证解密到空设备；仅 Agent 应用回退，非全栈或异机恢复。
- 部署窗口所有 Movie 容器身份、镜像、启动时间与重启数保持；Ollama/Router/前端记录保持。私有配置 hash 与 11,272 条索引 hash `f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4` 保持，工作区 1.7B 与影视指令 4B 配置保留。

## 实际验收与限制

| 验证 | 实际结果 |
| --- | --- |
| 年份/科幻/豆瓣评分/排除导演/数量 | HTTP/API 200、completed，27.98 秒；queryCatalog 11.24 秒超过旧 read 上限，返回两部候选，未再出现片库超时 |
| 类似《降临》及排除导演 | BGE-M3 真正返回 100 候选；片库 13.83 秒，简介相关性复核后保留《E.T.外星人》1982，62.5 秒，未虚构补足两部 |
| 《莫扎特传》1984 明确联网 | 真实 bing-html 调用，原始 2 条，同名 TV 排除 1 条，保留 IMDb 1 条；17.42 秒完成。剧情来自片库，搜索摘要未逐页核实剧情 |
| 真实生产只读队列 | 可续期租约 → 本机 worker → 模型解释 → 回执 → 片库搜《星际穿越》，7.52 秒，未返回备用提示 |
| 最终入口 | 四本机服务、源站/公网 AI 页面、Tunnel 200；源站匿名 AI 状态 401，公网内部 AI 路径 404，9 项均符合预期 |
| 日志 | 17:19:45–17:26:18 新 Agent 日志及同期 Movie backend 日志 ERROR/Exception=0；仅短窗口观察 |

首次条件回归仍有质量偏差：计划额外排除了“比利·克里斯托”，并将“2010 年之后”转换为包含 2010 的范围，候选中有 2010 年作品。相似查询计划也额外排除了“雷金纳德·丹尼”。这些属于人物/条件解析问题，保留实际证据，不把 completed 当成完全答对，不逐题调冻结题集；有用主题近似和严格条件分开评估。

本轮没有重新启用旧测试账号、伪造用户 JWT 或验收真实网站登录条件查询；受控 loopback 研究查询和真实生产只读队列不等同于用户浏览器登录验收。没有发送消息、转存或发帖。既有安全 **58 PASS / 11 FAIL / 1 UNKNOWN** 是前序复检，未在本补丁关闭 G01–G11。

## 重启安排与证据

17:21:55 只读回读 `GYing One-off Graceful Restart 20261025-2000`：Ready、2026-10-25 20:00 +08，20:00–20:10 开始/21:00 截止，未使用；主机/账户 SID/config/runtime/窗口/动作绑定均通过。runtime SHA `a4305d05ec1696a81560fe141ffc2253b51de3c62fba1b33946ae01cb778f53c`，禁止补跑和强制终止，未重新登记或立即重启。17:24 的只读 readiness 为 busyJobs 空、原四本机服务与 10 个现役容器可见、pendingAttempts=0；不是 20:00 空闲保证。重启后仍需 ASUS 交互登录恢复；详见[重启手册](weekly-graceful-restart.md)。

本轮脚本、失败/回退报告、加密检查点清单、启动/模型/语义/联网/队列/入口/日志/重启证据在 `E:/gying-tools/releases/ai-assistant-unavailable-20261025/`。原部署、原 30 题、10,432/11,220 快照及开发对照 provenance 不改写。
