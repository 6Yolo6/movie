# AI 执行可靠性部署与恢复验收

日期：2026-10-23，Asia/Shanghai。此前[候选实现与恢复契约](ai-execution-reliability-20261022.md)在10月22日因安全门禁暂停；用户随后对同一候选明确回复“可部署”，本次承接该批准。安全复检仍为59 PASS / 11 FAIL / 0 UNKNOWN，原G01–G11保持失败，不把批准写成安全整改完成。

## 实际上线

12:43–12:54仅替换GYing backend与本机local-ai-agent backend。源码提交分别为`3052f2c9dd9cd76c77f8994a10cc7d9dfd5e99f1`与`e11c631d9ac2191ca924c6f243f5b150578fb167`。前端、模型、source、publisher、OpenClaw及其他容器没有替换；nginx通过配置检查后平滑重载。

| 产物/配置 | 验证结果 |
| --- | --- |
| GYing backend镜像 | `sha256:81d6bd4c41161cab085c1732975e245a2613919cba172139a26d0334b499dd15`；镜像内JAR与候选SHA-256一致 |
| 本机Agent JAR | `2bd021c614de620fe594c0096dc892313f221093c1f18ee37bed8a414865e815`；以对应hash不可变运行副本启动 |
| GYing状态 | `APP_AI_STATE_FILE=/app/data/ai-state/queue.bin`；backend-data独立子目录0700、状态/锁0600 |
| GYing密钥 | `APP_AI_STATE_KEY_FILE=/run/gying-ai/queue.key`；专用外部卷`gying-movie_ai-state-key`只读挂载，10001:10001、目录0700/文件0400 |
| 本机状态 | `.local/ai-state/bridge.bin`，启动器发现`.local/gying-state-key.bin`后启用；密钥与状态使用仅所有者ACL |

两套状态密钥独立随机生成，没有旋转原凭据。backend有效环境、原卷、端口、网络、运行身份和安全参数保持基线，仅增加AI状态契约与只读密钥卷。新密钥、配置和状态均不进Git。

## 备份与回退

初次切换检查点`G:/gying-backups/20261023T044352.387300Z`；状态存在后的平滑重启检查点`G:/gying-backups/20261023T045302.843250Z`。每份包含14个age产物：SQL、环境/Compose/回退与部署覆盖、本机旧运行包/配置/状态密钥、backend数据与日志、QQ/微博两个发布状态卷、容器状态密钥及runtime。所有文件hash与完整认证解密通过，SQL结束标记存在。

现役日常备份配置`G:/gying-secrets/backup-config.json`已追加本机状态/key、当前部署覆盖和容器状态key卷；原配置已加密保存并认证解密，新增范围dry-run通过。尚未执行下一次日常完整备份。

这是应用回退检查点，不是异机灾难恢复演练；在线来源/发布器保持原运行，不能宣称全栈冻结。旧镜像、本机旧JAR、审查覆盖和脱敏证据在`E:/gying-tools/releases/ai-reliability-20261023/`。回退先排空两个后端，保留新密文、密钥与卷；旧版本使用内存队列，不能恢复新任务，不恢复旧SQL覆盖在线数据。

首次本机启动的包装进程等待输出管道关闭，应用健康和生命周期已经通过；只结束经身份核验的Python部署辅助进程，未强杀应用。随后使用文件接收启动器输出，平滑重启正常完成。密钥权限在切换前已校准并验证。

## 实际验收

- 前端容器经Docker DNS到backend/source/publisher的3项内部依赖读取通过。
- 源站/公网16入口检查通过：首页、AI页、详情、片库API；匿名AI要求登录，internal/QQ路径404，管理路径由Access拦截。
- 真正运行的Qwen完成生产内部队列自然片名提取及存量资源回复，批量转存请求拒绝。重启后的同类查询再次通过；转存/发布审计未变，无真实群消息、转存或帖子。
- 新Agent实际模型回归7场景/8轮，8/8程序校验通过；包含中英搜索、无结果、按来源ID追问详情、百科摘录引用和非法历史/字段拒绝。6个成功回答仍需人工质量复核，题集不是独立人工留出。p50=2547ms、p95=10391ms仅本次串行小样本。
- 两侧状态文件认证解密通过，生产任务带attempt=1、renewable=true及回执hash；终态没有request正文，密文不含明文问题。本机已确认回执清为空状态。
- 在空闲、无在途业务任务时，两个后端正常握手/优雅停止并重启。2条终态任务从加密队列恢复，状态和attempt未变；本机实例变化且旧实例有stopped确认。没有在生产故障注入或强制终止应用。
- 12:43:19–12:54:29观察窗口，backend/frontend/source/publisher及重启后的本机Agent ERROR/Exception均0；10个未改容器身份/启动时间/重启计数不变，发布/转存审计不变。

候选测试仍沿用10月22日证据：GYing全量694通过/1依赖跳过、最后16项定向全部通过；Agent60/60、评测器18/18。独立JVM退出、回执丢失/重传、过期租约和写入失败由合成fixture/替身模型验证，不能写成真实账号故障恢复。机器证据见[部署摘要](evaluation/ai-reliability-20261023/validation-summary.json)、[新包模型回归](evaluation/ai-reliability-20261023/model-regression.json)。

仍待：真实站内登录用户多轮与在途回执恢复、工具中间步骤/长期审计、独立人工题集、分布式租约及既有安全整改。
