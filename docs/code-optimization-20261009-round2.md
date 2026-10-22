# 第二批代码优化与手机适配

10月9–10实现/续验，10月11已部署；[上线](code-optimization-deployment-20261011.md)、[现行发布契约](social-publishing.md)、[完整原文](history/snapshots/20261022/docs/code-optimization-20261009-round2.md)。

## 范围与上线状态

种子响应/发布认领与不确定保护/手机管理/i18n首屏；10月10“未部署”是候选时点，11项安全风险仍未关闭。

## 1. 种子响应：在认证探测前执行限额

强制stream、Content-Length预拒与解码累计双限；GET/PoW/登录重试均在text/json认证读取前限制2MiB，替换/异常关闭响应，缓存复用不再第二bytearray。HTTPS/拒跳转/BTIH/会话频率保持；限正文非RSS。

## 2. 发布：持久化认领与不确定结果保护

### 状态规则

条件UPDATE认领同logId；PENDING/PREPARE_FAILED→PREPARING→持久化PUBLISHING→外部发送→POSTED，发送后错误UNKNOWN，回执丢失不降级成功。不确定写DB失败留在途，普通重试拒绝；管理POSTED409、内部复用已有结果。UUID/0600独占内容文件，成功后清理失败不变可重试。

### 保证边界

不是同影片/版本全局exactly-once，无过期租约/批量重置；不同版本并发建单仍有限制，按[状态机](social-publishing.md)人工核远端。

## 3. 手机端与首屏一致性

<768px目标/日志卡片、44px/换行/草稿保留/状态一致；SSR与初始客户端英文，挂载恢复保存/浏览器语言，storage拒绝回退不覆盖偏好。UI禁用不能代后台鉴权。

## 4. 验证证据

后端523（5skip/0失败）、compile；Python54（15限额）、publisher31、真实隔离MySQL6（40连接一次mock发送）、Node42；tsc/build/lint7旧warning；首页10/发布14 mock场景。fixture无外网/生产卷/凭据，清本次容器/临时服务；不是真实账号/真机/平台帖子。

## 5. 部署前检查、发布与回退约束

无DDL，status30容新值；backend/publisher/前端策略协调，旧版本不识别在途/UNKNOWN则不单边回退；不清队列/旧dump。检查点与实际切换在10月11报告，候选测试不等上线授权。

## 后续明确保留项

真机/真实账号/外部平台、评论DB读量与跨版本全局去重未凭本次结果闭环。
