# 历史运维验收（截至 2026-10-08）

归档整理：2026-10-11；未在归档时重新执行下列测试、部署或恢复。

[历史导航](README.md) · [当前项目状态](../current-project-status.md) · [2026-10-11 上线记录](../code-optimization-deployment-20261011.md)

> 以下从状态手册验收章节归档，保留当时结果、未验证边界和回滚线索。原文中的“当前”“最新”“本轮”和计数仅适用于各自记录时点，不代表现在，也不构成下一次操作授权。G01–G11 与其他未完成事项仍在当前状态和安全台账跟踪。

原始基线：提交 `fdf7276a4606b3957d2ee912232781e71503818a` 的 `docs/current-project-status.md`。行内仓库路径仍以仓库根目录解释；外部盘符/忽略目录只作线索，恢复前须重新验证存在性、完整性和权限。

## 原验收记录

- 安全门禁/P2P 续验工具（2026-10-08）：本次相关 Python 回归 78 项全部通过；四个真实隔离依赖候选均非 root/NNP/启动探针通过，Redis 另验匿名拒绝、认证/Lua 成功与 CONFIG 拒绝；候选无网络、无生产数据或真实凭据，已全部清理，不能替代生产卷/依赖迁移。线上入口 12/12 通过，backend/source 日志无 ERROR/Exception；6 个发布备份文件 hash 重验通过（本轮未解密/恢复）。主工具已在宿主执行，未重建业务容器、改变端口/网络/权限/凭据或手动发布。只读证据与隔离报告在 `tmp/continuation-20261008/`；操作入口见 `docs/security/hardening-gates.md`。

- 迅雷会话同步验收（2026-10-08）：17 项 Node 回归、21 项 Windows 检查及 5 项无网络/无生产挂载的容器写回测试通过。真实标签刷新、不写回验证和正式激活均成功；完整 refresh token 已写入，写回后同账户 Drive API 200/业务结构正常、权限 `10001:10001 / 600`。原计划任务再次执行为 `unchanged`、实际验证成功、退出 0，原两小时及登录触发已恢复，临时凭据叶文件清理为 0 残留；真实 Edge 进程保留。回滚/证据 `E:/gying-tools/releases/xunlei-browser-refresh-20261008`，没有业务容器替换、数据库变更、队列重置或外部发布。

- P2P 停滞修复构建验收（2026-10-08，上线前执行）：Java 17 后端全量 495 项，0 失败/0 错误/1 项 Redis 环境跳过；数据源 39 项通过。隔离 MySQL 8.0.46 fixture 的 8 项验证覆盖原行不变、大小写独立身份、唯一约束及迁移幂等。对应版本随后已部署，本次续验未重跑该业务全量测试。

- GYING P2P 采集停滞修复上线（2026-10-08，已部署）：代码 `457673a`（失败条目隔离 + 精确来源身份）、`8182cda`（迅雷会话刷新）。backend 由 `gying-qq-library-backend:20261007c` 换为 `gying-p2p-recovery-backend:20261008a`，source 由 `gying-p2p-source:20261006b` 换为 `gying-p2p-recovery-source:20261008a`；容器内 JAR/源码 hash 与候选一致、uid 10001、restart count 0，其余服务未重建，nginx 校验通过。迁移 `migration_source_identity_case_sensitive.sql` 已执行：`movie_source_identity.external_id` 改 `utf8mb4_bin`，唯一键与 2983 行不变，执行前确认无同键大小写变体并另存表级备份；应用回滚保留二进制排序规则。上线后首个 GYING 批次（CSCORE_ANIME）`SUCCEEDED`、`failed/deferred 0`，游标 page1 offset0→offset20；新入库影片触发夸克/迅雷归档 4305/4306 双双 `SUCCEEDED` 并写入 `GYING_P2P_ARCHIVE` 活动资源 4878/4879。未重置历史 58 条失败归档或重放旧转存，backend/source 无 ERROR/Exception，安全门禁仍 11 FAIL，不标记安全通过。发布/回滚材料 `E:/gying-tools/releases/p2p-workflow-recovery-20261008`。

