# GYing Movie 生产安全审计报告（Docker / Windows）

- 审计日期：2026-09-20
- 工作区：`D:\gying-movie\movie`
- 目标架构：Windows + Docker Desktop + Cloudflare Tunnel
- 审计性质：代码、Compose、配置和本机运行态的防御性审计；不是经授权的外部渗透测试
- 最近只读复核：2026-09-21；在线旧容器状态与初始基线一致，安全分支仍未部署。
- 重要状态：本次加固代码位于 `codex/security` 工作区，**尚未部署到生产**。在线容器、Windows MySQL、MinIO、Cloudflare Access/WAF、`.env` 和外部计划任务没有被本次任务自动修改。

## 1. 执行范围

检查了：

1. `docker-compose.prod.yml`、四个应用 Dockerfile、nginx 路由、网络、端口、挂载、用户、capabilities、日志和资源限制；
2. Spring Boot 配置、认证/管理员/资源提交/QQ Bot/API 限流、异常处理和敏感配置加载；
3. MySQL 监听、账号权限、传输安全、`local_infile`、备份能力；
4. Redis 端口、认证、ACL、限流脚本和缓存边界；
5. MinIO 端口、身份使用、匿名策略和对象路径边界；
6. `quark-auto-save` Cookie 的存储、环境变量覆盖和卷权限；
7. OpenClaw/QQ Bot、GYING Source、social-publisher、MCP；
8. Git 工作区和可达历史 Blob 的凭据扫描；
9. npm 依赖、Java 测试、Python 运维工具和隔离 nginx 回归测试。

以下结论中的初始“在线观测”来自 2026-09-20 的本机只读检查，并于 2026-09-21 复核确认；“目标配置”来自本工作区的加固改动，不能混写成已上线事实。

## 2. 在线基线（未加固生产）

| 项目 | 观测结果 | 风险 | 状态 |
| --- | --- | --- | --- |
| nginx | `0.0.0.0`/IPv6 发布宿主机 `80`、`443` | Cloudflare Tunnel 之外仍存在本机所有接口入口；Windows 防火墙是唯一额外边界 | Open，目标改为 `127.0.0.1:80` |
| Spring Boot backend | `0.0.0.0:8880` | 可被同网段/主机策略访问，绕过 nginx、Cloudflare、Access 和统一限流 | Open，目标改为 loopback；OpenClaw 改走 Docker 网络 |
| quark-auto-save | `0.0.0.0:5005` | 配置 UI/账户 Cookie 暴露风险 | Open，目标改为 `127.0.0.1:5005`，并要求 Access/本机管理 |
| MinIO | `0.0.0.0:9000`、`9001` | S3/API 和控制台不应成为公网入口 | Open，需单独迁移到 loopback/内部网络 |
| Redis | 未发现宿主机发布端口 | 在线边界较好；仍需密码/ACL 验证 | 目标已实现，需部署后复核 |
| PanSou | 未发现宿主机发布端口 | 只应在应用网络可达 | 目标已实现，需部署后复核 |
| MySQL Windows 服务 | `3306`、`33060` 监听所有地址；`bind_address=*`、`mysqlx_bind_address=*`；Windows 活跃网络为 Public 且 Public Firewall Disabled | 数据库可能被主机网络暴露；不能仅凭监听结果断言已经从 Internet 可达，但当前边界不符合生产最小暴露 | Critical/Open |
| 应用数据库账号 | 已验证应用会话为 `root@localhost`，具有管理权限；`root@%` 仅观测到 USAGE，不能据此声称拥有完整远程权限 | 应用被攻破后可改权限、结构和全部数据 | High/Open |
| MySQL 传输/密码策略 | `require_secure_transport=OFF`；未返回密码验证插件变量；`local_infile=OFF` | 内网/主机边界被误认为可信，凭据强度和传输保护不足 | High/Open |
| Docker 权限 | 未观测到 Docker socket 挂载或 privileged 生产容器 | 当前未发现容器逃逸捷径 | Pass（仍需部署后复核） |
| GYING Source | 在线 token 为空；旧代码以 `not API_TOKEN or ...` 形式 fail-open | 共享 Docker 网络上的调用者可伪造内部请求 | High；代码已改为 fail-closed，尚未部署 |
| MinIO 身份/匿名策略 | 应用使用 MinIO root identity；匿名 `GetObject` 覆盖全部 `gying/*`，而非仅图片；匿名 listing 未开启 | 误配或应用 SSRF/凭据泄露会扩大对象读取/写入影响 | Critical/High/Open |
| Quark Cookie | `/app/config/quark_config.json` 含 Cookie，观测权限为 `0755`；上游支持 `QUARK_COOKIE` 环境覆盖，但 Cookie 仍会持久化到配置/状态 | 同机低权限用户或错误备份可取得网盘会话 | High/Open |
| Cloudflare Tunnel | `E:\gying-tools\cloudflared\config.yml` 只有 `gyinghub.dpdns.org -> http://127.0.0.1:80` 和 catch-all 404；未检查到 origin Access 校验段；账号侧 Access/WAF 未审计 | 管理路径是否真正经过 Access 未证实；公网探针结果为“不确定”，不能当作已启用 | High/Open |
| Git 历史 | 扫描 1,278 个可达文本 Blob，发现 105 条规则命中（跨版本重复，不等于 105 个唯一凭据）；工作区当前扫描 0 条 | 历史 Cookie、密码、Token、共享管理员 hash 仍可能被克隆得到 | High/Open，必须轮换；历史重写需单独批准 |
| 备份/恢复 | 加密备份工具和模板已加入，但本次未执行真实加密备份、解密恢复或恢复演练 | 无法把“脚本存在”当成可恢复性证据 | High/Open |

