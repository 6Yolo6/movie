# 迅雷浏览器会话与 Token 同步

[现状](current-project-status.md) · [完整旧步骤/证据](history/README.md#git-history)

## 正常运行

GYing Xunlei Token Sync每两小时及登录触发，Limited/IgnoreNew/5分钟，手动命名锁；入口tools/run-xunlei-token-sync.cmd，不重启backend。

1. UIAutomation核原Edge精确HTTPS pan.xunlei.com标签并刷新，尽量恢复原页，不重启/关窗口/抢其他前台。
2. 登录仅浏览器已保存自动填充最多一次，不读取密码、不复制Login Data/Web Data/Local State/Cookie DB。验证码/短信/错误停止，先持久失败保护，仅API成功/显式人工重试解除。
3. WebStorage本轮私有副本仅当前用户/SYSTEM；临时headless只读凭据，禁止登录/refresh-token轮换/Drive写，避免消费原profile token。
4. 同access-token合完整字段，不混旧refresh/client/device/captcha；只官方主机/现backend账户/≥5分钟有效，相同access的refresh变化也写。
5. Node固定官方文件列表只读200+files/无业务错、拒跳转，避免页面CORS误报，不上传/转存/删/分享。
6. 旧状态DPAPI CurrentUser备份/验证，backend UID10001 stdin写同目录私有临时，旧hash比对后原子替换、10001:10001/600；并发变更退出75不覆盖，非backend共享事务锁。
7. 仅关本轮browser/清nonce标记run目录叶文件，越界/reparse停止；留空目录/标记，不递归删或接触真实profile。

## 位置与安全

真实profile `%LOCALAPPDATA%/Microsoft/Edge/User Data/Default`；PowerShell5.1/Edge/Node及 `E:/gying-tools/xunlei-auth-helper/node_modules/puppeteer-core`。私有区private-sync-v2、rollback/*.dpapi仅原用户/机器，last-result/live-session-status为脱敏结果；后端/app/data/xunlei-auth.json禁打印。只记阶段/固定原因/异常类别/状态码/计数。

无标签/锁屏/另前台刷新可能跳，只在存储仍通过真实API时同步。validated不等本轮登录/续期，另看live.refreshed/loginSubmitted；不绕风控。

## 人工恢复与验证

原profile手工完成必要验证，不强退有效登录/改storage到期/复制密码库/重复错密码。NoActivate仍可能刷新/一次登录，只是不写backend，不是纯只读：

```powershell
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -NoActivate
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -AllowLoginRetry
Get-ScheduledTaskInfo -TaskName 'GYing Xunlei Token Sync'
node --test tools/tests/xunlei-edge-token-sync.test.cjs
powershell.exe -NoProfile -File tools/tests/test_xunlei_live_session.ps1
python -X utf8 tools/tests/test_xunlei_token_activation.py
```

AllowLoginRetry仅人工确认允许一次保护重试；容器写回测试需XUNLEI_ACTIVATION_TEST_IMAGE固定本地镜像。SkipLiveRefresh仅诊断；真实失效登录等自然观察，不故意登出生产补验收。

## 2026-10-08 验收

17Node/21Windows/5隔离写回测试；原已登录页面刷新、完整refresh、非root原子写回、官方同账户200/600/DPAPI验证、原任务入口exit0通过。未实际失效后自动填密码/验证码路径，backend独立续期未代验。材料 `E:/gying-tools/releases/xunlei-browser-refresh-20261008`，scripts-before回脚本；凭据须先核旧仍有效再既有非root原子流程，不docker cp生成root状态。无业务重建/DB/队列重置/外部发布。

## 临时浏览器与内存观察

脚本刷新已有普通Edge的迅雷标签；读取WebStorage副本时会临时启动headless Edge，finally正常关闭本轮实例。browser.close错误可能被吞掉，异常或外部超时仍有残留可能；清理不能误关用户普通浏览器。

2026-10-25自然任务14:33结果0；16:20只读观察未见同步私有profile，普通Edge10个进程工作集约214.62MiB、private commit约381.64MiB。它们是不同指标和当时样本，普通后台Edge不能都归因于同步脚本，工作集不等独占物理内存。原后续同步/失效登录/异常残留仍按自然运行验证，不因本次整理修改凭据或浏览器设置。
