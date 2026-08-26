# 故障排查

## 事故处理顺序

1. 暂停高风险自动化：Resource Hub Worker、频道计划任务和机器人自动转存。
2. 记录时间、环境、现象、最近正常提交和近期变更。
3. 重启前保存容器状态和日志。
4. 从依赖向外检查：MySQL、Redis、存储、外部 API、backend、frontend、nginx、机器人和频道。
5. 每次只改变一个层级。
6. 重新验证最初的用户可见现象。
7. 在 `docs/current-project-status.md` 记录原因、修复、证据和剩余风险。

不能一次重启所有服务，否则会丢失证据并掩盖故障边界。

## 现象矩阵

| 现象 | 可能边界 | 检查项 |
| --- | --- | --- |
| 整站不可用 | nginx、端口、应用容器 | nginx 日志、80/443、上游容器 |
| 前端可加载但 `/api` 失败 | nginx 到 backend | backend 日志、`backend:8880`、数据库启动 |
| 浏览器 API 正常但详情 SSR 失败 | frontend 内部 API | `INTERNAL_API_URL=http://backend:8880` |
| backend 启动后出现 mapper SQL 错误 | 架构漂移 | 在线字段和索引与实体、SQL 对比 |
| MySQL 时间偏差 8 小时 | 容器、JVM、JDBC 时区 | `TZ`、`JAVA_OPTS`、JDBC、`NOW()` 与 `UTC_TIMESTAMP()` |
| Resource Hub 提示缺少 Key | backend 环境过期或目标错误 | 检查运行容器配置键并重建 |
| GYING 健康但候选失败 | Session、PoW、登录或内部 token | `gying-source` 日志、`/account` 状态、backend/source token |
| PanSou 根路径 `404` | 检查路径错误 | 容器日志和已知 API |
| 夸克任务已提交但未转存 | Cookie 或账号配置 | WebUI Cookie、API token、最终配置 |
| 分享任务没有 `share_id` | 空目录、Cookie 或 API 故障 | 目录内容、任务错误、Cookie |
| 重试显示完成但没有活动资源 | 旧逻辑跳过、任务缺失或分享未生成 | 重建任务并检查自有分享和 `resource_link` |
| 单片分享包含整套合集 | 未定位目标子目录或未收窄 `fid` | 标题/年份/季号、目录树、提交参数和最终分享内容 |
| 待入库或待转存数量异常 | 状态过滤语义 | 状态、分享链接、资源绑定条件，先 reconcile dry-run |
| 缺资源批量任务被截断 | 请求超过每批 20 部 | 前端选择数、请求体和后端逐条结果 |
| 发现或修复出现 nginx `504` | 调用了旧同步接口 | 确认返回 `jobId` 并轮询 |
| 修复后产生重复资源 | 重放或 canonical 匹配错误 | source URL/hash、旧失效行、发布行为 |
| 官方 QQ 主动消息返回 `40034105` | 平台权限或消息模式 | 使用带 `msg_id` 的被动回复或授权 |
| NapCat 容器存在或退出 | 历史遗留服务仍被误启动 | 不重启 NapCat；检查 OpenClaw Gateway、后端 QQ 接口和 QQBot 配置 |
| OpenClaw 重启循环 | gateway 配置 | `gateway.mode=local`、`gateway.bind=lan` 并重建 |
| OpenClaw 升级后搜索失效 | 本地插件补丁被覆盖 | 重新应用脚本或部署正式插件 |
| QQ 频道帖子乱码 | Windows CLI 编码 | UTF-8 content file，避免 stdin JSON |
| 计划任务存在但没有发帖 | 任务禁用或授权过期 | 任务状态、管理员启用、CLI 登录 |
| 多平台发布容器 401 | 内部 token 不一致 | backend 与容器的 `SOCIAL_PUBLISHER_TOKEN` |
| QQ 发布账号未就绪 | 凭据卷、扫码授权或未加入频道 | 账号目录、授权状态、目标频道 |
| 微博发布失败 | Cookie/fingerprint 过期、限流或安全验证 | `/health` readiness、失败日志；验证码人工处理 |
| 多平台同片重复发布 | 目标或影片去重异常 | 唯一键、目标 ID、资源 ID 和历史日志 |
| MySQL MCP 报 `1045` 和 password NO | MCP 缺少密码环境 | `GYING_DB_PASSWORD` 并重启 MCP |
| MCP Compose 找不到配置 | Compose 文件名非默认 | shell 显式使用 `-f` |
| Docker 依赖端口冲突 | embedded 与外部服务重复 | `docker ps -a`、profile、映射端口 |

## 日志命令

```powershell
docker compose -f docker-compose.prod.yml logs --tail=200 backend
docker compose -f docker-compose.prod.yml logs --tail=200 gying-source
docker compose -f docker-compose.prod.yml logs --tail=200 social-publisher
docker compose -f docker-compose.prod.yml logs --tail=200 frontend
docker compose -f docker-compose.prod.yml logs --tail=200 nginx
docker logs --tail 200 gying-pansou
docker logs --tail 200 gying-quark-auto-save
docker logs --tail 200 openclaw-openclaw-gateway-1
```

分享日志前删除 Cookie、token、Authorization、用户隐私和带访问参数的完整 URL。

## 数据安全排查

- 先使用 `SELECT` 和 dry-run。
- 同时检查少量样本和汇总数量。
- 重置卡住任务前先核实外部副作用。
- 重复清理要人工复核标题、别名和季信息。
- 失效链接要区分验证器不可用、疑似失效、保存目录为空和确认失效。
- 不能把可观测性问题变成删除或无限重试循环。

## 编码

Markdown、SQL、JSON 和 PowerShell 文件操作都显式使用 UTF-8。
连续异常汉字或问号表示可能出现 mojibake，应根据已知原文修复，
不能反复转码已损坏字符串。

## 关闭事故

满足以下条件后才能关闭：

- 原始故障路径已验证；
- 日志中没有立即复发；
- 自动化已明确重新启用或记录为关闭；
- 备份和回滚状态明确；
- 未完成工作已记录；
- 状态文档与在线证据不再矛盾。
