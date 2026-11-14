# GYING 自动采集去重、恢复与上线（2026-11-14）

新版本已上线并恢复自动采集。部署代码固定为 `0ce354e89664c4e3b768753c5b7452f721a8109c`，仅替换 backend/frontend，各激活一次；北京时间20:33:15/21:00:37启动，21:01:10恢复 Worker。最新电影、剧集、动漫使用实测最新顺序的 HITS 三目录，1..2页循环、20条/小时；新增 TIME/SCORE 后共有12个GYING榜单选项，GYING最多50页，TMDB仍500页。

21:13:20上线后的自然批次已完成：GYING17873成功处理20条，夸克7个、百度7个P2P归档全部首次成功，每盘共20份元数据文件；同轮TMDB17872处理20条成功。该轮重复跳过数为0，不能作为线上正向去重命中的实证。21:28:26独立只读终验PASS，确认当前服务、采集开关、宿主镜像约束、日常备份覆盖及历史保护。见[正式部署回执](deployment-receipt.json)、[自然批次](collection-postdeploy-watch.json)、[独立终验](final-independent-verified.json)。

## 实态调查

原配置选中 CSCORE_MOVIE/TV/ANIME，但三个来源均暂停。GYING 目录最后任务为10月31日，夸克 P2P 最后成功为11月8日19:39:36；三个高分榜历史最高页均曾到50，最近电影任务是第二轮17→18页，暂停不能证明当前全站已采完。

真实最新三个首页各48条，144条中118条尚无可信 GYING 来源身份（电影48、剧集37、动漫33）；可能已有 TMDB/canonical 片目，不能等同118部全新影片。四个新来源样本均有中文字幕1080P磁力和种子归档候选。三类型 HITS 与 TIME 顺序相同，SCORE 与 TIME/CSCORE 不同；未独立取得网站导航确认 HITS 合法性，只使用本次真实返回的最新顺序。六组 TIME/CSCORE 第51页均原样重复第50页，应限制为50页；现有自动路径完整读取每页48条再按offset处理，没有默认30条导致的漏尾问题。完整原始公开来源与页界数据按SHA保留；详情见 [来源汇总](concise-summary.json)、[页界证据](boundary-summary.json)、[资源样本](latest-resource-samples.json)。

## 上线前的配置恢复（历史）

18:34:53（Asia/Shanghai）当时仅修改 `resource.hub.gying.auto_sync_sources` 和 `resource.hub.gying.auto_sync_ranges`：选择现役已支持的 HITS_MOVIE/HITS_TV/HITS_ANIME，各1..2页；维持每小时20条。末页回第1页，持续检查最新目录。原三个 CSCORE 暂停标志、其它93条 sys_config、十三个容器代次、旧 Quark2262 全行保持。一次配置 PUT，零服务重启、直接SQL写入或旧任务重放；原自然调度在恢复后正常产生新采集与网盘归档。

18:53:07只读复验：夸克8/百度9归档全部SUCCEEDED；夸克7条新增资源（另1条既有资源原位更新）、百度9条新增资源均ACTIVE/NORMAL，旧任务和其它配置保持，本机首页/片库/新动漫SSR及匿名管理拒绝通过。见[恢复终态复核](restored-verification.json)及[首批任务/资源逐项复核](natural-first-batch.json)。

修改前 `G:/gying-backups/gying-latest-config-20261114-20261114T103447.957645Z` 的 SQL 与配置原像两份 age 已验证清单/大小/SHA和完整认证解密。不是跨服务原子快照或恢复演练。回退只恢复两项配置，保留当前 SQL、任务和已上传文件；外部助手 `E:/gying-tools/releases/gying-collection-20261114/restore_latest.py --rollback-report E:/gying-tools/releases/gying-collection-20261114/restored-latest-67b72365d5d14c89b7377c1eb7c5f95f.json` 要求当前配置精确相符，并重新备份，禁止自动重试。助手仅本地 AST mock 10/10、py_compile及独立审阅通过，未实际回退。详见 [配置结果](configuration-restored.json)。

首自然任务17838 HITS_ANIME于18:35:15创建、18:35:49成功，处理20条、directResourceLinks11、失败0、48条完整页/nextOffset20；生成夸克8与百度9个P2P归档。新17839夸克 SUCCEEDED/COMPLETE/attempt1、资源28422 ACTIVE/NORMAL，官方账号目录只读确认两份.txt/.torrent，文件ID、名称、父目录、大小156+19309=19465B一致。列表没有MD5/SHA1；只GET分享查询返回405/404，文件信息GET400，因此远端内容hash和自有分享目录绑定未独立验证。DB完成凭证与官方文件存在分开记录：[新夸克只读验收](quark-new-archive-readonly.json)。

