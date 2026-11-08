# 网盘工作流统一配置交付

2026-11-08（Asia/Shanghai），开发分支 `codex/cloud-drive-workflows`，基线 `3e09a8030778b3c23965fb2fc8d59cc95b9b4ef1`。用户选择本轮先实现统一配置与百度扩展入口。[配置及后续接入](../../cloud-drive-workflows.md)。

## 实现范围

- Resource Hub 新“网盘工作流”页签，夸克 / 迅雷独立配置发现、转存、生成自有分享、GYING 发布、保存目录和手动批次；每盘独立提交，保留其他网盘与元数据 / 周榜草稿。
- `config.driveWorkflows` 和 provider 目录；配置使用已有 `sys_config` 标量键，保留环境及旧夸克批次兼容，凭据不回显、不新增百度凭据保存。
- `POST /api/admin/resource-hub/transfers/run` 按网盘执行手动批次；严格鉴权与 JSON 类型检查。百度 / 未知网盘不能进入夸克默认分支或发布来源 URL。
- 发现 / 周榜跳过关闭阶段而不误标预告；关闭转存的重试不创建 / 重置旧任务；关闭分享的修复不清空旧分享；未就绪自有分享不能入库，已存在自有分享仍可入库。
- 普通 Worker 未恢复旧网盘或发布队列；已有任务目录、历史状态与独立上游追更保留。未执行真实转存、GYING 发布、账号登录或凭据轮换。

## 验证

后端最终全量 1,038 项：1,037 通过、0 失败 / 错误、1 项 Redis 环境跳过；必需真实 MySQL 用例和独立 Java17 `mvn -q -DskipTests compile` 通过。最终允许列表源码全部 hash 对照一致，两临时 fixture 已清理。[后端证据](backend-verification.json)保留此前测试导入和 Jackson 只读字段兼容失败，修复后已完整重跑。

前端 28 项真实 React 离线测试、TypeScript、相关 ESLint 及 `npm run build -- --webpack` 通过。隔离生产构建路径 `D:/gying-cloud-drive-build-20261108-n911hcea`，无生产 `.env`，SSR 使用只读 loopback fixture。

最新生产 Next / React / AntD 资源配合 mock API，完成中英 × 明暗 × 390 / 1280 八组浏览器检查：全部通过，浏览器 / 水合错误、外部请求和页面溢出均为 0。验证单盘保存、保存失败保留草稿、跨盘响应乱序、百度真实空值、禁止百度保存 / 转存、Switch 标签与按钮高度。该结果不代表真实管理员登录或生产配置写入。

前端原始证据与截图位于 `C:/Users/ASUS/.codex/visualizations/2026/11/08/01a1bb5c-cb39-7832-9b3a-af6825e4686a/cloud-drive-workflows/`：`build-result.json`、`browser-qa.json`、`source-manifest.json`、`zh-1280-light.png`、`zh-390-light.png`。最新源码 / public hash 已对照仓库；早先跨盘 junction 构建失败和百度空值修复前候选保留，不计最终通过。

API 静态清单更新为 25 个 Controller / 178 条唯一路由，核对方法、1 基行号、直接 guard 与源码 hash；不是动态鉴权或生产渗透验收。

## 运维与上线状态

只读基线于 21:34:25（Asia/Shanghai）取得。就绪检查 11 PASS / 1 WARN / 0 FAIL，WARN 为初始 schema 未包含两张迁移创建的 QQ 日志表。实际安全检查 58 PASS / 11 FAIL / 1 UNKNOWN，G01–G11 未关闭，UNKNOWN 为已退出的旧迅雷探针身份。

用户随后明确“可部署”，授权本轮仅 backend / frontend 应用替换，现有 G01–G11 风险不称关闭。候选固定、检查点与实际部署验收正在准备；此前未部署状态对应只读基线时点。

按 [gying-project-ops](../../../.agents/skills/gying-project-ops/SKILL.md) 的[部署与恢复](../../../.agents/skills/gying-project-ops/references/deployment-recovery.md)规则，门禁 FAIL 时停止生产替换；历史业务例外不延伸本轮。**基线时尚未部署**，也未进行 SQL 迁移或生产配置写入。现役 backend 镜像仍为 `sha256:91dc95395cc5d30d589a3c5bf6c4e6053bb972e91ceb138f45cf78dd81d9f521`，frontend 仍为 `sha256:f16cb8f1440681ef2fd2f3e2d508f7745b8c509bb6e96ce36592e9221291c841`，原 12 / 6 层 Compose 覆盖保留。

本机及经既有代理公网的首页 / 片库读取通过；源站匿名管理 401、nginx internal / QQ 404，公网管理入口跳到 Access 登录页。这是旧版本入口基线，不是新功能上线验收。保留旧固定镜像及当前数据库 / 状态；上线仍需本次门禁、备份认证和在途核对。回退前处理旧版本忽略新阶段开关的行为，不覆盖 SQL 或重放队列。

本轮修改文件的密钥扫描无新增命中；全工作树扫描仍有两项基线命中，位于 11 月 4 日两份历史部署证据的第 68 行，均与本轮基线一致且未修改。本轮不处理或提交这些历史文件。
