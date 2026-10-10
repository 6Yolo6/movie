# 项目代码分析与第一批优化（2026-10-09）

## 1. 范围与结论

- 本轮按“暂缓安全门禁整改，转向项目整体代码分析与优化”执行。
- 从当前检出 `D:\gying-movie\movie`、基线提交 `b3ac02a` 出发，梳理后端、前端、采集、发布的入口、主要读写链路、并发边界和已有测试；**不是逐行全审，也不是生产容量压测**。
- 第一批只落地三项有可重复验证收益的改动：收藏计数批量化、评论祖先查找索引化、首页请求生命周期优化。没有新增运行时依赖、修改数据库结构或改变外部发布行为。
- 本轮只完成代码、测试、构建与 Git 交付，**没有部署生产服务**。此前未通过的 11 项门禁未获豁免；本轮不复扫、不整改，也不绕过门禁。没有重放历史队列、转存资源或真实发帖。
- 原有安全文档及脚本的未提交修改保留在工作树，本次提交不包含它们；没有改写 `docs/current-project-status.md` 的线上状态。


> 后续：[第二批代码优化与手机适配](code-optimization-20261009-round2.md)（2026-10-09 开始，2026-10-10 续验）。本文第一批结果保持历史口径。

## 2. 整体结构与主要压力点

| 区域 | 当前技术与职责 | 分析结果 |
| --- | --- | --- |
| backend | Java 17、Spring Boot 3.5.16、MyBatis Plus 3.5.7；片库、评论、收藏、Resource Hub、QQ 搜索、转存与任务编排 | 主要业务源码约 2.95 万行；核心工作流已有较多回归。读路径仍有逐条查询/重复扫描；少数控制器同时承担编排与任务状态管理。 |
| frontend | Next.js 16.3.5、React 19.2.8、Ant Design 6；公共片库和管理页面 | 约 1.32 万行代码；类型检查及 lint 没有错误。公共页的请求串行依赖和旧响应覆盖是本轮直接改善点；管理页仍有轮询重叠候选。 |
| crawler | Python 3.12 容器、requests；GYING 会话、元数据、资源、种子、海报 | 主文件 2,322 行。已有会话复用、请求间隔、32 槽 HTTP 并发限制；不能简单增加上游并发。发现种子流式响应提前读完的问题，见后续优先项。 |
| social-publisher | Node.js、mysql2、Playwright；多账号社交平台发布 | 核心入口 506 行，HTTP/子进程/数据库/出站操作耦合。应优先补发布认领和不确定结果语义，再改并发；本轮没有发送任何真实内容。 |

这里的 social-publisher 不等同于 backend 的 QQBot/QQ 每日推荐链路。

较大的模块包括：`GyingSourceWorkflowService`（2,963 行）、`QqBotServiceImpl`（2,522 行）、`XunleiClient`（1,856 行），以及前端 Resource Hub（1,906 行）、自动化管理页（1,665 行）。行数本身不是缺陷，但这些位置适合在契约测试保护下按职责拆分，而不是一次性大重写。

## 3. 已落地的三项优化

### A. 收藏列表：一个页面只发一次批量计数请求

涉及 `FavoriteController.mine`、`UserFavoriteMapper.countByMovieIds`。

- 原来对当前页每个 movieId 分别执行一次 COUNT；60 条收藏会额外产生 60 次计数数据库往返。
- 现在去重查询 ID 后，用一条参数绑定的批量语句返回计数：**最多 60 次计数往返 → 1 次**。收藏分页查询和影片批量查询不变。
- 特意没有简单 `GROUP BY movie_id`：数据库列可能是不区分大小写的 collation，分组可能合并/改变返回 ID 的拼写。采用有界 `UNION ALL` 保留每个请求 ID 作为结果键，并保留原来 `movie_id = ?` 的比较语义。
- 这减少的是数据库往返，**不是把 N 次索引计数变成一次扫描**。现有 `idx_movie_id` 可继续用于等值计数，不需要迁移。
- 保留用户鉴权、页大小上限、收藏顺序、总数、收藏时间、海报地址处理、缺失影片跳过和缺失计数兜底。空 ID 集合不会退化成全表统计；数值结果以 `Number.longValue()` 处理。
- 回归覆盖 60 条页、空页、重复 ID、缺失影片/计数、大计数、未授权访问、参数绑定及大小写变体键。

