# 磁力与种子网盘归档

[采集契约](gying-p2p-collection.md) · [资源中心](resource-hub.md) · [现状](current-project-status.md) · [整理前完整证据](history/snapshots/20261022/docs/p2p-cloud-archive.md)

## 筛选政策

每片选 1080P、4K/2160p 各一版，磁力文本+种子一组、最多四个小文件；最终至少一版上游明确中文字幕，国语/配音不算字幕。标签不是播放验证。按影片/分辨率槽位原位更新，不批删历史资源/文件，不恢复软删除。

## 文件与目录

只生成 UTF-8 磁力 txt（≤16 KiB）和下载 torrent（≤2 MiB），不下载视频或 BT 离线任务。本站 HTTPS、拒跳转，bencode 原始 info 字节 BTIH 必须与磁力一致。写已确认正式影片目录的“磁力种子”子目录，拒根目录/临时 QQ/其他片；文件名含分辨率/字幕/BTIH 短标识，重试核对大小/hash，冲突不覆盖。

## 持久化与入库

- P2P_ARCHIVE 按影片/provider/版本指纹独立去重；每轮≤2、失败30分钟退避、最多3次，临时票据变化不改身份。
- 新 v2 `resourceIds` 引用实际保存的公开资源，`torrentMagnetIds` 指向磁力；执行时内部稳定 BT ID 取种子。账号 /dbt/ URL/票据不入资源表/任务。v1 保留引用，新旧指纹相同；槽位改变先取消过期任务。
- 保存目录/文件/分享检查点；单实例启动只恢复本队列中断项，不重放视频/发布。
- 所选文件全部确认后才分享并写 resource_link：TORRENT、实际网盘 provider、GYING_P2P_ARCHIVE，明确“不含视频”；同片同 provider 原位更新。不用 DISK 冒充视频，仍受 Resource Hub/Worker/provider 分享开关。
- 内部 `POST /api/internal/resource-hub/p2p/{movieId}/sync` 需 token/已确认 GYING 身份，公网 404。

## 验证与运维

| 日期 | 证据及边界 |
| --- | --- |
| 10 月 6 日 | 后端448（1 Redis skip/0失败）、Python37；《第六感》双盘各4文件/84923字节、路径/分享/重复幂等通过；22片补采20入队/2跳过，入队不算完成 |
| 10 月 8 日 | utf8mb4_bin 身份迁移2983行保持；双盘任务4305/4306成功；电影/剧集/动漫自然游标前进，来源大小写双记录共存 |
| 10 月 8 日续验 | 228任务/170成功/58耗尽失败；上线新增8活动/审核/NORMAL归档；旧58未重置，自然失败隔离未实证 |
| 10 月 19 日 | 账号下载URL公开过滤/v2任务已部署；见[部署报告](resource-search-p2p-deployment-20261019.md) |

上述计数为历史，不代表现在。10 月 8 日失败阶段为12种子准备/45迅雷目录/1迅雷上传，阶段不是底层原因；令牌刷新只读通过不授权重试全部任务。

### 初始 P2P 部署基线与续验（2026-10-06）

backend gying-p2p-backend:20261006d/source gying-p2p-source:20261006b；检查点 `G:/gying-backups/20261006T115631.510103Z` 七文件认证通过。发布材料 `E:/gying-tools/releases/gying-p2p-20261006` 保留旧镜像/精确清单；当前镜像从现状读取，不复用历史授权。

### 2026-10-08 上线前排查与修复准备（历史基线）

当时220任务/162成功/58失败、无可重试项；目录停滞由来源身份/季号触发。安全11失败未关闭。准备阶段不改线上队列/文件。

### 2026-10-08 修复上线与首批验收（已部署）

457673a/8182cda，backend/source 替换、uid10001；发布目录 `E:/gying-tools/releases/p2p-workflow-recovery-20261008` 含 deploy.ps1/rollback.ps1。回退镜像 gying-qq-library-backend:20261007c/gying-p2p-source:20261006b；应用回退保留二进制身份列。

### 2026-10-08 续验与可重复检查

只读查 resource_hub_task 的 P2P_ARCHIVE 与 resource_link 的 GYING_P2P_ARCHIVE，区分 provider/文件确认/分享/入库。旧58条需精确范围及当前远端核验；不删文件或批量改失败。v2 后回退不兼容旧 backend 必须暂停相关采集/Worker、保留任务/SQL，直到兼容消费者恢复。[旧原文](history/snapshots/20261022/docs/p2p-cloud-archive.md)保留完整计数与回退记录。
