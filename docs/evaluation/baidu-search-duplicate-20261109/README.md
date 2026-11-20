# 百度搜索临时清理与全片库重复扫描上线

2026-11-09（Asia/Shanghai），固定代码 `048795f0fe4e9ef95c3aed144e3f1a2efcb51e54` 已仅替换 backend/frontend；实际 backend 于 22:39:17 启动，frontend 于 22:48:50 启动，22:49:07 本机部署核验完成。宿主百度扫码黑窗修复已于 21:21 部署，后续固定镜像 pin 和备份核验完成。[精选验证结论、外部路径与 SHA](validation-summary.json)。

## 实现与测试

QQ/网站共用百度搜索筛选和单序号选择；新临时请求独立 scope，与永久 mv/tv/ac 归档、正式详情和自动发布隔离。默认保留十分钟，首次自有分享确认后固定清理时钟，不因重试或设置变化延长。用户选择的彻底删除只作用于本系统新建的精确临时目录；每次核账号、目录 ID、完整文件树和持久提交意图，不清空整个回收站。POST 结果不确定只做 GET 对账。原 [bd16 候选与删除边界](../baidu-search-temp-20261109/README.md) 和 [上传/分享兼容实测](../baidu-share-compat-fix-20261109/README.md) 保留原验证时点。

重复扫描由最近 100/200 条改为按 ID 每批 500 条遍历有效全库；limit/page 仅分页候选。统一空格、大小写和中英文季后缀，使用现有标题、系列名和别名交集，不自动翻译。类别、年份、季数、版本和明确人员冲突保持约束，未知季数不猜第一季，合集排除。扫描 100,000 条、5,000 对候选、50,000 次比较及拥挤桶均设上限，达限返回 complete=false。页面逐对显示两记录、正常资源数、依据和待审状态；仅 dryRun=true，自动合并关闭，dryRun=false 返回 409。资源计数排除临时百度、软删除、非 ACTIVE 及明确非 NORMAL 链接。

后端 1,384 项中 1,383 通过、1 项既有 Redis 跳过，零失败/错误；必需真实 MySQL 与独立 Java17 compile 通过，408 份源码绑定固定提交。前端 101/101 React、typegen/TypeScript/受影响 lint/隔离 Next build 通过，90 份完整 Git 构建源、92 份唯一提交绑定文件和 375 份固定运行产物核验通过。

固定候选浏览器 mock 验证覆盖中英、明暗和 390/1280：重复扫描成功、分页、空、部分和错误共 40/40，表格内部横向滚动 8/8，48 份 PNG 留 SHA；搜索七组通过。生产 API 请求为 0，这些结果不等同于真实 ADMIN、转存或删除。首次 harness 等待扫描结果超时的失败保留；重试调整按钮滚动定位及 analytics mock 拦截，不改业务代码。

## 真实部署与入口

用户在旧写入者正常停止后执行两个增量 SQL；助手只读核对新 scope 列、索引、清理表和配置契约。新部署守护重新获取实际 OS 锁，核精确 TERM/StartedAt、七份 age、固定输入、原 17580/17583/17584/3426 历史和当前 activity，再启动已测试候选。旧 idle/消费者兼容和严格零在途门禁仍为 FAIL，不能启动 b550 旧消费者来绕过 UNKNOWN。

首部署在新 backend ready 后因自然 TMDB METADATA_SYNC 17600 进入 RUNNING 暂停后续动作；该任务 22:39:36 开始、22:40:12 自然 SUCCEEDED。只读复验精确 history/activity/schema 回到基线后，经过独立审查的续作助手只替换仍旧的 frontend，未再启停已就绪 backend。原失败报告和 SQL handoff 均保留，不把暂停写成服务崩溃或自动回退。

| 服务 | 实际固定产物 |
| --- | --- |
| backend | image `sha256:b972a85394e12647efaf440534f9702e67a4f62cff78d7c8bf1f07a10c83b6e4`；JAR `6be29239cc55912fc6bce6a8ab2e66a457f15a7ab3316b3915175bf9d1b9090e` |
| frontend | image `sha256:37d166c94c9566611f0c15ffc43f618faac57e0f4d4f29d17381cb93364ce715`；375 份实际产物 |

实际 Compose 为 17/11 层，仅本轮候选镜像变化，原资源、身份、卷、网络和运行配置保持；nginx 测试与平滑重载、本机首页/片库通过。独立运行核验 17 PASS/0 FAIL，固定源码、image/JAR、375 产物和区间内全部非目标容器身份保持；本机/公网 20 入口及 194 份静态/语言资产 SHA 核验闭环。

公网管理入口是正常 Cloudflare Access 302，未认证管理 UI。公网搜索 raw HTML 因 Cloudflare Insights 脚本和一处换行与本机不同，精确去除这两项后内容等于真实本机及固定候选；不宣称 raw HTML SHA 相同。原入口误期望 200 和 HTML 差异失败保留，补证分别记录。

## 重复扫描真实 ADMIN 与快照验收

22:53 原 ADMIN 验收因有效 device=0、EXISTING_ACTIVE_ADMIN_DEVICE 门禁失败而未发送 duplicate API；固定产物、九表与历史锚核验记录和原失败文件保留。用户随后选择并实际重新登录管理员，最新 root 聚合有效 ADMIN device=1；脚本没有登录、创建或撤销设备，USER 设备有效性本轮未核。