### B. 评论组装：一次建索引，避免逐跳全量扫描

涉及 `CommentServiceImpl.getCommentsPaged`。

- 原实现对每条回复查祖先时，都重新创建根 ID 集合，并在所有回复中线性找父评论。
- 现在根 ID 集合和 `id → Comment` 索引各建一次，祖先跳转直接查表；只为当前页实际返回的根评论/回复批量加载用户资料。
- 在同一个 4,000 条回复测试夹具中，记录到的 ID 读取次数从 **4,018,000 → 12,000**，约减少 **99.7%**。这是确定性的测试对象调用计数，不是线上耗时或 QPS。
- 保持原有最多 20 次祖先跳转的安全界限、扁平回复的数据库时间顺序、实时昵称及历史昵称回退；孤儿、环和空父 ID 不会让整个分页失败。
- **仍然读取该 relateId 下全部可见非根回复**。本次解决的是 Java 端组装成本，不声称已解决高评论量下的数据库读取量和全部内存占用。

### C. 首页：列表不再等待筛选，旧请求不能污染新条件

涉及前端首页和中英文错误文案。

- 移除“筛选条件未返回就不挂载片单/轮播”的全页阻塞。延迟筛选响应时，三个分类片单和近期热门轮播可以独立发出请求并显示。
- 筛选、轮播、片单均传入 AbortSignal；离开/切换时取消旧请求，并在响应完成后检查失效状态。即使测试传输层故意忽略 abort，旧结果也不能覆盖当前分类。
- 查询条件绑定独立的 MovieGrid 实例，分页游标和旧列表一起重置；同步 in-flight 引用避免重复点击发出同页请求。
- 检查 HTTP 状态，失败不再当作“空结果”。首屏可重试；翻页失败保留已显示影片并重试同一页；筛选失败只影响筛选区域。
- 没有全局缓存、放宽鉴权或改变搜索/影片 API 契约。

## 4. 验证与证据

| 验证 | 本轮结果 |
| --- | --- |
| 后端全量 `mvn -q test` | 509 项，504 通过，0 失败/错误，5 跳过。4 项依赖 POSIX 文件语义，1 项需要显式 Redis 集成测试环境。 |
| 本轮新增后端回归 | 14 项全部通过；旧实现已复现重复扫描和无关用户加载等问题。 |
| 提交前 `mvn -q -DskipTests compile` | 通过，Java 17。 |
| 前端 TypeScript `tsc --noEmit` | 通过。 |
| 前端全量 ESLint | 0 错误，7 项既有警告：4 项 img、2 项未使用变量、1 项 location 跳转建议。修改页只有原有的 img 警告。 |
| 前端 `npm run build` | 通过，生成 standalone 产物。 |
| 首页离线浏览器回归 | 7 个场景通过，覆盖请求独立启动、取消/忽略迟到响应、旧分页隔离、重复点击、首屏/翻页/筛选重试，包含中文移动端。全部 API 被 mock，非本地请求被拒绝。 |
| 现有 Node 工具单测 | 32 项通过（资源搜索 UI 解析、QQ 资源路由、迅雷会话辅助工具的模拟单测）。 |
| social-publisher 单测 | 9 项通过，出站请求均为测试替身。 |
| crawler 单测 | Python 3.12 下 39 项通过；使用已有镜像 ID 的断网、只读、非 root 临时容器，只挂载采集源码，退出后容器已移除。 |

环境差异单独记录：

- 本机 Python 3.10 不支持既有测试使用的 `unittest.TestCase.enterContext`，不能把这次本机尝试视为采集业务回归失败；正式结果使用与 Dockerfile 一致的 Python 3.12。
- 本机 Next 首次构建遇到用户目录 telemetry 配置的 EXDEV rename 错误；使用**仅当前进程**的 `CI=1`、`NEXT_TELEMETRY_DISABLED=1` 后构建通过，没有修改系统或生产配置。
- 浏览器先验证构建后的 Next 服务，再验证 standalone 入口；临时服务仅监听 `127.0.0.1:18083`，不是生产部署。

