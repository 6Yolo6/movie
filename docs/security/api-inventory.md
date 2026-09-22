# API 安全清单

生成日期：2026-09-20。基于当前 Controller 映射的静态清单；不等同于逐接口动态渗透测试。所有 `/api/**`（包括编码后的等价路径）先经过统一限流和参数边界；OPTIONS 仅用于 CORS 预检。业务授权仍由各 Controller/服务端执行。

| 方法 | 路径 | 授权边界 | 来源 |
| --- | --- | --- | --- |
| GET | `/api/admin/comments` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminCommentController.java:29` |
| PUT | `/api/admin/comments/{id}/status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminCommentController.java:69` |
| GET | `/api/admin/movies` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:54` |
| GET | `/api/admin/movies/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:73` |
| POST | `/api/admin/movies` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:83` |
| PUT | `/api/admin/movies/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:105` |
| POST | `/api/admin/movies/{id}/poster` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:128` |
| DELETE | `/api/admin/movies/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminMovieController.java:149` |
| GET | `/api/admin/resource-reports` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminResourceReportController.java:59` |
| PUT | `/api/admin/resource-reports/{id}/status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/AdminResourceReportController.java:89` |
| POST | `/api/auth/login` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/AuthController.java:41` |
| GET | `/api/auth/devices` | requireUser | `backend/src/main/java/com/gying/movie/controller/AuthController.java:54` |
| DELETE | `/api/auth/devices/{id}` | requireUser | `backend/src/main/java/com/gying/movie/controller/AuthController.java:61` |
| GET | `/api/auth/registration-policy` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/AuthController.java:67` |
| POST | `/api/auth/email-code` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/AuthController.java:72` |
| POST | `/api/auth/register` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/AuthController.java:88` |
| POST | `/api/auth/reset-password` | requireUser | `backend/src/main/java/com/gying/movie/controller/AuthController.java:98` |
| GET | `/api/auth/me` | requireUser | `backend/src/main/java/com/gying/movie/controller/AuthController.java:110` |
| GET | `/api/captcha/generate` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/CaptchaController.java:22` |
| POST | `/api/comments` | requireUser | `backend/src/main/java/com/gying/movie/controller/CommentController.java:51` |
| GET | `/api/comments/{relateId}` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/CommentController.java:103` |
| POST | `/api/comments/{id}/upvote` | requireUser | `backend/src/main/java/com/gying/movie/controller/CommentController.java:116` |
| DELETE | `/api/comments/{id}` | requireUser | `backend/src/main/java/com/gying/movie/controller/CommentController.java:146` |
| POST | `/api/favorites/toggle` | requireUser | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:50` |
| GET | `/api/favorites/check` | requireUser | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:109` |
| GET | `/api/favorites/count` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:132` |
| GET | `/api/favorites/hot` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:143` |
| GET | `/api/favorites/tmdb-hot` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:205` |
| GET | `/api/favorites/mine` | requireUser | `backend/src/main/java/com/gying/movie/controller/FavoriteController.java:232` |
| POST | `/api/admin/gying-source/ingest` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:67` |
| POST | `/api/admin/gying-source/resources/{resourceId}/publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:83` |
| POST | `/api/admin/gying-source/resources/{resourceId}/update` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:95` |
| GET | `/api/admin/gying-source/candidates/recent` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:110` |
| GET | `/api/admin/gying-source/candidates/trailers` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:118` |
| GET | `/api/admin/gying-source/candidates/catalog` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:126` |
| POST | `/api/admin/gying-source/catalog/ensure` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:137` |
| POST | `/api/admin/gying-source/recent/ensure` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:150` |
| POST | `/api/admin/gying-source/movies/{movieId}/seasons/ensure` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:160` |
| POST | `/api/admin/gying-source/movies/{movieId}/poster/repair` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:171` |
| POST | `/api/admin/gying-source/posters/repair` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:179` |
| GET | `/api/admin/gying-source/account` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:188` |
| PUT | `/api/admin/gying-source/account` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:195` |
| POST | `/api/admin/gying-source/movies/{typeCode}/{mid}/ensure` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:206` |
| POST | `/api/admin/gying-source/trailers/ensure` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:217` |
| POST | `/api/admin/gying-source/published-resources/check` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:228` |
| POST | `/api/admin/gying-source/published-resources/sync` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:239` |
| POST | `/api/admin/gying-source/published-resources/repair` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:250` |
| POST | `/api/admin/gying-source/published-resources/repair-by-ids` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:261` |
| GET | `/api/admin/gying-source/jobs/{jobId}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/GyingSourceAdminController.java:271` |
| GET | `/api/invitations` | requireUser | `backend/src/main/java/com/gying/movie/controller/InvitationController.java:35` |
| POST | `/api/invitations` | requireUser | `backend/src/main/java/com/gying/movie/controller/InvitationController.java:45` |
| DELETE | `/api/invitations/{id}` | requireUser | `backend/src/main/java/com/gying/movie/controller/InvitationController.java:61` |
| POST | `/api/monitoring/page-view` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MonitoringController.java:14` |
| POST | `/api/monitoring/resource-operation` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MonitoringController.java:15` |
| GET | `/api/admin/monitoring/overview` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/MonitoringController.java:16` |
| GET | `/api/admin/monitoring/logs` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/MonitoringController.java:17` |
| GET | `/api/admin/monitoring/hot-searches` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/MonitoringController.java:18` |
| GET | `/api/movies/list` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MovieController.java:43` |
| GET | `/api/movies/series` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MovieController.java:113` |
| GET | `/api/movies/{id}` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MovieController.java:126` |
| GET | `/api/movies/filters` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/MovieController.java:144` |
| GET | `/api/notifications` | requireUser | `backend/src/main/java/com/gying/movie/controller/NotificationController.java:26` |
| GET | `/api/notifications/unread-count` | requireUser | `backend/src/main/java/com/gying/movie/controller/NotificationController.java:44` |
| PUT | `/api/notifications/{id}/read` | requireUser | `backend/src/main/java/com/gying/movie/controller/NotificationController.java:54` |
| PUT | `/api/notifications/read-all` | requireUser | `backend/src/main/java/com/gying/movie/controller/NotificationController.java:68` |
| GET | `/api/admin/qq-automation/overview` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java:47` |
| GET | `/api/admin/qq-automation/config` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java:64` |
| PUT | `/api/admin/qq-automation/config` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java:71` |
| GET | `/api/admin/qq-automation/bot-searches` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java:87` |
| GET | `/api/admin/qq-automation/channel-posts` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/QqAutomationAdminController.java:108` |
| GET | `/api/qq-bot/health` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/QqBotController.java:32` |
| GET | `/api/qq-bot/search-reply` | service token; never public | `backend/src/main/java/com/gying/movie/controller/QqBotController.java:37` |
| POST | `/api/qq-bot/onebot` | service token; never public | `backend/src/main/java/com/gying/movie/controller/QqBotController.java:53` |
| GET | `/api/admin/resource-hub/overview` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:154` |
| GET | `/api/admin/resource-hub/config` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:186` |
| PUT | `/api/admin/resource-hub/config` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:193` |
| GET | `/api/admin/resource-hub/worker/status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:201` |
| POST | `/api/admin/resource-hub/worker/run-once` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:208` |
| GET | `/api/admin/resource-hub/tasks` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:216` |
| POST | `/api/admin/resource-hub/tmdb/metadata-sync` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:238` |
| POST | `/api/admin/resource-hub/tmdb/metadata-sync/{taskId}/run` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:256` |
| POST | `/api/admin/resource-hub/discover` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:264` |
| GET | `/api/admin/resource-hub/discover/jobs/{jobId}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:280` |
| POST | `/api/admin/resource-hub/discover/{taskId}/run` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:292` |
| GET | `/api/admin/resource-hub/discoveries` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:300` |
| POST | `/api/admin/resource-hub/discoveries/publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:339` |
| POST | `/api/admin/resource-hub/discoveries/{discoveryResultId}/publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:347` |
| POST | `/api/admin/resource-hub/discoveries/{discoveryResultId}/retry-share-publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:355` |
| POST | `/api/admin/resource-hub/discoveries/batch/retry-share-publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:363` |
| POST | `/api/admin/resource-hub/discoveries/retry-discovered` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:371` |
| POST | `/api/admin/resource-hub/discoveries/reconcile` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:378` |
| POST | `/api/admin/resource-hub/discoveries/batch/publish` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:387` |
| POST | `/api/admin/resource-hub/discoveries/{discoveryResultId}/qq-channel-post` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:404` |
| POST | `/api/admin/resource-hub/discoveries/batch/qq-channel-post` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:413` |
| POST | `/api/admin/resource-hub/quark/transfers/submit` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:499` |
| POST | `/api/admin/resource-hub/quark/transfers/{taskId}/submit` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:507` |
| GET | `/api/admin/resource-hub/missing-resources` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:515` |
| POST | `/api/admin/resource-hub/missing-resources/{movieId}/resolve` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:570` |
| POST | `/api/admin/resource-hub/missing-resources/batch/resolve` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:587` |
| POST | `/api/admin/resource-hub/cleanup/duplicate-tmdb` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:1172` |
| POST | `/api/admin/resource-hub/cleanup/mismatched-resources` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceHubAdminController.java:1224` |
| POST | `/api/internal/resource-hub/discoveries/{discoveryResultId}/publish` | requireInternal | `backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java:68` |
| POST | `/api/internal/resource-hub/quark-transfers/{taskId}/run` | requireInternal | `backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java:76` |
| POST | `/api/internal/resource-hub/xunlei-transfers/{taskId}/run` | requireInternal | `backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java:84` |
| GET | `/api/internal/resource-hub/health` | requireInternal | `backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java:92` |
| POST | `/api/internal/resource-hub/ingest` | requireInternal | `backend/src/main/java/com/gying/movie/controller/ResourceHubInternalController.java:104` |
| GET | `/api/resources/form-config` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:137` |
| GET | `/api/resources/bind-candidates` | public / review input bounds | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:147` |
| POST | `/api/resources` | requireResourcePublisher | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:195` |
| POST | `/api/resources/admin` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:287` |
| POST | `/api/resources/{id}/report` | requireUser | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:348` |
| GET | `/api/resources/mine` | requireUser | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:381` |
| PUT | `/api/resources/{id}` | requireResourcePublisher | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:411` |
| DELETE | `/api/resources/{id}` | requireResourcePublisher | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:485` |
| PUT | `/api/resources/{id}/audit` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:505` |
| PUT | `/api/resources/batch/audit` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:526` |
| GET | `/api/resources/admin/all` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:552` |
| PUT | `/api/resources/admin/{id}/link-status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:596` |
| DELETE | `/api/resources/admin/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:616` |
| DELETE | `/api/resources/admin/batch` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:631` |
| POST | `/api/resources/admin/{id}/repair-invalid` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:648` |
| POST | `/api/resources/admin/repair-invalid` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:702` |
| GET | `/api/resources/admin/repair-invalid/jobs/{jobId}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:721` |
| GET | `/api/resources/admin/invalid-checks` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:858` |
| POST | `/api/resources/admin/invalid-checks/scan` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/ResourceLinkController.java:870` |
| GET | `/api/admin/social-publishing/overview` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:58` |
| GET | `/api/admin/social-publishing/qq-accounts` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:78` |
| POST | `/api/admin/social-publishing/qq-accounts/login` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:85` |
| GET | `/api/admin/social-publishing/qq-accounts/{accountKey}/login-status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:96` |
| DELETE | `/api/admin/social-publishing/qq-accounts/{accountKey}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:104` |
| POST | `/api/admin/social-publishing/weibo/login` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:124` |
| GET | `/api/admin/social-publishing/weibo/login-status` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:131` |
| POST | `/api/admin/social-publishing/targets` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:138` |
| PUT | `/api/admin/social-publishing/targets/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:169` |
| DELETE | `/api/admin/social-publishing/targets/{id}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:206` |
| POST | `/api/admin/social-publishing/targets/{id}/publish-next` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:233` |
| POST | `/api/admin/social-publishing/publish-next` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:242` |
| POST | `/api/admin/social-publishing/logs/{id}/retry` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:251` |
| GET | `/api/admin/social-publishing/logs` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SocialPublishingAdminController.java:259` |
| GET | `/api/admin/config` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SysConfigController.java:24` |
| GET | `/api/admin/config/{key}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SysConfigController.java:31` |
| PUT | `/api/admin/config/{key}` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/SysConfigController.java:43` |
| GET | `/api/admin/users` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/UserManagementController.java:30` |
| PUT | `/api/admin/users/{id}/role` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/UserManagementController.java:59` |
| POST | `/api/admin/users/{id}/impersonate` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/UserManagementController.java:89` |
| PUT | `/api/admin/users/{id}/enabled` | ADMIN (central + controller) | `backend/src/main/java/com/gying/movie/controller/UserManagementController.java:115` |
