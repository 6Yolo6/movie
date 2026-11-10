# 百度归档文件集合校验修复

2026-11-10 23:09:58（Asia/Shanghai）仅backend激活固定源码 `2f282d26525b3ce64c5c854a7c237c61dc85b142`，23:10:29完成运行态核验、23:11:32完成真实GET验收、23:14:33完成host pin与post检查点，23:16:45日常覆盖44→45通过。23:19:01最终只读保持通过；23:19:53下一影片只读准备完成，但来源无资源，不适合真实归档验收。真实百度影片归档仍未成功；三次实跑额度已用完，父任务与三个历史UNKNOWN保持暂停。

## 修复与隔离验证

先确认上传的txt若在后续上传、创建分享或确认自有分享期间被删除或替换，原有目录非空、仅元数据和自有分享可用检查仍可能允许发布。现在全部上传后通过 `VERIFY_UPLOADED_FILES` 检查完整预期文件集合；确认现役自有分享后、ResourceLink持久化前通过 `VERIFY_SHARE_FILES` 再检查一次，并再次检查目录仅含元数据。每次使用已有只读 `findUploadedFile` 检查预期FileData及保存的fileId。

缺失或相同内容不同fileId报 `UPLOAD_CHECKPOINT_MISMATCH(false,true,false)`，保留上传检查点及已创建shareUrl，不补传。provider内容/size冲突、登录和网络错误保持原错误码与flags，父归档子任务使用原ACTION_REQUIRED分流并保留此前attempts（本轮初始0的回归保持0），standalone保持原FAILED。新stage沿用既有 `ARCHIVE_METADATA_FILES` fallback，没有前端改动。这是本轮独立复现的文件集合漂移缺陷，不作为历史17622上传ID错误的根因；两次检查是时点校验，不能保证provider原子性或永久完整性。

[修前RED](regression-red.json)72项/原67通过/恰5个新增失败/0error/skip，夹具清理通过。[单独GREEN基础设施失败](green-infrastructure-failure.json)因classpath末尾空条目assert在创建容器前停止，测试0、容器0、provider0、SQL夹具0、源码未变，原失败保留。[首完整suite](suite-first.json)1443项/1441通过/1失败/1既有Redis跳过，唯一失败为旧txt重试核验次数仍要求1次；更新断言为3次后，[最终fresh suite](suite-final.json)1443项/1442通过/1既有Redis跳过/0fail/error。P2pArchiveTaskServiceTest76、BaiduWebClientTest44和真实MovieDuplicateMergeMySqlTest24全部通过/无skip，必需SQL、独立Java17 compile与两夹具清理通过。

[固定构建](backend-build.json)使用network=none的Java17/Maven，未加载生产env、挂载或端口，package时testsRerun=false。416源码及440类资源、Git/source/image/JAR绑定与构建夹具清理通过。实际image `sha256:5b5530ed328738aca237776200825b71041002fd4cd5304286efc2e53855386d`，JAR `ce41205a599890bf53314b214e5db0515d5e61584a0a54845487a0cc509c8cc0`。[30项运维离线](root-offline-tests.json)、15个AST检查、[独审](offline-independent-review.json)和[具体计划独审](prepare-independent-review.json)通过，33份依赖固定在[依赖清单](dependencies.json)，[候选绑定](candidate-binding.json)固定最终测试、源码及产物。

## 一次backend激活与恢复材料

[部署](deployment-passed.json)23:07:45开始；prepare及立即TERM前，已认证的完整旧Docker Config与当前Compose+image推导的全部Env和Config字段、原生完整Compose config-hash均相符，配置/HostConfig漂移在TERM前阻断。旧Env完整序列与所有旧labels通过内存tar→age保存于仓库外，计划仅留hash与字段名，弥补上一阶段未保存旧Config原文的证据边界。该改进不改变上一MobLand原runtime rawhash FAIL及旧Env/labels缺证历史。

旧backend正常TERM停止，七份恢复age完整认证后仅一次固定candidate激活；实际container `0b96c7e0e4809269ad59ab717bfbc13a31de94d9360de256bb14b99fc7bce19f`、启动时间23:09:58.682715，backend原21份Compose追加本release为22层，frontend12层保持。全部17个Config字段、153个唯一Env键值、精确baked/custom/generated labels（含replace=backend-1）、HostConfig/网络/挂载、实际JAR及其他12个原代次、sys_config与保护历史通过。Env序列实际不同，但完整键值映射相等；不宣称原始Config序列hash相等。既有nginx容器 `nginx -t` 与in-process reload通过，nginx代次保持。

停止窗口检查点 `G:/gying-backups/baidu-archive-file-guard-20261110-20261110T150808.375471Z`，7份age、manifest SHA `f83eba3a57c318c08e971ca3b2644d26808eb56dea30169d41143146b7320325`。74配置成员、882host成员/1374ACL、旧backend/frontend镜像、SQL31表/1过程/34完整保护、7backend-data、队列/key和百度AES-GCM/当前pair/同volume/同账号/UID10001及0600/0700核验通过。未导入SQL/data，不宣称跨服务原子快照；应用回退保持当前SQL/data/state/key。

## 入口与相关后台状态

