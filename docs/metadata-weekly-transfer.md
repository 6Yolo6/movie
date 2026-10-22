# 元数据采集与本周热门转存分离

[资源中心](resource-hub.md) · [P2P](p2p-cloud-archive.md) · [现状](current-project-status.md) · [整理前完整证据](history/snapshots/20261022/docs/metadata-weekly-transfer.md)

## 交付状态（2026-10-05）

10 月 5 日部署三服务，提交 e6dead5/1aaa401；周榜配置改紧凑 JSON 适配 VARCHAR(500)，无 DDL。以后镜像见现状。原安全 53 PASS/11 FAIL 仅当日，授权仅该次业务发布；没有手动转存/移动删除或改历史追更。

## 高频采集

TMDB/GYING 自动/手动元数据只保存元数据/海报/P2P，不排视频发现/转存。Worker 仅 METADATA_SYNC；旧队列保留，定时 discovered retry 不执行，兼容字段不意味着可恢复高频视频写入。独立 P2P_ARCHIVE 只上传磁力文本/种子元数据；手动发现、修复和 QQ 按需保留。页码/偏移/来源轮换独立。

## 三个独立周榜

| 参数 | 约束 |
| --- | --- |
| 来源 | `/hits/mv/week`、`/hits/tv/week`、`/hits/ac/week`；缺失/错类型/非 week 失败，不回退综合榜 |
| enabled | 各榜独立，默认 true；同时受 Resource Hub/Worker 开关控制 |
| intervalDays | 默认 7，范围 1–365 |
| maxItems | 默认 5，范围 1–20，单位影片 |
| 执行时间 | 初始化/改频率或开关等待完整间隔，改 N 不改下次时间 |

网络副作用前持久化下次时间，每次最多领一榜；失败/中断不每分钟重放，RUNNING 可保留但下一次仍等完整间隔。当前单实例锁，多实例需 DB 租约。优先复用自有分享，不为双盘重复保存；夸克一次性即时执行，未启用失败关闭，不注册每日追更，不自动排 PanSou fallback。

## 接口与持久化

管理员 config GET/PUT `weeklyTransferSchedules` 完整包含 mv/tv/ac，字段 typeCode/enabled/intervalDays/maxItems。客户端运行时间/状态/任务 ID 被忽略。sys_config `resource.hub.gying.weekly_transfer_schedules` 版本化紧凑 JSON（兼容早期描述式），外部 API 仍完整字段；任务 WEEKLY_TRANSFER/GYING_WEEKLY 不由普通 Worker 消费。source 认证 GET `/weekly-popular?typeCode=mv&limit=5`。

## 既有追更的边界

历史 quark-auto-save 继续独立执行；10 月 4 日记录 1295 项、每日 08/18/20 仅旧快照。缩减须备份/明确范围，不删已发布文件。不能说全部自动写入已只剩周榜。

## 清理核对

10 月 4 日两主目录 1198 个直属影片目录：656 关联优先保留、56 有分享未匹配发布、30 空且有追更、456 证据不足。生产当时仍写入，清单不是一致性快照，不可直接批删。私有审计在 `E:/gying-tools/releases/metadata-only-weekly-20261004`。

## 迅雷保存路径修复（2026-10-05，代码已部署）

目标“我的转存/影视剧资源分享(先转存后再查看)/GYing Resource Hub/影片/来源子目录”。完整分页找目录；复用须覆盖整组预期视频，部分/不同视频失败；本轮每个文件 ID 逐一确认落位，只移未到位文件。目标/季目录/分组文件身份写 response_payload，失败保留 WAITING_SHARE，重启复用计划，不重转存。无 ID 时按精确文件名+时间查，不以前缀或已有任意视频当完整。

不自动处理历史 SUCCEEDED/归属不明文件；远端请求与本地落库不具分布式事务，结果不明先人工核对。10 月 5 日根目录 798 视频、任务 584 的 67 文件仍在根目录是样本证据，不证明所有根目录文件都由缺陷产生。迁移前重新核对 ID/父目录/分享，保存回退清单，小批获确认后实施。

## 验证（2026-10-05）

后端 424 项（1 Redis skip、0失败）、compile；source 18 项；前端类型/构建、lint 7 旧警告、四组 mock 浏览器。部署 source 三周榜真实 GET 各5且类型正确；12入口及本机四组通过，公网仅中文桌面场景完整，后续 ERR_CONNECTION_CLOSED 未算通过。未手动真实视频转存。

## 部署和回滚

初次 nextRunAt=2026-10-12 11:50:35 已过去，首次实际结果仍待验收。原 TMDB 4–15、GYING 1–15、4 小时间隔/开关保持；检查点 `G:/gying-backups/20261005T032935.839103Z` 五加密文件 hash/认证解密通过，不是新恢复演练。

当次回退 backend/frontend `gying-metadata-crawl-*:20261004a`、source `gying-series-search-source:20260929a`，不要用配置长度失败的 20261005a backend。旧 Worker 会恢复视频消费，先核对/暂停相关开关，保留 JSON/任务，不清队列或恢复旧 SQL。精确脚本/镜像与旧完整证据见归档。
