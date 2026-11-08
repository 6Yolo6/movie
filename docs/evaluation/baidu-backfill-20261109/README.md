# 百度存量慢速补全与扫码接通

2026-11-09（Asia/Shanghai）。分支 `codex/cloud-drive-workflows`。原百度与文件类型版本 00:32 已上线；00:48 官方专用账号导入成功，服务端 CONNECTED/configured=true，原失败请求保留，未归因为确定网络或接口问题。

新增慢速补全候选默认关闭、十分钟一条，先处理存量，再仅百度来源搜索；持久父任务、DB 租约、子任务指针和未知结果阻断。宿主扫码已有目录 ACL 幂等与显式 loopback 代理修复。后端1180项中1179通过/1 Redis跳过、真实SQL/独立Java17 compile与385源绑定通过；前端48 React、TS/lint/Next build、八组构建产物mock浏览器和375运行产物验证通过。宿主13项helper与实际Limited任务exit0通过。固定候选已完成，部署验收进行中，不代表实际采集或发布成功。

外部发布目录 `E:/gying-tools/releases/baidu-backfill-20261109`。登录后检查点 `G:/gying-backups/baidu-login-session-20261109-20261108T165355.091889Z`：整个专用 host/profile/ACL、disabled 任务 XML、backend-data 三份 age 的完整 SHA、解密、内容和会话 GCM 认证通过。旧检查点/失败记录保留，没有明文会话归档。

本轮真实操作仅账号登录及授权的服务/宿主上线维护；不测试转存、生成分享、发布或删除。安全基线仍 58 PASS / 11 FAIL / 1 UNKNOWN，G01–G11 未关闭；沿用用户本轮两服务限定上线授权，不扩大依赖安全变更范围。