- GYING P2P 采集与网盘归档验收（2026-10-06，已部署）：后端全量 448 项，0 失败/0 错误/1 项 Redis 环境跳过；源镜像真实代码 37 项通过，secret scan 0 findings。电影《第六感》1080P/4K 均有来源中文字幕标注，夸克与迅雷各实存 2 个磁力文本、2 个 torrent，共 84,923 字节；影片路径、文件类型/大小与分享内四个文件均只读核验。自有资源 4333/4334 为 TORRENT、NORMAL、已审核；重复补采仍仅有原两条任务与两条归档资源。夸克实盘 PDS 主机已按精确白名单兼容，重试成功后旧错误已清除；没有下载视频或执行离线下载。内部服务正常，本地/公网 12 项入口检查通过，详情显示归档资源；后端/源直接缺 token 均 401，公网内部路径 404。安全复核仍为 53 PASS / 11 FAIL / 0 UNKNOWN，既有风险集合未变，不标记安全门禁通过。

- 元数据/周榜与迅雷上线验收（2026-10-05）：实现 `e6dead5`、兼容修复 `1aaa401`；Java 17 编译/打包，全量 424 项测试 0 failures/errors、1 Redis 环境跳过，source 新镜像 18 项测试、前端构建/类型检查通过，lint 0 error/7 既有 warning。首次 backend 上线暴露 sys_config.config_value VARCHAR(500) 限制，改版本化紧凑 JSON 后 20261005b 已初始化 229 字符配置；未改表结构。最终 JAR SHA-256 与测试产物一致，新版三个服务日志无 ERROR/Exception/Traceback；仅三服务替换，nginx 平滑重载，其余七容器、全部环境和挂载不变。Source 健康及三周榜 GET 200、各返回 5 条正确类型；本地/公网 12 项入口通过，候选镜像和本地生产入口各 4 组模拟 API 浏览器通过（不等于真实管理员保存/转存），公网中文桌面通过但后续导航间歇断连。备份 `G:/gying-backups/20261005T032935.839103Z` 五文件 hash/认证解密通过，SQL 25 表完整，本次未重做隔离恢复。初次观察无新增云盘任务，旧网盘文件/分享/追更未改；部署和回滚材料在 `E:/gying-tools/releases/metadata-only-weekly-20261004`，安全门禁 11 FAIL 保留。

- 元数据范围续采上线验收（2026-10-04）：实现提交 `2737b04`，Java 17 编译/打包及全量 391 项测试通过（0 failures/errors、1 项环境跳过），前端构建通过、lint 0 error / 7 项既有 warning。初次安全扫描 53 PASS / 11 FAIL / 0 UNKNOWN 后曾停止部署，随后用户明确要求“直接上线部署”，据此仅实施本次 backend/frontend 镜像更新；未改变无关安全配置，部署后仍为同样 11 项既有风险，不标记安全门禁已通过。11:20–11:21 替换前后端并平滑重载 nginx，backend 实际 JAR SHA-256 与测试产物一致，前后端 restart count 为 0、启动后日志无 ERROR/Exception；其他 8 个容器 ID、启动时间、重启计数不变，所有容器环境与挂载保持。GYING Source、social-publisher、backend 与 frontend 内部读取均 200；本地/公网首页、采集设置页、影片列表、form-config 均 200，匿名管理配置 401、内部 health 路径 404，共 12 项入口检查通过。最终候选镜像及已部署公网页面各通过中英桌面、中文 390px 手机、旧响应兼容 4 组浏览器回归（API 模拟，无生产写入），覆盖保存校验、范围刷新、进度/状态文案与无 hydration 错误；不以模拟保存替代真实管理员保存。只读库确认新增结束页默认值均为 3、原间隔 2 小时及开关不变，尚未手工推进自动任务；10:46 的近 24 小时新增活动影片为 18 条。发布材料 `E:/gying-tools/releases/metadata-crawl-20261004`，回滚目标 backend `20260930b` / frontend `20260930c`；最新备份 `G:/gying-backups/20261004T031935.885272Z` 三项加密文件 hash 通过，未做本轮解密/恢复。

