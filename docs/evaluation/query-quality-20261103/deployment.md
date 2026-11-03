# 2026-11-03 查询条件、主题与可信历史上线验收

Asia/Shanghai。用户在获知 G01–G11 仍失败后明确“可部署”，本次仅替换本机 Agent backend 与 Movie backend；安全加固、凭据轮换和真实外发不在本次范围。第一阶段质量闭环仍未完成。

## 实际上线

| 服务 | 时间和固定产物 |
| --- | --- |
| 本机 Agent | 21:12:14完成；代码15a2b8a、JAR055f1bdc…、PID38460/instance7d5364c2…。旧7700正常受管停止，新hash runtime独立启动、ready与hostInJob=false通过；[独立复核](agent-deployment.json)25项通过，普通权限实时argv明确UNKNOWN，部署期管理员证明另绑定回执 |
| Movie backend | 21:47:47完成；代码13660c9、imagef2b7361b…、实际JAR1b8a09d5…。旧容器正常TERM143；原8层Compose追加第9层固定镜像，配置、UID、内存/挂载/端口/网络和另外9容器保持；[原回执](backend-deployment-receipt.json)与[独立验收](backend-deployment.json) |

顺序是Agent先、Movie后：新Agent兼容旧后端，旧Agent会拒绝新增candidateHistory/historyComplete/expiresAt。原观测Movie4f73846f…与后来角色边界修复的1b8a09d5…分别绑定；原15轮未重跑，也未覆盖原报告或评分。最终Movie902项中872通过、30环境跳过，Agent173/173，两侧compile通过。

## 源码与恢复材料

共享工作树随后出现其他聊天的夸克改动，最初部署预检因6个manifest文件hash漂移停止，尚未停服务。固定构建副本349个raw hash及完整Git提交13660c9内容均匹配，仅允许CRLF归一化；没有把新夸克文件加入构建或本次提交。[来源证明](source-binding-proof.json)、[原失败事实](source-binding-initial-failure.json)、[14项守护测试](pinned-source-guard-validation.json)和[助手hash](pinned-helper-hashes.json)分别保存。原部署助手/已认证配置字节保持，另用固定来源助手并建立单份补充age认证。交付收尾时另一聊天独立提交夸克修复d24e08f，共享检出HEAD已推进；本次实际运行和源码来源仍绑定13660c9，未重建或部署该夸克提交。

- Agent三份完整认证检查点：`G:/gying-backups/ai-agent-query-quality-20261103-20261103T131135.560714Z`，manifest139a4104…。
- Movie五份最终停服窗口检查点：`G:/gying-backups/ai-query-quality-backend-20261103-20261103T134656.321321Z`；旧镜像、有效配置、单事务SQL、backend-data和ai-state-key，size/SHA/完整age认证及配置成员/25表/镜像图/queue-key内容核对通过。后端保持停止，其他写入方保持，非跨服务原子快照；[检查点](backend-checkpoint.json)。
- 新助手/来源证明/清单的单份补充检查点：`G:/gying-backups/ai-query-quality-pinned-helper-20261103-20261103T134458.791036Z`；原五份准备检查点保持，[补充认证](pinned-helper-checkpoint.json)。
- 原日常备份配置已单独加密认证；只追加`E:/gying-tools/releases/ai-query-quality-20261103/backend-candidate.json`，apply/readback/dry-run通过；[备份路径](daily-backup-overlay.json)。下一次自然完整备份未验，不把dry-run称作完整运行。

未导入旧SQL或执行应用回退。回退保留当前SQL/state/key；新requestHashVersion2身份写入后禁止自动切回旧Movie，避免同requestKey输入被旧算法判409。四次新网站任务已发生，后续修复优先前向推进，不绕过该守护。

## 真实入口与网站链

[14个入口](entry-verification.json)分次通过：本机/公网首页、片库、同ID详情API/SSR、internal/QQ404，源站匿名admin401、公网CloudflareAccess302。公网使用既有127.0.0.1:7897代理，非本机直连或浏览器验收。初始默认urllib标识遭1010/403拒绝；浏览器请求标识复验中的三项URLError随后仅定向只读重验成功，全部失败记录保留。nginx测试/重载命令成功由原助手回执证明，独立事件日志没有SIGHUP证据。backend21:47:19–21:54:42的限定日志窗ERROR/Exception/OOM=0、restarts0，不作长期可用性承诺。

21:53:54–21:55:10执行已观察的开发题dev-v3-06三轮与dev-v3-09一轮，[网站报告](website-report.json)4提交/4SUCCEEDED/4机械通过/0重试，p50 10.219秒/p95 33.250秒仅4样本。新USER任务沿服务端previousJobId链，第三轮details回到首轮首项1MeM，导演David Barnard与片库字段一致；年份替换和150>100矛盾澄清通过。[46项契约检查](website-contract-validation.json)核对身份、trace/hash、lineage与清理；跨owner拒绝、客户端伪造身份隔离及900秒TTL逻辑由既有Java回归覆盖；生产自然过期待观察，本次未额外重测。

test两表限定行新age备份完整认证后临时启用并签发短期USER设备；token仅内存/stdin。runner先结束，finally撤销本次设备1、恢复disabled1、有效设备0、token401、密码和USER角色不变、临时MySQL凭据删除通过；[运行/清理证据](website-runtime.json)。不覆盖密码登录、浏览器页面、跨账号或真实外发。

## 质量和剩余边界

原15轮自定义v1字节hash评分保持满足5/部分2/澄清2/未满足4/缺证2，三维完整12、真人0。新增网站四轮使用runner机械passed与[质量v2规范JSON协议](website-review-validation.json)，不能混用。网站[模型诊断](website-review.md)为满足2、未满足1、缺证1；事实维度null2、完整三维2/4、真人0，不称4条都答对或独立盲测提升。

旧列表导演和矛盾条件澄清满足限定请求；Radiohead录音棚现场录像是否具有所需电影身份缺证；RAYE简介明示电视特辑却以mv充作音乐电影仍未满足。胶片里程不证明修复主题、小镇人物引文不证明电影院核心场景、徒步误限ac等原15轮问题继续保留，核心引文合同不能确定性证明语义蕴含。

代码此前已分别commit/push；本次脱敏部署/网站证据及状态/架构/路线/知识文档另行提交。完整机器汇总见[部署结果](deployment-result.json)。
