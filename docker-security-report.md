# GYing Movie 生产安全审计报告（Docker / Windows）

- 审计日期：2026-09-20
- 工作区：`D:\gying-movie\movie`
- 目标架构：Windows + Docker Desktop + Cloudflare Tunnel
- 审计性质：代码、Compose、配置和本机运行态的防御性审计；不是经授权的外部渗透测试
- 最近只读复核：2026-09-25；生产已部分部署加固，工作区 HEAD `ead71b9`，backend 在线镜像 `gying-library-qr-backend:20260924c`（`sha256:6ccf38a9bee1…`），frontend 为 `gying-library-qr-frontend:20260925h`。
- 重要状态：应用/入口及迅雷凭据权限补丁已上线；在线 jar 已确认包含 `PrivateFileWriter`，状态文件为 `10001:10001 / 600`。同步任务 2026-09-25 09:27、10:33 两次已完成运行均返回 0，对应两次 stat 均为 600；backend 自身重复写入与实际授权有效性仍待验收。用户已在本机新建 localhost 专用备份账号并导出防火墙策略；本任务完成完整逻辑/持久数据备份及隔离恢复，期间 6 个原始写入容器暂停约 31 秒后全部解冻，未重建容器或修改生产数据。防火墙加固另需 UAC 与结果证据，不把脚本启动当作策略已生效。

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

初始审计日期为 2026-09-20；以下当前基线已按 2026-09-25 只读证据校准；未重新查询的 DB 变量、grants 与 Quark 文件模式沿用带日期的历史证据，不作为本轮新增验收。历史测试/CVE 计数保留原日期，未复核项目明确标注，目标配置不能代替在线事实。

## 2. 在线基线（2026-09-25，部分加固已上线）

| 项目 | 当前观测 | 状态/剩余风险 |
| --- | --- | --- |
| nginx/backend | `127.0.0.1:80` / `127.0.0.1:8880`；首页/列表 200、匿名管理员 401、内部 QQ/internal 404 | 入口部分已验证，不代表 Cloudflare Access 已启用 |
| quark-auto-save | 5005 非 loopback；UID 0；配置模式 `0:0 / 755` 为 2026-09-24 证据，本轮未复测 | High/Open：端口与 Cookie 文件权限 |
| MinIO | 9000/9001 非 loopback；UID 0；已接入应用网络且有 `minio` alias | 网络 alias/图片路径已验证；端口/权限仍 Open |
| MinIO 应用身份 | backend key 与 root key 不同；2026-09-22 已记录 scoped 身份创建 | 本次未重审完整 policy/匿名范围；不能延用“应用仍用 root”也不能宣称权限闭环 |
| Redis/PanSou | 无宿主端口；Redis 未认证 PING 被拒绝，但仍仅接入非 internal 的 `gying-net`；PanSou 进程 UID 0 | 认证/端口部分通过，Redis cache-net 隔离尚未部署，仍需容器权限/完整 ACL 复核 |
| Windows/MySQL | 活跃 WLAN 为 Public；Public Firewall Disabled；3306/33060 非 loopback；MySQL/MySQLX bind 为 `*` 是 2026-09-24 查询结果 | Critical/Open；未凭监听结果推断 Internet 已可达 |
| 数据库身份 | 三应用容器配置均为 `gying_app`；本机实际会话 `gying_app@%`，gying 库 SELECT/INSERT/UPDATE/DELETE/EXECUTE；MCP 为此前证据 | 已迁离 root，但 Host 为通配、身份共用，且缺完整备份权限；分服务与 grants 仍需收紧 |
| 数据库安全变量 | 2026-09-24 查询为 `require_secure_transport=OFF`、`local_infile=OFF`；本轮未重查 | TLS 未闭环；local_infile 是限制项，不误报为开放 |
| 容器权限 | 应用/入口/OpenClaw/Redis 非 root；quark/PanSou/MinIO 存在 root 进程 | 5 个旧依赖容器缺 `no-new-privileges`；未见 socket/privileged |
| OpenClaw | 当前只在默认 bridge；健康容器运行中 | 内部应用网络和真实 QQ 搜索未验收 |
| 迅雷凭据 | backend-data 状态文件为 `10001:10001 / 600`，mtime 2026-09-25 10:33:36；在线 jar 含 `PrivateFileWriter` | 补丁部署/两次文件权限已验证；同步任务 09:27、10:33 均返回 0，backend 自身重复写入与实际授权有效性仍未验收 |
| Cloudflare Access/WAF | 账号侧策略本轮未核验 | High/Open；本机 401/404 不能证明 Access 策略生效 |
| 历史凭据/恢复 | 已完成 19 文件完整逻辑/持久数据备份、25 表行数/CHECKSUM 与对象元数据恢复比对、MinIO 单图验证 | H-04/H-06 仍未全闭环：MySQL 系统账号、完整应用/异机恢复与私钥离线保管未验证 |