- 无上传者分享行并入绑定组验收（2026-09-30）：修复「流人」迅雷分享编辑时第五季不回显——该分享 6 行同一 URL 中，第五季行 `resource_link.id=2500` 由系统发布创建、`uploader_id` 为空，而其余 5 行为 `uploader_id=1`，原 `sameShare` 要求上传者完全相等，导致该行被排除在绑定组外。现同一 URL、类型、网盘与同系列下，任一侧上传者为空即视为同一分享，两侧上传者都非空且不同时仍严格分开；仅放宽回显与同组更新范围，不改变非管理员只能编辑自己资源行的鉴权。后端全量 368 项测试 0 failures/errors、1 项按环境跳过（新增 2 项：无上传者行回显、不同真实上传者仍分开）；生产只读复核该分享 6 行由修复前 5 行并入变为 6 行并入，全库混合空/有主上传者的分享共 10 组，其余按同系列继续分开。仅重建 backend 为 `gying-binding-quickadd-backend:20260930b`（运行容器 JAR SHA-256 `df03a0f…f560da`，字节码含新增 `sameUploader`），frontend 与其它容器未变；首页/登录页/`form-config` 200，匿名绑定接口 401，无数据库写入。回滚目标 `gying-binding-quickadd-backend:20260930a`，材料在 `E:/gying-tools/releases/binding-uploader-merge-20260930`。

- 资源绑定快速追加全库搜索验收（2026-09-30）：快速添加输入框不再限定同系列，关键词命中库内任意影片都列为可点击候选；同系列候选之外跨系列命中同样可点选，最多显示 24 条并在超出时提示「另有 N 条匹配未显示」，精确影片 ID/标题仍可点「添加」直接追加。前端 lint 0 error、生产构建通过；本地与已部署站点各跑一轮 5 组浏览器场景全部通过（含跨系列候选显现）。仅重建 frontend 为 `gying-binding-quickadd-frontend:20260930c`，backend 与其它容器 ID、启动时间未变；本地首页/登录页 200、匿名绑定接口 401、`/api/resources/form-config` 200，无数据库写入。回滚目标 `gying-binding-quickadd-frontend:20260930b`，材料在 `E:/gying-tools/releases/binding-quickadd-global-search-20260930`。

- 资源绑定快速追加候选验收（2026-09-30）：前端 lint 0 error、生产构建通过。快速添加输入框改为 250ms 防抖查询同系列候选，并把未绑定命中列成可点击标签；跨系列命中被过滤，精确影片 ID/标题仍可点「添加」直接追加。部署后连续两轮 5 组浏览器场景全部通过（已绑定季回显、加载失败禁用保存、并发 409、取消勾选后追加、关键词候选点选与精确追加）。仅重建 frontend 为 `gying-binding-quickadd-frontend:20260930b`，backend 与其它容器 ID 未变；本地首页/登录页 200、匿名绑定接口 401，无数据库写入。回滚目标 `gying-binding-quickadd-frontend:20260930a`，材料在 `E:/gying-tools/releases/binding-quickadd-picker-20260930`。顺带复核 `AdminMovieModal` 的 `maxTagCount:'responsive'` 在 antd v6 下仍生效（6 个导演标签折叠为「+N」），无需改动。

