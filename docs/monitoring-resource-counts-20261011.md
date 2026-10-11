# 后台监控优化与影片资源分类计数部署记录（2026-10-11）

范围：后台监控页面优化、影片详情页分别显示网盘与磁力/种子资源数量。

授权：用户在本轮安全扫描结果（59 通过 / 11 失败）披露后明确同意生产部署，属于仅限业务发布的例外；未关闭或降低任何安全门禁，安全配置、凭据、端口、Tunnel 和依赖容器均未改动。

## 1. 变更内容

- 后端监控统计只统计 `event_type='API'` 的请求与错误，页面浏览埋点不再计入接口错误率；访客数与页面浏览量分开计数。
- 概览返回统计日期与 Asia/Shanghai 时区，发布结果区分已发布/失败/进行中/未确认；热门搜索在 Redis 不可用或缓存为空时回退当日 SQL。
- `GET /api/admin/monitoring/logs` 支持服务端搜索、状态筛选、计数与分页：带 `type` 返回 `{records,total,page,size,pages}`，省略 `type` 保持原有结构（向后兼容）。查询上限 100 字符、`size` 1–100、页码收敛，SQL 标识符白名单化、值参数化，`%`/`_`/`!` 转义。管理员鉴权不变，无 schema 变更。
- 监控页面前端重建：明暗主题与响应式概览、6 张指标卡、14 天趋势（缺失日期零值补齐）、诚实的响应分布与发布成功率、热门搜索与发布汇总下钻、桌面表格/移动卡片、错误详情展开、真实分页；概览与日志独立加载、请求中断与过期结果保护、刷新失败保留同查询数据与成功时间戳、首次失败显示未知而非伪造 0；30 秒自动刷新且隐藏标签页暂停。
- 影片详情页按内容分类计数：`DISK` 为网盘，`MAGNET/TORRENT` 为磁力/种子（经夸克/迅雷归档的种子同样计入 P2P），`ONLINE` 与未知类型各自独立成分类；主分类与 provider 分类均显示精确数量（含 0 与超过 99），只有磁力的影片默认选中磁力/种子分类；自有资源请求防过期覆盖、按 ID 去重并拒绝其他影片记录。
- 修正详情页英文页面既有的水合错误：资源创建日期不再使用依赖操作系统的 `toLocaleDateString`，改为确定性的 `YYYY-MM-DD`。

## 2. 测试证据

- 后端全量 Java 17：557 项测试，0 失败 / 0 错误 / 5 项既有跳过，含 4 项在隔离临时 MySQL 上运行的真实集成测试。
- `mvn -DskipTests compile package` 通过，产出被测 `app.jar`。
- 前端 TypeScript 与生产 standalone 构建通过；全量 lint 0 错误 / 7 项既有警告。
- Node 模型、发布状态与 i18n：21 项通过。
- 浏览器回归（Edge，中英/明暗/360–1440px）：监控 16 个场景、资源分类计数 10 个场景，分别在隔离环境、候选容器镜像和生产 nginx 入口通过。
- 候选后端 JAR 离线冒烟（`--network none`）：Spring 上下文完整装配至数据源阶段，仅因刻意断网失败，无类路径或 JAR 问题。

## 3. 镜像与构建

| 服务 | 候选镜像 | 镜像 ID | 说明 |
| --- | --- | --- | --- |
| backend | `gying-monitoring-backend:20261011a` | `sha256:b265d7bb…` | 基于保留的 `gying-monitoring-rollback-backend:20261011a` 运行时，仅替换 `app.jar`；镜像内 JAR SHA-256 与测试产物一致，入口/用户/工作目录/端口未变 |
| frontend | `gying-monitoring-frontend:20261011a` | `sha256:a2825077…` | 使用固定 Node 基础镜像 `gying-monitoring-node-base:20261011a`，构建期 `NEXT_PUBLIC_API_URL` 保持空值走同域，运行用户 `nextjs` |

提交 `de83b39cdd0166b52f451c1c178d18a4e023ab77`（分支 `codex/security`）；23 个源码/测试/契约文件，隔离构建目录中 387 个受跟踪源码文件与仓库提交逐字节一致。候选配置除镜像标签外与生产 Compose 完全一致。

## 4. 加密检查点

- 切换前仅停止 backend（90 秒超时），MySQL 单事务快照、`.env`、生产 Compose、候选/回滚覆盖文件与 backend data/logs 卷加密导出，另存 11 个原容器的运行态。
- 目录：`G:\gying-backups\20261011T124714.996872Z`；8/8 加密产物通过 hash、大小与完整认证解密（MySQL 含 25 张表与完成标记，配置归档与源文件一致）。
- 该检查点为应用回滚检查点，不是跨服务灾难恢复快照；未执行恢复演练（`restoreDrill=false`）。

## 5. 切换与验收

- `docker compose -p gying-movie -f docker-compose.prod.yml -f <deploy.json>`：仅 `up -d --no-deps --no-build --pull never backend frontend`；nginx 先 `nginx -t` 再 `nginx -s reload`，未重建 nginx 或任何依赖。
- 运行态复核：backend 149 项、frontend 8 项环境变量的键与值逐项与切换前完全一致，网络、端口、挂载、内存/PID 限制、`cap_drop`、`no-new-privileges`、只读根与 tmpfs、日志与重启策略均未变化；backend 以 prod 配置 15.0 秒完成启动，日志无 ERROR。
- 入口：本机 nginx 首页与详情 200，公网首页 200，公网匿名 `/api/admin/monitoring/overview` 与 `/logs` 401，`/actuator` 404。
- 真实数据：影片 `tmdb_movie_969681` SSR 渲染 网盘=2、磁力/种子=126、provider 分组 2/1/1；数据库明细为 DISK 2（夸克 1/迅雷 1）、MAGNET 62 + TORRENT 62（P2P）+ TORRENT 2（夸克/迅雷归档），完全一致。
- 未改动服务：9 个其他容器的容器 ID、启动时间、重启计数与镜像与基线一致（PanSou 历史重启计数 1 保留）。
- 副作用：切换后回滚守护二次通过，`social_post_log` 仍为 193 POSTED / 12 FAILED、无待发或在途发布；未执行 SQL 迁移、未重放历史任务、未做真实转存、修复或发布。历史 Resource Hub 3426 与迅雷 434 的 RUNNING 记录未重置。

## 6. 回滚

- 旧镜像保留为 `gying-monitoring-rollback-backend:20261011a`、`gying-monitoring-rollback-frontend:20261011a`，回滚覆盖文件与固定标签经守护校验。
- 回滚使用同一组显式 Compose 参数与 `--no-deps --no-build --pull never`，仅替换 backend/frontend；若外部发布结果出现新变化或守护失败，停止自动回滚并转为对账或前向修复。

## 7. 剩余风险

- 安全扫描仍为 59 通过 / 11 失败：2 个依赖端口暴露、5 项缺少 `no-new-privileges`、3 个 root 进程、Redis 网络隔离等；本次部署不构成安全闭环。
- 管理员监控页面未使用真实管理员凭据登录验收（匿名接口 401 已确认），页面本身在候选镜像与生产入口以 mock API 通过浏览器回归。
- 全新库 `schema.sql` 仍缺少 QQ 日志表（既有警告，本次未改 SQL）。
