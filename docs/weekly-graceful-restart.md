# Windows 每周优雅重启

每周日09:30 Asia/Shanghai，首个XML计划2026-10-25 09:30（Windows显示09:30:30）；10月18已过不补。交流电/ASUS已登录、手动登录、不设置自动登录或保存空密码。[完整原证据/控制边界](history/README.md#git-history)。

## 已记录的安装与运行状态

24隔离安全测试（含Windows Job子树超时）+8偏移测试、公私钥内存认证、同镜像无网卷诊断SIGTERM143、readiness11容器/本机AI四服务通过；11原容器不变。UAC注册每周/登录恢复两任务Ready、Interactive/Highest，禁止补跑/强制结束、登录延迟90秒。安装证据 `G:/gying-tools/restart-20261018/installation-20261019-215841`。任务注册不等真实运行；下面补10月25真实触发与单次窗口，ASUS登录恢复尚待自然验收。

2026-10-25 09:30:30每周任务实际触发、结果1；原preflight前无尝试日志，确切原因不能还原。同期开P2P在途是可能阻挡证据，不能断言唯一原因。现役已补独立attempt-log、配置/runtime/主机绑定与有界等空闲。

单次 `GYing One-off Graceful Restart 20261025-2000` 于2026-10-25 20:00实际触发，20:00:02返回1。独立attempt-log `c4b57f7156b540fe97d0ad73e908c1a3.json` 的stage=window、reason=`Invalid bounded one-off window`，未进入preflight、未停服务、未创建恢复journal；permit未消费且已过期，不复用。2026-10-26核主机最后启动仍为2026-09-24 21:51:41。根因为安装器生成.NET七位小数时间，绑定Python3.10.6的`datetime.fromisoformat`拒绝此格式。下次每周为2026-11-01 09:30:30。

2026-10-26修复安装器：窗口字段改为秒级+08:00，注册前用配置Python调用实际runtime验证permit、失败走原回退；维护HTTP探针显式浏览器UA，避免Cloudflare403/1010将健康公网误判失败。实际Python3.10.6维护42/42、PowerShell任务时间8/8通过，本机API/Tunnel ready/公网主页与API探针均正常。12:56回读已安装runtime SHA `aa7fb8b56658965fbbf5309c6f8ea852de47b448166f6951e62bec4a0c66e6b8`，新增 `GYing One-off Graceful Restart 20261026-1305` Ready，窗口13:05–13:15开始、14:05截止（Windows NextRunTime13:05:05）；15项绑定/安全/恢复/入口校验全部通过，10个原运行容器的ID/镜像/启动时间和主机启动时间不变。旧脚本、任务XML与原子回退保留于 `G:/gying-tools/restart-20261018/oneoff-installation-20261026-125553/`，排查/验证和取消新窗口脚本在 `E:/gying-tools/releases/restart-window-20261026/`。新permit尚未消费，实际重启/ASUS登录恢复待验，不能据注册宣布完成。

## 控制边界

- 开始仅周日09:30–09:39，发重启须10:30前，过窗取消（StartWhenAvailable=false）。校验主机/时区/程序配置/MySQL/入口/白名单/宿主任务/AI/备份，失败取消，不强杀业务。
- 2026-10-25已安装的补丁：开始窗口内只对在途业务最多等待5分钟，每次重新检查窗口及完整preflight，未停任何服务前等自然空闲；其他失败不重试。独立attempt-log记录窗口、waiting_idle和失败理由，未创建维护journal不触发恢复。36项隔离测试通过，当日外部runtime SHA `a4305d05ec1696a81560fe141ffc2253b51de3c62fba1b33946ae01cb778f53c`；10月26现役SHA及附加验收见上文。
- 记录ID/镜像/开关/原生MySQL-Redis启动类型；仅暂停Tunnel/原频道/Xunlei任务新调度。原已停止但Auto的mysqlzt/Redis暂Manual，恢复类型不主动启动。
- 本机AI实例握手停frontend/router/backend；Ollama无安全控制台，应用停/连接清零/模型卸载后保进程待正常关机，不宣称提前退出。
- 容器按nginx/frontend/OpenClaw/backend/publisher/source/Quark/诊断/PanSou/MinIO/Redis排空；业务docker stop --timeout -1防自动SIGKILL，worker写同请求/ID/启动会话完成收据。控制器超时取消，恢复等匹配收据，不凭暂时stopped启动造成迟到误停。
- 诊断gying-xunlei-path-probe-20261006唯一例外不恢复：精确ID/镜像/命令hash/restart=no，核PID1唯一tail后SIGTERM子进程；留容器/文件且停止，不能自动重建/删。

仅忽略两条精确旧在途，任字段/更新时间改变即不忽略；其他在途取消，不改任务：

| 表 | ID / 状态 / 更新时间 |
| --- | --- |
| resource_hub_task | 3426 / RUNNING / 2026-09-28 19:56:43 |
| xunlei_transfer_task | 434 / RUNNING / 2026-09-28 20:45:54 |

全部停后新age备份/认证共享15分钟预算；自有worker握手在独立Windows Job，超时只终止本Job备份进程与匹配随机label/名称/只读挂载/无网的归档容器，不动业务。失败manifest maintenance_aborted、取消重启进恢复；成功要求清单/hash/每age完整认证到空设备。正常停MySQL/DockerDesktop，shutdown /r /t 0不带/f，未保存应用可阻止关机；不自动恢复DB。

## 恢复与失败

OS锁协调控制器/watchdog/登录恢复，保可重试日志；未完Docker停止保守等待。ASUS登录90秒后启MySQL/Desktop，按依赖恢复基线原运行且同ID/镜像容器，身份变拒绝；不用compose up/停旧容器/NapCat/队列重置。仅原启用AI，健康后还原原生启动类型及三任务开关，按原状态启Tunnel、不手触QQ/Xunlei；核API/ready/公网。系统自身Auto行为另记，不设自动登录。

## 安装、检查和撤销

配置 `G:/gying-tools/restart-20261018/config.json` 引受限凭据，runtime/config/log ACL仅ASUS/SYSTEM/Administrators。只读技术readiness报告在途但不要求此刻空闲：

```powershell
python -X utf8 tools/maintenance/weekly_restart.py --config G:/gying-tools/restart-20261018/config.json --readiness
& tools/maintenance/Install-WeeklyRestart.ps1 -ConfigFile G:/gying-tools/restart-20261018/config.json
```

已安装不重复Apply；安装拒同名/先恢复后每周，UAC/精确XML及同分钟非负偏移校验，失败只撤本轮新任务。取消未来只禁GYing Weekly Graceful Restart；未完日志保留Maintenance Recovery先恢复，stateRoot保存日志。手动恢复有实际状态变化：

```powershell
python -X utf8 G:/gying-tools/restart-20261018/runtime/weekly_restart.py --config G:/gying-tools/restart-20261018/config.json --recover
python -X utf8 -m unittest discover -s tools/maintenance/tests -v
```

不以当前生产--execute/--rehearse补测试；完整安装/撤销及身份/预算证明从原文核验。
