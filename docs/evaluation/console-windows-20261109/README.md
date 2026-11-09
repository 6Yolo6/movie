# 本机周期黑窗只读调查

2026-11-09，WindowsTerminal窗口闪现与计划任务 `GYing Baidu QR Login` 对应。19:49至19:53每分钟12秒左右出现；后续观察捕获同一时点的 powershell.exe，父进程归属 Schedule / Task Scheduler 服务。COM Task Scheduler回读及19:58:13进程再次确认：任务执行 `E:/gying-tools/baidu-login/run-baidu-browser-login.ps1`，每分钟运行。

启动参数已有 Hidden 且位于 -File 前，实际仍有终端窗口事件。这里记录来源，不把已有Hidden当作已消除黑窗。未修改或停用计划任务、未启用任务历史日志或提权。WMI ProcessStartTrace权限不足后，使用窗口事件与进程快照只读观察；所有观察器已经结束。

CIM计划任务秒字段存在序列化问题，时序采用窗口事件、进程实际CreationDate与COM时间。任务运行中267009只表示RUNNING，不解释为退出失败。报告不包含原始认证参数或Cookie；[证据路径与SHA](evidence-summary.json)。