- 资源绑定回显与快速追加验收（2026-09-30）：后端全量 366 项测试 0 failures/errors、1 项按环境跳过（含「同一分享跨历史根节点全部回显」回归，绑定专项 18/18）；前端 lint 0 error、生产构建通过。隔离与部署后各通过 5 组浏览器场景，覆盖九个已有季自动勾选、绑定加载失败禁止保存、并发 409、取消勾选并追加新季、按影片 ID/完整标题快速追加；本地首页与登录页 200，匿名绑定接口 401，`/api/resources/form-config` 200。根因是快速追加控件与多选 `Select` 同处一个 `Form.Item`，多子节点使 `Form.Item` 无法把 `bindMovieIds` 注入 `Select`，重开编辑不再回显已绑定季；现由 `Form.Item` 的 `extra` 承载快速追加控件，并移除对 antd v6 无效的 `maxTagCount`。backend/frontend 镜像 `gying-binding-quickadd-backend:20260930a` / `gying-binding-quickadd-frontend:20260930a` 已上线，运行容器 JAR SHA-256 与发布产物一致，其他容器未重建、重启计数 0；无数据库写入。回滚目标 `gying-series-search-backend:20260929c` / `gying-series-search-frontend:20260929b`，发布与回滚材料在 `E:/gying-tools/releases/binding-quickadd-20260930`。

- 系列/搜索修复续验（2026-09-30）：功能提交 `6382152` 已推送 `origin/codex/security`，远端哈希一致；SSH 22 连接中断后通过已信任的 SSH 443 完成推送，未改 Git 远程或全局配置。现役 backend/frontend/source 镜像仍为 `20260929c` / `20260929b` / `20260929a`。容器内网站/QQ 分流的 14 个拒绝词探针全部通过且无链接，「生化危机：爆发夜」仍返回库内夸克/迅雷；本轮密钥扫描 0 findings，未重跑上条全量构建/单测，也未发送真实 QQ 消息或触发转存/发布。入口故障及有限恢复结果见「仍需处理」，不沿用 9 月 29 日公网健康结论；脱敏证据 `E:/gying-tools/releases/series-search-20260929/continuation-20260930.json`。

- 系列元数据与搜索治理（2026-09-29）：后端 365 项测试 0 失败（5 项既有环境跳过），crawler 15 项通过，前端类型检查、构建与 lint 通过（7 项既有 warning）。网站/QQ 两条真实内部接口的 14 个词库探针均拒绝且不返回链接；PanSou 对 `porn` 查询仍返回候选，不能依赖上游过滤。新 GYING 系列接口 1.5 秒返回「星际迷航：奇异新世界」1–4 季。按严格季号/年份匹配修正「破产姐妹」6 季及「奇异新世界」4 季的现有元数据和双源身份：10/10 资源链接内容不变，公开图片响应及 SHA-256 确认分别为 6/4 张不同海报。「生化危机：爆发夜」QQ 搜索实际返回库内夸克和迅雷，不再因第一项中国大陆上映日期误判。未触发网盘转存或向群发送测试消息。

- 影片元数据海报修复验收（2026-09-28）：后端全量 347 项测试 0 failures/errors、5 项按环境跳过（新增 6 项海报 URL 与 TMDB 续集匹配回归）。backend `gying-poster-metadata-fix-backend:20260928a` 已部署，运行容器 JAR SHA-256 与发布制品一致；本地与公网首页、影片列表和海报均 200，匿名管理员接口 401，数据库无重复 `/media` 前缀存量。现有“复仇者联盟”2-4 已按核实的 TMDB ID 99861/299536/299534 原位更新，图片对象补入 MinIO；四张系列海报均返回 200 且 SHA-256 各不相同。该数据修复仅更新三行影片元数据，依赖部署前完整备份 `G:/gying-backups/20260928T131723.360179Z`，未改变资源、账号或转存状态。

- 资源绑定编辑修复验收（2026-09-28）：后端全量 331 项测试 0 failures/errors、5 项按环境跳过；前端生产构建通过。隔离与公网各通过 4 组浏览器场景，覆盖九个已有季自动勾选、绑定加载失败禁止保存、并发 409、取消勾选并追加新季；匿名绑定接口 401，首页 200。新镜像已部署，环境变量哈希与旧容器一致，其他服务未重建；无数据库写入。生产“心动的信号”仍有 6 个历史重复影片组（15 条绑定行对应 9 个影片），本轮未擅自删除；再次打开并保存会原位同步这些现有行。