### 2.1 不应误报的事实

- `root@%` 的存在不等于它有完整 root 权限；本次只确认到 USAGE，仍不能抵消监听和防火墙风险。
- `secure_file_priv=NULL` 与 `local_infile=OFF` 是限制项；本报告没有把它们误列为开放文件读写。
- 未发现 Docker socket 挂载或 `privileged`，但这不代表所有容器已非 root；在线 `docker top` 的部分结果因权限/命令限制为 UNKNOWN，必须在部署后复核。
- 公网 HTTP 探针曾返回 403 或网络错误；这只能记为“公网接受结果未验证”，不是 Cloudflare Access 已启用的证据。
- MinIO 对象清单尝试超时，未声称已检查全部对象。

## 3. 已实施的代码/配置加固（目标状态）

### 3.1 Docker/网络

- `docker-compose.prod.yml` 显式作为生产 Compose 文件；nginx、backend、quark 的宿主机发布绑定到 `127.0.0.1`；Redis 进入 `internal: true` 的 `cache-net`，不发布端口。
- nginx 以 UID 101 运行，`cap_drop: ALL`、`no-new-privileges`、只读根文件系统和受限 tmpfs；backend/source/social 使用专用非 root UID/用户。
- 集成服务只接收明确的环境变量 allowlist，不再把完整 `.env` 传给 GYING Source/social-publisher。
- 加入 JSON 日志轮转、内存/PID 上限、Dockerfile `.dockerignore`，并移除默认共享凭据。
- NapCat 移入 `legacy-disabled` profile；没有把它当成 QQ 生产依赖。

### 3.2 nginx/Cloudflare 边界

- 内部/QQ Bot/actuator/调试/隐藏文件/私有媒体路径在 nginx 层返回 404；管理员 API 不能通过静态文件或 `.js` 后缀绕过 API 规则。
- API、登录、搜索分别限流；媒体只允许固定前缀和图片扩展，清空客户端 query string，禁止写方法。
- 反向代理丢弃浏览器提供的 `X-Forwarded-For`/`CF-Connecting-IP`，只在审计过的 peer 上启用真实 IP；访问日志不记录 query、Cookie、Authorization 或 body。
- `deploy/cloudflared-config.example.yml` 给出管理路径 Access 校验模板，但必须在 Cloudflare 账号侧创建并验证 Access Application/Policy。

### 3.3 Spring Boot/API

- 生产启动时拒绝 root DB 用户、弱/空凭据、通配 CORS 和缺少内部 token；支持 `configtree:/run/secrets/`。管理员运行时配置接口对 token/cookie/password/secret 类键只返回脱敏值并拒绝直接写入。
- Redis Lua 滑动窗口限流，Redis 故障时 fail closed；统一请求 ID、参数/分页/body 上限和安全事件日志。QQ 机器人运行时配置把每用户限额钳制为至少 1 次/分钟，不能通过 `sys_config` 改成无限制。
- 登录、注册、邮箱、搜索、资源提交、管理员、QQ Bot 和内部服务分别纳入限流/认证边界；QQ Bot token 缺失或错误时拒绝。
- JWT 必须包含会话 `jti`；密码变更撤销设备；异常响应不再回显 SQL、堆栈或异常消息。
- CORS 改为精确 origin、禁用 wildcard credentials；管理员拦截器覆盖历史资源审核路径。

### 3.4 数据与集成

