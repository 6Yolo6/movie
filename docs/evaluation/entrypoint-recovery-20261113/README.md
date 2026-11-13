# 2026-11-13 入口与 Docker 端口恢复

本次仅恢复现役服务，不更新业务代码或镜像。13:31:46（Asia/Shanghai）本机与公网首页、片库、详情 SSR、海报及服务健康入口通过，Tunnel HA=4；20 项入口检查、Redis 认证 PING 与 PanSou 内部 TCP 检查通过。上线前 nginx 网络重连在同镜像隔离夹具验证 9 项通过，夹具已清理。

## 故障证据

- 00:22:28 Windows System Event 2004 记录虚拟内存不足：vmmemWSL 约 6.80 GiB、Ollama 约 2.63 GiB，随后 SQL Server 出现内存不足错误。
- 00:23:33 Docker WSL/vpnkit guest 退出，00:25:35 Docker 自动恢复引擎。Windows 最后启动仍为 11 月 8 日，本次不是 Windows 计划重启。上述时序相关，不声称已经证明 WSL 被 OOM 终止。
- nginx 残留固定 IP `172.18.0.5`，该地址在恢复时被 quark-auto-save 占用；nginx 报 `failed to set up container networking: Address already in use`，退出 255。主机 80 无其他监听，Tunnel ready=200/HA=4，公网当时为 502。
- backend 容器内健康和片库正常，主机 8880 却 `RemoteDisconnected`；MinIO 主机 9000 同样空响应。其余服务并未全部停止。
- Docker host 日志 `com.docker.backend.exe.log.20261113-015246.817` 的 2480、2512、4444、4451 行及 VM 日志 `init.log.20261113-022015.227` 的 2193 行由只读独立调查核对；日志原文与凭据不进入仓库。

## 实际修复

| 服务 | 操作与结果 |
| --- | --- |
| nginx | 仅原容器断开旧网络连接，无 `--ip` 重连并保留 aliases；启动于 2026-11-13 13:17:41，动态 IP `172.18.0.10`，`IPAMConfig` 不再固定地址，nginx `-t` 通过。根 Compose 本来使用动态地址，本次消除运行态漂移。 |
| MinIO | 先尝试原网络重连，宿主端口仍空响应，保留失败事实；随后仅 SIGTERM 正常停止并启动原容器，于 2026-11-13 13:26:14 恢复主机 9000 HTTP 200。 |
| backend | 前置精确核验无当前代在途写任务；SIGTERM 正常退出 143，启动原容器于 2026-11-13 13:27:00，主机 8880 真实片库和 QQ 健康恢复 HTTP 200。 |

三者均保留原容器 ID、镜像、完整 Config 与 HostConfig；backend 仍为 `65fd377` 对应固定 `b7134d36…` 镜像，frontend 未替换。未执行 Compose 全栈重建、Docker/Windows 重启、数据库迁移或队列重置。其它容器仍为凌晨恢复后的运行代次；未保存其它容器完整初始时间向量，因此不声称已逐值比较原始向量。

Quark 2262 的 RUNNING/attempts=1/更新时间 00:23:32 早于现役 backend 00:25:52 启动；现役提交器只选择 PENDING/SUBMITTED，直接提交也跳过 RUNNING，没有自动恢复该任务的代码路径。该记录全字段 SHA-256 `70697cddd0008031a59c8ca3a81fb757af4fbee884e7d2c9ac5310640573771f` 在后端恢复前后相等，未重放或更改。9 月的 3426/434 两条既有 RUNNING 也保留；其它检查的发布、百度及清理运行态为空。本次由助手直接发起的 SQL 均为只读；原有自然调度可能正常写入。

## 验收与恢复材料

[入口验收](acceptance.json)覆盖真实本机及公网首页、两条片目列表、同片详情与 SSR、同海报两侧 SHA 相同；匿名 internal/QQ 返回 404、源站管理返回 401。backend、MinIO、Quark、OpenClaw、Agent、Router、Ollama、Tunnel 主机入口及认证 internal/source、publisher health 均通过。[依赖验收](dependency-acceptance.json)为 Redis 认证 PING 与 PanSou 内部 TCP，不代真实 provider 转存、分享或发布。

回退材料在 `E:/gying-tools/releases/entrypoint-recovery-20261113/`：三服务完整原 inspect 已通过 CurrentUser DPAPI 加密并完整解密认证，仅原主机/用户可用；`container-baseline.dpapi` SHA-256 为 `9b22d295ca5b2194b147a490234ad5f342dafcf40284416a468c206e6cb2d39b`。原镜像、数据卷及挂载均保留，没有数据恢复操作。恢复旧网络参数前必须重新核实地址未被占用，不能自动回退到本次已冲突的 `.5`。

独立复核另外完成 20/20 真实入口、三服务完整原配置比较、七个其它活跃容器未在恢复窗口启动及三服务有界 Docker 日志检查，ERROR/emerg/Exception/OOM 为零，见[独立验收](independent-verification.json)。首轮校验器误将 MovieDetailDTO 作为顶层 movie 字段，实际 HTTP 200；原失败字节留在仓库外，最终按现役 movie/resources 结构复验通过。

全仓秘密扫描原始退出 1，两个告警均来自 11 月 4 日已跟踪且字节未变的 bridgeToken 证据字段。只在内存逐项比较，确认其值是现役凭据的 SHA-256 指纹，均不等于凭据本值；本次交付范围扫描为零发现。未改历史证据或放宽扫描规则，不能把原扫描结果写成零告警。

## 保留事项

- 内存压力仍需处理：本次测得页文件在 E 盘约 16.3 GiB，提交限制约 31.74 GiB；恢复后提交使用约 85%，可用物理内存约 1.1 GiB。未修改页文件、WSL 配额、模型或其它用户进程，入口恢复不等于资源耗尽根因已消除。
- Quark 2262 的远端转存结果未核，不因本地记录旧或无 saved_path/share_url 推断云端无副作用；需单独只读对账，不能默认重试。
- 现役 backend `APP_TRUSTED_PROXIES=172.18.0.8/32`，当前该地址为 backend 自身，nginx 为 `.10`，已有真实客户端 IP 信任失配。此轮保持原配置，未扩大信任网段；后续应协调精确代理身份与 Docker 地址分配。
- 原安全 G01–G11、自然完整备份、真实群消息、云盘和外部发布验收不由本次探活代替。