## 已上线的去重与榜单

自动目录用精确、大小写敏感的GYING身份/季数/canonical及完成凭证去重。历史仅认可正常自有P2P_ARCHIVE种子资源；新采集完整资源保存和归档入队成功后保存来源指纹，含合法空资源。重复跳过详情/资源请求并推进offset；身份、标题、年份或季数变化会重新采集。同影片仅新增磁力、清晰度或集数而这些字段不变时自动路径仍跳过，可手动刷新或独立item retry。此凭证记录资源采集和归档入队，云端上传结果由独立归档任务确认。部分资源保存、入队、检查点CAS失败保留重试，不把单条磁力或旧/dbt账号种子当完整凭证。手动采集与独立item retry维持刷新语义。

新增 TIME 与 SCORE 三类型选项、独立暂停/range，TIME未显式设范围默认1..2页。GYING各接口/配置/UI最多50页，历史超限配置仅运行时收敛、保存原像不回写；TMDB仍500页。独立审查发现并修复部分保存误跳过、同mid跨季选错、测试mock误覆盖已存配置等问题。

早期 `Common.idle` 门禁被旧 quark_transfer_task2262 RUNNING阻断（11月13日00:23:32、attempt1），当时停止替换的原结论保留。它没有远端task_id、saved_path/share_url/response凭证；QAS现役1361任务无精确匹配，进程缺席不证明云端结果，QQ一次任务本就不写入长期tasklist。后续独立审阅冻结源码的启动/消费者路径，并用部署专用只读守护确认旧任务不会自动恢复、无新在途队列、QAS/原stale allowlist及旧完整行保持后才继续。原 `Common.idle` 规则没有修改，也没有把2262加入stale allowlist。旧2262与discovery9904全行SHA保持，云端结果仍UNKNOWN，不推断FAILED或重放。详见[本地旧任务](quark-2262-local.json)、[上游边界](quark-2262-upstream.json)及部署回执的分阶段守护。

## 实际部署与恢复材料

现役 backend image `sha256:807798b99d0054bca76f98126b960a010333e672d1675a05abb2b252631f77c4`，container `9239e03222057de70ba9b9b5fb05c9f1dd3ec9bb8ac74e7566d5e7e635c9b8ec`；frontend image `sha256:3dad5be604c4467e97ca52d408323420f2a863edd4ff14cb95a483bd49daa578`，container `e901160d28c1291e61e3ebcfde2bb9fe30eca546c46eacbdf596afb61e839527`。现役Compose为24/13层，旧两应用正常TERM，仅nginx进程内测试/重载，其它11容器代次保持。实际17个Config字段、backend153/frontend8个Env键值、有效挂载/网络及native Compose hash核验通过；前端642实际产物SHA/大小/UID/GID与固定构建一致。

首部署在backend已激活后因HostConfig原始表示差异停止：[原失败](deployment-initial-failure.json)。三条Binds只有顺序改变，Dns/DnsOptions/DnsSearch为[]→null；Docker29.7.2实际版本的处理逻辑、无DNS覆盖的有效Compose、完整唯一挂载及127.0.0.11解析器共同证明语义一致，不能声称raw HostConfig完全相同。之后只续作frontend，没有再次激活backend。Worker一次恢复成功后，严格零在途门禁遇到新自然采集而停止：[原恢复后失败](worker-post-restore-failure.json)。保留原回执/预约，跟踪全部14归档完成，再由独立只读finalizer形成正式成功回执；没有重复激活或重复Worker PUT。

百度存量补全父17583由原CHILD_FAILED在暂停时自然变为WORKER_DISABLED，恢复后又回CHILD_FAILED；只允许blockedReason/revision/nextRunAt和对应调度/更新时间/last_error自然变化。父仍BLOCKED、enabled=true、10分钟1条、currentChild17761；该child仍FAILED/3次耗尽且全行不变，其它六个保护hub历史全行保持。父完整行确有变化，守恒投影SHA `27360a8d04479aaba473f07caaa80dc0e3c1960fce89a78ed6d7f9480190e8db` 保持。正式回执如实记录 `protectedRowsUnchanged:false`、历史保持和调度投影验证true；未修复或重试此无关补全，见[自然调度差异证明](scheduler-delta-proof.json)。

