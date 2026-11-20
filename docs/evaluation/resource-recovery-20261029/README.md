# 2026-10-29 资源搜索与片库运行恢复

2026-10-29，Asia/Shanghai。Movie backend 已恢复并于 18:02 完成候选部署；最终镜像上的 QQ 本库回复、nginx/公网片库搜索和桌面/手机页面均已复验。Agent 于20:56–20:57经管理员确认上线最终候选，21:00类别/评分来源专项复验通过；网页登录后的资源任务尚未验收。

## 部署与故障依据

| 项目 | 本次确认 |
| --- | --- |
| 后端提交 | `c4b8955`：减少 CatalogResearchService 不必要的索引/人物字典构建，并增加运行内存余量 |
| 最终镜像 SHA-256 | `0b349813f78962f52d84b0289aa9f5d383b8c93ce445343c6a70b9ef9a6c7984` |
| 最终 JAR SHA-256 | `c52afe9f9b2bceceea375b86d446695da32a9b22b80679d26588812b19ae1481` |
| JVM / 容器 | `-Xms256m -Xmx768m`；容器 1280 MiB，内存+交换限额 2560 MiB |
| Movie 发布范围 | 仅 backend；正常 TERM 退出，其他容器身份与启动时间保持原样；数据库架构未变。20:57 后续单独替换本机 Agent，见下表 |

[早前只读诊断](../quality-fix-20261029/memory-diagnosis.md)确认 13:35 +08:00 的 `Java heap space`；当时为 512 MiB heap / 768 MiB 容器，采样接近限额。近期日志还有 QQ 资源选择、分享校验相关的内存异常，支持 backend 是两端需要优先恢复的共同依赖；未取得 heap dump，不能断言准确分配点或内存泄漏。

公网 `/api/resource-search/query` 复用 QQ 的 `buildSearchReply`。该调用链说明后台故障可影响两端，但没有证据证明本次公网故障是异步 job 卡在 RUNNING；当时没有已落库的 `web:` 搜索记录。公网页面和片库入口通过独立验证确认恢复。

## 验证结果

仓库外脱敏证据目录：`E:/gying-tools/releases/ai-recovery-20261029-1750/`。以下 QQ、页面和查询记录均采用最终镜像部署后的复验；未发送真实 QQ 消息，未执行转存、分享或发布。

| 验证 | 结果 | 证据文件 |
| --- | --- | --- |
| 后端构建 | 742 项：712 PASS、30 SKIP、0 FAIL / ERROR；346 个源码文件与打包清单一致，镜像内 JAR hash 已核验 | `backend-build-report.json`、`backend-source-manifest.json` |
| 实机片库 query | 冷/暖结构条件、语义查询、人物筛选及后续 TTL 刷新均 HTTP 200，条件检查通过；documents 12400 条、未截断；模型调用 0 | `catalog-verification.json`、`catalog-verification-after-ttl.json` |
| QQ 本库回复 | 18:13 复验 HTTP 200、0.20 秒、5 个链接，含本库优先标记，搜索日志 `LIBRARY_RESOURCE`；这是回复生成验收 | `search-verification.json` |
| nginx / 公网搜索 | 18:13 的精确片名查询均 HTTP 200 且有记录；内部受限路由返回 404 | `search-verification.json` |
| 公网页面 | 18:12–18:13，在加载指示和骨架消失后验收；1280 / 390 px 各有 100 个影片链接、98 张已加载海报，HTTP/API 均 200，无加载失败提示、横向溢出或 pageErrors | `public-browser-verification.json`、`public-1280.png`、`public-390.png` |
| 本地 PanSou 纯搜索 | HTTP 200，4.153 秒；QUARK 10 / XUNLEI 3；现役外部 PanSou API 未配置启用 | `upstream-probe.json` |
| GYING 安全读取 | `/health` 与实际 `/search` 均 HTTP 200；搜索 6.018 秒、返回 1 部影片 | `upstream-probe.json` |
| Agent最终部署 | 20:57现役01eab8…、104项Java证据绑定、正常停止与独立启动通过；三份age认证，其他服务/配置/state保持 | `final-agent-deployment-result.json`、`final-agent-elevated-result.json` |
| 真实Agent查询 | 21:00串行两条HTTP/API200、completed；类别/评分来源否定条件均正确，23.50/2.25秒。两条queryPlan均遗漏rating_desc，第二条还遗漏剧情类型，只算专项复验通过 | `final-agent-real-query.json` |
| 后端日志窗口 | 18:02–18:17 合并 stdout/stderr 检查，未见新 OOM、资源请求异常或 QQ/网页搜索失败标记 | `backend-log-verification.json` |

TTL 复验的最高容器内存采样约 945 MiB，低于 1280 MiB 限额；本轮短窗口和专项查询通过不能替代长期稳定性或压力验证。20:58公网片库仍返回200（12446条/1.81秒），21:01复核自18:02部署后的日志无新OOM；历史计数按原观测保留。30 项跳过测试仍按跳过记录，未计为通过。

## 回退材料与未完成项

旧 backend 镜像、有效配置、`backend-data`、`ai-state-key` 共四份 age 加密材料已完成 hash 和认证解密校验，存放于 `G:/gying-backups/backend-recovery-20261029T095350.591530Z/`。本次恢复和部署没有回灌数据库、状态或密钥；应用回退保留当前数据卷，不重放历史任务。证据为 `backend-recovery-result.json`、`backend-deployment-result.json`。

- Agent 现役 JAR 已为 `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`（代码9cd1d2b）。用户明确重试后20:56–20:57管理员部署成功，旧实例正常stopped、新不可变runtime ready、独立hostInJob=false；其他本机服务、Movie容器、配置/密钥/索引与当前state均保持。三份age回退材料在 `G:/gying-backups/ai-agent-final-20261029T125645.591658Z`，SHA/完整认证解密通过；只回退旧JAR，不回灌旧state。
- 当前有效登录设备聚合只有 1 个 ADMIN，没有可用普通 USER 会话。因此网页登录后的 resource-search 提交/轮询尚未验收；旧测试账号保持 disabled，本轮没有创建、恢复或启用账号。
- 已知安全门禁的 11 项 FAIL 仍存在，本次恢复不关闭这些问题；不能把业务入口通过当作整体安全验收通过。
