# API 安全清单

2026-11-10 按 `codex/cloud-drive-workflows` 当前工作树完整提取；Git 基线为 `6d6ef1269b54b4eeaf8f3fd0a90b615ea63c2839`，实际源码以 JSON 中逐 Controller 的 SHA-256 为准。基线提交尚未包含本轮百度 UNKNOWN 恢复与人工去重合并修改，清单不代表生产已部署。静态映射不是动态鉴权或渗透验收。完整方法名、HTTP 方法、路径、控制器、1 基行号、源码 hash 与直接 guard 调用保存在 [JSON 清单](api-inventory.json)。

| 项目 | 数量 |
| --- | ---: |
| Controller | 27 |
| 方法级映射注解 / 展开路由 | 191 / 191 |

## 使用边界

- 核对公开、登录、发布者、ADMIN 与内部 token 时，同时读取统一过滤器、Controller、服务和 nginx；`directGuardCalls` 与 `directGuards` 仅记录直接调用，不证明授权完整。`line` 为映射注解行，`methodLine` 为方法声明行，均从 1 开始。
- 提取器 [generate_api_inventory.py](../../tools/security/generate_api_inventory.py) 识别 fully qualified Mapping、无参数映射和类/方法组合，忽略注释与字符串中的伪注解；遇到非字面路径或 HTTP 方法表达式即失败。表达式、常量、继承或自定义组合注解须改用编译后 Spring 映射提取。
- `POST /api/admin/resource-hub/transfers/run` 的候选实现支持 QUARK/XUNLEI/BAIDU，直接调用 `authHelper.requireAdmin`；未知 provider 被拒绝，按已保存的文件类型和阶段开关执行单盘任务。真实管理员鉴权、转存和发布仍需独立验收。
- 新增百度管理员接口：GET `baidu/session`、GET `baidu/transfers`、POST `baidu/login`、POST `baidu/transfers/{taskId}/submit`，均直接调用 `requireAdmin`。登录只返回脱敏状态；配置保存和登录完成不自动启用工作流。
- 新增内部百度 `POST /api/internal/resource-hub/baidu/login-state` 和 `POST /api/internal/resource-hub/baidu/session`。它们在服务层检查管理员发起的一次性 ticket/nonce，不持有通用 JWT；Controller 中没有直接 `requireAdmin` 调用并不表示接口公开。公网 nginx 拒绝内部路由，仍须独立验证过滤器、路由和服务的实际边界。
- 百度补片状态/设置 GET/PUT `/api/admin/resource-hub/baidu/backfill`、运行 POST `baidu/backfill/run`、保留不确定子任务 POST `baidu/backfill/defer-unknown` 均直接调用 `requireAdmin`。恢复入口核对父任务、子任务、revision 和租约，仅登记本地审计、推进父游标；保留原 UNKNOWN 子任务，不执行网盘操作。
- 人工去重接口 POST `/api/admin/resource-hub/duplicates/preview`、POST `duplicates/merge`、GET `duplicates/history` 均直接调用 `requireAdmin`。合并要求同一管理员、10 分钟内的审阅、明确确认与 UUID 幂等键，并在事务中复核引用与结构门禁；成功重放可超过审阅期限。旧 `cleanup/duplicate-tmdb?dryRun=false` 仍返回 409。表结构与写入保护触发器验收见 [合并契约](../duplicate-movie-merge.md)。
- 当前内部 AI 心跳、租约与 CatalogResearchController 映射一并纳入；非 Spring 的 source、publisher 与宿主 CLI 不在此范围，契约见 [API](../api.md)。
- 只读 GET 也须按代码核对副作用；写路径及 runNow=false 不能用探活自动执行。前端 mock 八场景只验证页面行为，不能代替真实服务鉴权验收。
- 更新接口时同步 JSON 和本页，验证全部方法级映射计数、唯一路由、方法名、行号与源码 hash；历史版本在 [旧清单](../history/README.md#git-history)。

## 控制器索引

| 控制器 | 路由注解数 | 源码 |
| --- | ---: | --- |
| AdminCommentController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminCommentController.java) |
| AdminMovieController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminMovieController.java) |
| AdminResourceReportController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminResourceReportController.java) |
| AiAssistantController | 8 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AiAssistantController.java) |
| AuthController | 12 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AuthController.java) |
| BaiduBackfillController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/BaiduBackfillController.java) |
| BaiduLoginController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/BaiduLoginController.java) |
| CaptchaController | 1 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CaptchaController.java) |
| CatalogAiController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CatalogAiController.java) |
| CatalogResearchController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CatalogResearchController.java) |
| CommentController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CommentController.java) |
| FavoriteController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/FavoriteController.java) |
| GyingSourceAdminController | 22 | [源文件](../../backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java) |
| InvitationController | 3 | [源文件](../../backend/src/main/java/com/gying/movie/controller/InvitationController.java) |
| MonitoringController | 5 | [源文件](../../backend/src/main/java/com/gying/movie/controller/MonitoringController.java) |
| MovieController | 5 | [源文件](../../backend/src/main/java/com/gying/movie/controller/MovieController.java) |
| MovieRecommendationController | 1 | [源文件](../../backend/src/main/java/com/gying/movie/controller/MovieRecommendationController.java) |
| NotificationController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/NotificationController.java) |
| QqAutomationAdminController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java) |
| QqBotController | 3 | [源文件](../../backend/src/main/java/com/gying/movie/controller/QqBotController.java) |
| ResourceHubAdminController | 35 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java) |
| ResourceHubInternalController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java) |
| ResourceLinkController | 20 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java) |
| SocialPublishingAdminController | 14 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java) |
| SysConfigController | 3 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SysConfigController.java) |
| UserManagementController | 7 | [源文件](../../backend/src/main/java/com/gying/movie/controller/UserManagementController.java) |
| WebResourceSearchController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/WebResourceSearchController.java) |