- 安全只读复核（2026-09-28）：本次提交范围 32 项测试通过（审计边界专项 16 项）；完整工作区 66 项测试中 57 通过、9 项 POSIX 权限用例在 Windows 跳过，完整工作区计数含其他未提交工具用例，compileall 与工作区密钥扫描通过（0 命中）。严格限定密码重置用途常量豁免的文件路径与完整声明；Redis 网络检查验证实际 inspect 的 internal/Compose 标记，异常元数据不得掩盖已确认失败。真实入口 9/9 通过；19 个加密备份文件 hash 复核通过（本轮未解密/重做恢复）。运维就绪检查 10 通过、2 项既有架构/文档警告。脱敏证据为 `tmp/security-{audit,health,runtime,windows}-20260928-continuation.json` 与运维快照。本轮仅更新宿主审计工具/文档；Access 按用户要求暂缓，未修改云端策略、凭据、防火墙、生产数据或重建服务，未手工触发外部发布。

- 页脚、迅雷队列与合集补季验收（2026-09-28）：后端 331 项测试 0 failures/errors，5 项按环境跳过（4 POSIX 文件权限项、1 Redis 集成项）；前端类型检查、lint（0 error、7 个既有 warning）、生产构建通过。隔离与公网各通过 4 组页脚浏览器场景及 3 组补季结果 UI 场景（管理员 API 全模拟，无真实账号或生产写入）；后端 JAR、页脚 JPG hash 与构建/源文件一致。本地与公网 IPv4 页面/图片/热搜 200、匿名管理 API 401、内部路径 404；Python urllib 公网探针受边缘拦截返回 403，浏览器与 curl IPv4 正常。上线后首批 5 条旧 PENDING 均自动执行，2 条成功、3 条失败，观察期共 6 条迅雷转存成功；没有手动重放转存或发布。补季真实管理员点击、绑定数据与扫码入群仍未验收；此前已有的中文直达留言/登录页 hydration 警告未在本次无关范围修复。

- GYING 单片查询部署验收（2026-09-27）：crawler 12 项、后端 GYING 工作流/客户端 25 项测试全部通过，后端编译通过；source 镜像 `gying-source-detail:20260927a` 的脚本 hash 与提交产物一致。真实 backend 容器按原 20 秒超时调用同一单片，耗时由旧样本 35.08 秒降至 7.68 秒，仍返回 257 条资源/2 条自有资源；目录 1.72 秒、搜索 1.24 秒均返回有效结果。本地/公网首页、资源搜索与热搜接口正常，匿名管理接口 401、内部 QQ 入口 404；nginx 校验/重载成功，其他服务容器与环境配置未变。部署观察期没有 BrokenPipeError、Traceback 或 PoW 失败，但自动发布有 2 条上游 RuntimeError，写入验收继续保留。没有手工触发发布、修改、导入或转存。

- 转存配图部署验收（2026-09-27）：Java 17 编译/打包成功，后端全量测试 314 项、0 failures/errors、1 项 Redis 集成测试按环境跳过；镜像 `gying-transfer-image-backend:20260927a` 已上线，容器 JAR SHA-256 与构建产物一致，非 root 与原环境配置保持不变。本地/公网 `/`、`/resource-search`、`/api/movies/hot-searches` 均 200，匿名管理接口 401、内部 QQ 入口 404；内部 Resource Hub、GYING Source、social-publisher、frontend→backend 与 MinIO 健康均正常，nginx 配置校验和重载成功，backend 启动日志无 ERROR。部署前无 RUNNING 转存；本次未手工触发真实转存/分享，源图只读检查不等同实盘复制验收。