[验收](acceptance-passed.json)完成20个本机/经既有代理公网匿名GET和2个既有有效ADMIN device的本机GET：`/api/admin/resource-hub/config`、`/api/admin/resource-hub/baidu/backfill`。入口和相关后台状态、已部署关键class、完整runtime再次核验通过；prepare中的相同2个ADMIN GET单独保留在[计划](plan.json)，不计入这次验收的2次。这20个匿名GET按预期200/401/404/302通过，分别为9/2/6/3个；公网管理路径为既有Cloudflare Access302，没有跟随重定向或发送凭据，不把它们概称全部200。所有API正文只在内存，回执仅保存hash及固定安全投影；无新登录/device。

`operatorDirectSqlWrites=0`只描述助手直接SQL写入；认证GET可能按已有AuthHelper逻辑更新 `login_device.last_seen_at`，不泛称全部生产SQL写入为0。其他实际配置开关保留prepare值，不把原全局开关默认说成关闭；专用补全父任务保持PAUSED/enabled=false。

父17583保持revision63、10分钟/batch1、processed5/skipped5/succeeded0/failed0/deferred3，currentChild/currentIdentity/nextRun/lease均空。UNKNOWN17584/17606/17622保持attempts1；17580及baidu_transfer_task:1、相关影片/八资源/历史整行和sys_config保持。当前这次部署和GET验收没有POST、scan/preview、provider网盘API、云上传/分享/删除、实际影片合并、QQ/GYING发布、UNKNOWN resolve/defer或恢复调度。MobLand上一阶段2scan/2preview成功及其两张真机图、Troll八组/16图、194静态资产/375产物仅[历史引用](../mobland-merge-compat-20261110/README.md)，不算本轮新浏览器验收。

## host pin、日常覆盖与最终边界

[host pin](host-pin-passed.json)23:13:29–23:14:33唯一CAS只改expectedBackendImage；owner/group/DACL、taskXML/ACL/profile、运行代次和保护历史保持，configurationOutcomeUnknown=false。post检查点 `G:/gying-backups/baidu-archive-file-guard-post-pin-20261110-20261110T151352.898502Z` 的3份age完整认证，manifest SHA `59373b4684e8eb9cba4627edba695a4d2815129f1e71aa6258bd80fdbb3caf55`；host完整内容、队列pending0/running0与key、百度会话身份/GCM/权限通过，未恢复生产数据。

[日常准备](daily-prepared.json)认证原配置单age检查点 `G:/gying-backups/baidu-archive-file-guard-daily-config-before-20261110-20261110T151520.706159Z`，原文preimage相同。[实际daily apply](daily-apply-passed.json)23:16:16–23:16:45唯一CAS只新增本release路径，44→45原生before/proposed/after dry-run及owner/group/DACL保持通过。当前配置SHA `3ed90a283e8ac8a6447ffc692b30262e1e0bf64eb1854d758ec0988f84bddb29`，configurationOutcomeUnknown=false；完整/自然备份没有执行，不算自然完整备份验收。

[最终只读](final-read-only-passed.json)23:18:36–23:19:01核验当前runtime/image/JAR/17Config字段/153Env映射、其他12代次、host taskXML/完整ACL/profile与文件、pin/daily45及保护历史和sys_config前后保持，五idle计数均0。另2个本机片库/同片详情匿名GET均200；本轮验收阶段20匿名GET与最终2匿名GET分开记录。Docker日志限启动23:09:58至23:18:40、最多tail2000行，stdout1621B/stderr0，ERROR/Exception/OOM匹配0；应用文件日志未验。

[下一影片只读准备](next-candidate-readonly.json)固定现役image/JAR和父revision63/cursor46，通过当前NEXT_IDENTITY_SQL精确SELECT得到identity47、movie0WG4/mv；单次内部来源resources GET返回resourceTotalCount0，P2P/torrent/magnet与预计元数据均0、eligible=false。没有真实归档POST、云写、UNKNOWN重放/resolve或恢复调度，全部13代次和保护行保持，原响应/URL不落盘。该候选不适合上传验收，未虚构可执行下一影片或申请追加额度。回执顶层status为PAUSED，是父状态投影覆盖了流程status；终端读取完成不能把该原始回执顶层改称PASS，原字节保留。[静态解释](next-candidate-interpretation.json)单独绑定原报告SHA，结论是读取准备完成、下一identity不符合资格；未修改封存helper或进行额外云动作。

最多三次新影片实跑额度3/3耗尽；真实归档/自有永久分享及逐文件path/fsid/size/hash对账尚未完成，旧UNKNOWN与调度继续暂停。时点校验上线和GET通过不代表百度真实归档闭环。安全58 PASS/11 FAIL/1 UNKNOWN与G01–G11仍未关闭，本轮不宣称重做安全审计或加固。

[最终独审](final-independent-review.json)精确绑定部署/GET验收/host pin/post/daily/final/下一来源只读及静态解释的原始SHA，PASS且无阻塞发现。该独审仅复核本地代码、产物和已保存回执，生产I/O0、未独立执行runtime/provider/SQL探针或解密归档，不把静态独审扩大为另一次线上验收。

脱敏报告原路径、字节大小与SHA见[证据清单](evidence-manifest.json)。JSON通过本目录.gitattributes保留原字节；未复制age正文/私钥、私有cfg/env/Cookie/JWT、完整inspect/provider正文或原应用日志。