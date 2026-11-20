# 迅雷浏览器会话与 Token 同步

[现状](current-project-status.md) · [11月11日刷新修复](evaluation/xunlei-token-refresh-20261111/README.md) · [11月2日诊断与验收](evaluation/platform-diagnosis-20261102/README.md) · [旧步骤和证据](history/README.md#git-history)

## 正常运行

GYing Xunlei Token Sync 每两小时及登录触发，ASUS/Interactive/Limited、IgnoreNew、5分钟，手动命名锁；入口 tools/run-xunlei-token-sync.cmd，不重启 backend。

1. 固定生产 backend、UID10001及当前账户。从持久状态读取完整凭据，用官方固定文件列表只读接口验证；有效期超过下次2小时运行加5分钟余量时直接复用，不打开浏览器。
2. 无效或临近到期时，新开独立 headless Edge，使用 private-sync-v2/env-login-profile 的受限持久会话，让官方网页正常续期。无需普通 Edge 迅雷标签，不复制普通 profile、Cookie 或密码库。
3. 会话无法自然续期时，仅从同一运行中 backend 的 XUNLEI_ACCOUNT/PASSWORD 在进程内存读账号密码。守护检查通过后导航固定官方登录页，有界等待唯一可信 iframe，每轮优先验证自然续期，再切换账号密码登录，最多提交一次；发现验证码或提交前持久 loginBlocked 保护，SDK重复密码signin请求也被拦截。验证码、短信、密码错误、提交后超时停止；后续自动任务只读检查，不再次提交，只有官方API验证成功或显式人工重试可解除。
4. 同 access-token 合完整 refresh/user/client/device/captcha 字段，不借用旧 token 的配套。只接受当前 backend 账户、有效期覆盖125分钟、固定官方 files HTTP200且文件数组成立及无业务错误。网络/5xx不能作为再次密码登录的依据。
5. 浏览器拦截 Drive 写操作；除精确官方认证/验证码初始化外，仅允许官方 `/risk` POST 的可解析 JSON对象且无 Authorization/密码字段，其余写请求拒绝；带 Bearer 的请求只允许官方 Drive/认证边界。官方验证拒重定向，不上传、转存、删除或分享。
6. 旧状态 DPAPI CurrentUser 加密备份并认证；backend UID10001 通过 stdin 写同目录600私有临时文件，旧 SHA 比对后原子替换、10001:10001/600并回读。并发变更退出75，保留 backend 已写的新状态；不是跨进程共享事务锁。
7. 仅正常关闭本轮独立 browser，清理 nonce 标记 run 目录叶文件；受限 env-login-profile 留作后续续期。清理越界/reparse停止，不接触用户普通 Edge。

## 位置与安全

PowerShell5.1/Edge/Node，依赖 E:/gying-tools/xunlei-auth-helper/node_modules/puppeteer-core。私有区 private-sync-v2 与 rollback/*.dpapi 仅当前用户/SYSTEM；DPAPI备份仅原用户/机器可解密。last-result/live-session-status 是脱敏结果；后端 /app/data/xunlei-auth.json 和受限 profile 禁打印或提交。账号密码不进入参数、日志或配置副本，只记固定阶段/原因/状态码/布尔值。

validated 表示官方只读接口验证通过；status=unchanged 表示当前凭据被复用，不能算本轮已重新登录。live.refreshed 表示浏览器页面已访问；是否实际轮换还看 changed 和 expiresAt。临近到期、自然失效、验证码与异常残留按实际运行观察，不故意登出有效账号。

## 人工恢复与兼容诊断

出现 needsInteraction 时，在同一受限 profile 手工完成平台要求的验证，关闭该独立窗口后再运行同步；保持同一账户，不重复错误密码。AllowLoginRetry 仅用于人工明确允许一次保护重试。

NoActivate 仅不写 backend，仍可能登录或轮换 token。旧普通 Edge UIAutomation/仅WebStorage副本路径保留为 UseLiveBrowser；SkipLiveRefresh 自动选择这条旧路径作诊断，原只读捕获仍禁止登录、refresh-token轮换和 Drive 写，不复制 Login Data/Web Data/Local State/Cookie DB。

~~~powershell
& 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' --user-data-dir='E:/gying-tools/xunlei-auth-helper/private-sync-v2/env-login-profile' 'https://pan.xunlei.com/'
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -NoActivate
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -AllowLoginRetry
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -UseLiveBrowser
powershell.exe -NoProfile -File tools/sync-xunlei-edge-token.ps1 -SkipLiveRefresh -NoActivate
Get-ScheduledTaskInfo -TaskName 'GYing Xunlei Token Sync'
node --test tools/tests/xunlei-edge-token-sync.test.cjs tools/tests/xunlei-env-browser-login.test.cjs
powershell.exe -NoProfile -File tools/tests/test_xunlei_live_session.ps1
python -X utf8 tools/tests/test_xunlei_token_activation.py
~~~

隔离写回测试需 XUNLEI_ACTIVATION_TEST_IMAGE 固定本地镜像。脚本回退恢复本轮 scripts-before，保留当前有效凭据；凭据回退须先核旧状态仍有效，使用既有非root原子流程，不 docker cp 生成 root 状态。

## 历史验收

2026-10-08：17 Node/21 Windows/5隔离写回测试；原已登录页面刷新、完整refresh、非root原子写回、官方同账户200/600/DPAPI验证、原任务入口exit0通过。当时未实际验自然失效后密码/验证码，不能用旧结果代本轮独立浏览器登录。材料 E:/gying-tools/releases/xunlei-browser-refresh-20261008。

2026-10-25：14:33自然任务成功，16:20未见同步私有profile；普通Edge10进程工作集约214.62MiB、private commit约381.64MiB，为不同指标和历史样本，不能全部归因于同步脚本。browser.close异常或外部超时仍可能残留，只清理确定属于本轮的实例。

2026-11-02：旧token自然过期、官方401及旧任务无标签失败已实证；17:51环境变量账号密码在独立官方Edge登录一次成功，无验证码，17:52同账户完整新状态DPAPI备份/非rootCAS写回/官方200通过。18:18主机脚本已启用，51 Node/21 Windows/5隔离写回以及原任务真实入口exit0通过；独立profile官方200、0密码signin且token未变化。详情见本轮验收记录；自然到期后的下一轮和真实验证码仍需按发生情况核对。

2026-11-11：修复前官方401和后台过期已实证，浏览器拦截官方/risk导致初始化失败。已精确允许无凭据JSON bootstrap并补固定登录导航/有界iframe等待，62 Node/21 Windows及就绪检查通过。23:03原任务自然续期updated/exit0/0密码；23:04同账户完整凭据/官方200/10001:10001_600/后台expired=false，到期11月12日10:49:07。原两小时任务启用，百度true/10/1及13容器代次保持，DPAPI旧状态认证匹配；空白诊断profile官方iframe已验，真实验证码/短信未发生。[本轮证据](evaluation/xunlei-token-refresh-20261111/README.md)。