### 2.1 验证边界

- 只读检查为 53 PASS / 10 FAIL / 0 UNKNOWN；证据保存为本机忽略文件 `tmp/security-audit-20260925.json`，不包含凭据值。
- 原 `docker top -eo user,comm` 缺少 Docker 所需 PID 字段，造成全部用户检查 UNKNOWN；已改用 `pid,uid,comm` 并新增 6 项回归，异常/缺行仍 UNKNOWN，不以镜像 Config.User 代替进程证据。
- 10 个失败项是 2 项非 loopback 端口、5 项缺 `no-new-privileges`、3 项 root 进程；Windows Firewall、Cloudflare、DB grants、MinIO policy、备份恢复和 Redis 网络隔离不在该计数覆盖范围内。
- 未执行外部发帖、网盘转存、真实 QQ 消息、账号轮换或生产重建；未检查全部 MinIO 对象。本轮只观察既有计划任务，未手动触发迅雷同步。
- 公网匿名 `/admin/movies` 返回 200，未表现为 Access 拦截；管理员 API 401、内部路由 404 均通过。此探针不证明账号侧没有其他策略，也不等于业务管理权限被绕过。

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
| H-02 | High | 三服务共用非 root gying_app；本机实测 gying_app@% 具有库级 SELECT/INSERT/UPDATE/DELETE/EXECUTE，缺完整备份权限 | 分服务身份、精确 Host、最小 grants 与独立 backup 账号待配置；不扩大应用账号以完成备份 |
| H-03 | High | Cloudflare Access/WAF 账号侧未核验，管理员路径公开接受结果未闭环 | 创建 Access policy，做登录/未登录/服务 token 三态验收 |
| H-04 | High | Git 历史存在凭据命中，历史未重写、凭据未全部轮换 | 立即按 `secret-management.md` 轮换；历史重写另行审批 |
| H-05 | High | Quark Cookie 持久化配置权限过宽 | 停止/备份前提下收紧卷 ACL/文件模式，旋转 Cookie，验证 WebUI 登录 |
| H-06 | High | localhost 备份账号、完整 gying 逻辑/持久数据备份、约 31 秒本地写入冻结及隔离 DB/MinIO 恢复已通过 | 仍需私钥离线保管、系统账号重建、完整应用/异机演练；不把本机隔离验证当成整机灾难恢复 |
| H-07 | High | 旧 2026-09-20 backend 扫描为 61 个唯一漏洞，不能代表 2026-09-24 重建的当前镜像 | 记录当前 backend 镜像 6ccf38a9bee1，重新扫描并做 fixed-version triage；不以旧计数或 npm audit 0 结案 |
| M-01 | Medium | MySQL `require_secure_transport=OFF`、密码校验策略未确认 | 先建立证书/连接验证计划，再开启并回归所有连接器 |
| M-02 | Medium | 部分第三方镜像仍使用 `latest`，完整 JVM/Python/容器 CVE 扫描未闭环 | 固定版本/摘要，保留 Docker Scout/依赖扫描结果 |
| M-03 | Medium | 已接入 gying-movie_gying-net 且有 minio alias；nginx 图片抽样 200 | 网络 alias 与图片路径已验证；端口与对象权限风险仍归 C-01/C-02 |
| M-04 | Medium | 当前只接入默认 bridge，尚未接入应用网络；本轮未验证真实 QQ 搜索 | 备份配置后迁移到 backend:8880 内部链路，核验 token 与真实 QQ 搜索 |
| M-05 | Medium | 日志/安全事件已有结构化输出，但尚未接入告警/集中保留 | 配置 Windows/Docker 日志收集和 401/403/429/5xx 告警 |
| H-08 | High | 私有原子写入修复已部署，在线为 10001:10001 / 600；两条写入链路的持续性未闭环 | 不再要求重复发布；备份后验证同步脚本及 backend 自身重复写入始终 600、旧文件可回滚；同步任务退出 0 不替代授权/业务验收 |
| M-06 | Medium | quark/PanSou/MinIO 有 root 进程；5 个旧依赖容器缺 no-new-privileges | 逐个验证非 root、卷可写路径与 no-new-privileges；避免批量重建造成中断 |
| M-07 | Medium | Redis 无宿主发布但仍在非 internal 的共享 gying-net，不是目标 cache-net 隔离 | 备份/维护窗口内验证 backend Redis 地址与 ACL，逐步迁到 cache-net；生产扫描计数未覆盖此项 |
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

