# 11 项安全门禁与续验工具

更新时间：2026-10-08。本文区分**线上事实、隔离候选验证与实际部署**；不能据候选通过批量重建生产。

## 固定编号与当前结果

`tools/security/check_security.py` 的 `gate_ledger` 固定保留以下 11 项；缺失证据或重复的 PASS 为 UNKNOWN，已确认的 FAIL 不被异常数据覆盖。它不是 Windows/Cloudflare/数据库/备份等全部部署门禁。

2026-10-08 续验：**原 11 项仍全为 FAIL**。旧工具为 53 PASS / 11 FAIL；新版为 **59 PASS / 11 FAIL / 0 UNKNOWN**。新增 6 PASS 来自 10 个预期生产容器的清单完整性检查，以及同生产网络的一个额外诊断容器的 5 项检查，不是已修复 6 项线上风险。

| 编号 | 对象 / 检查 | 当前事实 | 必须验证的目标 |
| --- | --- | --- | --- |
| G01 | Quark 发布端口 | 5005 非 loopback | 仅 loopback/不发布，backend 内部调用与本机登录正常 |
| G02 | MinIO 发布端口 | 9000、9001 非 loopback（计为一项） | 内部 S3 调用切换完成后再改绑；公开图片正常、控制台不进入 Tunnel |
| G03 | OpenClaw NNP | 未启用 | 实际容器 `no-new-privileges=true`，Gateway/唯一插件与内部 QQ 鉴权正常 |
| G04 | Redis NNP | 未启用 | 实际容器启用 NNP，认证与 Lua 限流正常 |
| G05 | Quark NNP | 未启用 | 实际容器启用 NNP，不依赖提权初始化 |
| G06 | PanSou NNP | 未启用 | 实际容器启用 NNP，健康/搜索正常 |
| G07 | MinIO NNP | 未启用 | 实际容器启用 NNP，S3/图片/权限正常 |
| G08 | Quark 进程 UID | Python 以 UID 0 运行 | 所有实际进程非 root，配置卷可用且保持私有权限 |
| G09 | PanSou 进程 UID | PanSou 以 UID 0 运行 | 所有实际进程非 root，缓存目录可写 |
| G10 | MinIO 进程 UID | MinIO 以 UID 0 运行 | 所有实际进程非 root，真实数据/config 兼容且策略正确 |
| G11 | Redis 网络隔离 | 仍在非 internal 的 `gying-net` | 仅在带 Compose `cache-net` 标签的 internal 网络；backend 认证/Lua/故障拒绝验证通过 |

新版同时防止以下假通过：`no-new-privileges:false` 的字符串包含误判、Docker 不可用/inspect 不完整时漏检、缺失字段默认为安全、Docker socket 改名挂载。按实际生产网络补入不同名字的容器；当前额外诊断容器只做审计，未被停止或删除。

## 为什么不能直接运行一次 Compose

- `docker-compose.prod.yml` 是目标配置；现役镜像与覆盖文件必须以容器为准。不要省略 `-p gying-movie`，不要批量拉取 `latest`。
- PanSou/Quark 的目标 Compose 仍没有完整的非 root 卷迁移方案；仅添加 `user` 会使 Quark 现有 root-owned `0700` 配置目录不可读。
- MinIO/OpenClaw 为独立运行容器，不会因主 Compose 的安全锚点改变而自动加固。
- backend 的 `MINIO_ENDPOINT`、source 的 `GYING_MINIO_ENDPOINT` 仍经 `host.docker.internal:9000`。MinIO 已有内部 alias **不代表**可以直接关闭宿主端口。
- 本次新加密备份包含 6 个应用发布相关文件；本轮重验了 hash，未重做解密/恢复，也不覆盖新的 Quark/MinIO/OpenClaw 依赖迁移快照。

## 非 root 改造可行性：隔离验证已通过，尚未部署

`tools/security/test_dependency_profiles.py` 使用现役容器的**固定镜像 ID**，不拉镜像、不读取其环境值、不使用生产卷或真实凭据。所有候选只在 `network=none` 中运行，无宿主端口；只允许私有 tmpfs，以及 Redis 的单个只读仓库脚本。HTTP helper 只共享对应候选的无网络命名空间。

2026-10-08 真实隔离结果：

| 候选 | 配置 | 已验证 | 仍不能据此声称 |
| --- | --- | --- | --- |
| PanSou | UID/GID 10001、NNP、只读根、私有 `/app/cache` | 非 root、健康 200 | 真实缓存迁移/外部搜索已完成 |
| Quark | UID/GID 10001、NNP、只读根、私有 `/app/config`、空 Cookie | 非 root、登录页 200 | 生产 Cookie/config、追更/转存已兼容 |
| MinIO | UID/GID 10001、NNP、只读根、空 `/data`、临时 HOME/合成身份 | 非 root、live health 200 | 真实持久化数据/config、scoped policy 已迁移 |
| Redis | UID 999、NNP、只读根、现有 ACL entrypoint、合成凭据 | 匿名拒绝、认证 PING、Lua 成功、CONFIG 被拒绝 | 生产网络/会话/限流切换已完成 |

