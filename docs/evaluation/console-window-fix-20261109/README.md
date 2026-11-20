# 百度扫码黑窗修复（2026-11-09）

21:21原任务改用 E:/gying-tools/baidu-login/baidu-login-hidden.exe，以GUI子系统/CreateNoWindow启动原PowerShell并传退出码。原参数、Limited/Interactive、每分钟触发器、设置/ACL及private/profile配置保持。

4Windows隔离回归、135秒3次自然启动0可见控制台/Terminal事件，21:28自然exit0/Enabled/Ready通过，限该桌面会话窗口。未提交扫码或云写。[验收与SHA](validation-summary.json)。

原任务XML/文件/ACL的age认证原像及回退 E:/gying-tools/releases/console-window-fix-20261109/；ManageConsoleFix.py rollback先核空闲/当前SHA，仅恢复原任务/注册脚本，保留新启动器及当前凭据。新机先[装启动器](../../../tools/install-baidu-login-launcher.ps1)再[注册任务](../../../tools/register-baidu-login-task.ps1)，禁止覆盖同名任务。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
