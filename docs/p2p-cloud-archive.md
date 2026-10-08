# 磁力与种子网盘归档

## 筛选政策

- 每部影片最多选两个 BT 版本：1080P 一份、4K（含 2160p）一份。同版本的磁力文本与 torrent 为一组，合计最多四个小文件。
- 优先明确标注中文字幕、中字、简繁中或 CHS/CHT 的版本，最终至少有一份明确标注中文字幕。国语或中英配音/音轨不作为字幕证据；没有符合条件的版本时不归档。
- 标记只代表上游标签，不宣称实际播放验证字幕。只更新采集选择，不批量删除历史资源或网盘文件。
- 磁力入库按影片与分辨率槽位原位更新，避免未来每次出现新版本就不断新增活动行。历史多余行仍需单独审查。

## 文件与目录

- 只生成包含磁力链接的 UTF-8 `.txt` 并下载对应 `.torrent` 元数据；不运行 BT 客户端，不调用离线下载或视频转存。
- torrent 上限 2 MiB，验证 bencode 结构与原始 info 字节的 BTIH，必须匹配同版本磁力；文本上限 16 KiB。下载只允许本站 HTTPS 下载入口，拒绝跳转。
- 优先复用该影片已成功的非临时转存目录映射；否则沿用项目按影片 ID 命名的正式目录规则。始终写入其 `磁力种子` 子目录，不写网盘根目录、临时 QQ 目录或其他影片目录。
- 文件名包含清晰度、字幕标签和 BTIH 短标识。重试先确认同名文件的大小及可用哈希，不覆盖不匹配文件，不删除旧文件。

## 持久化与入库

- `P2P_ARCHIVE` 队列按影片、provider 和 BT 版本指纹去重，夸克与迅雷分别执行；种子临时下载票据变化不会产生新的归档身份。
- 只有原始 P2P 资源保存成功、符合两个分辨率和字幕规则，才创建归档任务。每轮最多处理两个归档任务，失败间隔 30 分钟、最多三次。
- 文件夹、已确认文件与分享地址保存检查点；单实例启动只恢复本类型中断任务，不重放历史视频转存/发布队列。
- 全部选中文件确认到位后才创建子目录分享，并写入 `resource_link`。类型为 `TORRENT`、provider 为实际网盘、source 为 `GYING_P2P_ARCHIVE`，名称与版本说明明确“不含视频”；不会以 DISK 冒充视频或满足视频转存检查。
- 同影片同 provider 的归档分享原位更新。仍受既有 Resource Hub/Worker 开关与各 provider 分享开关限制，不新增公网端口。
- 定向补采入口 `POST /api/internal/resource-hub/p2p/{movieId}/sync` 需要 internal token，且影片必须已有确认的 GYING 来源映射；公网 nginx 不开放该路径。

## 验证与运维

测试：后端全量 448 项，0 失败/0 错误/1 项 Redis 环境跳过；Python 37 项通过。覆盖分辨率/字幕筛选、种子完整性、下载目标与大小限制、上传校验和/幂等、独立 provider 队列、部分失败不得分享入库及分辨率槽位更新。2026-10-06 双盘实传《第六感》各 4 个小文件（84,923 字节），影片路径、分享内容和公开资源均已验证，重复补采不新增任务或分享行。字幕是来源标注，不是播放验收。

部署只涉及 backend 与 gying-source，无表结构迁移。先保存加密数据库/环境/挂载备份及原镜像，保持 GYING 自动采集暂停直到受控验证结束；已于 2026-10-06 20:00 恢复原值 true。既有安全风险仍须单独整改，不将本功能上线写成整体安全门禁通过。

### 初始 P2P 部署基线与续验（2026-10-06）

- backend `gying-p2p-backend:20261006d`，source `gying-p2p-source:20261006b`。夸克上传兼容实际 PDS 存储主机并强制 HTTPS，拒绝无关主机/用户信息/额外端口；上传票据作为单个不透明查询值编码。成功重试显式清除数据库旧错误，保留失败检查点到真正完成。
- 22 部既有影片受控补采后，20 部进入双盘队列、2 部因无合格分辨率/字幕版本跳过；剩余上传与失败重试仍由后台执行，不以入队数量代替成功数。
- 发布目录 `E:/gying-tools/releases/gying-p2p-20261006` 保存构建、备份、真实目录/分享验收、去重和补传清单。只读查询 `resource_hub_task.task_type=P2P_ARCHIVE` 与 `resource_link.source=GYING_P2P_ARCHIVE` 跟进；不要重放旧视频转存、不要清空历史文件或重置所有失败任务。


### 2026-10-08 上线前排查与修复准备（历史基线）

- 只读复核：220 条归档任务、110 部影片；成功 162 条（夸克 106、迅雷 56），失败 58 条
  （夸克 4、迅雷 54），失败均已耗尽 3 次重试，没有 PENDING/RUNNING 或可自动重试项。
  最后新增原始 P2P 为 2026-10-07 15:46:40，最后新增网盘归档为 15:53:22。