临时容器全部移除，清理需要本轮唯一标签、精确容器 ID 及删除后的清单验证；不会按模糊名称删除容器。OpenClaw 的真实账号、插件与 QQ 出站不在此隔离测试范围。

## 真实部署前的独立阻碍

### Cloudflare：有 Token，不是“尚未录入”

2026-10-08 使用既有 DPAPI 文件只读复验：Token 为 `active`，Zone API 200；目标账号的 Access organization、identity providers、applications API 均 403。匿名公网 `/admin/movies` 仍为 200，管理员 API 为 401；后者不是 Access 已生效的证明。

需用户在控制台核对 Token 的**目标账号/资源范围**和 Access 权限：Apps and Policies（后续实施需 Edit）、Organizations/Identity Providers/Groups（Read）。不要扩大到全部账号/Zone，也不要增加 DNS/Tunnel Edit。现有 Token 不需要重复隐藏录入；不覆盖其 DPAPI 文件，不在聊天发送 Token。调整授权后的复验仍只读，应用/OTP/重叠策略、真实邮箱登录和回退范围必须分别确认。详见 `cloudflare-security.md`。

### 其他前置项

- DB 分服务身份与最小 grants、MinIO scoped/匿名 policy、需要确认的密钥轮换；不擅自扩大应用账号或撤销旧凭据。
- Windows Firewall 公网直连/IPv6 入站证据；已有本机规则不重复 Apply，同网历史测试不能替代公网/IPv6。
- 对实际待变更服务重新保存加密配置/卷/运行参数、旧镜像与回滚材料，并验证恢复。不能用 hash 通过替代可解密或恢复成功。

这些条件未闭环时，只完成代码、只读审计与隔离测试，不修改在线端口/卷属主/网络/身份。

## 门禁通过后的分步路线

每次只变更一个服务，先观察再继续；不是可直接执行的批量发布脚本。

1. **PanSou（G06/G09）**：选择可写的非 root 缓存路径/新卷，保留旧缓存卷；通过真实健康与外部只读搜索后再验收。
2. **Redis（G04/G11）**：在目标 cache-net 校验 DNS、认证、Lua 与所有真实消费者，再维护窗口切换；验证失联时 fail closed，保留旧配置与回退能力。
3. **OpenClaw（G03）**：保存插件/认证配置，NNP 与应用网络变更分别验收；不能把仅进程启动当作 QQ 搜索/权限通过。
4. **Quark（G01/G05/G08）**：先备份并用副本验证 UID/私有 config；逐文件校验内容，明确移交属主方案，再替换单服务和改绑端口。真实转存需单独授权，不借测试重放旧队列。
5. **MinIO（G02/G07/G10）**：先完成实际数据/config 与身份/policy 验证；backend/source 分别迁入内部 endpoint 后再收紧宿主端口，逐层复核图片、私有对象和控制台。

## 可重复执行的验证

在仓库根目录运行；Docker/MySQL 客户端需使用本机现有安装，不安装新依赖。

```powershell
# 只读在线扫描。存在 FAIL/UNKNOWN 时返回非 0，不是“命令坏了”。
python -X utf8 tools/security/check_security.py --repo . --probe --output tmp/security-current.json

# 只读快照：非 root defaults + READ ONLY 一致性事务，无密码参数/环境值输出。
python -X utf8 tools/inspect_p2p_progress.py --mysql 'E:\Mysql-8.0.28\mysql8.0.28\bin\mysql.exe' --defaults-file 'G:\gying-secrets\mysql-backup.cnf' --since '2026-10-08T17:28:00+08:00' --output tmp/p2p-progress.json

# 显式运行隔离候选测试；会创建并清理本轮临时容器，不接生产网络/卷。
python -X utf8 tools/security/test_dependency_profiles.py --docker 'C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe' --output tmp/dependency-profiles.json
```

P2P 工具验证页码/offset、工作量对应的下一游标，以及失败条目的持久化重试引用，不把 `SUCCEEDED` 标签直接算作前进。每来源最多读取最近 3 批、重试证据上限 1000 条；缺失引用保持 UNKNOWN。自然失败分支尚未发生时，`live_failure_isolation` 仍为 UNKNOWN，不能人为制造生产错误来让它变绿。任务/资源行也不等同远端分享内容已重新验证。

本轮可复核证据位于 `tmp/continuation-20261008/`（忽略文件）：`security-current.json`、`p2p-final.json`、`p2p-tv-natural-batch.json`、`dependency-profile-verification.json`、`cloudflare-permission-checks.json`、`entrypoints-and-logs.json`。生产镜像、旧失败队列、云端策略与凭据均未由本次工具改动。交付证据及编辑前工具/文档副本归档于 `E:/gying-tools/releases/security-gates-20261008/`，其中不包含生产配置值或数据。