- Redis ACL 默认移除危险命令、禁用持久化并设定 `noeviction`；密码不写入命令行参数。
- MinIO 策略模板区分公开图片前缀与应用读写前缀；应用身份应使用 scoped access key，不再使用 root identity。
- GYING Source 使用恒时 token 比较、请求/响应大小和超时边界；token 缺失即拒绝。
- `quark-auto-save` 使用 `QUARK_COOKIE` 环境覆盖和外部受保护卷；backend fallback 仅在内存中使用，不再 POST Cookie 到上游 `/update`。上游配置仍不是加密存储，仍需 ACL/轮换。
- MCP 默认本地 stdio、只读事务、SQL 形态校验，写操作和 Docker 变更均需要显式开关。

## 4. 风险登记表

| 编号 | 等级 | 风险 | 处理结论 |
| --- | --- | --- | --- |
| C-01 | Critical | MySQL/MinIO 在所有接口监听，且 Windows Public Firewall Disabled；外部可达性未完成证明 | 先收紧 Windows 防火墙和 loopback/内部绑定，再做端口复测 |
| C-02 | Critical | MinIO 使用 root identity，匿名读取覆盖所有 `gying/*` | 创建 scoped key、应用最小策略，公开策略只保留图片扩展并轮换 root key |
| H-01 | High | backend、quark、MinIO 直连宿主机端口绕过 Cloudflare/Access | 部署目标 Compose/MinIO 网络改动；OpenClaw 改 Docker 网络 |
| H-02 | High | 应用以 MySQL root 运行 | 使用 `tools/security/provision_db_accounts.py` dry-run → 预检 → 切换 → 验证；不要删除 root |
| H-03 | High | Cloudflare Access/WAF 账号侧未核验，管理员路径公开接受结果未闭环 | 创建 Access policy，做登录/未登录/服务 token 三态验收 |
| H-04 | High | Git 历史存在凭据命中，历史未重写、凭据未全部轮换 | 立即按 `secret-management.md` 轮换；历史重写另行审批 |
| H-05 | High | Quark Cookie 持久化配置权限过宽 | 停止/备份前提下收紧卷 ACL/文件模式，旋转 Cookie，验证 WebUI 登录 |
| H-06 | High | 加密备份/恢复尚未真实演练 | 配置 age recipient、执行一次备份、异机/临时目录恢复 MySQL 和关键卷 |
| H-07 | High | Docker Scout 已扫描现有 backend 镜像：17 个受影响包、61 个唯一漏洞（13 Critical、48 High；SARIF 共 62 条组件记录）；该镜像仍包含 Spring Boot 3.2.0 依赖，不能代表工作区 3.5.16 目标镜像 | 先不把镜像标记为清洁；按 fixed version 做 triage、锁定 digest，并在目标镜像重建后复扫；隔离无公网服务 |
| M-01 | Medium | MySQL `require_secure_transport=OFF`、密码校验策略未确认 | 先建立证书/连接验证计划，再开启并回归所有连接器 |
| M-02 | Medium | 部分第三方镜像仍使用 `latest`，完整 JVM/Python/容器 CVE 扫描未闭环 | 固定版本/摘要，保留 Docker Scout/依赖扫描结果 |
| M-03 | Medium | MinIO 仍是独立容器，目标 nginx 使用 Docker DNS `minio:9000` | 将现有容器以 alias `minio` 接入 `gying-net`，迁移前备份并验证 |
| M-04 | Medium | OpenClaw 现有配置仍调用 `host.docker.internal:8880` | 部署端口收紧前改为 `http://backend:8880/api/qq-bot/search-reply` 并做真实链路验收 |
| M-05 | Medium | 日志/安全事件已有结构化输出，但尚未接入告警/集中保留 | 配置 Windows/Docker 日志收集和 401/403/429/5xx 告警 |
| L-01 | Low | origin 使用 HTTP，由 Cloudflare 提供公网 TLS | Cloudflare 开启 Always Use HTTPS、HSTS 与严格 origin policy；不直接暴露 origin |
| L-02 | Low | 工作区审计工具不能代替外部渗透、云账号策略审计 | 每季度或重大变更时复核范围和证据 |

## 5. 依赖和镜像扫描

