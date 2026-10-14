# 访问日志原始 IP / User-Agent（2026-10-14）

## 范围与当前阶段

用户要求保存原始 IP/UA，并在知悉既有安全检查为 59 PASS / 11 FAIL 后，明确批准只重建相关服务。已于 2026-10-14 20:53（Asia/Shanghai）完成生产切换与后续验收，业务源码提交 `926e61e`；不据此关闭 G01–G11 或其他独立安全门禁。

仅涉及 backend/frontend 和 site_access_log 两个可空追加字段。nginx 如需刷新上游只做配置检查和平滑重载，不重建；source、publisher、依赖容器、宿主任务、云端策略和凭据不属于替换范围。候选以当前已提交的查询/异步列表修复为基线，不混入工作区未提交的安全工具或其他基础设施改动。

## 行为与边界

- client_ip 保存安全过滤器解析后的 IP：仅显式信任的 nginx 传输对端可提供 X-Forwarded-For，其余请求使用直连地址。不直接读取客户端自带的 CF-Connecting-IP/X-Real-IP。
- 生产 APP_TRUSTED_PROXIES 需使用本轮核验的 nginx 对端 /32（IPv6 为 /128）；不信任整个共享 Docker 网段，不使用 0.0.0.0/0。nginx 或网络重建后必须重新核验。空配置会记录代理地址，而不是访客地址。
- user_agent 保留收到的头值（最多 1024 个 Unicode 字符，不截断代理对）；缺失头为 NULL。UA 是客户端自报信息，不能单独证明个人、账号或设备身份。
- 保留已有哈希用于统计；不记录 Cookie、Authorization、请求体或 URL 查询参数。既有日志保留策略不变；旧哈希无法还原，不回填历史 IP/UA。
- 分页和旧版多流日志接口均支持 IP/UA 查询，仅 ADMIN 可读取并设置 Cache-Control: no-store。前端只作文本展示，手机可展开 UA；旧记录显示破折号。

## 迁移与回退

1. 核对 MySQL 版本、InnoDB/行格式、现有字段规格，以及 site_access_log 旧记录边界；先完成加密备份、清单/hash 和完整认证解密。
2. 用已有授权维护身份执行 migration_access_log_client_identity.sql，不给应用账号增加 DDL 权限。两个字段在表末追加，要求 ALGORITHM=INSTANT；不重建表，不更新旧数据。
3. 先迁移后切换 backend/frontend。保持原环境/端口/卷/网络及镜像运行身份，唯一预期配置差异为 backend 的显式可信 nginx 对端。
4. 固定旧镜像和原运行参数。回退前核对发布/在途副作用守护；保留新列、新日志及 utf8mb4_bin，不 DROP COLUMN、不恢复旧 dump、不重放发布/转存。

## 实现与隔离证据

- 后端最终全量：614 项，613 通过、1 项独立 Redis 集成因依赖未启用跳过，0 失败/异常；包含 8 项原始身份专项、7 项监控真实 MySQL 测试与查询边界 SQL 回归。额外 mvn -q -DskipTests compile 通过。
- 后端 314/314、前端 71/71 源文件与验收副本 SHA-256 一致；MySQL/Maven 临时容器已精确清理，未挂生产卷或连接生产网络。
- 前端 typegen、TypeScript、lint、生产构建通过；监控模型 12 项、fixture 守护 7 项通过；隔离 Edge 浏览器 19/19 场景通过，覆盖中英/明暗/360–1440px、IPv6、UA 搜索、Unicode、历史 NULL、HTML 注入不执行和无横向溢出。
- 浏览器使用隔离构建，所有 API mock、外部请求阻断，SSR 指向不可连接测试地址；不是生产管理员登录或手机真机验收。
- 本轮安全只读扫描时间 2026-10-14 20:09（Asia/Shanghai）：59 PASS / 11 FAIL / 0 UNKNOWN，属于已披露的业务例外，不写成安全加固完成。

## 生产交付证据

