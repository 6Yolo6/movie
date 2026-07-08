# 当前项目状态

更新时间：2026-07-08

## 当前项目目标

项目目标是在现有 GYing Movie 站点上接入 Resource Hub，让系统可以自动发现影视资源、转存到自己的夸克网盘、创建自己的分享链接，并把影视信息和可用资源链接沉淀回现有数据库。

核心链路：

1. 本站优先查 `movie_metadata` 和 `resource_link`。
2. 本地没有可用资源时，通过 TMDB 补影视元数据和热门待采集任务。
3. 通过 PanSou 搜索资源。
4. 通过 quark-auto-save 转存资源。
5. 通过 Quark share API 创建自己的分享链接。
6. 将最终可用链接写回 `resource_link`。
7. 前台、后台、QQ群机器人和腾讯频道发帖共用同一套资源编排能力。

## 已完成部分

- Resource Hub 后端主链路已接入：TMDB 同步、PanSou 搜索、quark-auto-save 转存、Quark 分享、发布到 `resource_link`。
- 后台 Resource Hub 页面已加入监控和配置入口，支持 TMDB 采集频率、采集条数和手动采集。
- TMDB 热门数据已和站内收藏热度拆开：
  - `movie_metadata.popularity` 保持为站内收藏数。
  - `movie_metadata.tmdb_popularity` 保存 TMDB 热度。
  - 前台 TMDB 热门使用独立接口。
- TMDB 图片已改成本地化存储思路，避免前端直接加载 `host.docker.internal` 或 TMDB 原图。
- TMDB TV 元数据匹配已加强，会优先匹配已有 `series_name`、标题和别名，减少“达顿牧场”和“达顿牧场第一季”这类重复。
- 手动单片资源搜索已支持输入片名，不再要求用户知道 `movie_id`。
- 采集不到资源的 TMDB 影片会按预告状态处理。
- 前端已补充 Resource Hub 页面和搜索页部分中英文 i18n。
- QQ 群机器人后端链路已具备基础能力：群消息进来后先查本地资源，没有则触发 Resource Hub 搜索、转存、分享，再回复用户。
- 腾讯频道 CLI 已安装并完成授权。
- 腾讯频道已创建“电影”和“电视剧”两个版块。
- 腾讯频道发帖脚本已存在，后续资源帖格式为三段：标题、链接、简介。

## 未结束任务

- QQ 群机器人还需要做真实群聊端到端联调：
  - NapCat 事件上报到后端。
  - 群里 @机器人或命令搜索影片。
  - 本地命中资源时直接回复。
  - 未命中时触发搜索、转存、分享，并在完成后回发结果。
- 腾讯频道自动资源帖还需要接入业务自动化：
  - 按影片类型选择“电影”或“电视剧”版块。
  - 从最新已发布资源中取未发过的链接。
  - 成功发帖后记录已发布 `resource_link.id`，避免重复发。
  - 后续如 OpenClaw CLI 可用，再评估是否替换或补充当前 `tencent-channel-cli` 路径。
- 历史数据还需要持续清理：
  - 合并 TMDB 采集生成的重复影视条目。
  - 迁移或归并重复条目的资源、任务和发现结果。
  - 不直接删除历史数据，优先软停用或迁移到 canonical 影片。
- 豆瓣评分暂未接入，当前没有可靠公开官方 API；不要依赖非官方接口作为稳定生产能力。
- 已发布到腾讯频道的乱码测试帖是一次错误验证产物，后续可以手动删除或保留为测试记录。

## 关键架构决策

- 不替换原有片库模型，继续以 `movie_metadata` 为影视主表，以 `resource_link` 为可用资源链接表。
- Resource Hub 采集结果先进入发现、转存、发布三个阶段，不直接把外部搜索结果写成可用资源。
- 自动采集资源必须创建“我的夸克分享链接”后再发布，避免把第三方原始链接直接暴露为本站资源。
- `movie_metadata.id` 仍兼容原爬虫片库 ID；TMDB 新数据使用合成 ID。后续如要引入自增主键，需要单独设计迁移，不能直接改现有主键。
- 热度语义拆分：
  - 站内热度来自用户收藏。
  - TMDB 热度只用于外部热门采集和 TMDB 热门展示。
- Docker 不默认新建 Redis、PanSou、quark-auto-save 等依赖容器；优先连接用户已有容器，避免端口冲突。
- 敏感信息不提交进仓库。TMDB Key、Quark cookie、QQ/OpenClaw token、NapCat token 只放环境变量或本机配置。
- 腾讯频道当前优先使用 `tencent-channel-cli`，因为本机没有可用 OpenClaw CLI；npm 上的 `openclaw` 包是占位包。

## 已踩坑清单

- 不要重复启动 PanSou、Redis、quark-auto-save 容器；用户已有容器在运行，重复启动会端口占用。
- quark-auto-save 的 WebUI cookie 和配置文件可能被覆盖；用户也提供了环境变量兜底，后续排查时要先检查容器内最终配置。
- quark-auto-save token 只是 WebUI/API token，不等于夸克账号 cookie。
- TMDB API Key 应放 `.env`，不要写入代码或提交。
- `movie_metadata.popularity` 不能再写 TMDB 热度，否则会污染原首页热门逻辑。
- TMDB poster URL 不应返回 `host.docker.internal` 给浏览器，浏览器访问会失败；应使用前端可访问的本地/MinIO URL。
- 同一部影片不要因为多条发现结果创建多个自己的分享链接；除非旧分享失效或违规，否则一次分享即可复用。
- 新 TMDB TV 数据要尽量匹配已有剧集名、季名和别名，不要轻易新增一条 `tmdb_*` 重复影片。
- PanSou 搜不到资源通常说明流媒体资源还未出现，此时影片应标记为预告状态，而不是反复无限重试。
- PowerShell 通过 stdin JSON 调用 `tencent-channel-cli` 发送中文会出现乱码；后续发帖脚本应走命令参数或确保 UTF-8 输入，不再用普通 PowerShell 管道传中文 JSON。
- 已经出现乱码的帖子不会因为脚本修复自动恢复，需要重新发布或手动删除旧帖。
- 文档和脚本如果出现连续异常汉字或问号乱码，说明文件内容可能已经 mojibake，需要按原意重写为 UTF-8。
- 在脏工作区提交时，只 stage 当前任务相关文件；`crawler/gying_crawler.py` 目前是已有未提交修改，不要顺手带入无关提交。
