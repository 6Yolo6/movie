# 微博权限、TMDB 采集与迅雷续期核查

2026-11-02，Asia/Shanghai。微博和 TMDB 为只读调查；迅雷按用户要求使用环境变量账号密码在独立浏览器登录，并同步经过验证的同账户 token。未手工发博、发送群消息、转存文件或修改 TMDB 范围、游标、历史任务。

## 微博仍无法发布

17:39 真实会话：side/cards 为 HTTP 200、ok=1，登录有效；feed/allGroups 为 HTTP 200、ok=0、error_type=confirm，原文为“你的账号暂时无法进行发博、发评、关注、点赞等操作，可在验证身份后恢复。”平台没有给出触发限制的原因，不能据此归因于频率或登录过期。

11月1日经用户确认未实际发出的12条原记录已恢复队列。11月2日读取时，247、269、270、271共4条为 PREPARE_FAILED，其余248、249、263–268共8条为 PENDING。失败均发生在实际发送前，原因是微博要求身份验证；没有成功时间或外部地址。目标5保持启用、自动开启、每日09:00、3篇、500秒，last_auto_run_at 为11月2日09:00:08。

恢复待发布只恢复队列，不能解除平台限制。重新扫码恢复登录也不能解除身份验证要求。当前代码在发送前抛 WeiboSecurityError，首条失败后停止批次，避免继续发送。账号持有人需在同一微博账号完成官方身份验证，再使用原记录重试；本轮没有再次恢复、重置或试发。

## TMDB 高分剧集在重复空跑

17:43:49–50 直接读取上游 /tv/top_rated：total_pages=131、total_results=2610，第1页20条、第131页10条，第132、306、360页为空。当前剧集范围360–500页，今天截至调查的9轮都请求第360页，attempted=0、pageSize=0、nextPage=360。

最后有内容的剧集任务为13686，10月23日17:03，第131页处理1条；自10月23日21:05以来，104轮空页仍标为 SUCCEEDED。近期没有新增高分剧集采集，重复的是超出榜单范围的空请求。

高分电影仍正常推进：11月2日16:16任务17298处理20条，17条新建、3条更新，下一页251。第243页重现属于失败条目重试后继续，不能解释为重新从头入库。

范围改变与管理 PUT 返回200的窗口对应：10月23日150、10月25日200、10月27日300、10月30日306、11月1日20:06:44变为360。访问日志 user_id=null 且没有请求正文，不能确认是谁改的；sys_config.updated_at 的 upsert 复制旧时间，不能用于证明未变更。

建议保留上游 total_pages，范围起点超出末页时明确显示“范围无数据”、停止高频空请求并低频复查，在界面展示真实末页，以及修正配置更新时间。用户本项要求查历史，本轮未调整范围或部署这类业务变更。

## 迅雷一次恢复与定时续期

旧状态在11月2日07:48:58到期，官方固定只读 files 接口返回401。backend环境 XUNLEI_ACCOUNT 和 XUNLEI_PASSWORD 都非空；状态文件含 refresh token，权限10001:10001/600。11月2日16:33:33原定时执行exit1、needs_live_tab、未提交登录：原脚本依赖已有普通Edge迅雷标签及浏览器自动填充，不读取环境变量账号密码，也不会新开登录窗口。

17:51 在独立受限profile打开官方 https 登录页面，使用环境变量账号密码提交一次，成功且无验证码。新凭据由同一 access token 对应的 auth响应、浏览器storage和官方请求头合并，没有借用旧refresh/client/device字段。同当前backend账户，完整refresh/client/device配套，激活前官方 files HTTP200且files数组成立。

旧状态已用DPAPI CurrentUser加密备份并完整认证。17:52:39以UID10001通过stdin写同目录600临时文件，旧SHA校验后原子替换；全字段回读一致、10001:10001/600，激活后官方 files 再次HTTP200。新到期为11月3日05:51:24。源码在权限检查、状态读取和请求入口按文件mtime重载，无需重启backend；未用新ADMIN会话代验管理页面。仅关闭本轮独立Edge，普通用户Edge保持。

现有任务是 ASUS/Interactive/Limited、每PT2H及登录触发、IgnoreNew、5分钟，入口 tools/run-xunlei-token-sync.cmd。只读ops snapshot和readiness退出0；readiness 11通过、1条既有QQ schema警告、0失败。最后自然任务结果仍是16:33的1，不把手动恢复伪装为下一轮自然续期成功。

18:18已启用主机定时续期脚本，原任务注册与两小时间隔保持。先复用经官方验证且有效期覆盖下次2小时运行及5分钟余量的backend状态；临近到期或无效时打开独立profile，必要时才读环境变量登录。发现验证码或提交前持久保护，密码错误或提交后超时不重复提交；SDK密码signin请求受同一保护且只允许首个，纯token交换保留。严格官方POST/Bearer边界，保留同账户验证和原DPAPI/CAS写回。

51项Node（新增34和原17）、21项Windows/ACL/DPAPI、5项固定本地backend镜像的隔离写回检查均通过，无失败/跳过。隔离写回命令与旧脚本一致，验证600/非root/原子替换、失败保留、并发退出75；新候选PowerShell5.1语法通过。

候选 NoActivate 真实接口HTTP200、unchanged，未写backend。18:18独立headless profile真实检查时强制打开浏览器并禁止密码fallback，官方200、同账户完整状态、0 signin请求、token未变化，浏览器正常关闭。随后手动启动原GYing Xunlei Token Sync任务，LastTaskResult=0、Ready；新入口日志为XUNLEI_ENV_SYNC=unchanged/VALIDATED=true/HTTP=200/LOGIN_SUBMITTED=false，last-result同样unchanged/validated，下一次仍18:33:33。正式入口后再次官方200、10001:10001/600、新状态到期仍11月3日05:51:24。手动启动原任务不等于自然到期后已续期，真实验证码与自然失效路径继续按发生情况观察。

[脱敏验收摘要](validation.json)保存阶段、测试计数与实际入口结果，未保存token值。应用镜像和服务进程保持，本轮只启用主机脚本。

## 材料和回退

原只读JSON、snapshot/readiness、主机task、私有状态metadata及候选/原脚本副本在 E:/gying-tools/releases/weibo-diagnosis-20261102/。旧凭据DPAPI备份位于 E:/gying-tools/xunlei-auth-helper/private-sync-v2/rollback/，仅原用户和机器可解密。不能直接把已过期旧状态当可用凭据回退；脚本回退保留当前新状态。

11月1日部署的backend和publisher镜像及7152edb代码保持，本轮不重建应用、不重放历史队列。原58 PASS/11 FAIL/1 UNKNOWN安全基线和G01–G11未关闭；主机续期验证不表示全局安全加固完成。相关[前次上线证据](../publishing-recovery-20261101/README.md)保留原日期。
