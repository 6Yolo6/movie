# GYing Movie 生产安全审计报告（Docker / Windows）

- 审计日期：2026-09-20
- 工作区：`D:\gying-movie\movie`
- 目标架构：Windows + Docker Desktop + Cloudflare Tunnel
- 审计性质：代码、Compose、配置和本机运行态的防御性审计；不是经授权的外部渗透测试
- 最近只读复核：2026-09-24；生产已部分部署加固，当前工作区 HEAD `7a8e821`，backend 在线镜像 `sha256:8f15013b5a35…`。
- 重要状态：2026-09-22 已记录应用/入口部署；2026-09-24 的迅雷凭据权限修复尚未部署。本轮只读复核生产，未修改 `.env`、Firewall、账号/policy、Cloudflare 策略、计划任务或生产卷。

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

初始审计日期为 2026-09-20；以下当前基线已按 2026-09-24 只读证据校准。历史测试/CVE 计数保留原日期，未复核项目明确标注，目标配置不能代替在线事实。

## 2. 在线基线（2026-09-24，部分加固已上线）

| 项目 | 当前观测 | 状态/剩余风险 |
| --- | --- | --- |
| nginx/backend | `127.0.0.1:80` / `127.0.0.1:8880`；首页/列表 200、匿名管理员 401、内部 QQ/internal 404 | 入口部分已验证，不代表 Cloudflare Access 已启用 |
| quark-auto-save | 5005 非 loopback；UID 0；配置 `0:0 / 755` | High/Open：端口与 Cookie 文件权限 |
| MinIO | 9000/9001 非 loopback；UID 0；已接入应用网络且有 `minio` alias | 网络 alias/图片路径已验证；端口/权限仍 Open |
| MinIO 应用身份 | backend key 与 root key 不同；2026-09-22 已记录 scoped 身份创建 | 本次未重审完整 policy/匿名范围；不能延用“应用仍用 root”也不能宣称权限闭环 |
| Redis/PanSou | 无宿主端口；Redis 未认证 PING 被拒绝；PanSou 进程 UID 0 | 认证/端口部分通过，仍需容器权限/完整 ACL 复核 |
| Windows/MySQL | 活跃 WLAN 为 Public；Public Firewall Disabled；3306/33060 非 loopback，MySQL/MySQLX bind 为 `*` | Critical/Open；未凭监听结果推断 Internet 已可达 |
| 数据库身份 | 三应用容器配置均为 `gying_app`；MCP 实测 `gying_readonly@127.0.0.1` | 已迁离 root，但分服务身份与 grants 仍需验收 |
| 数据库安全变量 | `require_secure_transport=OFF`、`local_infile=OFF` | TLS 未闭环；local_infile 是限制项，不误报为开放 |
| 容器权限 | 应用/入口/OpenClaw/Redis 非 root；quark/PanSou/MinIO 存在 root 进程 | 5 个旧依赖容器缺 `no-new-privileges`；未见 socket/privileged |
| OpenClaw | 当前只在默认 bridge；健康容器运行中 | 内部应用网络和真实 QQ 搜索未验收 |
| 迅雷凭据 | backend-data 状态文件仍为 `10001:10001 / 644` | 新私有原子写入代码已补充，尚未部署；刷新失败另行处理 |
| Cloudflare Access/WAF | 账号侧策略本轮未核验 | High/Open；本机 401/404 不能证明 Access 策略生效 |
| 历史凭据/恢复 | 本轮工作区扫描 0；历史轮换、加密备份和恢复演练未形成新证据 | 保留 H-04/H-06；不能以脚本存在代替恢复验收 |

### 2.1 验证边界

