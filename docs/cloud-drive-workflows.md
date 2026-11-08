# 网盘工作流配置

开发分支：`codex/cloud-drive-workflows`。2026-11-08 22:34 上线的是双盘统一配置与百度占位；本轮百度扫码、转存和文件类型配置为新增候选，尚未部署。[本轮交付](evaluation/baidu-file-selection-20261108/README.md)。

## 页面与阶段

后台 `/admin/resource-hub` 的“网盘工作流”页签按网盘独立保存，不覆盖元数据采集范围或其它网盘草稿。

| 配置 | 作用 |
| --- | --- |
| 采集视频文件 | 是否发现、转存视频；关闭后夸克/迅雷视频任务暂停，不删除已保存的视频 |
| 采集种子文件 | 是否采集/归档小型 `.torrent` 与配套磁力文本；不会触发 BT 离线下载视频 |
| 发现资源 | 控制 GYING / PanSou 候选发现和种子归档入队 |
| 转存资源 | 控制转存与小文件上传；关闭不消耗旧任务重试预算 |
| 生成自有分享 | 控制生成分享；关闭时保留旧分享和归档任务 |
| 发布 GYING | 控制该盘视频向 GYING 发布/更新；种子归档保留 TORRENT 类型，本库入库独立 |
| 保存目录 | 1–300 字符绝对路径，禁止反斜杠、控制字符、`..` 段；不移动既有任务目录 |
| 手动批次上限 | 1–20；执行时处理该盘视频/种子队列，总数量不超过上限 |

空间不足可选择“视频关闭、种子开启”，并开启需要的发现、转存和分享阶段。种子和磁力文本占用少量空间；不会清理已有视频。两类都关允许保存并暂停新文件采集。阶段与文件类型互相独立。

夸克/迅雷缺少新类型键时保持两类开启，百度两类和四阶段默认全部关闭。保存和扫码不自动启用阶段。夸克/迅雷分享转存器恢复整个视频目录，因此仅种子模式走小文件归档队列；百度只选择所有叶子都符合策略的完整根文件/目录，混合含禁用/未知类型的目录整体跳过，不连带转存视频。

普通 Worker 保持原元数据/P2P职责，不恢复旧视频、发现或 GYING 发布队列。三个周榜独立调度并检查阶段和视频选择。已有夸克上游追更独立，本页面不批量改其周期。

## 配置 API

ADMIN `GET /api/admin/resource-hub/config` 返回三盘 `driveWorkflows`。候选代码中百度 `supported=true / AVAILABLE` 表示实现适配；`credentialConfigured` 只表示加密会话存在，账号有效性由执行时上游核验。

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

## 部署与回退

本轮仅新增表迁移，尚未运行生产 SQL、注册扫码任务或登录真实账号。上线前备份 MySQL、backend-data、宿主私有配置与浏览器会话；仅更新 backend/frontend，核验入口、镜像和 UID。

旧后端忽略文件类型开关并不能识别百度任务，不能直接自动回退。回退前停止新写入者，读取新配置、百度副作用阶段和 P2P 检查点；有类型关闭、百度在途/未知意图或读取失败时阻断旧消费者，保留当前 SQL/会话/计划与候选版本。不能用旧 SQL 覆盖在线数据，不重置/重放队列。真实转存、发帖和删除不作默认健康探针。
