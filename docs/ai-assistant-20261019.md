# 本地 AI、项目问答与目录识别

2026-10-19/20已部署；[标题推荐](ai-recommendations-20261020.md)、[当前自然搜索](catalog-features-search-20261021.md)、[完整旧证据](history/snapshots/20261022/docs/ai-assistant-20261019.md)。

## 能力与边界

/ai-assistant登录问答，前序模型每轮真实searchMovies/getMovieDetails并展示时间/片库证据；NORMAL仅存量非实时验活。失败不入历史，用户切换/新对话清内存。本机桥接主动轮询loopback，APP_AI_BRIDGE_TOKEN/X-AI-Bridge-Token只授权internal/ai，不复用通用内部token，不公开Ollama/MCP。网页6次/分钟、队列8活动/单执行/200结果/15分钟，完成丢问题历史/trace，nginx拒公开internal。

OpenClaw自然提词必须原句出现，不猜剧情名/批量/URL/命令/转存发布；原序号/provider/管理员保护保留，源+唯一安全运行副本+Gateway共同验。目录NFKC/繁简/拼音/有限偏旁，全名至少半数且≥3汉字锚点，纯首字母/局部/错续集拒绝；年份/季/视频检查仍有效，不能内容拒绝后退整分享。

## 实盘只读结果

858ng混合“一个–部门的DS(2026)”与正常目录定位到2026 MKV；GnVK混写目录虽识别名称，但仅exe/解压内容拒绝，不能因“更新02”退整分享。READ ONLY/share-detail/解析器，不下载执行exe/转存/重放。

## 已完成修复和验收

真实本地模型新回环连接修EOF，星际穿越/空闲15秒追问通过；一次未调用工具不计通过。local Maven47、前端历史5/Router6/生命周期2及构建；GYing先前639通过/15skip、最终定向42、JAR324文件；前端5组mock；插件11提词/3ADMIN/3拒绝/4命令/UTF8。24Python+8PowerShell重启任务偏移修复，未整机重启。[维护计划](weekly-graceful-restart.md)。

## 部署与回退

材料 `E:/gying-tools/releases/ai-assistant-20261019` 固定候选hash；本地助手e709d8c、旧运行包 `G:/local-ai-agent/backups/ai-catalog-20261019/before.jar`；外部配置检查点 `G:/gying-backups/ai-assistant-config-20261019-232528` 四age认证通过，仅配置非一致性DB灾备。backend/frontend/独立桥接/OpenClaw已部署，source排空后原镜像恢复、publisher不替换。后续镜像/恢复从最新状态查；原安全59/11/0未闭环，未群出站/网盘转存。