- 主要入口阻塞为三个 GYING 目录批次反复遇到 ID 大小写/季号匹配失败。当时待部署的修复包含精确来源身份、
  季标优先于 Part 标号、独立失败条目队列；详见 `resource-hub.md` 和 `database.md`。
- 历史归档失败阶段：12 条种子文件准备、45 条迅雷影片目录定位、1 条迅雷种子上传。
  这些阶段计数不是底层原因；不据此自动重置全部任务。早期排查发现过期 access token；同日已修复
  宿主机浏览器会话同步、补齐 refresh token，写回后同账户只读 Drive API 200。未测试 refresh-token
  自身续期、未重置历史归档队列；同步验收见 `xunlei-token-sync.md`。
- 上线前安全预检为 53 PASS / 11 FAIL / 0 UNKNOWN，当时曾暂停部署及迁移。该准备阶段未改变线上
  配置、网盘文件或旧失败任务；正式维护前需备份、验证授权/下载/目录链路，再选择明确任务受控重试。

### 2026-10-08 修复上线与首批验收（已部署）

- backend 由 `gying-qq-library-backend:20261007c` 换为 `gying-p2p-recovery-backend:20261008a`，gying-source 由
  `gying-p2p-source:20261006b` 换为 `gying-p2p-recovery-source:20261008a`；两容器以 `gying`/uid 10001 运行，
  容器内 JAR/源码 hash 与候选镜像一致，restart count 0，其余服务未重建，nginx 校验通过。代码 `457673a` / `8182cda`。
- 迁移 `migration_source_identity_case_sensitive.sql` 已执行：`movie_source_identity.external_id` 由
  `utf8mb4_unicode_ci` 改为 `utf8mb4_bin`，唯一键 `uk_source_identity(source,source_type,external_id,season)`
  与 2983 行保持（AUTO_INCREMENT 不变）；执行前确认无同键大小写变体并另存表级备份。应用回滚保留该二进制排序规则。
- 上线后首个 GYING 批次（CSCORE_ANIME，18:00）`SUCCEEDED`，`failed 0 / deferred 0`，游标由
  `page1 offset0` 前进到 `page1 offset20`；旧代码在同类失败上会整批停在原游标（例如 CSCORE_MOVIE page5 offset40）。
- 同批次新入库影片「进击的巨人 第三季 Part.2」触发双盘归档：夸克任务 4305、迅雷任务 4306 均 `SUCCEEDED`，
  各写入一条 `GYING_P2P_ARCHIVE` 活动资源（`resource_link` 4878/4879）；下一影片的夸克/迅雷任务已入队。
- 未重置历史 58 条失败归档，未重放旧视频转存/发布；backend/gying-source 日志无 ERROR/Exception。
- 发布目录 `E:/gying-tools/releases/p2p-workflow-recovery-20261008` 保存构建、加密备份、基线、迁移记录、部署与
  回退脚本（`deploy.ps1` / `rollback.ps1`，回退镜像 `gying-qq-library-backend:20261007c` / `gying-p2p-source:20261006b`）。
- 首批验收未覆盖电影/剧集后续批次与自然失败隔离；最新续验见下节，不再把该阶段缺口视为全部当前状态。

### 2026-10-08 续验与可重复检查

- 动漫 page1 offset0→page1 offset20（failed 0 / deferred 0）；电影 page5 offset40→page6 offset0（failed 0 / deferred 0）；剧集 page2 offset0→page2 offset20（failed 0 / deferred 0）；自然批次尚未触发失败分支，线上隔离重试仍未实证。
- `dWXo` 与 `dwXo` 已分别绑定《卡萨布兰卡》/《蝙蝠侠》，来源身份 season 均为 0；二进制列比较与实际两行共存均已验证。剧集自然批次后另查《大明王朝1566》主表 season 为 1。
- 截至 2026-10-08 20:03:56+08:00，上线后新增归档资源 8 条，活动/已审核/NORMAL 为 8 条；旧 58 条失败任务未重置，未手动创建转存、分享或社交发布。当前任务 228 条、成功 170、失败 58。
- `tools/inspect_p2p_progress.py` 使用受保护的非 root MySQL defaults 与 READ ONLY 一致性事务，检查工作量、真实下一游标和持久化重试引用。它不读取/输出原始 payload、URL、错误或凭据，不启动 Worker/重试；未见自然失败时仍明确保留 UNKNOWN。
- `docs/security/hardening-gates.md` 记录原 11 个失败项、依赖隔离候选、独立部署前置条件与命令。当前线上门禁仍 11 FAIL；四个依赖的隔离启动通过不等于已改生产权限/端口/网络。
- 本次新增/改造的是宿主验收工具和文档，不需要替换 backend/source；回滚仍使用既有发布目录的旧镜像/脚本，不撤销已兼容的二进制身份迁移。
