# 网盘工作流配置

开发分支：`codex/cloud-drive-workflows`。2026-11-09 backend于22:39:17、frontend于22:48:50启动固定`048795f`，22:49:07完成两服务上线：百度上传/分享兼容、QQ/网站独立临时搜索与默认十分钟精确清理、全有效片库重复候选扫描。实际Compose17/11、固定image/JAR/375产物与本机公网资产核验通过；部署类+实库只读快照15366有效/144待审候选/完整、MobLand0/8和分页通过；用户实际新登录后23:05真实ADMIN四次dryRun API扫描15366/144候选、完整/MobLand0/8/144对资源数和分页通过；原22:53无设备FAIL保留，真实归档/转存清理/配额和已认证浏览器UI待验。[本轮上线与边界](evaluation/baidu-search-duplicate-20261109/README.md)。

早先13:54:51按GYING ID补种子上线b55080d8；14:24单影片首个磁力文本上传为UNKNOWN，自动补全保持关闭、十分钟一条。后续e6370cb上传/分享兼容候选1254项及真实61字节合成文本/同目录一次分享核验通过，当时idle/旧consumer门禁FAIL、未部署；兼容代码现随048795f上线，原17583/17584与意图保留，尚未证明真实影视归档成功。[原种子验收](evaluation/baidu-torrent-backfill-20261109/README.md)、[上传实测](evaluation/baidu-share-compat-fix-20261109/README.md)。

## 页面与阶段

后台 `/admin/resource-hub` 的“网盘工作流”页签按网盘独立保存，不覆盖元数据采集范围或其它网盘草稿。

| 配置 | 作用 |
| --- | --- |
| 采集视频文件 | 是否发现、转存视频；关闭后该盘视频任务暂停，不删除已保存的视频 |
| 采集种子文件 | 是否采集/归档小型 `.torrent` 与配套磁力文本；不会触发 BT 离线下载视频 |
| 发现资源 | 控制 GYING / PanSou 候选发现和种子归档入队 |
| 转存资源 | 控制转存与小文件上传；关闭不消耗旧任务重试预算 |
| 生成自有分享 | 控制生成分享；关闭时保留旧分享和归档任务 |
| 发布 GYING | 控制该盘视频向 GYING 发布/更新；种子归档保留 TORRENT 类型，本库入库独立 |
| 保存目录 | 1–300 字符绝对路径，禁止反斜杠、控制字符、`..` 段；不移动既有任务目录 |
| 手动批次上限 | 1–20；执行时处理该盘视频/种子队列，总数量不超过上限 |

空间不足可选择“视频关闭、种子开启”，并开启需要的转存和分享阶段。百度“种子补全”按库内 GYING ID 运行，独立于发现开关；其它采集入口仍遵守各自发现配置。种子和磁力文本占用少量空间；不会清理已有视频。两类都关允许保存并暂停新文件采集。阶段与文件类型互相独立。

夸克/迅雷缺少新类型键时保持两类开启，百度两类和四阶段默认全部关闭。保存和扫码不自动启用阶段。夸克/迅雷分享转存器恢复整个视频目录，因此仅种子模式走小文件归档队列；百度只选择所有叶子都符合策略的完整根文件/目录，混合含禁用/未知类型的目录整体跳过，不连带转存视频。

普通 Worker 保持原元数据/P2P职责，不恢复旧视频、发现或 GYING 发布队列。三个周榜独立调度并检查阶段和视频选择。已有夸克上游追更独立，本页面不批量改其周期。

## 配置 API

ADMIN `GET /api/admin/resource-hub/config` 返回三盘 `driveWorkflows`。百度 `supported=true / AVAILABLE` 表示实现适配；`credentialConfigured` 只表示加密会话存在，账号有效性由执行时上游核验。

`PUT /api/admin/resource-hub/config` 可仅更新一个网盘：

```json
{
  "driveWorkflows": [{
    "provider": "QUARK",
    "videoEnabled": false,
    "torrentEnabled": true,
    "discoveryEnabled": true,
    "transferEnabled": true,
    "shareEnabled": true,
    "gyingPublishEnabled": false,
    "savePath": "/GYing Resource Hub",
    "batchLimit": 5
  }]
}
```

未提供或为 `null` 的可写字段保持现值。能力/凭据元数据只读；未知/重复网盘或字段、非布尔开关、非整数批次拒绝，完整验证后才保存。

