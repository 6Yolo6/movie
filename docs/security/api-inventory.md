# API 安全清单

2026-10-22 按当前源码 dc13094 重新整理；静态映射不是动态鉴权/渗透验收。完整可查的方法、路径、控制器、行号、源码 hash 与直接 guard 调用保存在 [JSON 清单](api-inventory.json)。

| 项目 | 数量 |
| --- | ---: |
| Controller | 24 |
| 方法级映射注解 / 展开路由 | 172 / 172 |

## 使用边界

- 核对公开/登录/发布者/ADMIN/内部 token 时，同时读取统一过滤器、Controller、服务和 nginx；JSON 的 directGuardCalls 仅可见直接调用，不证明授权完整。
- fully qualified Mapping、无参数映射和类/方法组合均计入；当前源码均为字面路径，未来表达式/常量/继承/自定义组合注解须改用编译后 Spring 映射提取。
- daily-recommendation/run 与 natural-search 已纳入；非 Spring 的 source/publisher/宿主 CLI 不在此范围，契约见 [API](../api.md)。
- 只读 GET 也须按代码核对副作用；写路径及 runNow=false 不能用探活自动执行。
- 更新接口时同步 JSON 和本页，验证全部方法级映射计数、唯一路由及行号；历史版本在 [旧清单](../history/snapshots/20261022/docs/security/api-inventory.md)。

## 控制器索引

| 控制器 | 路由注解数 | 源码 |
| --- | ---: | --- |
| AdminCommentController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminCommentController.java) |
| AdminMovieController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminMovieController.java) |
| AdminResourceReportController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AdminResourceReportController.java) |
| AiAssistantController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AiAssistantController.java) |
| AuthController | 12 | [源文件](../../backend/src/main/java/com/gying/movie/controller/AuthController.java) |
| CaptchaController | 1 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CaptchaController.java) |
| CatalogAiController | 4 | [源文件](../../backend/src/main/java/com/gying/movie/controller/CatalogAiController.java) |
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
| ResourceHubAdminController | 28 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java) |
| ResourceHubInternalController | 6 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java) |
| ResourceLinkController | 20 | [源文件](../../backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java) |
| SocialPublishingAdminController | 14 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java) |
| SysConfigController | 3 | [源文件](../../backend/src/main/java/com/gying/movie/controller/SysConfigController.java) |
| UserManagementController | 7 | [源文件](../../backend/src/main/java/com/gying/movie/controller/UserManagementController.java) |
| WebResourceSearchController | 2 | [源文件](../../backend/src/main/java/com/gying/movie/controller/WebResourceSearchController.java) |
