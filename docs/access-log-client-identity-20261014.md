# 访问日志原始 IP / User-Agent（2026-10-14）

## 范围与当前阶段

用户要求保存原始 IP/UA，并在知悉既有安全检查为 59 PASS / 11 FAIL 后，明确批准只重建相关服务。当前为代码与隔离验收完成、生产切换待执行；不据此关闭 G01–G11 或其他独立安全门禁。

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

待候选镜像、备份、字段迁移、实际可信代理链、本机/公网入口和容器差异核验完成后更新。原始日志和敏感值不进入本文档或 Git。