配置存于 `sys_config`：
`resource.hub.drive.<quark|xunlei|baidu>.<video_enabled|torrent_enabled|discovery_enabled|transfer_enabled|share_enabled|gying_publish_enabled|save_path|batch_limit>`。
读取不插入新网盘键；旧夸克批次兼容保留。账号会话不存入这些键。

手动 `POST /api/admin/resource-hub/transfers/run` 接受 `{ "provider": "BAIDU", "limit": 5 }`，省略 limit 使用已保存值。关闭转存/两类皆关返回 409；未知网盘、重复/额外字段或非法 limit 返回 400。旧夸克接口仅执行视频队列。

## 百度专用账号扫码

使用网页协议兼容适配，依赖百度上游接口，并非开放平台 OAuth。遇验证码、会话失效、账号变化或未知结果暂停，在官方登录页面处理。

ADMIN `GET /api/admin/resource-hub/baidu/session` 只返回连接状态、脱敏昵称、票据期限；`POST /api/admin/resource-hub/baidu/login`（空请求或 `{}`）创建十分钟票据。宿主有限权限任务仅在页面主动请求后打开独立 Edge，用户用百度手机 App 扫码，不在聊天中发送 Cookie 或密码。仅待扫码状态轮询，结束/过期后停止。

默认私有文件：

- Backend `/app/data/baidu/session.enc`、`session.key`：AES-GCM 加密会话与受限密钥。
- Backend `/app/data/baidu/login-request.json`：短期单次票据，nonce 不返回页面。
- Host `E:/gying-tools/baidu-login/private/login-config.json` 和 `private/baidu-qr-profile`：受限 ACL，禁止个人默认浏览器配置与密码管理。

宿主工具核对固定 backend 镜像、UID、官方网页和内部 loopback 入口。内部回调绑定单次 nonce；加密会话导入标记保证崩溃后不能重放。账号绑定后不允许扫码替换专用账号。

ADMIN `GET /api/admin/resource-hub/baidu/transfers` 查看摘要，`POST /api/admin/resource-hub/baidu/transfers/{id}/submit` 执行。独立 `baidu_transfer_task` 先持久化账号、内容计划和转存/分享意图；未知结果只读核验，不重复 POST。目标内容与自有分享核验通过才入库。仅种子结果为 TORRENT，不把影片标记为可播放视频。

## 百度种子补全

百度卡片单独保存补全开关、间隔和批次数量，默认关闭、十分钟一条。此功能仅按库内已确认的 GYING 来源 ID 读取种子，校验后将小型 `.torrent` 与配套磁力文本归档到百度，并回写本库的 TORRENT 资源。它遵守总工作流、百度种子、转存、分享和账号门禁；自动运行还要求 Worker 开启。视频采集和发现开关不参与这项独立功能。

扫描使用 `movie_source_identity` 中 GYING 的 `AUTO/CONFIRMED` 映射，保留外部 `mv/tv/ac` 类型与 ID；本地资源行 ID 不能代替 GYING ID。不扫描旧百度分享、转存任务或发现候选，不调用 PanSou、片名搜索、来源发现、视频转存或 GYING 发布。种子占少量空间，不会下载关联视频。

读取 `GET /api/admin/resource-hub/baidu/backfill` 及无父任务时的启动调度不创建任务。`PUT` 同路径保存 `{ "enabled": false, "intervalMinutes": 10, "batchLimit": 1 }`；间隔 1–1440 分钟、每批 1–5 条。`POST /api/admin/resource-hub/baidu/backfill/run` 仅手动一小批，可在自动补全关闭时使用，仍受该盘策略约束。页面未保存的工作流或补全草稿不能用于执行。

复用 `resource_hub_task` 的 `CLOUD_DRIVE_BACKFILL` 父任务，以新的 `resource.hub.baidu.torrent-backfill.task-id` 独立指针保存 v2 进度，不需新增表。固定初次来源身份高水位，记录 GYING 游标、百度 P2P 子任务、统计和三十分钟数据库租约；重启后继续原项。普通 Worker 不消费补全专属子任务。页面显示当前 GYING ID、种子选择、归档步骤和具体错误类别；种子开关关闭会明确提示。

