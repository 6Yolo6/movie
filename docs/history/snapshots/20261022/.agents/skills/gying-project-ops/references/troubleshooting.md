> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# 故障排查

## 事故处理顺序

1. 暂停高风险自动化：Resource Hub Worker、频道计划任务和机器人自动转存。
2. 记录时间、环境、现象、最近正常提交和近期变更。
3. 重启前保存容器状态和日志。
4. 从依赖向外检查：MySQL、Redis、存储、外部 API、backend、frontend、nginx、机器人和频道。
5. 每次只改变一个层级。
6. 重新验证最初的用户可见现象。
7. 在 `docs/current-project-status.md` 记录原因、修复、证据和剩余风险。

不能一次重启所有服务，否则会丢失证据并掩盖故障边界。

## 现象矩阵

| 现象 | 可能边界 | 检查项 |
| --- | --- | --- |
| 整站不可用 | nginx、端口、应用容器 | nginx 日志、80/443、上游容器 |
| 前端可加载但 `/api` 失败 | nginx 到 backend | backend 日志、`backend:8880`、数据库启动 |
| 浏览器 API 正常但详情 SSR 失败 | frontend 内部 API | `INTERNAL_API_URL=http://backend:8880` |
| backend 启动后出现 mapper SQL 错误 | 架构漂移 | 在线字段和索引与实体、SQL 对比 |
| MySQL 时间偏差 8 小时 | 容器、JVM、JDBC 时区 | `TZ`、`JAVA_OPTS`、JDBC、`NOW()` 与 `UTC_TIMESTAMP()` |
| Resource Hub 提示缺少 Key | backend 环境过期或目标错误 | 检查运行容器配置键并重建 |
| GYING 健康但候选失败 | Session、PoW、登录或内部 token | `gying-source` 日志、`/account` 状态、backend/source token |
| PanSou 根路径 `404` | 检查路径错误 | 容器日志和已知 API |
| 夸克任务已提交但未转存 | Cookie 或账号配置 | WebUI Cookie、API token、最终配置 |
| 分享任务没有 `share_id` | 空目录、Cookie 或 API 故障 | 目录内容、任务错误、Cookie |
| 重试显示完成但没有活动资源 | 旧逻辑跳过、任务缺失或分享未生成 | 重建任务并检查自有分享和 `resource_link` |
| 单片分享包含整套合集 | 未定位目标子目录或未收窄 `fid` | 标题/年份/季号、目录树、提交参数和最终分享内容 |
| 待入库或待转存数量异常 | 状态过滤语义 | 状态、分享链接、资源绑定条件，先 reconcile dry-run |
| 缺资源批量任务被截断 | 请求超过每批 20 部 | 前端选择数、请求体和后端逐条结果 |
| 发现或修复出现 nginx `504` | 调用了旧同步接口 | 确认返回 `jobId` 并轮询 |
| 修复后产生重复资源 | 重放或 canonical 匹配错误 | source URL/hash、旧失效行、发布行为 |
| 官方 QQ 主动消息返回 `40034105` | 平台权限或消息模式 | 使用带 `msg_id` 的被动回复或授权 |
| NapCat 容器存在或退出 | 历史遗留服务仍被误启动 | 不重启 NapCat；检查 OpenClaw Gateway、后端 QQ 接口和 QQBot 配置 |
| OpenClaw 重启循环 | gateway 配置 | `gateway.mode=local`、`gateway.bind=lan` 并重建 |
| OpenClaw 升级后搜索失效 | 本地插件补丁被覆盖 | 重新应用脚本或部署正式插件 |
| QQ 频道帖子乱码 | Windows CLI 编码 | UTF-8 content file，避免 stdin JSON |
| 计划任务存在但没有发帖 | 任务禁用或授权过期 | 任务状态、管理员启用、CLI 登录 |
| 多平台发布容器 401 | 内部 token 不一致 | backend 与容器的 `SOCIAL_PUBLISHER_TOKEN` |
| QQ 发布账号未就绪 | 凭据卷、扫码授权或未加入频道 | 账号目录、授权状态、目标频道 |
| 微博发布失败 | Cookie/fingerprint 过期、限流或安全验证 | `/health` readiness、失败日志；验证码人工处理 |
| 多平台同片重复发布 | 目标或影片去重异常 | 唯一键、目标 ID、资源 ID 和历史日志 |
| MySQL MCP 报 `1045` 和 password NO | MCP 缺少密码环境 | `GYING_DB_PASSWORD` 并重启 MCP |
| MCP Compose 找不到配置 | Compose 文件名非默认 | shell 显式使用 `-f` |
| Docker 依赖端口冲突 | embedded 与外部服务重复 | `docker ps -a`、profile、映射端口 |
| 公网 `530`、cloudflared 反复 `failed to dial to edge with quic: timeout` | 本机代理（Clash TUN fake-IP）把 `cfd.argotunnel.com` 解析成 `198.18.0.0/15` 地址，QUIC/UDP 经代理超时 | 计划任务参数加 `--protocol http2`（该参数已从 `--help` 隐藏但仍有效；`config.yml` 可能被 ACL 设为只读），重启任务后用 `http://127.0.0.1:20241/ready` 与 `cloudflared_tunnel_ha_connections` 复核 |
| `git push` 报 `Connection closed by remote host`（对端 198.18.x.x） | 同一个代理 fake-IP 拦截 22 端口 | 改用 `ssh://git@ssh.github.com:443/<owner>/<repo>.git`，或全局 `insteadOf` 映射 |