### 2026-09-25 本轮只读复核

- 生产扫描 53 PASS / 10 FAIL / 0 UNKNOWN；Python 安全工具 16 项通过；工作区 secret scan 0 findings。没有重跑后端全量、镜像 CVE 或外部副作用测试。
- 运维就绪检查 10 PASS / 2 WARN / 0 FAIL；警告为迁移文档漂移和新库架构覆盖，不等同安全上线门禁已通过。
- 10 个运行容器 restart count 均为 0；本机健康探针通过。公网首页 200、匿名管理员 API 401、QQ/internal 路由 404；匿名管理页仍为 200。
- 在线 jar 含 `PrivateFileWriter`；凭据文件 owner/mode 为 `10001:10001 / 600`；任务 2026-09-25 09:27、10:33 两次运行均返回 0，观察期间文件 mtime 从 09:27:36 更新为 10:33:36，权限保持 600。未读取或输出凭据内容，未手动触发刷新。
- Public Firewall 仍 Disabled；3306/33060/5005/9000/9001 仍有非 loopback 监听。Redis 实际仅接入 `gying-movie_gying-net`（internal=false），未迁入已有的 `gying-movie_cache-net`（internal=true）。
- G 盘便携 age v1.3.2 官方摘要已校验；用户已完成 `gying_backup@localhost` 与 `G:/gying-secrets/mysql-backup.cnf`，元数据与 SHOW_ROUTINE 权限实测通过，私有文件 ACL 仅当前用户/SYSTEM。应用账号权限未扩大。
- 当前完整备份 `G:/gying-backups/20260925T030328.719395Z` 保存 19 个加密文件（371.76 MiB），manifest complete，标准校验器与全部 age 认证解密通过。使用 routines/events/triggers 完整选项；有权限的源端和恢复端均确认视图/触发器/事件/例程为 0。早先 partial 目录保留且未改标，不混用两轮结果。
- 本地冻结窗口 11:03:25–11:03:56 Asia/Shanghai：6 个原始容器按 ID 暂停/解冻，180 秒解冻 watchdog 未触发；主机定时写入不与窗口重叠，数据库行数/CHECKSUM 与关键配置哈希前后一致。没有恢复或重放外部已提交网盘任务。
- 完整逻辑恢复在独立 MySQL 8.0.28/loopback 13380 验证：25/25 表行数与逐表 CHECKSUM 一致、对象数量与中文往返通过，测试实例已关闭。无网络 MinIO 测试恢复健康、1 张公开图片 hash 一致、私有元数据 403，测试容器已移除；不等价于完整业务链路或账号 policy 验收。
- 私钥仅当前用户/SYSTEM 可读，位于 F 盘且仍在线；恢复副本保留在 G 盘受限目录，未绕过此前递归清理限制。备份账号步骤已完成；Windows 防火墙单独使用带备份/健康门禁/自动回退的脚本；本轮自动 UAC 启动返回 InvalidOperationException，未生成执行结果，Public 仍关闭。需管理员 PowerShell 手动执行 `Apply-FirewallStep.ps1 -Apply` 并重新验收，不能凭脚本已准备宣称生效。
- 脱敏证据：`tmp/ops-snapshot-security-20260925.md`、`tmp/security-audit-20260925.json`、`tmp/security-runtime-20260925.json`、`tmp/security-preflight-details-20260925.json`、`tmp/security-host-20260925.json`、`tmp/security-xunlei-task-20260925.json`；均为本机忽略文件，不提交。

