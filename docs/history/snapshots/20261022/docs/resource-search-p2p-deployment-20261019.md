> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# 搜索资源游客入口与 GYING 种子隔离上线（2026-10-19）

## 结果

左侧“搜索资源”对游客可见。游客进入 `/resource-search` 看到中英登录提示与登录按钮，没有查询输入框，也不请求搜索任务 API；登录成功后返回包含原关键词和 `auto` 参数的页面。

GYING `/dbt/` 登录专用 TORRENT 下载 URL 不再进入新资源或归档任务 payload。公开详情、网页/QQ 的库内资源读取过滤历史账号下载链接。磁力及 QUARK/XUNLEI 的种子归档分享继续展示；真实 torrent 通过内部账号按稳定 BT 标识读取，v2 任务只保存磁力 ID。旧 v1 的资源引用保留，不迁移或删除历史数据。

新增 source 正常退出处理：停止接收新请求，等待当前 HTTP handler 完成，然后关闭服务器；SIGTERM 与 SIGINT 均支持。旧 source 作为 PID 1 没有 SIGTERM handler，停止等待期间已确认只有一个线程、无已建立连接，使用 SIGINT 正常退出。没有 SIGKILL。

## 镜像与服务范围

- 业务提交 `cbec6b1`；退出修复 `20880e7`，均已推送 `origin/codex/security`。
- backend：`gying-resource-p2p-backend:20261019a`，镜像 `sha256:d8445ad41f657c22acc977f3028079b97fc198cfeb7fe1da1b5b17f34559d439`。
- frontend：`gying-resource-p2p-frontend:20261019a`，镜像 `sha256:747aca93ccafaf536d820864f3db28b17f4b060b534ebb014778b19ddd5f3e07`。
- gying-source：`gying-resource-p2p-source:20261019b`，镜像 `sha256:76fe318b24b1c1222a6615918f5aedc83f772357d6ba01ed1e94458d63bdb58a`。
- 显式项目 `gying-movie`、`docker-compose.prod.yml` 和 `E:/gying-tools/releases/resource-search-p2p-20261019/deploy.json`。环境键值、挂载、网络和公开端口与原有效配置一致；保留既有 `APP_TRUSTED_PROXIES`。
- 仅替换三个受影响服务；nginx 测试后平滑重载。发布器、DB、Redis、MinIO、OpenClaw、Quark、PanSou 与宿主任务没有随发布重建。

首次 source 健康探针在启动完成前失败，切换脚本停止。后续探针 200 后继续已有切换，没有绕过实际健康失败；本机与公网最终全部通过。

## 测试与真实验收

- Maven 全量 632 项：617 通过、15 跳过（独立 Redis/MySQL 环境未启用）、0 失败/错误。无网络、无生产凭据/挂载的 Maven 容器，已移除自有测试容器。镜像 `app.jar` SHA-256 为 `e992c527cf2a06e2bf3cb9e04877b4fb43c692b1721a6cbf59df20c46ff1bd64`，与测试打包产物一致。
- Python 3.12 同生产依赖隔离容器 60 项通过，含真实 SIGTERM 在途请求排空。前端类型检查、Lint（7 条既有警告）、Webpack 构建和最终 Docker Turbopack 构建通过。
- 候选镜像真实 React/Ant Design：中文/英文、390px/1440px、深色/浅色四场景通过。所有 API 为 fixture，验证游客菜单、登录提示、原搜索参数、mock 登录后只发出一次原关键词查询，无页面错误或横向溢出。
- 生产 Headless Edge 的本机和公网游客菜单、登录提示、返回参数各通过；没有使用真实登录凭据或提交搜索转存。
- 本机与公网首页、资源搜索页、片库列表和影片详情共 8 项 GET 均为 200。`gying_tv_vkN9` 资源从 36 条（16 条 `/dbt/`）变为 20 条（0 条 `/dbt/`、16 条磁力、2 条归档），其他公开资源保留。
- source 受保护只读资源接口返回 118 条、0 条账号下载链接、2 个 `archive_torrent` 标记。上线后自然采集新增 40 个 v2 归档任务，最终只读快照为 29 SUCCEEDED / 1 RUNNING / 10 PENDING，全部 payload 不含 `/dbt/`。任务成功是后端流水线审计，本轮未逐个复验远端分享文件；排队不表示远端归档成功，没有手动重放/转存/发布。
- 历史账号链接 5,258 条保持；`social_post_log` 216 POSTED / 12 FAILED / 3 UNKNOWN 在发布前后保持。原 9 个非受影响容器身份、启动和重启计数先确认一致；随后单独停掉零客户端的隔离测试 MySQL `gying-query-mysql-c43cc988fc3e`，保留容器及数据，不属于生产服务。
- 21:25:30 以后三个服务日志检查无 ERROR/Traceback/unhandled_exception，backend 1 条 WARN。安全扫描上线前后均 59 PASS / 11 FAIL / 0 UNKNOWN，按原聊天披露后的本次业务发布授权执行，不代表安全整改完成。
- 本地 AI 恢复受管理的四个服务后，真实 `searchMovies` 和 `getMovieDetails` 均成功，有本次查询来源；没有调用写入工具。

## 备份与回退

加密检查点 `G:/gying-backups/20261019T132109.901502Z`：8 项 age 产物的 manifest、大小、SHA-256 和完整认证解密均通过，SQL 完成标记验证通过。包含 MySQL、环境、Compose、两份旧部署覆盖、backend 数据/日志卷和运行配置。取得快照时 backend/source 已停止，其他写入方保持原状态；这是应用回退检查点，不是全服务恢复演练。

回退镜像、运行前后投影、部署覆盖、`rollback.json`、构建日志和浏览器证据在 `E:/gying-tools/releases/resource-search-p2p-20261019`。frontend 可单独回退。旧 backend 不支持 v2 归档描述，回退前必须暂停相关采集/Worker，并核对新任务与外部结果；保留 v2 任务、历史资源和文件，禁止清队列、用旧库覆盖现库或让旧消费者继续执行 v2。

每周重启为独立维护任务，状态见 [每周优雅重启](weekly-graceful-restart.md)。手机为 Headless Edge 视口，非手机真机；本次没有重新验证远端分享文件或实际字幕播放。