- 夸克配图复制修复验收（2026-09-27）：根因是夸克账号文件复制接口拒绝旧 `action/fid_list` 参数并返回 HTTP 400、上游 code 14001；改用 `action_type/filelist` 后，后端全量 319 项测试通过（0 failures/errors，1 项 Redis 集成测试按环境跳过）。镜像 `gying-quark-copy-backend:20260927b` 已上线，非 root、环境配置保持不变；内部健康入口通过。失败任务 `1583` 受控重试在部署后实际执行复制分支，返回 submitted=1/failed=0，目标目录由 0 张配图/10 个视频变为 1 张配图/10 个视频，任务为 `SUBMITTED`，自有分享令牌校验 HTTP 200/code 0；另一条 `1597` 复核 1 张配图和 4 个视频，对应发现记录为 `SAVED`、`resource_link` 状态为 `NORMAL`。同批其余失败任务未批量重放，避免与已入队 PanSou 回退重复。

当前结论（本次部署复核 2026-09-27；历史验收日期见各条目）：

- 后端全量测试 239 项通过（0 failures/errors，1 项 Redis 集成测试按环境跳过）；前端 `tsc --noEmit` 0 错误。
- 浏览器回归在隔离镜像与公网环境均通过，覆盖热门搜索面板与跳转、筛选默认收起与摘要、资源搜索对话与二维码展示、管理员建号与搜索频率保存、注册与编辑绑定；公网复验无页面运行时错误。
- 入口复核：本地与公网 `/`、`/admin/movies`、`/resource-search`、`/api/movies/hot-searches` 均返回 200；匿名管理接口 401，内部 QQ 路径 404。
- 品牌与首页轮播复核：中文「影窝」/英文「FilmNest」按语言切换且不并排；浅色/深色轮播背景与文字正确切换；375px 手机端轮播内容完整、按钮单行无裁切；768/1024/1200/1440 导航无横向溢出。
- 首页与分类页结果总数复核：均不显示「N 条结果」，关键词搜索仍显示匹配数量。
- 留言入口复核（2026-09-28）：顶栏与左侧抽屉均无留言项，页脚「留言」可跳转原留言页；登录用户抽屉仍保留「搜索资源」。原留言分类和频率配置未改变。
- 注册上限与邮件复核（2026-09-25）：临时把上限设为当前用户数 `3` 时，无邀请码策略返回 `registrationLimitReached=true`、`registrationAllowed=false`，发码接口 403 `Public registration limit reached; an invite code is required`；带有效邀请码时策略仍返回 `registrationAllowed=true`。清理临时邀请码后上限恢复生产值 `500`。Resend 域名 `gyinghub.dpdns.org` 三条记录 verified、DMARC 已添加，发件地址 `noreply@gyinghub.dpdns.org`；163 邮箱（`yolo136@163.com`）实测 delivered，Gmail 550 拒收；邮箱验证码开关已启用，注册页提示优先使用 QQ/163 邮箱（仅验证开启时显示），Gmail 用户暂收不到验证码。
- 账号自助能力验收（2026-09-26）：后端全量 257 项测试通过（新增 14 项：邮箱 90 天冷却与冲突 6 项、认证接口 8 项），前端 `tsc --noEmit` 与 lint 通过；生产镜像 backend `20260926a` / frontend `20260926a` 已部署。`sys_user.email_updated_at` 由 DBA 维护账号在本机执行幂等迁移（应用账号只有 DML、无 ALTER 权限），实测 `datetime NULL` 且中文注释正确。本地与公网 `/`、`/login`、`/register`、`/profile`、`/locales/{en,zh}/common.json` 均 200；浏览器复核中英文登录/注册页文案正确；无 token 与无效 token 访问 `me`、`reset-password(/code)`、`email(/code)` 均 401。回滚目标 backend `20260925e` / frontend `20260925n`，无需撤销新增列。
- 昵称与邮箱登录验收（2026-09-26）：后端全量 291 项测试通过（新增 20 项：昵称校验与回退、登录标识匹配、评论展示名解析），前端 `tsc --noEmit` 0 错误、lint 0 error；镜像 backend/frontend `20260926b` 已部署。`sys_user.nickname` 由 DBA 维护账号在本机执行幂等迁移，5 个存量账号已回填为用户名。本地与公网 `/`、`/login`、`/register`、`/profile` 均 200；以邮箱标识调用登录接口在错误密码下返回 401 且无 SQL 错误，新前端镜像经临时容器复核中英文登录/注册文案。真实账号成功登录与自助改昵称未做端到端点击（无生产账号凭据）。回滚目标 backend/frontend `20260926a`，无需撤销新增列。
- 迅雷资源重试修复与验收（2026-09-26）：影视资源中心对 XUNLEI 发现结果重试时保留 `WAITING_SHARE` 的 `saved_path` 续分享状态，不再重复转存；转存或分享失败改为 409 可读原因并写入 `failure_reason`，随后自动尝试 PanSou 备用发现。后端全量 293 项测试通过（新增 2 项重试回归）；backend `20260926c` 已部署，本地与公网入口 200。回滚目标 backend `20260926b`；生产管理员真实点击仍待复核。
- 管理接口参数边界修复（2026-09-26）：公开 API 的 `limit` 仍限制为 1–100，管理员接口允许有界扫描上限 5000；修复后台状态校准 `limit=2000` 与 GYING 后台 `limit=200` 被安全过滤器提前 400 的问题。后端全量 296 项测试通过（1 项跳过）；backend `20260926d` 已部署，管理员接口匿名请求实证返回 401 而非 400，公开 `limit=101` 仍返回 400；回滚目标 `20260926c`。
- 本轮未创建真实账号、未修改生产搜索频率、未手工触发转存或发布；模拟 API 回归与单测不替代真实扫码转存和外部副作用验收。
- 安全续审（2026-09-25 防火墙变更前）：只读扫描 53 PASS / 10 FAIL / 0 UNKNOWN，安全工具单测 16 项通过、工作区 secret scan 0 findings；运维就绪 10 PASS / 2 WARN / 0 FAIL（迁移文档漂移、新库覆盖）。未重跑上述业务全量测试，未重启生产或修改防火墙/生产凭据。
- 防火墙单步验收（2026-09-25）：用户实施记录 `20260925-113327-6e2eac1b/result.json` 为 applied，变更前后各 9/9 健康检查通过；本任务 11:34 再查 ActiveStore 的 profile/端口/网卡/方向/动作与预期一致，出站未变，回退守护进程为 0 且无回退记录。独立 `-CheckOnly` 再次 9/9 通过（`G:/gying-tools/security-20260925/firewall-attempts/20260925-113458-e40a05c2/result.json`）；10 个容器运行且未暂停，restart count 均为 0、启动时间早于本次变更。未改数据库、代理/DNS/TLS 或生产容器；此前工具 30 项测试包含模拟回退，真实回退未触发，整体安全门禁仍未通过。
- 异机局域网验收（2026-09-25，用户报告）：另一台有线电脑 → 当前 Wi‑Fi 服务器 `192.168.1.147`，TCP 3306/33060/5005/8880/9000/9001 全部 False；用户同时确认网站可打开并可注册。未收集原始输出/抓包，不将失败连接全部归因于防火墙；这不覆盖公网直连、IPv6、影片列表/图片/评论或完整应用回归。
- 完整备份阶段：19/19 加密文件 hash/认证解密通过，25/25 表恢复计数与 CHECKSUM 一致，视图/触发器/事件/例程数量匹配；隔离 MinIO 图片与拒绝探针通过。源站/公网首页、公开列表与管理员/内部路径拒绝复测正常；未进行应用全链路/异机恢复，整体安全门禁仍未通过。

---

后续操作请使用[现行部署流程](../deployment.md)与[迁移手册](../../MIGRATION.md)，不要直接复用旧命令或历史授权。
