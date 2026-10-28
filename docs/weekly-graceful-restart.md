# Windows 每周优雅重启

每周日09:30 Asia/Shanghai，首个XML计划2026-10-25 09:30（Windows显示09:30:30）；10月18已过不补。交流电/ASUS已登录、手动登录、不设置自动登录或保存空密码。[完整原证据/控制边界](history/README.md#git-history)。

## 已记录的安装与运行状态

24隔离安全测试（含Windows Job子树超时）+8偏移测试、公私钥内存认证、同镜像无网卷诊断SIGTERM143、readiness11容器/本机AI四服务通过；11原容器不变。UAC注册每周/登录恢复两任务Ready、Interactive/Highest，禁止补跑/强制结束、登录延迟90秒。安装证据 `G:/gying-tools/restart-20261018/installation-20261019-215841`。任务注册不等真实运行；下面保留各单次窗口证据；10月27真实重启、10月28手动恢复结果见后文，修复后的ASUS自然登录恢复仍待验。

2026-10-25 09:30:30每周任务实际触发、结果1；原preflight前无尝试日志，确切原因不能还原。同期开P2P在途是可能阻挡证据，不能断言唯一原因。现役已补独立attempt-log、配置/runtime/主机绑定与有界等空闲。

单次 `GYing One-off Graceful Restart 20261025-2000` 于2026-10-25 20:00实际触发，20:00:02返回1。独立attempt-log `c4b57f7156b540fe97d0ad73e908c1a3.json` 的stage=window、reason=`Invalid bounded one-off window`，未进入preflight、未停服务、未创建恢复journal；permit未消费且已过期，不复用。2026-10-26核主机最后启动仍为2026-09-24 21:51:41。根因为安装器生成.NET七位小数时间，绑定Python3.10.6的`datetime.fromisoformat`拒绝此格式。下次每周为2026-11-01 09:30:30。

2026-10-26修复安装器：窗口字段改为秒级+08:00，注册前用配置Python调用实际runtime验证permit、失败走原回退；维护HTTP探针显式浏览器UA，避免Cloudflare403/1010将健康公网误判失败。实际Python3.10.6维护42/42、PowerShell任务时间8/8通过，本机API/Tunnel ready/公网主页与API探针均正常。12:56回读已安装runtime SHA `aa7fb8b56658965fbbf5309c6f8ea852de47b448166f6951e62bec4a0c66e6b8`，新增 `GYing One-off Graceful Restart 20261026-1305` Ready，窗口13:05–13:15开始、14:05截止（Windows NextRunTime13:05:05）；15项绑定/安全/恢复/入口校验全部通过，10个原运行容器的ID/镜像/启动时间和主机启动时间不变。旧脚本、任务XML与原子回退保留于 `G:/gying-tools/restart-20261018/oneoff-installation-20261026-125553/`，排查/验证和取消新窗口脚本在 `E:/gying-tools/releases/restart-window-20261026/`。12:56时新permit尚未消费、真实执行待验；该轮实际运行及恢复见下文。

2026-10-26 13:05:05 单次任务实际进入维护，13:08:42 因 `gying-movie-social-publisher-1` 优雅停止超时取消。此前已停 nginx/frontend/OpenClaw/backend；publisher 停止收据13:19:04完成，维护journal13:19:32标为 `recovered`，入口和原宿主任务开关恢复。该轮未发系统重启，旧permit已消费，不复用。13:35外部记录 `container-init-deployment.json` 确认publisher/quark按原镜像、环境与卷部署 `init=true`，Quark用 `SIGINT`；其余容器身份/镜像/启动时间不变，入口正常。原镜像和六份认证加密检查点保留。

17:30只完成注册/验证脚本与 `baseline-1730.json` 准备，Windows无对应单次任务，且无 `registration-1730.json` 或成功回读结果，因此未执行；不能将文件准备视为已注册。该阶段为何未完成注册缺少执行结果，不能还原。

19:47排查发现普通会话无法读取高权限AI进程路径，导致readiness误报未登记frontend；管理员只读复验通过，四AI身份匹配、`busyJobs=[]`、`pendingAttempts=0`。62项维护安全测试及8项任务时间测试通过，10个运行容器的实际停止信号接收检查通过。19:52安装器原子备份/更新runtime为SHA `41a527965dbe98ae5cf1d1cbb18fd4aa3cc422a070332e53339f2d9007948683`，注册 `GYing One-off Graceful Restart 20261026-1959`，窗口19:59–20:09开始、20:59截止，Windows回读NextRunTime **19:59:59**，Ready、Interactive/Highest、禁止补跑/强制结束。原每周11月1日09:30:30和ASUS登录90秒恢复任务保持启用。旧runtime/任务XML与安装回退在 `G:/gying-tools/restart-20261018/oneoff-installation-20261026-195220/`，基线、管理员就绪结果、注册结果与取消未来窗口脚本在 `E:/gying-tools/releases/restart-window-20261026/`。19:52时真实触发与重启仍待验；该轮实际执行、恢复及后续修复见下文。

19:59单次任务实际进入维护，在本机AI停止阶段抛出PowerShell异常，20:00:19恢复完成。frontend/router完成匹配退出收据，尚未停止容器或进入备份；原任务开关及本机/Tunnel/公网入口恢复。20:34按相同子进程调用复现 `local-common.ps1:67` 的空值方法异常：进程退出与属性读取竞争，`StartTime` 为空。修复读取并校验启动时间/可执行路径，空值或读取失败返回无匹配身份；原退出回执、实例与端口检查保持。7项身份/退出竞态测试通过，20:43–20:44相同子进程全量AI正常停止、退出码0并恢复四原服务；Agent代码 `e3d97f6` 已提交推送，原脚本在 `G:/gying-tools/restart-20261018/local-common.before-exit-race.ps1`，诊断和回归结果在上述外部release目录。

20:48重新通过管理员readiness并注册 `GYing One-off Graceful Restart 20261026-2055`，开始窗口20:55–21:05、21:55截止，Windows回读NextRunTime **20:55:55**、Ready。现役runtime SHA仍为 `41a527965dbe98ae5cf1d1cbb18fd4aa3cc422a070332e53339f2d9007948683`，四AI和10容器身份/信号就绪，20:48无在途/无未完恢复；20:54快照出现一条新元数据RUNNING，执行前仍须自然空闲校验。安装旧runtime/任务XML与回退在 `G:/gying-tools/restart-20261018/oneoff-installation-20261026-204818/`。该轮20:55:55实际触发，等空闲后20:56:01进入维护；20:56:15采集器又启动新元数据任务15685，停backend前的在途复检取消维护，20:58:00恢复完成。主机未重启，permit已消费。

2026-10-26 22:03准备了22:11单次窗口与独立Worker恢复守护，但暂停开关误用只读备份账号，MySQL拒绝UPDATE；目标重启任务随即禁用。暂停operation于22:04:02标为restored，2026-10-27只读回查开关仍为原true、无待恢复维护，主机最后启动仍2026-09-24 21:51:41。2026-10-27 12:31新MySQL检查点 `G:/gying-backups/resource-hub-pause-20261027T043140Z/` 的 `mysql.sql.age` SHA-256与完整age认证解密通过；13:00复验实际backend应用账号具备sys_config所需SELECT/UPDATE，只读备份账号保持原用途。13:03只读基线10个运行容器停止信号检查、本机及公网六入口通过；出现新的元数据RUNNING，由新窗口暂停消费后自然排空，不改任务状态。外部证据在 `G:/gying-tools/restart-20261018/preparation-20261027-130338-4bc884dd/`；新helper和注册流程的后续处理见下文。

2026-10-27完成暂停工具和 `Install-QuiescedRestart.ps1`：从现役backend内存读取应用账号并先验证sys_config SELECT/UPDATE，错误只保留阶段/数值码；检查点非空、24小时内、hash/size及许可在暂停前复验；完整绑定ASUS登录恢复任务的Action/Principal/Trigger/Settings，兼容Windows省略默认Enabled/WakeToRun。prepared阶段取消不写数据库；部分暂停由CAS恢复，守护等待原维护恢复完成；注册输出损坏仍按独立已知任务绑定取消，取消/恢复错误分别保存。107项维护测试及8项任务时间测试通过，仅隔离测试和只读链路验证，不能代替真实重启。

用户随后指定**2026-10-27 17:30（Asia/Shanghai）**。冻结方案在17:15运行预检和临时Worker暂停，仅全部通过才注册17:30–17:40开始、18:30截止的单次重启；不提前暂停两小时，不复用旧许可。候选5文件及SHA清单、计划注册入口 `G:/gying-tools/restart-20261018/scheduled-20261027-1730/Schedule-1730.ps1` 已放入仅ASUS/SYSTEM/Administrators可访问的外部目录。15:30:20 UAC启动返回“操作已被用户取消”，管理员预检和任务注册没有执行。15:45回读17:15准备任务/17:30重启任务均不存在，10原容器/主机启动不变、worker原true、0待恢复维护、六入口正常；证据 `launch-failure.json`。本方案**尚未注册**，需在管理员PowerShell运行该受限注册入口；未取得管理员令牌时不会改runtime、开关或任务。

2026-10-27用户将已过期的17:30计划改为**20:00（Asia/Shanghai）**。独立冻结候选在 `G:/gying-tools/restart-20261018/scheduled-20261027-2000/`，7文件SHA清单/受限ACL、107维护测试及8时间测试通过。19:31:55管理员预检（应用sys_config SELECT/UPDATE、新MySQL检查点完整age认证、现役任务绑定、0待恢复维护）通过并注册准备任务Ready，Windows回读19:45:45。19:45:57实际准备完成，部署helper SHA `52898f5ecab8c6dca91c08a3045d2b4d9556b740c18dacf461bb6e4d60431fd9`，Worker按CAS从true暂停为false，注册单次 `GYing One-off Graceful Restart 20261027-2000`，Ready、ASUS/Interactive/Highest、NextRunTime **20:00:00**，开始20:00–20:10、21:00截止，禁止补跑/强制结束。独立恢复守护Running，原ASUS登录90秒恢复任务保留。19:59十原容器ID/镜像/启动时间不变、六本机/公网入口正常、0待恢复维护；主机最后启动仍9月24日21:51:41，真实重启/登录恢复待验。前后验收、schedule-result与execution-result在冻结目录；runtime/任务XML及helper回退分别保留于 `G:/gying-tools/restart-20261018/oneoff-installation-20261027-194539/` 和 `G:/gying-tools/restart-20261018/quiesced-installation-20261027-194520-76c271ee/`。

## 2026-10-28 重启后恢复

系统事件1074/6006/6005与维护journal互证：10月27日20:02:39发出正常重启，Windows最后启动为20:03:15.5。新检查点 `G:/gying-backups/20261027T120113.606809Z` 的21份age在journal记录hash及完整认证成功；不是异机恢复。十个原运行容器自行恢复，ID/镜像与停机基线一致；登录恢复20:51:51返回1，journal仍为recovery_failed，Worker保持false。

10月28日定位到Windows IPv4/IPv6 TCP排除范围11402–11501覆盖Ollama11434；无监听但实际bind返回10013，child.stderr.log同样记录access permissions。21434临时loopback绑定通过。Agent启动、后端URL、健康/排空/退出统一读取 `.local/environment.json` 可选整数 `ollamaPort`（默认11434），本机设置21434；维护 `local_running(c)`读取同一文件，避免第二份端口配置。Ollama独立隐藏控制台保留，子日志采用三个明确句柄；新生命周期目录仅当前用户/SYSTEM/Administrators，实际新child日志ACL验证通过。未删除Windows端口保留或重启HNS/WinNAT。

18:20:11受限管理员脚本验证候选/备份hash和进程身份后，对精确匹配、子进程已退出的旧Node宿主发送正常控制台中断，确认退出；设置21434并同步现役weekly runtime，SHA `3223c5c1cfe9eb53e5db2854a4dc1abc397d447ead8a7c27013ed48c7eff2de9`。原 `--recover` 于18:21:12使journal进入recovered；readiness退出0、0 pending、10容器与4个本机服务匹配。随后原Worker guard于18:21:37按CAS还原true，任务返回0。QQ/Xunlei任务开关及mysqlzt/Redis的Auto启动类型恢复，后两服务仍Stopped。没有再发系统重启、没有重建容器或改业务JAR/镜像。

18:22起九个本机/公网入口200，Tunnel ready200/HA4；1.7B真实readFile返回正确package名称/dev命令，Router真实调用通过；4B对“查询片库中的星际穿越”完成计划与queryCatalog，返回一部。简短历史追问返回“未读到，无法确认”，不算多轮质量通过；网站登录用户链路未复验。维护111项、Ollama配置14项、身份7项、Node4项、Job宿主6项、新目录ACL3项与原console fixture12项通过。原 `GYing Maintenance Recovery` 任务LastResult仍保留10月27日失败1，本轮执行的是同一现役命令并取得0，不将手动恢复视作下一次自然登录已验。

回退/执行证据在 `G:/gying-tools/restart-20261018/recovery-20261028/port-21434-recovery/`：配置/原runtime/原代码备份及hash、固定候选、执行结果、入口/真实AI、回退说明。旧单次permit已消费或过期，新runtime不匹配旧hash，不重新签发复用。回退端口需先按实例排空AI并确认原11434可绑定；仍被Windows保留时不能直接改回。下次每周任务为2026-11-01 09:30:30，修复后自然ASUS登录恢复仍待验。

## 控制边界

- 开始仅周日09:30–09:39，发重启须10:30前，过窗取消（StartWhenAvailable=false）。校验主机/时区/程序配置/MySQL/入口/白名单/宿主任务/AI/备份，失败取消，不强杀业务。
- 2026-10-25已安装的补丁：开始窗口内只对在途业务最多等待5分钟，每次重新检查窗口及完整preflight，未停任何服务前等自然空闲；其他失败不重试。独立attempt-log记录窗口、waiting_idle和失败理由，未创建维护journal不触发恢复。36项隔离测试通过，当日外部runtime SHA `a4305d05ec1696a81560fe141ffc2253b51de3c62fba1b33946ae01cb778f53c`；10月26现役SHA及附加验收见上文。
- 记录ID/镜像/开关/原生MySQL-Redis启动类型；仅暂停Tunnel/原频道/Xunlei任务新调度。原已停止但Auto的mysqlzt/Redis暂Manual，恢复类型不主动启动。
- 本机AI实例握手停frontend/router/backend；旧Ollama实例无安全控制台时，应用停/连接清零/模型卸载后保进程待正常关机，不宣称提前退出。
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
