# 当前项目状态

更新时间：2026-09-03

本文只记录生产环境当前能力、运行约束、待处理事项和少量可复核的验收证据。一次性任务编号、重复部署过程和基础接口状态不在这里长期保留，详细操作以 `docs/api.md`、`docs/deployment.md` 及运维参考文档为准。

## 当前目标

- 维护电影、剧集、动漫和用户提交资源的统一片库。
- 通过 TMDB、GYING、PanSou/Panso API 与网盘自动化服务补全元数据和可用资源。
- 自动转存并生成夸克、迅雷自有分享，经过校验后再写入正式资源库。
- 支持 QQ 群搜索、资源候选选择、频道发布和多平台发布审计。

## 运行架构

- 对外入口：nginx `80/443`，反向代理 Next.js 前端和 Spring Boot 后端。
- 核心服务：`frontend`、`backend`、`gying-source`、`social-publisher`。
- 数据与依赖：MySQL、Redis、MinIO、PanSou、`quark-auto-save`；生产 Compose 文件为 `docker-compose.prod.yml`。
- QQ 集成使用 OpenClaw QQBot；第二个 QQ 账号和微博由 `social-publisher` 独立承载。NapCat 不参与当前生产链路。
- 容器和 JVM 时区统一为 `Asia/Shanghai`。

## 已具备能力

### 片库与治理

- 片库分页、分类筛选、详情、收藏、评论、站内通知和管理员审核。
- 电影、资源链接和来源身份均支持软删除，历史记录保留。
- TMDB 采集支持去重和本地片库匹配；模糊匹配只提供候选，不直接触发转存。
- 管理端支持缺失海报自动补图。GYING 海报下载按 `avif`、`webp`、`jpg`、`png` 回退并重试，只有返回有效 `posterUrl` 才计为成功。

### 影视资源中心

- 资源搜索合并本地 PanSou 与外部 Panso 结果，并按 URL 去重。
- 自动资源必须先转存为系统自有夸克或迅雷分享，再写入 `resource_link`。
- 夸克任务支持目录创建、剧集目录更新、失效分享重试和周转存；`update_subdir: ".*"` 用于递归追踪同名目录新增文件。
- 迅雷任务使用官方 Drive API 校验分享、遍历目录和筛选视频文件；Authorization 为短期凭据，支持运行时更新和 refresh token 优先。
- 失败任务保留状态和错误，重试尽量复用已有转存结果，避免重复调用转存接口。

### GYING 数据源

- 支持按类型和模式搜索、目录同步、元数据导入、资源发布与已发布资源抓取。
- “爬取我已发布资源”按账号 `/my-resources` 分页读取，复用现有片库和资源工作流；已存在的来源 ID 或 URL 自动跳过。
- 数据源请求带有统一间隔限制，图片下载和站点请求均支持超时、重试和失败记录。
- GYING 资源发布仅使用固定契约 `/res/pan/add`，绑定字段为 `binds[0][dir]` 与 `binds[0][id]`。

### QQ 自动化

- 搜索先回复“正在搜索资源，请稍后...”，随后展示影片元数据和资源候选。
- 候选最多 10 条，夸克优先；用户选择单条资源后才创建对应转存任务。
- 夸克或迅雷分享失效时统一提示“该分享已失效，不可访问”，并保留当前候选上下文，用户可继续选择其他序号。
- 搜索、转存、分享和失败结果写入自动化日志，便于管理员审计和重试。

### 多平台发布

- `/admin/automation` 管理 QQ 频道账号、频道目标和微博目标。
- 支持单目标、批量、定时、间隔、模板和失败重试；发布记录保存 `PENDING`、`POSTED`、`FAILED` 及外部地址。
- QQ 和微博使用独立凭据目录或运行时配置，互不覆盖原有机器人环境。

## 配置与安全

- Cookie、Authorization、JWT、refresh token、密码和 API Key 只允许存在于 `.env`、外部服务配置或进程内存，不提交到 Git 或写入业务日志。
- 浏览器登录态不等同于迅雷 Drive API Bearer token；Resource Hub 不读取浏览器 Cookie。收到 401 时更新官方 Authorization 或 refresh token。
- 生产操作前后检查 `git status --short`；涉及数据库或卷的高风险操作必须先备份并保留回滚点。
- 不执行 `docker compose down -v`、删除卷、`DROP`、`TRUNCATE` 或物理删除核心历史数据作为日常维护手段。
- 自动采集只有在生成并校验自有分享后才允许发布，第三方原始链接不得直接写入正式资源。

## 仍需处理

- 迅雷官方凭据仍受短期 Authorization 和账户交互验证影响；需要持续维护 refresh token 或在管理端更新新凭据后再做真实转存验收。
- GYING 图片源和部分外部网盘接口存在偶发超时、风控或响应结构变化，需保留重试和失败审计，不把单次 HTTP 200 视为业务成功。
- 历史遗留的乱码任务、重复目录和无视频分享仍需按资源价值逐批人工确认，优先 dry-run 和软删除。
- 微博自动发布需在凭据更新并完成单条真实发布验收后再开启长期计划任务。
- 豆瓣评分没有稳定官方 API，不作为生产链路的强依赖。

## 验收记录

- 2026-09-03：修复 GYING 自动补图并重建 `gying-source`、`backend`、`nginx`；实测钢铁侠 `vPW8` 写入 `movie_metadata.poster_url=mv/vPW8/384.avif`，MinIO 地址返回 200。
- 2026-08-26：完成生产运维基线复核，Compose 服务、数据库依赖和核心入口可按运维脚本检查；未执行破坏性数据操作。

## 长期不变量

- `movie_metadata` 是影片主表，`resource_link` 是可用资源表，`movie_source_identity` 保存外部来源绑定。
- 资源状态和发布状态必须可追踪；失效修复优先原位更新，避免同片产生多条活动自有分享。
- 自动化只处理明确匹配的影片和资源；不确定匹配必须转为人工候选。
- 删除、迁移和重复清理默认使用 dry-run，并保留可回滚的软删除或迁移记录。

## 常用校验

```powershell
cd backend
mvn test

cd ../frontend
npm run lint
npm run build

cd ..
docker compose -f docker-compose.prod.yml config --quiet
```
