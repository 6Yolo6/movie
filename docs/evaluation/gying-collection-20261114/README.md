# GYING 采集恢复与去重候选（2026-11-14）

18:34:53（Asia/Shanghai）已恢复最新电影、剧集、动漫目录的自动采集。应用版本保持原有版本；新增去重和 TIME/SCORE 榜单代码尚未部署。

## 实态调查

原配置选中 CSCORE_MOVIE/TV/ANIME，但三个来源均暂停。GYING 目录最后任务为10月31日，夸克 P2P 最后成功为11月8日19:39:36；三个高分榜历史最高页均曾到50，最近电影任务是第二轮17→18页，暂停不能证明当前全站已采完。

真实最新三个首页各48条，144条中118条尚无可信 GYING 来源身份（电影48、剧集37、动漫33）；可能已有 TMDB/canonical 片目，不能等同118部全新影片。四个新来源样本均有中文字幕1080P磁力和种子归档候选。三类型 HITS 与 TIME 顺序相同，SCORE 与 TIME/CSCORE 不同；未独立取得网站导航确认 HITS 合法性，只使用本次真实返回的最新顺序。六组 TIME/CSCORE 第51页均原样重复第50页，应限制为50页；现有自动路径完整读取每页48条再按offset处理，没有默认30条导致的漏尾问题。完整原始公开来源与页界数据按SHA保留；详情见 [来源汇总](concise-summary.json)、[页界证据](boundary-summary.json)、[资源样本](latest-resource-samples.json)。

## 线上恢复

仅修改 `resource.hub.gying.auto_sync_sources` 和 `resource.hub.gying.auto_sync_ranges`：选择现役已支持的 HITS_MOVIE/HITS_TV/HITS_ANIME，各1..2页；维持每小时20条。末页回第1页，持续检查最新目录。原三个 CSCORE 暂停标志、其它93条 sys_config、十三个容器代次、旧 Quark2262 全行保持。一次配置 PUT，零服务重启、直接SQL写入或旧任务重放；原自然调度在恢复后正常产生新采集与网盘归档。

18:53:07只读复验：夸克8/百度9归档全部SUCCEEDED；夸克7条新增资源（另1条既有资源原位更新）、百度9条新增资源均ACTIVE/NORMAL，旧任务和其它配置保持，本机首页/片库/新动漫SSR及匿名管理拒绝通过。见[恢复终态复核](restored-verification.json)及[首批任务/资源逐项复核](natural-first-batch.json)。

修改前 `G:/gying-backups/gying-latest-config-20261114-20261114T103447.957645Z` 的 SQL 与配置原像两份 age 已验证清单/大小/SHA和完整认证解密。不是跨服务原子快照或恢复演练。回退只恢复两项配置，保留当前 SQL、任务和已上传文件；外部助手 `E:/gying-tools/releases/gying-collection-20261114/restore_latest.py --rollback-report E:/gying-tools/releases/gying-collection-20261114/restored-latest-67b72365d5d14c89b7377c1eb7c5f95f.json` 要求当前配置精确相符，并重新备份，禁止自动重试。助手仅本地 AST mock 10/10、py_compile及独立审阅通过，未实际回退。详见 [配置结果](configuration-restored.json)。

首自然任务17838 HITS_ANIME于18:35:15创建、18:35:49成功，处理20条、directResourceLinks11、失败0、48条完整页/nextOffset20；生成夸克8与百度9个P2P归档。新17839夸克 SUCCEEDED/COMPLETE/attempt1、资源28422 ACTIVE/NORMAL，官方账号目录只读确认两份.txt/.torrent，文件ID、名称、父目录、大小156+19309=19465B一致。列表没有MD5/SHA1；只GET分享查询返回405/404，文件信息GET400，因此远端内容hash和自有分享目录绑定未独立验证。DB完成凭证与官方文件存在分开记录：[新夸克只读验收](quark-new-archive-readonly.json)。

## 代码候选与上线阻塞

自动目录用精确、大小写敏感的GYING身份/季数/canonical及完成凭证去重。历史仅认可正常自有P2P_ARCHIVE种子资源；新采集完整资源保存和归档入队成功后保存来源指纹，含合法空资源。重复跳过详情/资源请求并推进offset；身份、标题、年份或季数变化会重新采集。同影片仅新增磁力、清晰度或集数而这些字段不变时自动路径仍跳过，可手动刷新或独立item retry。此凭证记录资源采集和归档入队，云端上传结果由独立归档任务确认。部分资源保存、入队、检查点CAS失败保留重试，不把单条磁力或旧/dbt账号种子当完整凭证。手动采集与独立item retry维持刷新语义。

新增 TIME 与 SCORE 三类型选项、独立暂停/range，TIME未显式设范围默认1..2页。GYING各接口/配置/UI最多50页，历史超限配置仅运行时收敛、保存原像不回写；TMDB仍500页。独立审查发现并修复部分保存误跳过、同mid跨季选错、测试mock误覆盖已存配置等问题。

`Common.idle` 只读部署门禁被旧 quark_transfer_task2262 RUNNING阻断（11月13日00:23:32、attempt1）；无远端task_id、saved_path/share_url/response凭证。QAS现役1361任务无精确匹配，进程缺席不证明云端结果，QQ一次任务本就不写入长期tasklist。因此停止backend/frontend替换，保留原应用/旧任务，不加入stale allowlist，不推断FAILED或重放。详见 [本地旧任务](quark-2262-local.json)、[上游边界](quark-2262-upstream.json)。配置恢复独立执行，不重启应用。

## 验证

最终后端全套1494项：1493通过、1项既有Redis跳过、零失败/错误；必需真实MySQL及独立Java17 compile通过，两个隔离夹具容器按owned ID清理。源码417文件与完整测试结果绑定并与当前工作树逐字节核对，见[最终后端验证](backend-validation.json)。Java17隔离离线package成功，JAR SHA256 `0a536d7f471e1f375da26d7168d2c23bda259fd6c51befea079d916fe7b229b2`，无生产挂载、未重复测试、未部署，见[后端package](backend-package.json)。前端生产build成功；首次Next全局telemetry配置初始化EXDEV失败保留，重试仅当前子进程设置NEXT_TELEMETRY_DISABLED=1。离线构建HTML/全部API mock的桌面与移动中英文浏览器4/4通过，覆盖GYING50/TMDB500、范围保存刷新、legacy及非法范围拒绝；首轮错误DOM断言已修正并保留。见[前端验证](frontend-validation.json)。早期136项定向通过；后续旧快照187项有两项失败、1490项有两项失败（无error/一项Redis跳过），其中两项mock设置缺陷已修正；这些旧快照不能替代最终代码验证。前端React17/17、TypeScript和目标ESLint通过。未做新代码生产API/UI验收或安全加固；此前安全待验保持。