本地详细日志、浏览器结果和移动端截图保存在 `D:\gying-movie\movie\tmp\code-optimization-20261009`（Git 忽略，不含生产凭据）。主要回归代码：

- `backend/src/test/java/com/gying/movie/controller/FavoriteControllerTest.java`
- `backend/src/test/java/com/gying/movie/mapper/UserFavoriteMapperTest.java`
- `backend/src/test/java/com/gying/movie/service/impl/CommentPaginationTest.java`
- `tools/tests/home-loading.browser.cjs`

浏览器回归使用仓库已有 Playwright 安装，不需要引入新的生产依赖：

```powershell
$env:PLAYWRIGHT_MODULE = 'D:\gying-movie\movie\social-publisher\node_modules\playwright-core'
$env:HOME_LOADING_BASE_URL = 'http://127.0.0.1:18083'
node D:\gying-movie\movie\tools\tests\home-loading.browser.cjs
```

先在空闲回环端口启动本地构建产物；standalone 运行前需要按 Dockerfile 的方式放入 `public` 和 `.next/static`。测试脚本拒绝非回环目标，不加载真实认证材料，并拦截动态影片页预取，避免触发服务端真实后端请求。

## 5. 下一批优先项（本轮未修改）

以下是代码层面的后续项，与之前的 11 项基础设施门禁分开管理。

| 优先级 | 证据与影响 | 建议改造与验收边界 |
| --- | --- | --- |
| P1 | crawler 的 `fetch_torrent_file` 虽使用 `stream=True` 并在迭代中限制 2 MiB，但 `site_get` 先调用的挑战/登录识别函数会读取 `.text`/`.json()`。离线 3 MiB 响应在返回 site_get 前已被完整读取，后置限额挡不住预读。 | 有界响应读取、挑战探测和响应关闭必须一起改；补普通种子、HTML/JSON 挑战、登录重试、错误 Content-Type、超限和中途异常测试。不能通过跳过认证识别来“提速”。证据：`crawler-streaming-probe.json`，无网络请求。 |
| P1 | social-publisher 按 logId 读取数据并直接出站，再写 POSTED/FAILED；边界未见原子认领，同一 logId 并发/重复调用可能重复发布。这里是代码检查结论，未重放真实发布。 | 先实现持久化认领、状态转换和并发测试；外部已受理但本地记账失败须保留 UNKNOWN/人工核对语义，不能自动重发冒充 exactly-once。真实发帖验收仍须另外确认。 |
| P2 | `MovieController` 的 featured/recent_hot 排序两次使用相关资源更新时间子查询；首页会对三个分类及轮播读取。 | 先用代表性数据比较执行计划、扫描行和索引，再决定合并聚合或维护摘要。不要在无证据时改排序语义或增加失效难控制的缓存。 |
| P2 | 评论回复仍按 relateId 全量读取，即使只展示一页根评论。 | 评估只取当前根节点后代的递归查询/分页契约，保留审核、类型、孤儿处理及深度边界；用大线程数据比较读取行数。 |
| P2 | ResourceHubAdminController 使用 `newSingleThreadExecutor`，并有多处 submit；状态记录裁剪不等于执行队列有界。 | 加有界队列、明确拒绝/排队反馈和关闭语义；将编排移入 service。不要直接并行转存或重放历史失败任务。 |
| P2 | 监控页手动加载、关键字变化和 30 秒 setInterval 可重叠，未见取消或旧响应保护。 | 按首页已验证模式改为单飞/完成后调度，并补切词、切页、卸载和慢请求回归。 |
| P2 | publisher 的子进程 stdout/stderr 持续拼接，账号状态使用 Promise.all 全量启动 CLI。 | 限制输出字节和账号并发，统一超时/退出清理；先补模拟子进程测试，不改真实发布内容。 |

## 6. 交付与回退边界

- 本次改动不需要数据库迁移，外部会话、身份匹配、资源所有权、队列游标和发布配置不变。
- 代码交付仅提交上述实现、测试、语言文案及本文。线上部署仍暂停；后续满足门禁后只部署受影响的 backend/frontend，并再验证真实入口。
- 本轮没有线上替换动作，因此没有执行线上回滚。代码回退使用普通 revert，不回滚既有的 source identity 数据迁移，不清理历史资源或日志。
