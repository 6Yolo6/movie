# Resource Hub 运维

本文只描述运维和配置。处理纯运维任务时不得修改 Resource Hub 业务代码。

## 流水线

```text
TMDB 元数据或本地 canonical 影片
  -> movie_source_identity（TMDB / GYING）
  -> 本地 PanSou + 外部 Panso API + GYING 候选
  -> resource_discovery_result
  -> quark_transfer_task
  -> quark-auto-save
  -> 目标影片或季的保存目录
  -> 自有夸克分享
  -> resource_link
  -> 前端 / QQ 机器人 / QQ 频道
```

外部搜索结果不能直接发布，必须先获得可用的自有分享链接。电影合集和多季剧集必须定位目标子目录，
并用目标 `fid` 收窄转存与分享范围。

## 配置优先级

启动属性来自 `application*.yml` 和环境变量。backend 启动时，
`ResourceHubConfigServiceImpl` 会从 `sys_config` 重新加载受支持的非敏感运行参数，
覆盖对应的环境默认值。

以下敏感值和连接地址保留在环境变量或本地外部配置：

- `TMDB_API_KEY`
- `RESOURCE_HUB_PANSOU_BASE_URL` 及 token
- `PANSOU_API_BASE_URL` 与 `PANSOU_API_KEY`
- `GYING_SOURCE_BASE_URL`、`GYING_SOURCE_API_TOKEN` 和 GYING 账号凭据
- `QUARK_AUTO_SAVE_BASE_URL` 及 API token
- 夸克 Cookie，优先在 quark-auto-save WebUI 配置，环境变量只作兜底
- backend 内部认证、QQ token 和 `QQ_CHANNEL_PUBLISHER_*`

`sys_config` 管理：

- `resource.hub.enabled`
- `resource.hub.auto_approve`
- `resource.hub.tmdb.auto_sync_*`
- `resource.hub.tmdb.auto_discovery_enabled`
- `resource.hub.tmdb.discovery_*`
- `resource.hub.worker.enabled`
- `resource.hub.worker.task_limit`
- `resource.hub.worker.quark_limit`
- `resource.hub.worker.publish_limit`

调度间隔从启动属性 `RESOURCE_HUB_WORKER_FIXED_DELAY_MS` 绑定；
修改其他 `sys_config` 不会重建调度器。

## 安全启动

新环境或恢复环境按以下顺序：

1. 设置 `RESOURCE_HUB_ENABLED=false`。
2. 设置 `RESOURCE_HUB_WORKER_ENABLED=false`。
3. 验证 MySQL、Redis、MinIO、TMDB、PanSou/Panso、quark-auto-save 和夸克 Cookie。
4. 启动 `gying-source`，验证健康、账号状态和只读候选。
5. 启动 backend，检查 Resource Hub 配置和概览。
6. 启用主开关。
7. 对计划保留的已知影片手动运行一次 TMDB、发现、转存和发布链路。
8. 验证自有分享链接、数据库状态和前端结果。
9. 手工链路无误后启用 Worker。
10. 最后启用机器人和频道自动化。

## 运维接口

管理接口前缀为 `/api/admin/resource-hub`：

- `GET /overview`、`/config`、`/worker/status`、`/tasks`、`/discoveries`；
- `POST /worker/run-once?force=true`；
- TMDB 同步、发现任务创建和执行接口；
- 夸克转存提交接口；
- 单条/批量发布和 `retry-share-publish`；
- 缺资源影片查询及 GYING/PanSou 单条或最多 20 部批量补全；
- `discoveries/reconcile?dryRun=true` 历史状态校准；
- 失效资源检查和修复 job；
- 重复 TMDB 与标题不匹配资源的 dry-run 清理。

管理接口需要管理员认证。`/api/internal/resource-hub` 下的内部接口需要 internal token。
不得把 token 写入命令历史或状态文档。

耗时发现和修复接口返回 `jobId`，应轮询 job 接口。不能通过增加 nginx 超时来掩盖旧同步调用。

## 状态含义

- `resource_hub_task`：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELED`。
- `resource_discovery_result`：`DISCOVERED`、`SAVED`、`DUPLICATE`、`IGNORED`、`FAILED`。
- `quark_transfer_task`：任务生命周期及转存、分享结果。
- `resource_link`：正式发布状态、`link_status`、验证时间和最近错误。
- `movie_source_identity`：外部来源身份、季号、匹配方式、置信度和审核状态。

统计数字有明确过滤条件。“待入库”要求发现结果已有可用 `share_url` 且尚未绑定正式资源。
“待转存”不等于“等待重新分享”。排查数量差异时记录完整过滤条件。

## 依赖检查

- TMDB：运行中 backend 已加载 Key，且只读榜单或详情请求成功。
- PanSou/Panso：本地容器或 API 可访问，外部 API Key 有效；根路径 `404` 不能单独证明服务异常。
- GYING：`gying-source` 健康，当前账号有效，PoW/登录和候选读取正常。
- quark-auto-save：API token 可认证，WebUI 中账号 Cookie 有效。
- Quark share：保存目录存在且非空，分享任务返回 `share_id`，最终 URL 验证通过。
- MinIO：health 成功，浏览器可访问海报前缀。
- Redis：backend 连接成功。

`QUARK_AUTO_SAVE_TOKEN` 是 API/WebUI token，不是夸克账号 Cookie。

## 修复与清理

- 分享失败：先重跑原转存；任务缺失或已取消时重建任务；没有生成自有分享和活动资源时必须返回失败。
- 失效链接：优先复用 `saved_path` 原位重分享，失败后再尝试同片其他候选；只有目录为空或验证失败时重新发现。
- 状态漂移：先运行 `discoveries/reconcile?dryRun=true`，人工复核标题误判、旧任务冲突和季号冲突后再执行。
- 电影合集或多季剧集：递归定位明确的目标片名、年份或季目录，收窄 `fid`；找不到时尝试下一候选。
- 重复 TMDB 影片：先 `dryRun=true`，保留既有片库行为 canonical，
  迁移全部关联数据后软删除重复项。
- 标题不匹配资源：先复核标题、别名和季信息，再通过状态和 `deleted_at` 软清理，
  保留发现和转存历史。
- 同一影片和来源存在有效分享时，不重复创建自有分享。

每次维护记录输入过滤条件、dry-run 数量、人工排除项、实际处理数量、处理后状态和备份。

## GYING 来源身份

- GYING 外部 ID 与本地影片 ID 永远分离，通过 `movie_source_identity` 关联。
- 自动关联必须同时满足严格片名、类型以及年份、季号或主创等锚点。
- 历史 TMDB 影片缺少身份时先精确搜索，再扫描 GYING 片库目录；模糊结果只作为候选。
- 电影季号使用 `0`；冲突身份保留并标记审核状态，不物理删除。
- 管理页切换的密码和 Cookie 只存在进程内存，容器重启后回到环境变量默认值。

## 外部集成

- OpenClaw 是独立部署，使用主机本地配置和插件状态。
- NapCat 配置按 QQ 账号隔离，切换账号不会继承 OneBot 配置。
- 原 QQ 频道发帖通过受内部 token 保护的宿主机桥接和 `tencent-channel-cli` 执行，
  审计记录在数据库，计划任务位于 Compose 之外。桥接失败时保留 `PENDING`。
- 独立多平台发布由 `social-publisher` 执行，使用单独目标和日志表；详见多平台发布运维参考。
- 脚本或配置存在不代表计划任务和登录有效，两者都要验证。
