# 百度扫码任务黑窗修复

2026-11-09 21:21（Asia/Shanghai）已部署最小宿主修改：`GYing Baidu QR Login` 的启动命令改为 `E:/gying-tools/baidu-login/baidu-login-hidden.exe`。该程序使用 GUI 子系统，内部以 `UseShellExecute=false`、`CreateNoWindow=true` 启动原系统 PowerShell，等待完成并传递退出码。原完整参数、Limited/Interactive 身份、每分钟触发器、任务设置和任务 ACL 保持。

原 runner、Node helper 和 `private/login-config.json` 字节保持，仅更新注册脚本并新增启动器及维护源文件。本次修改没有手动启动真实任务、提交扫码登录或执行云写操作。此前调查另见[黑窗来源](../console-windows-20261109/README.md)。

4 项真实 Windows 隔离测试全部通过：GUI PE 子系统、子 PowerShell `GetConsoleWindow=0`、中文路径与引号/空参数/尾反斜杠传递、等待和非零退出码，以及重叠调用保留互斥行为。135 秒只读窗口事件观察捕获 3 次自然启动及 3 个 PowerShell 子进程，均在同一交互会话、主窗口句柄为 0，可见控制台/WindowsTerminal SHOW 事件为 0。观察末次任务仍在运行，267009 只表示 RUNNING；21:28:07 后续读取确认自然任务已完成、结果 0、Enabled/Ready，精确任务绑定和 ACL 保持。观察器已结束。这些证据覆盖本次桌面会话与观察区间。

原任务 XML/SDDL、4 个选定宿主文件及 ACL 已保存到 Git 外的 `host-originals.json.age`，完整 age 认证和内容逐字节回读通过；这份限定检查点不含浏览器 profile，不替代应用部署的完整备份。旧安装证据保留，新运行文件清单为 `E:/gying-tools/releases/console-window-fix-20261109/installation.json`，新增发布目录的日常备份覆盖提案交由主部署流程统一 CAS 处理。

回滚工具为同目录 `ManageConsoleFix.py rollback`：先核对自然空闲及本次任务 XML/注册脚本 SHA，再恢复原任务和原注册脚本；新增启动器文件保留，当前配置和凭据不恢复为旧副本。未来新机器安装时先运行 [安装启动器](../../../tools/install-baidu-login-launcher.ps1)，再运行[注册任务](../../../tools/register-baidu-login-task.ps1)；注册器继续拒绝覆盖同名任务。

准备阶段遇到两类兼容问题：Codex 的 PowerShell 7 模块环境影响 Windows PowerShell 5 自动加载 `Get-Acl`，限定部署助手改为显式导入系统 Security 模块；任务 XML 省略默认 RunLevel，改用实际 COM `RunLevel=0` 与 `LogonType=3` 正向证明，未向任务补写该节点。这些调整仅作用于限定部署助手，扫码逻辑未变。

[脱敏验收与证据 SHA](validation-summary.json)。