- 只读检查为 53 PASS / 10 FAIL / 0 UNKNOWN；证据保存为本机忽略文件 `tmp/security-audit-20260924.json`，不包含凭据值。
- 原 `docker top -eo user,comm` 缺少 Docker 所需 PID 字段，造成全部用户检查 UNKNOWN；已改用 `pid,uid,comm` 并新增 6 项回归，异常/缺行仍 UNKNOWN，不以镜像 Config.User 代替进程证据。
- 10 个失败项是 2 项非 loopback 端口、5 项缺 `no-new-privileges`、3 项 root 进程；Windows Firewall、Cloudflare、DB grants、MinIO policy、备份恢复不在该计数覆盖范围内。
- 未执行外部发帖、网盘转存、真实 QQ 消息、账号轮换或生产重建；未检查全部 MinIO 对象。

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
| C-02 | Critical | backend 已不使用 root key；匿名/scoped policy 与 root key 轮换本次未重新验收 | 保留未闭环状态，核验最小 policy/匿名范围并在维护窗口轮换 root key |
| H-01 | High | nginx/backend 已 loopback；quark 5005、MinIO 9000/9001 仍为非 loopback | 继续收紧剩余端口，独立验证 Firewall/Access；不能重复写成 backend 尚未收紧 |
| H-02 | High | 三服务已使用非 root gying_app，但仍共用身份；本次未完整复核 grants | root 迁移已部分解决；分服务账号/最小 grants 仍待验收，保留受控 break-glass |
| H-03 | High | Cloudflare Access/WAF 账号侧未核验，管理员路径公开接受结果未闭环 | 创建 Access policy，做登录/未登录/服务 token 三态验收 |
| H-04 | High | Git 历史存在凭据命中，历史未重写、凭据未全部轮换 | 立即按 `secret-management.md` 轮换；历史重写另行审批 |
| H-05 | High | Quark Cookie 持久化配置权限过宽 | 停止/备份前提下收紧卷 ACL/文件模式，旋转 Cookie，验证 WebUI 登录 |
| H-06 | High | 加密备份/恢复尚未真实演练 | 配置 age recipient、执行一次备份、异机/临时目录恢复 MySQL 和关键卷 |
| H-07 | High | 旧 2026-09-20 backend 扫描为 61 个唯一漏洞，不能代表 2026-09-22 重建的当前镜像 | 记录当前 backend 镜像 8f15013b5a35，重新扫描并做 fixed-version triage；不以旧计数或 npm audit 0 结案 |
| M-01 | Medium | MySQL `require_secure_transport=OFF`、密码校验策略未确认 | 先建立证书/连接验证计划，再开启并回归所有连接器 |
| M-02 | Medium | 部分第三方镜像仍使用 `latest`，完整 JVM/Python/容器 CVE 扫描未闭环 | 固定版本/摘要，保留 Docker Scout/依赖扫描结果 |
| M-03 | Medium | 已接入 gying-movie_gying-net 且有 minio alias；nginx 图片抽样 200 | 网络 alias 与图片路径已验证；端口与对象权限风险仍归 C-01/C-02 |
| M-04 | Medium | 当前只接入默认 bridge，尚未接入应用网络；本轮未验证真实 QQ 搜索 | 备份配置后迁移到 backend:8880 内部链路，核验 token 与真实 QQ 搜索 |
| M-05 | Medium | 日志/安全事件已有结构化输出，但尚未接入告警/集中保留 | 配置 Windows/Docker 日志收集和 401/403/429/5xx 告警 |
| H-08 | High | 迅雷凭据在线仍为 10001:10001 / 644；后端私有原子写入修复尚未部署 | 备份后发布修复，验证重复写入始终 600、旧文件可回滚；登录刷新故障另行处理 |
| M-06 | Medium | quark/PanSou/MinIO 有 root 进程；5 个旧依赖容器缺 no-new-privileges | 逐个验证非 root、卷可写路径与 no-new-privileges；避免批量重建造成中断 |
| L-01 | Low | origin 使用 HTTP，由 Cloudflare 提供公网 TLS | Cloudflare 开启 Always Use HTTPS、HSTS 与严格 origin policy；不直接暴露 origin |
| L-02 | Low | 工作区审计工具不能代替外部渗透、云账号策略审计 | 每季度或重大变更时复核范围和证据 |

## 5. 依赖和镜像扫描（2026-09-20 历史证据，待当前镜像复扫）