### 2026-09-24 历史结果

- 后端全量 Maven + 独立 Redis：201 tests，0 failures/errors/skips；包含 6 项新增私有写入回归；该结果对应测试执行时源码，不覆盖随后出现的并行业务改动。Maven 在离线缓存、只读源码挂载和独立 internal 网络中运行，不挂生产数据卷。
- Python 安全工具：16 项通过，其中 6 项覆盖 Docker 进程 UID 采集；Windows Java 17 smoke 验证 owner-only ACL、重复原子替换和失败清理通过。
- 当前工作区 secret scan：0 findings；本机入口/图片抽样通过；生产只读审计 53 PASS / 10 FAIL / 0 UNKNOWN。测试 Redis 容器及网络已清理，未重启生产服务或修改生产凭据。
- 当时迅雷修复只在源码和隔离测试成立、线上仍 `644`；2026-09-25 已按新证据更新为补丁已部署/文件 600，不把历史结果当作当前部署状态。

### 历史验证（2026-09-20 至 2026-09-21）

已完成：

- 隔离 Maven/Redis 测试：194 tests，0 failures/errors/skips；包含 Redis 原子限流、过期、编码 API 前缀、sys_config 脱敏/写入边界和 QQ 限流钳制回归。
- 隔离 nginx Docker 回归：UID 101、内部路径/编码路径/矩阵参数、隐藏文件、媒体 query 丢弃、只读媒体、API 头清洗和登录限流全部 PASS。
- Python 运维工具单元测试 10 项、Python compileall、social-publisher 安全测试 3 项通过；执行命令见 `docs/security/deployment-checklist.md`。
- 当前工作区 secret scan 为 0 findings；历史扫描仍报告 105 条跨版本命中。
- 合成凭据下 `docker compose -f docker-compose.prod.yml config --quiet` 通过；当时生产 `.env` 尚不满足 hardened contract；2026-09-24 真实配置已通过 `config --quiet`。

- 2026-09-21 复核：容器内 Maven/Redis 全量测试 195 项通过（0 failures/errors/skips），新增驼峰敏感配置键脱敏回归；测试专用容器和网络已清理，生产容器/卷未修改。

尚未完成：

- 剩余依赖容器权限/端口、分服务 DB 身份/grants、Windows Firewall、MinIO 完整 policy/root-key 轮换、Cloudflare Access/WAF、OpenClaw 内部网络、Redis 网络隔离、加密备份恢复和迅雷两条写入链路的持续性验收。
- 完整 backend/JVM、Python 包、OS/容器 CVE 扫描及外部攻击面扫描；现有 Scout 结果必须在上线门禁中继续处理，不能忽略 critical/high。

## 7. 结论

当前生产环境不能标记为“已安全加固”。应用/入口部分加固已上线；C-01、C-02、H-01 至 H-08 与容器权限等剩余风险仍需证据闭环。所有部署步骤必须遵循 `docs/security/deployment-checklist.md`，并更新 `docs/current-project-status.md` 的“已具备能力/仍需处理/验收”而不是把目标配置直接写成在线事实。