当前实际新父任务为 17583，专属子任务 17584 在 `UPLOAD_MAGNET_TEXT` 为 UNKNOWN，父状态 BLOCKED/CHILD_UNKNOWN、自动开关关闭。2026-11-09 14:29 官方只读目录检查为 0 文件、同一账号且会话保持；未保存上传文件 ID 或分享提交意图，不能将目录为空推定为可以重放。确认未知结果与接口原因前保持暂停。

旧 `resource.hub.baidu.backfill.task-id` 及父任务、游标、转存子任务和 UNKNOWN/远端意图原样保留，新补全不读取它们。旧视频分享的审核或源链接错误不会阻塞种子补全。若同片百度种子已有未知提交或待核验 P2P 任务，则保留其原选择及远端检查点，显示该种子任务的具体状态；不重置、重建或重放未知提交。

宿主扫码配置可选 `proxyServer`，仅接受显式 `http://127.0.0.1:<端口>`；当前复用既有 7897 代理。有限权限任务对已有私有目录只验证所有者和 ACL，遇不安全目录直接失败，不重新取得所有权。13:54 本轮仅同步新 backend 固定镜像并核对实际配置、严格前后 idle、原任务 XML/ACL，保持 Enabled/Ready、profile 和加密会话。01:34 历史任务恢复 Enabled/Ready、Limited/Interactive 实际运行 exit0 与独立核验保留；当时 stdout 未捕获，idle 由严格前后无 handoff 与 exit0 推定。首次默认 Enabled 节点省略造成的失败和精确 CAS 回 disabled 保留于历史验收记录。

## 百度新永久目录候选

上传兼容修复的新视频计划与种子归档已随048795f上线，按影片类别写到保存根下的 `mv/tv/ac`：默认 `/GYing Resource Hub/mv`、`tv`、`ac`；movie/anime 别名映射到 mv/ac。未知类别在百度云写入前停止。只复用同类别目录；仅用于新计划，历史保存计划、路径与 UNKNOWN 检查点保持原位，未搬迁生产文件。

QQ群与网站共用百度搜索链路、成功后默认十分钟清理已随048795f上线；使用独立临时scope及精确账号/目录/完整树证据，与永久mv/tv/ac归档隔离。用户已选择彻底删除本系统新建临时任务目录，逐对象回收站核验和持久意图，不清空整个回收站；首次分享确认固定时钟，任务创建时固定保留分钟数，重复/补偿不延期。自然真实转存清理与配额变化未实测，历史UNKNOWN/探针/永久目录未删除或重放。[本轮上线](evaluation/baidu-search-duplicate-20261109/README.md)、[原候选与删除边界](evaluation/baidu-search-temp-20261109/README.md)。

## 部署与回退

百度任务表由用户创建；本轮旧写入者正常停止后由用户执行临时scope/清理增量SQL，助手只读完整契约通过。22:49:07仅backend/frontend完成048795f上线，独立运行17/0、Compose17/11、408/90源码、375产物、20本机公网入口和194资产SHA闭环（管理Access302、Insights精确归一化，原失败保留）。用户实际新登录后23:05真实ADMIN四次POST200/dryRun、全库15366/144候选及资源数/分页通过，登录后新基线业务/授权/历史保持、常规last_seen更新；原22:53无设备FAIL保留，已认证浏览器UI未独立验。停机七age与pin后三age完整认证/会话/原XML和ACL保持，独立post27/0通过（原两次HostCheckpoint RuntimeError保留、原因未定），hostpin仅改预期backend image、Enabled/Ready/profile与会话保持；日常40项CAS/dry-run通过，自然完整备份未验。旧idle/consumer/严格零在途FAIL仍保留，新精确历史与activity守护通过；自动补全关闭10分钟1条，不恢复旧SQL或重放UNKNOWN。[当前部署与备份](evaluation/baidu-search-duplicate-20261109/README.md)。

旧后端忽略文件类型开关并不能识别百度任务，不能直接自动回退。回退前停止新写入者，读取新配置、百度副作用阶段和 P2P 检查点；有类型关闭、百度在途/未知意图或读取失败时阻断旧消费者，保留当前 SQL/会话/计划与候选版本。新增补全父任务或指针也阻断旧版回退。不能用旧 SQL 覆盖在线数据，不重置/重放队列。真实转存、发帖和删除不作默认健康探针。
