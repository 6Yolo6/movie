# API 安全清单

2026-11-08 按 `codex/cloud-drive-workflows` 当前工作树完整提取；Git 基线为 `3e09a8030778b3c23965fb2fc8d59cc95b9b4ef1`，实际源码以 JSON 中逐 Controller 的 SHA-256 为准。该基线提交尚未包含本轮工作树修改，清单不代表生产已部署。静态映射不是动态鉴权或渗透验收。完整方法名、HTTP 方法、路径、控制器、1 基行号、源码 hash 与直接 guard 调用保存在 [JSON 清单](api-inventory.json)。

| 项目 | 数量 |
| --- | ---: |
| Controller | 25 |
| 方法级映射注解 / 展开路由 | 178 / 178 |

## 使用边界

- 核对公开、登录、发布者、ADMIN 与内部 token 时，同时读取统一过滤器、Controller、服务和 nginx；`directGuardCalls` 与 `directGuards` 仅记录直接调用，不证明授权完整。`line` 为映射注解行，`methodLine` 为方法声明行，均从 1 开始。
- fully qualified Mapping、无参数映射和类/方法组合均计入；当前源码均为字面路径，未来表达式、常量、继承或自定义组合注解须改用编译后 Spring 映射提取。
- 本轮新增 `POST /api/admin/resource-hub/transfers/run`，方法 `runCloudDriveTransfers` 直接调用 `authHelper.requireAdmin`，静态标记为 ADMIN；百度和未知 provider 由该入口拒绝。真实管理员请求、转存和发布仍需独立验收。
- 当前内部 AI 心跳、租约与 CatalogResearchController 映射一并纳入；非 Spring 的 source、publisher 与宿主 CLI 不在此范围，契约见 [API](../api.md)。
- 只读 GET 也须按代码核对副作用；写路径及 runNow=false 不能用探活自动执行。
- 更新接口时同步 JSON 和本页，验证全部方法级映射计数、唯一路由、方法名、行号与源码 hash；历史版本在 [旧清单](../history/README.md#git-history)。

## 控制器索引

| 控制器 | 路由注解数 | 源码 |
| --- | ---: | --- |
| AdminCommentController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminCommentController.java) |
| AdminMovieController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminMovieController.java) |
| AdminResourceReportController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminResourceReportController.java) |
| AiAssistantController | 8 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AiAssistantController.java) |
| AuthController | 12 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AuthController.java) |
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
| ResourceHubAdminController | 30 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java) |
| ResourceHubInternalController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java) |
| ResourceLinkController | 20 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java) |
| SocialPublishingAdminController | 14 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java) |
| SysConfigController | 3 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SysConfigController.java) |
| UserManagementController | 7 | [源文件](../../backend/src/main/java/com/gying/movie/controller/UserManagementController.java) |
| WebResourceSearchController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/WebResourceSearchController.java) |
