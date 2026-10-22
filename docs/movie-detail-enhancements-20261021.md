# 详情页相关推荐、资源子标签与 AI 新标签页

2026-10-21已部署，483bf29；[完整旧验收](history/snapshots/20261022/docs/movie-detail-enhancements-20261021.md)、[现状](current-project-status.md)。

## 实现范围

AI与详情推荐链接target=_blank/rel=noopener noreferrer保原对话。公开GET /api/movies/{id}/recommendations默认8/1–12，内容/系列/多样性，排自身/停用/软删，空态/失败重试；缓存60秒/最多5万，不调用模型。MAGNET/TORRENT子标签各精确计数0/>99，网盘种子仍TORRENT，默认有内容类型；游客/中英/明暗/手机桌面。

## 验收

后端659（638通过/21skip），隔离MySQL6/6、compile；前端typegen/tsc/lint/build（7旧warning）。本机公网真实入口30项/边界、各8组页面；tmdb_movie_557实际8相关推荐/磁力2/种子1与API一致，AI身份/API fixture仅验证新标签/对话，不代真实账号个性化。候选/资源hash一致，无真实转存/外发。

## 部署与回退

两服务/9其他不变、nginx重载；backend sha256:8555dfc569644f7b03d5d597fec3a7b59145c70fe9b1cfa8eac7e23d3384028e，frontend sha256:97bf2757d4d08057551dcb1a0d7c8980297adcec29da41afcc58fdd032e0f066。检查点 `G:/gying-backups/20261021T131013.780587Z` 七age认证/hash，应用回退非灾备；`E:/gying-tools/releases/movie-related-tabs-20261021` 的rollback-deploy/旧镜像保留，审新写入后两应用/nginx，不SQL恢复。安全59/11/0原项未改；当前特征搜索版后续叠加见现状。