- 2026-09-20 frontend 和 social-publisher 的 `npm audit` 均为 0 vulnerabilities；这只覆盖 npm advisory 数据和声明的 JavaScript 依赖。
- Docker Scout `--only-severity critical,high` 对旧在线镜像和隔离目标镜像的结果并不为零：旧 frontend 镜像 55 条、旧 source 镜像 15 条、旧 social 镜像 176 条；重建后的隔离 frontend 仍有 10 条（含 1 条 critical 的 transitive/bundled package），source 在加入 Debian security upgrade 后降为 1 条未修复 zlib high，social 在加入升级后仍有 58 条，主要来自 Chromium/OS 和 bundled tooling；当时的 backend 镜像另有 17 个受影响包、61 个唯一漏洞。
- 这些结果已保存为本次本机临时扫描证据（`tmp/scout-*.sarif`，不提交）；backend 证据文件为 `tmp/scout-backend.sarif`。该文件对应 2026-09-20 旧镜像依赖集（含 Spring Boot 3.2.0），不是工作区当前 `pom.xml` 的 3.5.16 目标镜像；因此必须在重建目标镜像后重新扫描，不能把 npm audit 0 或代码测试通过写成镜像无 CVE。
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

### 2026-09-24 本轮结果

- 后端全量 Maven + 独立 Redis：201 tests，0 failures/errors/skips；包含 6 项新增私有写入回归；该结果对应测试执行时源码，不覆盖随后出现的并行业务改动。Maven 在离线缓存、只读源码挂载和独立 internal 网络中运行，不挂生产数据卷。
- Python 安全工具：16 项通过，其中 6 项覆盖 Docker 进程 UID 采集；Windows Java 17 smoke 验证 owner-only ACL、重复原子替换和失败清理通过。
- 当前工作区 secret scan：0 findings；本机入口/图片抽样通过；生产只读审计 53 PASS / 10 FAIL / 0 UNKNOWN。测试 Redis 容器及网络已清理，未重启生产服务或修改生产凭据。
- 迅雷写入修复只在源码和隔离测试成立，线上仍 `644`；发布/回滚必须按部署清单执行，不能把权限测试当成授权刷新恢复。

### 历史验证（2026-09-20 至 2026-09-21）

已完成：

- 隔离 Maven/Redis 测试：194 tests，0 failures/errors/skips；包含 Redis 原子限流、过期、编码 API 前缀、sys_config 脱敏/写入边界和 QQ 限流钳制回归。
- 隔离 nginx Docker 回归：UID 101、内部路径/编码路径/矩阵参数、隐藏文件、媒体 query 丢弃、只读媒体、API 头清洗和登录限流全部 PASS。
- Python 运维工具单元测试 10 项、Python compileall、social-publisher 安全测试 3 项通过；执行命令见 `docs/security/deployment-checklist.md`。
- 当前工作区 secret scan 为 0 findings；历史扫描仍报告 105 条跨版本命中。
- 合成凭据下 `docker compose -f docker-compose.prod.yml config --quiet` 通过；当时生产 `.env` 尚不满足 hardened contract；2026-09-24 真实配置已通过 `config --quiet`。

- 2026-09-21 复核：容器内 Maven/Redis 全量测试 195 项通过（0 failures/errors/skips），新增驼峰敏感配置键脱敏回归；测试专用容器和网络已清理，生产容器/卷未修改。

尚未完成：

- 剩余依赖容器权限/端口、分服务 DB 身份/grants、Windows Firewall、MinIO 完整 policy/root-key 轮换、Cloudflare Access/WAF、OpenClaw 内部网络、加密备份恢复和新迅雷权限代码的生产部署。
- 完整 backend/JVM、Python 包、OS/容器 CVE 扫描及外部攻击面扫描；现有 Scout 结果必须在上线门禁中继续处理，不能忽略 critical/high。

## 7. 结论

当前生产环境不能标记为“已安全加固”。应用/入口部分加固已上线；C-01、C-02、H-01 至 H-08 与容器权限等剩余风险仍需证据闭环。所有部署步骤必须遵循 `docs/security/deployment-checklist.md`，并更新 `docs/current-project-status.md` 的“已具备能力/仍需处理/验收”而不是把目标配置直接写成在线事实。