- frontend 和 social-publisher 的 `npm audit` 当前均为 0 vulnerabilities；这只覆盖 npm advisory 数据和声明的 JavaScript 依赖。
- Docker Scout `--only-severity critical,high` 对旧在线镜像和隔离目标镜像的结果并不为零：旧 frontend 镜像 55 条、旧 source 镜像 15 条、旧 social 镜像 176 条；重建后的隔离 frontend 仍有 10 条（含 1 条 critical 的 transitive/bundled package），source 在加入 Debian security upgrade 后降为 1 条未修复 zlib high，social 在加入升级后仍有 58 条，主要来自 Chromium/OS 和 bundled tooling；现有 backend 镜像另有 17 个受影响包、61 个唯一漏洞。
- 这些结果已保存为本次本机临时扫描证据（`tmp/scout-*.sarif`，不提交）；backend 证据文件为 `tmp/scout-backend.sarif`。该文件对应现有镜像依赖集（含 Spring Boot 3.2.0），不是工作区当前 `pom.xml` 的 3.5.16 目标镜像；因此必须在重建目标镜像后重新扫描，不能把 npm audit 0 或代码测试通过写成镜像无 CVE。
- crawler/social Dockerfile 已加入构建时 Debian security upgrade；无修复版本的上游/Chromium/打包依赖仍需持续跟踪，生产部署前按可利用性、运行时路径和 vendor 修复做人工 triage。


### 5.1 Backend Scout 证据摘要（2026-09-20）

以下仅是 `tmp/scout-backend.sarif` 的包名、扫描版本和 Scout 记录的 fixed version 摘要，不是已完成升级的承诺；`not fixed` 表示该扫描时没有可用修复版本。

| 包 | 扫描版本 | 观测到的修复版本 | 结果等级 |
| --- | --- | --- | --- |
| Alpine expat | 2.8.3-r0 | 2.8.4-r0 | High/Critical |
| Alpine openssl | 3.5.7-r0 | 3.5.8-r0 | High/Critical |
| logback-classic / logback-core | 1.4.11 | 1.4.12 | High |
| jackson-core / jackson-databind | 2.15.3 | 2.18.8 | High |
| mysql-connector-j | 8.1.0 | 8.2.0 | High |
| minio | 8.5.17 | 8.6.0 | High |
| netty-codec | 4.1.101.Final | 4.1.136.Final | High |
| netty-handler | 4.1.101.Final | 4.1.137.Final | High/Critical |
| tomcat-embed-core | 10.1.16 | 10.1.58 | High/Critical |
| bcprov-jdk18on | 1.78.1 | 1.85 | High/Critical |
| spring-boot | 3.2.0 | not fixed | High |
| spring-core / spring-expression | 6.1.1 | not fixed | High |
| spring-web | 6.1.1 | 6.1.6 | High |
| spring-webmvc | 6.1.1 | 6.1.14；部分通告 not fixed | High |

## 6. 验证证据

已完成：

- 隔离 Maven/Redis 测试：194 tests，0 failures/errors/skips；包含 Redis 原子限流、过期、编码 API 前缀、sys_config 脱敏/写入边界和 QQ 限流钳制回归。
- 隔离 nginx Docker 回归：UID 101、内部路径/编码路径/矩阵参数、隐藏文件、媒体 query 丢弃、只读媒体、API 头清洗和登录限流全部 PASS。
- Python 运维工具单元测试 10 项、Python compileall、social-publisher 安全测试 3 项通过；执行命令见 `docs/security/deployment-checklist.md`。
- 当前工作区 secret scan 为 0 findings；历史扫描仍报告 105 条跨版本命中。
- 合成凭据下 `docker compose -f docker-compose.prod.yml config --quiet` 通过；真实生产 `.env` 当前故意不能满足 hardened contract。

- 2026-09-21 复核：容器内 Maven/Redis 全量测试 195 项通过（0 failures/errors/skips），新增驼峰敏感配置键脱敏回归；测试专用容器和网络已清理，生产容器/卷未修改。

尚未完成：

- 生产 Compose 重建、MySQL 账号切换、Windows 防火墙/绑定、MinIO policy/key/网络迁移、Cloudflare Access/WAF 验收、OpenClaw URL 切换、加密备份和恢复演练。
- 完整 backend/JVM、Python 包、OS/容器 CVE 扫描及外部攻击面扫描；现有 Scout 结果必须在上线门禁中继续处理，不能忽略 critical/high。

## 6. 结论

当前生产环境不能标记为“已安全加固”。代码和目标配置已显著收紧攻击面，但上线前至少必须完成 C-01、C-02、H-01、H-02、H-03、H-04、H-05、H-06、H-07 的证据闭环。所有部署步骤必须遵循 `docs/security/deployment-checklist.md`，并更新 `docs/current-project-status.md` 的“已具备能力/仍需处理/验收”而不是把目标配置直接写成在线事实。
