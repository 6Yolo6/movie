# Windows 每周优雅重启

本机计划为每周日 **09:30（Asia/Shanghai）**；本轮准备的首个未来触发时间是 **2026-10-25 09:30**。2026-10-18 的时间已过，不能补跑。计划要求接通交流电且 ASUS 会话已登录；错过不补跑。Windows 保持 ASUS 手动登录，不设置自动登录，也不保存空密码。

## 当前验收状态（2026-10-19）

- 控制器、安装器与恢复日志已实现；24 项隔离安全测试（含真实 Windows Job 超时终止其自有 worker/子进程）通过。
- 配置的备份公钥与私钥已通过内存中的随机小文件认证加解密测试，没有输出或落地私钥/明文。
- 同镜像、无网络/卷/端口的诊断 fixture 收到 SIGTERM 后退出 143；11 个原有容器的 ID、启动时间和运行状态不变。
- 2026-10-19 完整 readiness 通过（11 个现役容器、本地 AI 四服务、备份身份与无遗留维护日志）。两项任务仍未注册：本次正常 UAC 提权请求被用户取消，未绕过权限。管理员可运行下方精确安装命令，仅注册任务、不立即重启。
- 没有进行生产停服/整机重启演练；真实周日重启与登录恢复仍待首次自然运行验收。现有其他安全整改事项不因此关闭。

## 控制边界

运行前验证主机、时区、程序/配置、MySQL 和入口、容器白名单、宿主计划任务、本机 AI 生命周期、备份身份。只有周日 09:30–09:39 可以开始，最终发出重启前必须仍在 10:30 之前；错过窗口直接取消，计划任务 `StartWhenAvailable=false`。

先记录容器 ID/镜像、宿主任务启用状态、本机 AI 实例及运行开关、原生 MySQL/Redis 启动类型。仅暂停 GYing Cloudflare Tunnel、GYing QQ Channel Auto Post、GYing Xunlei Token Sync 的新调度。原本停止但配置自动启动的 mysqlzt/Redis 暂改 Manual，恢复时还原原启动类型而不主动启动。

本机 AI 的 frontend/router/backend 通过实例绑定的生命周期握手停止。当前 Ollama 没有可安全附着的独立控制台：应用完成停止、连接清零并成功卸载模型后，保持该受管理进程运行，交给 Windows 正常关机；这不宣称 Ollama 已提前退出。任何检查失败均取消重启，不强杀服务。

容器按 nginx、frontend、OpenClaw、backend、publisher、source、Quark、诊断占位、PanSou、MinIO、Redis 的顺序退出；业务容器使用 `docker stop --timeout -1`，不让 Docker 自动 SIGKILL。独立 worker 写完成收据；控制器超时取消重启，恢复器必须等同一请求、容器 ID、启动会话的完成收据，不能仅凭 inspect 一时显示 stopped 就启动它。

唯一不恢复的例外是一次性诊断占位 `gying-xunlei-path-probe-20261006`。其启动命令包含不可重复执行的 `mkdir /tmp/probe`，并非业务服务。外部配置精确绑定它的 ID、镜像、命令 SHA-256 和 `restart=no`，核实 PID 1 仅等待唯一 `tail -f /dev/null` 子进程后向该子进程发 SIGTERM。容器与文件保留，周重启后维持 stopped；如要再次诊断，另行审查启动方式，不能自动重建或删除。

关闭写入方前后检查在途业务记录。仅忽略两条已核实的陈旧记录，匹配表、ID、状态和精确更新时间，任一变化即不再忽略：

| 表 | ID | 状态 | 更新时间 |
| --- | --- | --- | --- |
| resource_hub_task | 3426 | RUNNING | 2026-09-28 19:56:43 |
| xunlei_transfer_task | 434 | RUNNING | 2026-09-28 20:45:54 |

不修改、重放或重置这些记录。存在其他在途任务时取消当次重启。

所有容器退出后生成新加密备份。备份及认证验证共享 15 分钟预算；自有 Python/age/tar/Docker 客户端通过启动握手后才被允许运行，整个进程树位于单独 Windows Job。超时仅终止该 Job 的临时备份进程，以及同时匹配随机 label、精确名称、只读挂载、无网络的本轮 Docker 归档器；不涉及任何既有业务进程/容器。超时或失败的 manifest 标为 maintenance_aborted，并取消重启进入恢复。备份成功要求 manifest 完整、SHA-256 一致，并对每个 age 文件认证解密到空设备；不自动恢复数据库。然后正常停止 MySQL、Docker Desktop，调用 `shutdown.exe /r /t 0`，不带 `/f`。用户未保存的程序可阻止系统重启。

## 恢复与失败

全局操作系统文件锁防止主控制器、watchdog 与登录恢复同时改动。失败后保留可重试日志；watchdog 在同一启动会话内尝试恢复。若存在尚未返回的 Docker 停止请求，保持保守等待，不制造延迟误停。

ASUS 手动登录后，恢复任务延迟 90 秒启动。先启动 MySQL、Docker Desktop，再依赖顺序恢复**基线中正在运行的相同 ID/镜像容器**；身份改变即拒绝自动处理。不执行 compose up，不恢复停止的旧容器，不启动 NapCat，不重置业务记录。

只恢复基线启用的本机 AI 服务；服务健康通过后还原原生服务启动类型和三个宿主任务原启用状态。只按原状态启动 Tunnel，不手动触发 QQ 发布或迅雷同步。最终验证本机 API、Tunnel readiness 和公网入口。原有系统自动启动行为仍受 Windows 自身控制，本工具不设置自动登录。

## 安装、检查和撤销

本机配置位于 `G:/gying-tools/restart-20261018/config.json`，只引用受保护的凭据文件；不把密钥值写入配置或仓库。安装器会为运行副本、配置和日志设置仅 ASUS/SYSTEM/Administrators 可访问的目录权限。

先执行技术 readiness（报告在途任务，但此模式不要求此刻空闲）：

```powershell
python -X utf8 tools/maintenance/weekly_restart.py --config G:/gying-tools/restart-20261018/config.json --readiness
& tools/maintenance/Install-WeeklyRestart.ps1 -ConfigFile G:/gying-tools/restart-20261018/config.json
```

管理员 PowerShell 中安装；这一命令仅注册任务：

```powershell
& D:/gying-movie/movie/tools/maintenance/Install-WeeklyRestart.ps1 -ConfigFile G:/gying-tools/restart-20261018/config.json -Apply
```

安装器拒绝覆盖同名任务；先注册恢复任务再注册每周任务，核验 NextRunTime、Interactive/Highest 和禁止补跑。失败时仅删除本轮新建任务并还原配置。成功后将 XML 与结果保存在外部 installation 目录。它不会绕过 UAC，也不能从非管理员进程宣称安装成功。

取消未来计划时仅禁用 `GYing Weekly Graceful Restart`；若存在未完成维护日志，应保留 `GYing Maintenance Recovery` 并先处理恢复。日志目录为外部配置的 `stateRoot`。手动恢复入口：

```powershell
python -X utf8 G:/gying-tools/restart-20261018/runtime/weekly_restart.py --config G:/gying-tools/restart-20261018/config.json --recover
```

不要对当前生产运行 `--execute` 或 `--rehearse` 来“测试”。隔离测试：

```powershell
python -X utf8 -m unittest discover -s tools/maintenance/tests -v
```
