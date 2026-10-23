# GYING 手动资源补齐候选验收

验收日期：2026-10-23，Asia/Shanghai。当前交付为代码和后端候选包，未部署生产。

“确保资源发布”与按影片/资源 ID 的“修复并补齐”共用 `ensureMovieResource`。每个影片先同步磁力/公开种子元数据，再分别完成夸克与迅雷视频资源；同一影片的多个资源 ID 只执行一次。既有分享只有检查确认有效才跳过，确认失效则优先复用已保存目录、更新原记录及上游分享；检查器无法确定时返回 `CHECK_REQUIRED`，保留既有状态，停止该盘的继续转存/发布。缺少候选及部分失败分别报告，不计为批量完整成功。

该统一入口适用于 GYING 页面的 ensure 与 repair-by-ids。既有全账号批量失效修复、周榜入口仍使用各自范围和调度策略。未修改 SQL 架构、生产配置或自动计划；未执行真实网盘转存、上游发布或群消息。P2P 同步沿用既有幂等存储及小文件归档链路。

页面修复结果的正常/未知计数改为使用后端返回值，逐资源结果保留分享状态与修复结果。

## 验证证据

- 独立 MySQL、无网络出口/无宿主端口/无生产配置挂载：706 项，705 通过、1 项 Redis 依赖跳过、0 失败/错误；资源工作流 58/58，真实查询 MySQL 7/7、监控 MySQL 7/7。单独 `mvn -q -DskipTests compile` 通过；测试源码 SHA-256 与工作树一致；本次两个 fixture 容器已清理。
- 前端 `tsc --noEmit` 和目标页面 ESLint 通过。独立 webpack 生产构建未通过：Windows 提交内存接近上限，SWC 装载报告分页文件不足，部分 Node 进程发生内存分配失败；限制堆内存和单 CPU 重试仍不能完成，已停止本次构建。此前遥测配置出现 EXDEV，关闭本次遥测后越过该错误。不能把类型检查算作生产构建成功。
- 后端候选 JAR 打包通过，344 个类/资源逐字节匹配测试编译产物；包 SHA-256 `aa34016a48e8aedfbf9fffa7e1deec9e462d051928d19977c97edbb8bdf90490`。构建容器无网络/非 root，已清理。
- 后端候选镜像 `gying-manual-completion-backend:20261023` 为 `sha256:89f127f9e023c966d02422202ce9cdf18755afe046b5203b2cef3ea33de386f3`，镜像内 JAR SHA-256 与上述候选包一致；仅构建，未启动应用或替换生产。
- 工作区密钥扫描无发现，diff 空白检查通过。
- 现役本机及公网 `/api/movies/list` 为 JSON 200；源站匿名管理接口 401；公网管理接口跳转 Cloudflare Access 登录页。此为旧版本存活/入口保护检查，不是新逻辑的线上验收。

## 部署状态与后续

2026-10-23 20:18 安全复检为 59 PASS / 11 FAIL / 0 UNKNOWN，G01–G11 均保留：Quark/MinIO 暴露端口、五依赖缺 NNP、三依赖有 root 进程及 Redis 网络隔离。按照根目录 AGENTS.md 与 [gying-project-ops 部署流程](../.agents/skills/gying-project-ops/references/deployment-recovery.md)“门禁 FAIL、测试失败或目标不清时停止相应动作并汇报”，停止生产替换。前序 AI 候选例外批准不能用于本次候选。

候选包、构建日志、生产容器/镜像/挂载基线与入口检查存于 `E:/gying-tools/releases/gying-manual-completion-20261023/`；测试完整日志及 fixture 证据在 `tmp/query-boundaries-mysql-9c106576b4a3/`。生产 backend 保持 `sha256:81d6bd4c41161cab085c1732975e245a2613919cba172139a26d0334b499dd15`，frontend 保持 `sha256:6066320686beb3ae02e07a9f44418527b0bf91bcad44857c2affff800be7e6c1`；没有执行替换、配置变更或新数据迁移，前序回退材料保留。

后续须完成前端完整构建，并满足本候选部署门禁；实际替换前重新做受影响 backend/frontend 的备份、在途检查、固定镜像和真实入口验收。真实双盘分享与上游发布仍需另有明确影片/资源范围。

[机器摘要](evaluation/gying-manual-completion-20261023/validation-summary.json)
