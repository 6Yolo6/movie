# 多平台发布与重试运维

[API](api.md#多平台发布) · [部署](deployment.md) · [现状](current-project-status.md) · [完整旧说明](history/snapshots/20261022/docs/social-publishing.md)

## 1. 服务边界

backend→独立social-publisher为多QQ/微博（social目标/日志、QQ与微博两卷）；backend QQBot/OpenClaw为群搜索/每日推荐；宿主tencent-channel-cli/bridge为原频道任务。账号/权限/日志/调度不能混用，NapCat停用不恢复，迁移默认关闭不代表线上关闭。

## 2. 状态机与重试边界

仅同一social_post_log.id保护，非同片跨版本全局exactly-once。

| 状态 | 含义 / 管理普通retry |
| --- | --- |
| PENDING | 未认领；允许且目标存在/启用 |
| PREPARE_FAILED | 确认发送边界前失败；同上 |
| PREPARING | 认领准备；拒绝，崩溃不自动释放 |
| PUBLISHING | 外发前持久状态；拒绝，超时不证明未发 |
| UNKNOWN | 发送/回执/记账不明；拒绝，先核远端 |
| POSTED | 已确认；管理拒绝，查看结果 |
| 历史FAILED/空/未知 | 无证据未发；拒绝/保审计 |

### 不同入口，不同响应

管理logs/{id}/retry除前两态均409（含POSTED），不重置；内部posts/{logId}条件UPDATE唯一认领，POSTED返已有。PENDING/PREPARE_FAILED→PREPARING→持久PUBLISHING→外发→POSTED；边界后错误UNKNOWN，DB失败可留在途，回执丢失可能已POSTED，不能覆盖。HTTP200可能ok=false/status=UNKNOWN，须业务/审计/获批远端核验。无租约过期/批量重置，不能新建另一任务规避阻断。

### publish-next 也有副作用

runNow=true真实出站；false仍可建队列、非dry-run；空目标数组=全部启用。候选过滤/唯一键不提供同片所有版本全局去重。

## 3. 遇到不确定结果时

停止该条重试；按授权暂停相关调度，保状态/日志；只读核目标/账号/logId/时间/片与版本/外部地址；确认已发/未发/未知后审精确恢复。未知继续阻断，不能据HTTP或超时猜没发。实现[backend](../backend/src/main/java/com/gying/movie/service/impl/SocialPublishingService.java)/[publisher](../social-publisher/publish-task.mjs)/[UI策略](../frontend/src/lib/socialPublishing.ts)。

## 4. 凭据、账号与健康检查

SOCIAL_PUBLISHER_BASE_URL/TOKEN内部8093不入Tunnel。QQ_CHANNEL_ACCOUNTS_ROOT=/data/qq-accounts，账号2–32字母数字下划线连字符，独立扫码目录；微博WEB_COOKIE/XSRF_TOKEN/FINGERPRINT/CLIENT_VERSION/USER_AGENT仅外部注入。移除账号删凭据/停目标但保日志，先范围/备份。health匿名仅存活，认证详细ready不等真实帖子；验证码/频控/失效停止盲重试，不能用试发探活。

## 5. 部署、凭据更新与回滚

固定新旧镜像/配置；记录目标/状态/在途，backend-publisher-UI策略协调；备份MySQL/两发布卷/外部配置并认证，不只QQ卷。无在途证据不足/UNKNOWN未核/状态持续变即停切换。凭据改变逐服务recreate验加载；旧版本不懂新状态不直接回退，新审计/远端帖子保留，旧dump不覆盖、状态不回PENDING。恢复先关闭自动化；仅明确账号/目标/保留内容获批才真实试发。[部署流程](deployment.md)。

## 6. 手机界面与验收

/admin/automation小屏可编辑目标/日志卡片、44px/长文本/分页/切屏草稿；UI禁用不代backend校验。开发Node/SQL/mock浏览器见[回归](development.md#3-常规回归入口)，[10月11部署](code-optimization-deployment-20261011.md)非真机/真实账号出站验收。
