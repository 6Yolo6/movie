# Resource Hub 运维

运维不修改业务代码；先读[资源中心](../../../../docs/resource-hub.md)、[API](../../../../docs/api.md#resource-hub)与[现状](../../../../docs/current-project-status.md)。

## 元数据/周榜隔离版本（2026-10-05 已部署）

高频元数据/海报/P2P；普通Worker仅METADATA_SYNC、不排旧视频；三个周榜默认7天/5片独立，历史夸克追更不受此策略停止。[周榜/迅雷落位](../../../../docs/metadata-weekly-transfer.md)含配置/回滚兼容。

## 磁力/种子元数据归档（2026-10-06 已部署）

独立P2P_ARCHIVE：1080P/4K各一版、至少一中文字幕，只上传文本/种子到片目录“磁力种子”，全部确认后自有TORRENT分享；provider分开/30分钟退避/最多3次。v2无/dbt票据，旧引用保留；[完整契约](../../../../docs/p2p-cloud-archive.md)。

## 流水线

手动/QQ/周榜视频：来源严格确认→发现→对应provider任务→完整视频落位→配图→自有分享验证→resource_link→可选GYING同步。高频元数据不进入此视频链。不把排队/HTTP200当成功，不回退发布第三方源分享。

## 配置优先级

具体支持键按源码sys_config覆盖/启动env核对，只报键/是否存在；文件不等于运行加载。周榜/旧兼容字段不混用，历史disabled目标保持停用，NapCat不恢复。

## 安全启动

新/恢复环境先关闭Resource Hub/Worker/元数据/QQ/发布/上游追更及宿主任务，再按原状态逐项验收恢复。单backend恢复只本类型中断元数据/P2P，不重置旧转存/UNKNOWN；多实例需租约。旧consumer不懂v2/周榜时回退保持相关消费暂停。

## 运维接口

清单统一[API](../../../../docs/api.md)，只读overview/config/tasks/discoveries/missing-resources/worker；写包括discover/run、publish/retry/repair、定向internal及清理，force/runNow=false均须查副作用，后者可写队列。批量补资源最多20；清理先dry-run，不猜接口/批量队列重放。

## 状态含义

QUEUED/PENDING、RUNNING/SUBMITTED、WAITING_SHARE、SUCCEEDED、FAILED分别是排队/在途/待分享/完成/失败。deferred批次成功仅隔离失败项；PUBLISHING/UNKNOWN等看[发布规则](../../../../docs/social-publishing.md)，不重置成PENDING。

## 依赖检查

DB/Redis→source会话/token→PanSou/Quark/迅雷→MinIO→backend/SSR/nginx→公网；内部只读探针不开放端口。QQ每日推荐属backend，进度/管理员命令验证源/唯一插件/Gateway，不通过真实群消息默认探活。

## 修复与清理

单条 /api/resources/admin/{id}/repair-invalid，批量job轮询；分QUARK/XUNLEI、核原目录/任务/远端状态，再原位重分享/必要重搜。验新URL/NORMAL/url_hash与gyingUpdated；无映射可成功但不GYING同步。重复迁关联/软删，网盘历史根文件先ID/父目录/绑定dry-run，不按同名移动删。

## GYING 来源身份

canonical与外部typeCode/mid分开，utf8mb4_bin、电影season0；同名/年份/季/主创严格消歧，冲突待审。补系列电影仅元数据，剧集仅有效合集明确季范围复用，不把整合集收窄为单季或单季链接扩散。

## 外部集成

发布/res/pan/add的binds dir/id契约、来源短熔断及账号归属见[API](../../../../docs/api.md#gying)。修复、转存、试发有真实副作用，须当前范围；[旧完整运维说明](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/resource-hub-operations.md)仅历史。