## 日志命令

```powershell
# cloudflared 隧道连接状态（未显式配置时 metrics 默认监听 20241）
Invoke-WebRequest http://127.0.0.1:20241/ready -UseBasicParsing
(Invoke-WebRequest http://127.0.0.1:20241/metrics -UseBasicParsing).Content -split "`n" | Select-String 'ha_connections'
```

```powershell
docker compose -f docker-compose.prod.yml logs --tail=200 backend
docker compose -f docker-compose.prod.yml logs --tail=200 gying-source
docker compose -f docker-compose.prod.yml logs --tail=200 social-publisher
docker compose -f docker-compose.prod.yml logs --tail=200 frontend
docker compose -f docker-compose.prod.yml logs --tail=200 nginx
docker logs --tail 200 gying-pansou
docker logs --tail 200 gying-quark-auto-save
docker logs --tail 200 openclaw-openclaw-gateway-1
```

分享日志前删除 Cookie、token、Authorization、用户隐私和带访问参数的完整 URL。

## Docker 恢复后端口空响应与 Tunnel fake-IP 漂移

2026-09-30 的故障同时涉及两个独立边界，不能只凭容器 `Up` 或一次公网 200 判断恢复：

- **宿主端口仍转发到旧容器 IP**：容器内 backend/quark HTTP 正常，但宿主 `8880`/`5005` 返回 curl 52（Empty reply）。Docker Desktop 的 `vpnkitcontrol` 日志记录转发目标仍为旧 IP，而 `docker inspect` 的当前网络 IP 已改变；nginx 也出现过同类问题。先保存容器 ID、镜像、配置/挂载及转发日志摘要，核实没有新近执行中的任务，再逐个重启原容器以重新注册转发。每个容器恢复后验证宿主入口；backend IP 变化后检查并重载 nginx。不要修改端口暴露范围、删除卷或用整套 Docker 重启代替定位。
- **Tunnel 长期缓存了已漂移的 fake-IP**：HTTP/2 Tunnel 的 ready 在 200/503 间变化，公网 200/530 或超时，活动连接数曾归零。对照 cloudflared 的目标地址、当前 DNS 结果和代理日志；本次缓存的 `198.18.*` 地址对应关系变化，7844 连接被代理识别为无关域名。不能将所有这类超时都归因于 QUIC，也不能仅靠重复重启 Tunnel 关闭事故。

本机对此采用限定域名的 DNS 例外，而非关闭 TUN、切换全局代理或固定 Cloudflare 边缘 IP：

```yaml
dns:
  fake-ip-filter:
    - '+.argotunnel.com'
