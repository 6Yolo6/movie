# Windows 每周优雅重启

每周日09:30 Asia/Shanghai，首个XML计划2026-10-25 09:30（Windows显示09:30:30）；10月18已过不补。交流电/ASUS已登录、手动登录、不设置自动登录或保存空密码。[完整原证据/控制边界](history/snapshots/20261022/docs/weekly-graceful-restart.md)。

## 当前验收状态（2026-10-19）

24隔离安全测试（含本轮Windows Job子树超时）+8偏移测试、公私钥内存认证、同镜像无网卷诊断SIGTERM143、readiness11容器/本机AI四服务通过；11原容器不变。UAC注册每周/登录恢复两任务Ready、Interactive/Highest，禁止补跑/强制结束、登录延迟90秒。安装证据 `G:/gying-tools/restart-20261018/installation-20261019-215841`。任务注册不等真实运行；未生产停服/整机重启，周日/登录自然验收待补。

## 控制边界

- 开始仅周日09:30–09:39，发重启须10:30前，过窗取消（StartWhenAvailable=false）。校验主机/时区/程序配置/MySQL/入口/白名单/宿主任务/AI/备份，失败取消，不强杀业务。
- 2026-10-25候选：开始窗口内只对在途业务最多等待5分钟，每次重新检查窗口及完整preflight，未停任何服务前等自然空闲；其他失败不重试。独立attempt-log记录窗口、waiting_idle和失败理由，未创建维护journal不触发恢复。36项隔离测试通过；外部runtime尚未安装。
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