替换前四份age在 `G:/gying-backups/gying-collection-predeploy-20261114-20261114T123052.082980Z`，manifest SHA `5187397506e120e825ec9101c0b625e2e36000f50fe4a622f750bcc0d0716889`；停止backend后三份age在 `G:/gying-backups/gying-collection-stopped-backend-20261114-20261114T123258.691176Z`，manifest SHA `b9251682de29aa3c15abfd78c53422c770e1d49617b5b263463ffd3e20e119d0`。七份完整认证，包括旧两镜像/有效配置/宿主材料、SQL、backend-data与队列key；两检查点不构成跨服务原子快照，也没有实际恢复演练。现役及回退overlay在 `E:/gying-tools/releases/gying-collection-20261114/deployment/` 的candidate/rollback材料中；回退须保留当前SQL、任务、数据、state/key和已上传文件，不直接导入旧dump或重放UNKNOWN。

21:16:34宿主 `E:/gying-tools/baidu-login/private/login-config.json` 仅expectedBackendImage唯一CAS为新backend，完整taskXML/owner/group/DACL保持；原像单份age完整认证，见[宿主约束更新](host-pin-applied.json)。21:19:30日常 `G:/gying-secrets/backup-config.json` 仅追加整个本release，旧规则/字段/权限保持，原像单份age认证；实际native before/proposed/after dry-run为46→47份，见[日常覆盖](daily-coverage-applied.json)。宿主原像manifest SHA `d6b002c076bb38ff45a54dc0bdfc404448517b0a488837f3290076088764ce40`，日常原像manifest SHA `045bc62b032fcdbce14be3773570eca520afe7a2d968f893cd94da3ad6caae32`；原像路径分别为 `G:/gying-backups/gying-collection-host-preimage-20261114-20261114T131537.108926Z` 和 `G:/gying-backups/gying-collection-daily-preimage-20261114-20261114T131758.153270Z`。未执行完整备份，自然完整日常备份仍待实际发生时验证。

21:25初次独立审计误用了修改前host/daily原始hash门禁，在live检查前停止，零生产写；[原FAIL](final-independent-initial-failure.json)保持。21:28:26新独立审计只认可两次已ACK配置后像，其它原配置/源码/计划严格保持；再次完整认证原像、核实两个配置精确增量/权限、DB/API Worker=true、实际服务/JAR/旧历史及本机公网首页200，通过且没有重复native dry-run或变更。上述原始失败均没有改写为成功。

## 验证

最终后端全套1494项：1493通过、1项既有Redis跳过、零失败/错误；必需真实MySQL及独立Java17 compile通过，两个隔离夹具容器按owned ID清理。源码417文件与完整测试结果绑定并与当前工作树逐字节核对，见[最终后端验证](backend-validation.json)。Java17隔离离线package成功，JAR SHA256 `0a536d7f471e1f375da26d7168d2c23bda259fd6c51befea079d916fe7b229b2`，无生产挂载、未重复测试，实际线上JAR与此包一致，见[后端package](backend-package.json)。前端生产build成功；首次Next全局telemetry配置初始化EXDEV失败保留，重试仅当前子进程设置NEXT_TELEMETRY_DISABLED=1。离线构建HTML/全部API mock的桌面与移动中英文浏览器4/4通过，覆盖GYING50/TMDB500、范围保存刷新、legacy及非法范围拒绝；首轮错误DOM断言已修正并保留。见[前端验证](frontend-validation.json)。早期136项定向通过；后续旧快照187项有两项失败、1490项有两项失败（无error/一项Redis跳过），其中两项mock设置缺陷已修正；这些旧快照不能替代最终代码验证。前端React17/17、TypeScript和目标ESLint通过。上述浏览器用例是部署前mock回归。21:14:52–21:16:22真实线上ADMIN页面中英、1440/390视口四组全部通过，12榜单/50与500页限制、0异常/溢出；实际28个页面/API GET、38份资产本机/公网各读一次共76个SHA/大小匹配。统计POST均阻断，配置/provider写入0，复用用户已登录设备，没有脚本登录或新建device，常规last_seen可更新，见[真实后台验收](frontend-live-readonly.json)。正式回执另含本机/公网首页、片库、新动漫SSR200和匿名管理401、内部QQ404；就绪11 PASS/1既有WARN/0 FAIL。[有界Docker日志](post-deploy-bounded-logs.json)到21:08:53双流ERROR/Exception/OOM匹配0、重启0；应用文件日志未验，不推广全天。未安全加固；此前安全待验保持。

新自然14个归档的任务完成/文件计数已核，但没有逐个独立官方内容hash与自有分享目录绑定验收。历史17839的官方文件存在核验也不能替代全部新任务的远端完整性证明。自动去重正向场景由后端测试验证，本次自然17873跳过0条，线上正向命中与同片新增集数/清晰度的自动发现仍有上述边界。

本节新增13份安全JSON均逐字节复制自外部原回执，[证据SHA清单](deployment-evidence-manifest.json)绑定原文件名、大小与SHA；完整配置、SQL、会话、日志正文及16张线上截图保留在受限外部release，不入库。
