# 百度种子补全部署验收

2026-11-09 13:54:51（Asia/Shanghai）仅 backend/frontend 上线固定源码提交 `b55080d8c0449f6dc6852beff0356d7350cce570`。实际镜像、JAR、Build ID、375 份前端产物、Compose 16/10 层与 11 个无关容器保持通过。独立应用核验 25 PASS / 0 FAIL / 0 UNKNOWN；真实 ADMIN 八场景、配置及父任务保持、194 份公网/本机资源 SHA 核对通过。

停止窗口七份 age 与 pin 后三份 age 完整认证及内容核验通过。扫码任务保持 Enabled/Ready，原 installation、新 pinned installation、实际任务 XML 与 post 备份 XML 的独立前后绑定通过；1371 项 ACL、877 份宿主文件、494 个目录、7 份 backend-data 与加密百度会话保持。日常备份新增覆盖 CAS/dry-run 38 项通过，自然完整备份仍未验。

首个 post wrapper 失败的具体门禁保留 UNKNOWN，未推定为 mutex 竞争；重新核验原门禁并使用新 attempt02 完成备份，原件未覆盖。较早独审误取 ADMIN 场景数为 0，以及 retained runtime 中的旧镜像字段，已由新的独立终验明确实际场景为 8、实际镜像取 affected.imageId。

此页只记部署与恢复材料，真实种子归档另见上层验收记录。安全 58 PASS / 11 FAIL / 1 UNKNOWN 及 G01–G11 未关闭；同一范围的有限上线授权不表示安全加固完成。原两项密钥扫描摘要误报及独立客观证明留存，未修改扫描器或原报告。

[派生脱敏摘要](summary.json) 逐份绑定仓库外原件路径与 SHA。回退须保留当前 SQL、会话、state/key、父任务和 P2P 意图，不能直接启动不识别新状态的旧消费者，也不能导入旧 SQL 或重放未知提交。
