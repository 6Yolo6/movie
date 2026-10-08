# 迅雷浏览器会话与 Token 同步

## 正常运行

宿主机计划任务 `GYing Xunlei Token Sync` 每两小时及用户登录时执行。使用当前用户的交互会话、Limited 权限、IgnoreNew 和五分钟执行上限；手动运行另有命名互斥锁。运行入口是 `tools/run-xunlei-token-sync.cmd`，无需重启 backend。

1. 用 Windows UI Automation 找到已打开的迅雷标签，确认地址栏是精确的 HTTPS `pan.xunlei.com` 后调用浏览器刷新按钮。不会重启 Edge、关闭用户窗口、模拟输入密码或抢占其他前台标签；后台切换后尽可能恢复原标签。
2. 若出现密码登录表单，使用浏览器自己的已保存密码/自动填充能力，最多提交一次。**不读取密码输入值，不复制 Login Data、Web Data、Local State 或 Cookie 数据库。** 验证码、短信/安全验证和登录错误均停止自动操作。提交前持久化失败保护；只有官方 API 验证成功或显式人工重试才解除。
3. 刷新后把应用 Web Storage 复制到每次独立的私有临时目录。该目录只有当前用户和 SYSTEM 可访问。临时 headless Edge 仅用于读取凭证，阻止其发起登录/refresh-token 轮换及 Drive 写请求，避免副本抢先消耗真实浏览器的 refresh token。
4. 按完全一致的 access token 合并 Web Storage 中的完整凭证与官方 Drive 请求头。限定官方主机、当前后端账户和至少五分钟有效期。新 access token 不混用旧 refresh/client/device/captcha；相同 access token 下 refresh token 的变化也会写回。
5. 使用固定官方 Drive 文件列表接口做只读验证，必须 HTTP 200、无业务错误且存在 files 数组。验证在 Node 中执行，避免页面 CORS 策略误报；禁止 HTTP 跳转。不会上传、转存、删除文件或创建分享。
6. 先保存并验证 Windows DPAPI CurrentUser 加密的旧状态，再以 backend 自身 UID 10001 通过 stdin 写入同目录私有临时文件，检测旧文件 SHA-256 是否变化后原子替换，复核字段及 `10001:10001 / 600`。检测到并发 backend 刷新时退出 75，不覆盖其新状态；此检查不是与 backend 共享锁的跨进程事务。
7. 关闭本次临时浏览器，仅清理本次已标记 run 目录中的叶文件；路径越界或 reparse point 时停止清理并报告。保留空目录及所有权标记，不递归删除目录、不接触真实浏览器 profile。

## 位置与安全

- 默认真实 profile：`%LOCALAPPDATA%/Microsoft/Edge/User Data/Default`。
- 依赖：Windows PowerShell 5.1、现有 Edge、Node，以及 `E:/gying-tools/xunlei-auth-helper/node_modules/puppeteer-core`。
- 私有运行区：`E:/gying-tools/xunlei-auth-helper/private-sync-v2`。
- 加密回滚：该目录下 `rollback/*.dpapi`，只可由原 Windows 用户/主机解密；不是异机灾备。
- 无敏感值的最新结果：`last-result.json`；登录保护状态：`live-session-status.json`。
- 后端状态：`/app/data/xunlei-auth.json`。禁止打印或提交其内容。
- 失败日志只记录阶段、固定原因/异常类型、状态码和计数，不记录请求头、Token、目录列表或密码。

没有可操作标签、Windows 锁屏或用户正在另一前台标签时，真实窗口刷新可能跳过；仅当存储中现有凭证仍通过实际 API 验证时才可同步。`validated=true` 不等于本次发生了登录或延长了 access token 有效期，应同时看 `live.refreshed` / `live.loginSubmitted`。定时任务不保证绕过平台风控。

## 人工恢复与验证

先在原 Edge profile 中打开迅雷云盘，必要时手工完成验证码/安全验证。不要退出所有浏览器窗口、强制改写 storage 到期时间、复制密码库或持续尝试错误密码。

```powershell
# 完整刷新和 API 验证，但不写后端
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -NoActivate
# 已人工确认登录表单/已保存密码后，允许一次被保护机制阻止的登录重试
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -AllowLoginRetry
# 只读观察计划任务，不能仅凭 Ready 判断同步成功
Get-ScheduledTaskInfo -TaskName 'GYing Xunlei Token Sync'
# 测试
node --test tools/tests/xunlei-edge-token-sync.test.cjs
powershell.exe -NoProfile -File tools/tests/test_xunlei_live_session.ps1
# 设置 XUNLEI_ACTIVATION_TEST_IMAGE 为本机已构建 backend 镜像后执行
python -X utf8 tools/tests/test_xunlei_token_activation.py
```

`-SkipLiveRefresh` 仅供诊断，正常计划任务不使用。不要为验证保存密码路径而强制退出有效生产登录；真实失效后的登录仍需后续自然触发观察。

## 2026-10-08 验收

- 17 项 Node 回归、21 项 Windows 检查、5 项无网络/无生产挂载的容器写回测试通过。
- 原真实迅雷页面刷新成功，已有登录无需重新提交密码；不写回验证及随后正式写回成功，补齐完整 refresh token。
- 写回后同账户官方 Drive API 为 200 且业务结构正常，状态权限为 `10001:10001 / 600`；原始状态的加密备份验证通过。
- 通过原计划任务入口再次执行成功、退出码 0；原两小时与登录触发恢复，真实 Edge 进程保留。没有业务容器重建、数据库变更、历史失败队列重置或真实外部发布。
- 发布/回退脚本与验证材料：`E:/gying-tools/releases/xunlei-browser-refresh-20261008`。脚本回滚可从 `scripts-before` 恢复；凭据回滚须先确认旧凭据尚可用，通过既有非 root 原子写入流程恢复，不能用 docker cp 产生 root-owned 文件。
- 验证码以及登录失效后使用自动填充提交的真实路径尚未强制触发；代码保护与当前已登录刷新成功不代替这些场景的端到端验收。