```

- 例外写入 Clash Verge Rev 的全局合并配置 `profiles/Merge.yaml`，并同步当前生效配置 `clash-verge.yaml`；保留原有 DNS 过滤项。操作前必须核实 `fake-ip-filter-mode` 为 blacklist、当前配置生成顺序及 DNS 自定义开关，不能直接覆盖已有 DNS 段或把 whitelist 当作 blacklist。
- 本机配置目录为 `%APPDATA%/io.github.clash-verge-rev.clash-verge-rev`。这些文件可能含代理凭据或控制器密钥，备份须加密，不得写入 Git 或打印；仅输出文件路径、hash、配置键与验证结论。
- 先用当前 Mihomo 核心 `-t` 验证，再通过已认证的本地控制管道重载。保持代理节点、分流规则、HTTP/2 和 TLS 校验不变。刷新宿主 DNS 缓存后，确认 `region1.v2.argotunnel.com` 与 `region2.v2.argotunnel.com` 返回真实边缘地址，而非 `198.18.0.0/15`，再重启既有 Tunnel 计划任务，清除进程内缓存。
- 连续观察公网实际请求、ready、活动连接数和注册计数，至少覆盖此前的复发间隔；同时验证匿名管理 API 401、公开内部路径 404，不把通路恢复当作真实群消息或网盘写入验收。
- 本次加密检查点、原配置 hash、限定变更证明及回退脚本在 `E:/gying-tools/releases/entrypoint-recovery-20260930`；`Rollback-ProxyDns.ps1` 默认只检查，加 `-Apply` 才恢复。脚本遇到后续配置变化必须停下人工合并，禁止覆盖新的用户配置。DPAPI 检查点仅适用于原主机/账号，不替代异机灾备。

## Tunnel 边缘连接被代理节点绑架（TUN 默认路由）

2026-10-14 17:17–17:23 的入口故障即此模式：公网 530/1033，同时本机 Clash Verge 机场节点全部测速超时（TUN/虚拟网卡模式）；机场节点恢复后 Tunnel 自动重连、公网随之恢复，说明故障在代理侧而不是源站。

- 判定：先看 `%APPDATA%/io.github.clash-verge-rev.clash-verge-rev/logs/service/*.log` 是否出现 `dial <机场组> (match Match/) ...:7844 error: context deadline exceeded`，再对照 `http://127.0.0.1:20241/ready` 的连接数；机场节点整体超时与 Tunnel 四条 HA 连接（`region1/region2.v2.argotunnel.com` 的 7844）同时归零即为该模式。故障中不改 Tunnel 凭据、origin 或 Access，先把代理侧与源站侧分开。
- 持久修复只影响 `DOMAIN-SUFFIX,argotunnel.com`、`IP-CIDR,198.41.192.0/24`、`IP-CIDR,198.41.200.0/24`（后两条 `no-resolve`）三类目标。Clash Verge Rev 2.4.7 的订阅增强文件（`profiles/<uid>.yaml`，`rules`/`groups` 类型）只接受 `prepend`/`append`/`delete`，`prepend` 插到规则最前；全局 `Merge.yaml` 是深合并，写 `prepend-rules` 这类键不生效，不要用它改规则。
- 兜底组用 fallback（`白嫖机场` → `DIRECT`）而不是固定 DIRECT：正常情况下沿用既有可用路径，机场整体超时时自动改走直连；健康检查 URL 用 `https://www.cloudflare.com/cdn-cgi/trace`（直连与机场均可达）。
- 改完必须重启 Clash Verge 客户端，文件改动不会自动生效。重启前可先用 `verge-mihomo.exe -t -d <配置目录> -f <配置文件>` 离线校验生成结果；重启后确认 `clash-verge.yaml` 时间戳更新、`/rules` 前三条为新规则、`/connections` 中 7844 连接 `rule=IPCIDR` 且 `chains` 末尾为兜底组，再连续请求公网首页。
- 回退材料 `tmp/tunnel-diagnose-20261014/backup` 保存变更前的增强文件模板与 `clash-verge.yaml.bak`；回退只需删除 `prepend` 条目并重启客户端。这些文件可能含代理凭据或控制器密钥，不得写入 Git 或打印。

## 数据安全排查

- 先使用 `SELECT` 和 dry-run。
- 同时检查少量样本和汇总数量。
- 重置卡住任务前先核实外部副作用。
- 重复清理要人工复核标题、别名和季信息。
- 失效链接要区分验证器不可用、疑似失效、保存目录为空和确认失效。
- 不能把可观测性问题变成删除或无限重试循环。

## 近期功能专项排查

- 单条失效资源修复返回成功但链接不可用：检查对应 `quark_transfer_task`/`xunlei_transfer_task`、`saved_path`、新 URL 验证结果和 `resource_link.link_status`；确认 provider 没有被错误地按另一网盘处理。
- 资源已重分享但 GYING 未同步：查看接口返回的 `gyingUpdated` 和消息，核对 `movie_source_identity` 映射、GYING 账号和 `/res/pan/add` 审计；没有明确映射时这是预期结果。
- QQ 每日推荐保存后未出站：先在 `/api/admin/qq-automation/daily-recommendation/run` 手动触发，检查目标群配置、后端日志和官方错误码；`40034105` 表示主动消息权限/模式问题，不应只靠重启后端解决。
- QQ 搜索进度提示乱码：重新执行 `tools/patch-openclaw-qqbot-gying.ps1`，确认运行时副本使用 JavaScript Unicode 转义并重启 Gateway。
- OpenClaw 管理命令被普通成员执行：核对 QQ 号与群 `member_openid` 是否同时进入 `allowFrom`、`ownerAllowFrom` 或 `commands.allowFrom.qqbot`；确认 `/bot-*`、`/stop`、`/approve` 在插件入口二次校验并拒绝未授权请求。

## 编码

Markdown、SQL、JSON 和 PowerShell 文件操作都显式使用 UTF-8。
连续异常汉字或问号表示可能出现 mojibake，应根据已知原文修复，
不能反复转码已损坏字符串。

## 关闭事故

满足以下条件后才能关闭：

- 原始故障路径已验证；
- 日志中没有立即复发；
- 自动化已明确重新启用或记录为关闭；
- 备份和回滚状态明确；
- 未完成工作已记录；
- 状态文档与在线证据不再矛盾。
