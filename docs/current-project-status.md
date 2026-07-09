# 当前项目状态

更新时间：2026-07-09

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
- 腾讯频道最新资源发帖脚本已支持从数据库取最新未发资源、按电影/电视剧选择版块，并用本地 state 文件记录已发布 `resource_link.id` 避免重复发帖。
- 本机 Docker 已安装并运行 OpenClaw Gateway，端口为 `18789` / `18790` / `3978`，`/healthz` 返回 200。
- OpenClaw 已安装 `@tencent-connect/openclaw-qqbot` 插件并绑定 QQ Bot，当前插件可连接腾讯 QQ Bot WebSocket，日志显示具备“群聊+私信+频道+交互” intents。
- NapCat 已切换到新 QQ `3929013344`，并为该账号补齐 OneBot11 HTTP server 与 HTTP client 配置：
  - HTTP server 监听 `0.0.0.0:3000`，供后端调用 `send_group_msg`。
  - HTTP client 上报到 `http://backend:8880/api/qq-bot/onebot`，token 从 `.env` 注入，不提交到仓库。
- NapCat 扫码登录已完成，`get_login_info` 返回当前账号 `3929013344`。
- NapCat 直连发群已验证，可向 QQ 群 `2166070253` 发送消息。
- 后端 `/api/qq-bot/onebot` 模拟 OneBot 群消息已验证：
  - `/movie test` 可触发“正在搜索”和最终结果回复。
  - `搜 达顿牧场` 可命中“达顿牧场 第一季”并回复影片信息。
- 用户已用非机器人账号在 QQ 群真实验证：`搜 影片`、`/movie 影片`、带 @ 和不带 @ 都能解析。
- QQ 群回复已调整为只展示影片信息和正式资源链接，不再展示“状态”和“我的夸克分享”内部字段。
- 后端已支持可配置回复通道：默认 `napcat`，也可通过 `QQ_BOT_REPLY_PROVIDER=qqbot` 切到官方 QQBot API 回包。
- 本机已配置官方 QQBot 出站并完成后端重启；API 鉴权和 `GROUP_OPENID` 映射已通过，但平台返回 `40034105 主动消息失败, 无权限`，需要在 QQ 开放平台开启/授权群主动消息，或改为官方 QQBot 入站事件携带 `msg_id` 后做被动回复。
- 后端已新增 `/api/qq-bot/search-reply`，只返回搜索回复文本，不主动发消息，供 OpenClaw QQBot 被动回复调用。
- 本机 OpenClaw QQBot 插件已临时补丁：`/movie`、`/search`、`搜`、`找` 会调用后端 `search-reply` 接口，再由 OpenClaw 使用官方 `msg_id` 被动回复。
- 后端 compose 已暴露 `BACKEND_PORT`，当前用于 OpenClaw 容器通过 `host.docker.internal:8880` 调用后端。
- 已新增 `tools/patch-openclaw-qqbot-gying.ps1`，可在 OpenClaw QQBot 插件升级后重新应用资源搜索补丁。
- 已在 OpenClaw 容器内直接验证插件命令处理器：`/movie 人生切割术` 和 `搜 人生切割术` 都能拿到后端中文资源回复。
- OpenClaw QQBot 补丁已扩展到 `gateway.js`，让群聊里 @机器人后的 `搜 影片`、`找 影片` 也进入插件级直接回复路径，不再落入内置 Agent。
- 用户已在真实 QQ 群验证官方机器人 `@机器人 搜 影片` 可以搜索并回复。
- QQ 群机器人回复里的评分前缀已改为纯文本 `豆瓣`，避免星标/emoji 编码异常显示成 `??`。
- QQ 群搜索库内无元数据的影片时，会先创建 `qq_<hash>` 最小占位元数据，再继续触发 PanSou 搜索、转存、分享和发布流程。
- 已用 `复仇者联盟5` 验证 QQ 搜索接口不再直接返回“没有找到影片”，会进入外部搜索并返回候选资源。
- 腾讯频道 CLI 已重新扫码授权，`login status` 显示凭证有效且服务连通正常。
- 腾讯频道版块 ID 已确认：电影 `736142774`，电视剧 `736142775`，全部 `736090076`。

## 未结束任务

- QQ 群机器人还需要做官方机器人真实群聊端到端联调：
  - 后续可把当前脚本补丁升级为私有 OpenClaw 插件或上游配置，减少对 `node_modules` 补丁的依赖。
- 用真实未入库影片再测一次“未命中本地资源 -> PanSou 搜索 -> 转存 -> 分享 -> 入库 -> 回发”的完整异步链路。
  - 后续需要继续优化 PanSou 结果置信度，避免片名相近但不准确的资源被直接回发。
- 腾讯频道自动资源帖还需要接入业务自动化：
  - 跑一次真实自动发布验证。
  - 把 `tools/publish-latest-resource-to-qq-channel.ps1` 接入 Windows Task Scheduler 或其他调度器。
  - 当前仍以 `tencent-channel-cli` / 腾讯频道 community skill 作为“版块帖子”发布路径；OpenClaw QQBot 先用于对话和主动消息，后续若确认可稳定发布频道版块帖，再评估替换。
- OpenClaw QQBot 还需要补充真实群聊验证记录：
  - 用户已验证 `/bot-ping` 和 `搜 影片` 都可回复。
  - 如后续仍要做主动消息，需要捕获 QQ Bot 使用的 `GROUP_OPENID`，因为 OpenClaw 主动消息 target 使用 openid，不直接使用普通 QQ 群号。
  - 评估是否让 OpenClaw 直接接业务 Agent，或仅作为官方 QQ Bot 消息入口，把资源查询转发到后端接口。