- 切换窗口 20:52:17–20:53:01（Asia/Shanghai）。仅停止/替换 backend、frontend；nginx 只执行配置检查和平滑重载。9 个其他容器（含既有诊断容器）ID、镜像、启动与原有重启计数均保持不变。
- 实际镜像：`gying-access-identity-backend:20261014a`、`gying-access-identity-frontend:20261014a`；revision 均为 `926e61e1ee2f8299d1d333c23bc761ad65a330ea`。后端镜像内 JAR SHA-256 为 `ca83d7faa24c50c950248bfc92fca79a6bcd1c68f33cdc8811bbbdb215d3ed12`，318 个 class/resource 与已测编译产物逐字节一致。
- 生产覆盖文件：`E:/gying-tools/releases/access-identity-20261014/deploy.json`。实际环境只增加 backend 的 `APP_TRUSTED_PROXIES`；保留其余环境、端口、卷、网络、运行 UID（10001/1001）与安全参数。nginx 传输对端用旧日志指纹预先证明，采用单地址 /32，未信任整个共享网段。
- 加密检查点：`G:/gying-backups/20261014T125228.430856Z`，8/8 产物 hash 和完整认证解密通过（MySQL、backend 数据/日志卷、环境/Compose/切换配置与运行参数）。只冻结 backend 写入，不宣称完成跨服务灾难恢复。
- MySQL 8.0.28/InnoDB Dynamic：两个可空字段以 INSTANT 追加，80,467 条旧记录的全部原字段内容 SHA-256 不变，旧身份字段全为 NULL；没有数据回填、删除或应用账号授权变更。
- 新镜像监控 19/19、热门榜/通知 10/10 浏览器场景通过；生产 nginx 上监控再次 19/19。浏览器 API 均 mock，不使用真实管理员会话或执行通知写入；热门榜/通知的生产调用被高端口安全守护拒绝，未绕过，不能声称其生产浏览器 10 项已执行。
- 真实公网同一 TLS 连接的 Cloudflare trace 与 API 日志 IP 一致，UA 完整回读，路径/Referer 查询参数未存储；直接 backend 请求自带 X-Forwarded-For/CF-Connecting-IP/X-Real-IP 不能伪造记录。公网伪造 CF 头探针被边缘 403 拒绝，而不是强求它穿透到应用。
- 10 项本机/公网入口及边界检查通过：真实 API/首页 200，本机匿名管理日志 401，公网管理页/管理日志由 Access 返回 302，内部路径 404。backend→source/publisher 健康与 frontend→backend 实际读取通过；20:53–21:01 后端日志无 ERROR/Exception。
- 截至 21:06，迁移后新增 API 日志 506 条，506 条含 IP/UA；其中 331 条 4xx 全部有原始身份字段。此为该观察时点快照，缺失 User-Agent 的将来请求仍应保存 NULL，不能伪造 UA。
- 20:59 安全扫描仍为 59 PASS / 11 FAIL / 0 UNKNOWN；已披露的例外并未关闭 G01–G11。发布日志/目标配置和在途守护复验通过，保留既有陈旧 RUNNING，未人工触发真实发帖、转存或任务重放。
- 回退文件 `E:/gying-tools/releases/access-identity-20261014/rollback.json` 固定旧镜像；执行前重跑回退/在途守护并核对最新业务结果。应用回退保留新增列和日志，不恢复旧 dump。
- 专用测试容器与网络按标签/ID 清理，旧 18080 隔离前端已确认归属后停止；源码/日志/截图证据和回滚镜像保留。代码已推送，原始客户端日志与敏感值不进入 Git。

验收材料与可复核工具位于 `E:/gying-tools/releases/access-identity-20261014/`（受限 ACL），包含 `candidate-images.json`、`migration-result.json`、`live-identity-verification.json`、`runtime-verification.json`、`runtime-chains.json`、`production-browser.json`、`new-log-coverage.json`、`security-after.json` 与 `evidence/`。