23:05:12–23:05:33 从用户登录后的新基线，真实 nginx ADMIN 接口四次 POST 均200/dryRun=true：全库15,366条、144对待审候选（mv102/tv36/ac6）、complete=true、排除100/冲突12/拥挤桶0。MobLand两记录0/8资源、tv/2025/season1、NAME_AND_PEOPLE以及全部144对资源数与真实在线SQL一致；limit=1/page2、默认100共两页、超范围页夹取通过。九表业务/授权字段 SHA、历史锚与两固定镜像/容器身份在该新基线前后保持，仅正常 device.last_seen_at 更新，API审计也可追加，不能声称全数据库零写入或登录前后账号/device哈希不变。脚本配置、转存、分享、删除、发布、merge及直接SQL写入为0。已认证浏览器扫描界面尚未独立验收。

22:59:30 在线 repeatable-read 一致性只读 SQL 快照取得 15,366 条有效片目，实际已部署 JAR 的 scanner/matcher 类逐项 SHA 绑定且未重新编译；Java17、network=none 的隔离快照 adapter 扫出 144 对候选（mv102/tv36/ac6），complete=true，排除100、冲突12、拥挤桶0。MobLand 两记录 tmdb_tv_247718/1YKW 的正常资源为0/8、tv/2025/season1、依据 NAME_AND_PEOPLE；limit=1/page2、默认100和超范围页夹取三组通过。九表业务/授权 SHA、历史锚及两固定容器 generation 前后保持，SQL写入、HTTP请求、登录/设备新增和云请求均为0。

该隔离快照验证不执行生产 MyBatis/JDBC 数据读取链或 HTTP ADMIN 认证接口，后续真实HTTP接口已另行验收通过。144对仍是待审候选；共享系列名可将同年不同续作纳入候选（本次包含无间道2/3、黑客帝国2/3），必须逐对判断，不是144对确认重复或已批准合并。原 [MobLand 漏检与候选审计](../duplicate-audit-20261109/README.md) 保留历史数量，新结果独立记录。

## 宿主、备份与回退

扫码任务继续 Enabled/Ready、Limited/Interactive，每分钟触发；GUI 启动器令子 PowerShell 不创建控制台。四项 Windows 隔离测试及 135 秒三次自然启动 0 可见控制台/WindowsTerminal SHOW 事件、21:28 自然任务 exit0 已验，只覆盖该桌面会话与观察区间。[宿主修复证据与限定回退](../console-window-fix-20261109/README.md)。

新 host pin 仅将预期 backend image 更新为 b972，原 installation 历史、任务 XML、NTFS ACL、profile 和会话保持，严格前后无待扫码执行，未打开浏览器或云写。停机七份 age 检查点为 `G:/gying-backups/baidu-search-duplicate-20261109-20261109T142050.854970Z`；完整认证及有效配置、OCI、单事务 SQL26表、当前队列/key、宿主原 XML/ACL和会话 GCM/同账号/同卷/权限核验通过，未导入 SQL 或恢复生产数据。

pin 后三份 age 为 `G:/gying-backups/baidu-search-duplicate-post-pin-20261109-20261109T145702.543117Z`，22:57:32 完整认证完成；880 host 文件、494 目录及全部内容/结构/ACL、精确任务 XML、七份 backend-data、会话 GCM/同账号/0600/0700/UID10001 和前后库存/自然空闲保持。这些是限定检查点，不称跨服务原子快照。

23:12:23 独立 post-pin 审计27 PASS/0 FAIL，三份 age 完整流认证、旧配置加密前像与原安装绑定、仅 expectedBackendImage 字段更改、其他字段规范字节保持、当前 metadata/任务/profile/ACL、会话 GCM及七份 backend-data/固定 generation 前后核验通过。此前两次独立审计各完成16检查后在 HostCheckpoint 快照调用发生 RuntimeError，原文件保留、原因未确定。V2只改显式exe/NonInteractive/CREATE_NO_WINDOW与脱敏诊断，原门禁不减；新前后独立读取stderr均0字节、Ready摘要匹配，未新观察可见窗口或执行真实任务。

本轮 release 与黑窗 release 已以原配置认证备份及 CAS 加入日常覆盖，40 项 dry-run、两新增路径均通过；自然完整备份尚未验。仅应用回退保留当前 SQL/data/state/key；固定兼容 bd16 fallback 及来源/加密材料在外部 release 目录，须重新执行 scope/history/身份守护。b550 旧消费者不兼容新 scope，不能直接启动；本轮未执行应用回退或历史恢复。

23:10:50 最终本机五个健康入口均200，就绪11 PASS/1既有QQ schema WARN/0 FAIL。23:11:13两应用固定镜像/运行identity/restarts0/OOM=false保持；本轮Docker stdout/stderr均0字节，仅代表该采集范围，不称全应用文件日志已验。

## 保留的待验

真实正常影视百度归档、临时转存、自然十分钟彻底删除、配额变化、群内资源选择和 GYING 发布未验。原17583 BLOCKED/17584 UNKNOWN保留，自动补全关闭、十分钟一条，不重放历史、删除探针或搬迁永久目录。百度删除 API 没有服务端 expected-fsid CAS，精确核验与账号锁只能缩小最后 GET 到 POST 的外部并发窗口。

安全仍 58 PASS/11 FAIL/1 UNKNOWN，G01–G11 未关闭；本轮限定 backend/frontend 业务授权不表示加固完成或未来授权。