- 历史数据还需要持续清理：
  - 合并 TMDB 采集生成的重复影视条目。
  - 合并 QQ 群搜索临时创建的 `qq_<hash>` 占位影片和后续 TMDB 正式影片。
  - 迁移或归并重复条目的资源、任务和发现结果。
  - 不直接删除历史数据，优先软停用或迁移到 canonical 影片。
- 豆瓣评分暂未接入，当前没有可靠公开官方 API；不要依赖非官方接口作为稳定生产能力。
- 已发布到腾讯频道的乱码测试帖是一次错误验证产物，后续可以手动删除或保留为测试记录。

## 关键架构决策

- 不替换原有片库模型，继续以 `movie_metadata` 为影视主表，以 `resource_link` 为可用资源链接表。
- Resource Hub 采集结果先进入发现、转存、发布三个阶段，不直接把外部搜索结果写成可用资源。
- 自动采集资源必须创建“我的夸克分享链接”后再发布，避免把第三方原始链接直接暴露为本站资源。
- QQ 群查询优先读取 `resource_link`；库内已有可用链接时直接回复，不再对已有链接重复转存。只有库内无可用链接时才调用 PanSou 外部搜索并进入转存、分享、发布流程。
- QQ 群入站仍可继续用 NapCat/OneBot 监听，出站回复可配置为 NapCat 或官方 QQBot；官方 QQBot 出站必须使用 `GROUP_OPENID`，不能使用普通 QQ 群号。
- `movie_metadata.id` 仍兼容原爬虫片库 ID；TMDB 新数据使用合成 ID。后续如要引入自增主键，需要单独设计迁移，不能直接改现有主键。
- 热度语义拆分：
  - 站内热度来自用户收藏。
  - TMDB 热度只用于外部热门采集和 TMDB 热门展示。
- Docker 不默认新建 Redis、PanSou、quark-auto-save 等依赖容器；优先连接用户已有容器，避免端口冲突。
- 敏感信息不提交进仓库。TMDB Key、Quark cookie、QQ/OpenClaw token、NapCat token 只放环境变量或本机配置。
- QQ 群聊搜索优先走机器人身份，因为机器人身份更适合自动化、审计和长期运行；NapCat 路径仍可作为已有 QQ 号群聊接入方式。
- 腾讯频道“版块帖子”当前优先使用 `tencent-channel-cli` / 腾讯频道 community skill，因为该路径已验证可创建频道帖子并选择“电影”“电视剧”版块；OpenClaw QQBot 更适合频道消息或群聊消息，暂不把它视为版块帖子发布的唯一通道。

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
- `tencent-channel-cli` 登录凭证会过期；自动发帖真实验证前先跑 `tencent-channel-cli login status --json`，过期则重新扫码授权。
- 文档和脚本如果出现连续异常汉字或问号乱码，说明文件内容可能已经 mojibake，需要按原意重写为 UTF-8。
- OpenClaw Docker 首次启动可能因为 `openclaw.json` 缺 `gateway.mode` 进入重启循环；修复方式是在 `C:\Users\Administrator\.openclaw\openclaw.json` 设置 `gateway.mode=local`、`gateway.bind=lan`，然后强制重建网关容器。
- OpenClaw QQBot 插件会提示 `channelConfigs` manifest 警告和 `contracts.tools` 警告，目前不阻止 QQ Bot WebSocket 连接和消息通道启动；后续若要依赖其 agent tools，需要复查插件版本。
- OpenClaw QQBot 主动发送 target 使用 `qqbot:c2c:OPENID`、`qqbot:group:GROUP_OPENID`、`qqbot:channel:CHANNEL_ID`，不要把普通 QQ 号、普通 QQ 群号或频道短号直接当 target。
- NapCat 每个 QQ 账号有独立配置文件，例如 `onebot11_3929013344.json`；切换账号后旧号的 OneBot11 配置不会自动复用。
- 修改 NapCat OneBot11 配置后需要重启 NapCat，但重启可能触发 QQ 重新手Q验证或扫码，操作前要预期这一步。
- PowerShell 管道向 Linux 容器传中文 JSON 时可能出现编码偏差；测试 OneBot 中文命令时优先使用真实 NapCat 上报，或在手工 payload 中使用 `\u` 转义。
- 当前 OpenClaw QQBot 资源搜索处理器是本机 `node_modules` 补丁，OpenClaw/插件升级可能覆盖；升级后要重新应用或改造成正式插件。
- OpenClaw QQBot 插件原本只把 `/` 开头内容送进 `matchSlashCommand`；`搜 影片` 这类中文文本会进入内置 Agent。当前内置 Agent 配置的 `openai/gpt-5.5` 在本机不可用，会导致无回复，所以资源搜索必须走插件级直接回复快路径。
- PowerShell 脚本向 JS 文件写入中文正则可能 mojibake；补丁脚本里插入 JS 的中文逻辑要用 `\u` 转义，避免插件启动时报 `Invalid regular expression`。
- QQ Bot markdown 对部分 emoji/特殊符号不稳定；机器人业务回复尽量使用纯文本标签，例如评分用 `豆瓣 9.1`，不要用星标符号。
- 在脏工作区提交时，只 stage 当前任务相关文件；不要顺手带入无关提交。
