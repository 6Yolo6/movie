# 多平台发布与重试运维

[文档导航](README.md) · [API](api.md#多平台发布) · [部署与回滚](deployment.md) · [当前状态](current-project-status.md)

## 1. 服务边界

| 链路 | 职责 | 不能混用的对象 |
| --- | --- | --- |
| backend → `social-publisher` | 独立 QQ 多账号、微博目标与发布日志 | 独立凭据卷、`social_publish_target` / `social_post_log` |
| backend QQBot / OpenClaw | 群搜索、候选选择、每日推荐 | QQ 官方消息权限和群 openid；不等于独立发布器 |
| 宿主机 `tencent-channel-cli` / 桥接 | 原腾讯频道账号与计划任务 | 原账号目录、频道日志与调度；不复用新账号卷 |

NapCat 已停用，不作为备用通道、迁移依赖或验收项。部署版本和账号是否启用以[当前状态](current-project-status.md)为准，默认关闭的迁移种子不代表生产也关闭。

## 2. 状态机与重试边界

以下描述的是同一条 `social_post_log.id` 的保护，不是跨资源版本或同影片并发的全局 exactly-once 保证。

| 状态 | 含义 | 管理端普通 retry |
| --- | --- | --- |
| `PENDING` | 尚未被认领 | 允许，且目标必须存在并启用 |
| `PREPARE_FAILED` | 已确认在外部发送边界之前失败 | 允许，且目标必须存在并启用 |
| `PREPARING` | 已认领，正在准备 | 拒绝；崩溃残留也不自动释放 |
| `PUBLISHING` | 外部调用前已持久化发送状态 | 拒绝；不能依据超时推断没发出 |
| `UNKNOWN` | 发送、回执或持久化结果不确定 | 拒绝；先人工核对外部平台 |
| `POSTED` | 已确认并记录成功 | 拒绝重试；查看已有结果 |
| 历史 `FAILED`、空值或未识别状态 | 不能证明尚未发送 | 拒绝；保留历史并人工核对 |

### 不同入口，不同响应

- 管理端 `POST /api/admin/social-publishing/logs/{id}/retry`：除 PENDING/PREPARE_FAILED 外均返回 **409**，包括 POSTED；不会先把日志重置成 PENDING。
- 内部 `POST /posts/{logId}`：publisher 以条件 UPDATE 原子认领。只有认领成功者执行；未认领者读取现有状态，POSTED 返回保存的结果，不重新出站。
- 执行顺序为 **PENDING/PREPARE_FAILED → PREPARING → PUBLISHING → POSTED**。外部请求或 CLI 发送前必须成功进入 PUBLISHING；越过发送边界后的失败按 UNKNOWN 处理。
- 数据库失败时可能仍保留 PREPARING/PUBLISHING；响应确认丢失时也可能实际已提交 POSTED。不能据一次异常回执覆盖现有状态。
- **HTTP 200 不等于发布成功**：响应可能为 `ok:false / status:UNKNOWN` 或其他受保护状态。必须同时检查业务状态、审计和已获授权的外部结果核验。
- 没有自动租约过期或批量重置机制。不要把 UNKNOWN/PUBLISHING/历史 FAILED 改回 PENDING 来“疏通队列”。

### publish-next 也有副作用

`publish-next?runNow=true` 会真实出站；批量入口空目标列表表示全部启用目标。即使 `runNow=false` 也可能创建数据库队列记录，**不是 dry-run**。目标唯一键和候选过滤不能替代同影片跨资源版本的全局去重。

## 3. 遇到不确定结果时

1. 停止对该记录重试；如仍有调度风险，按授权范围暂停相关目标/写入方，保留原始状态和日志。
2. 只读核对目标、账号、日志 ID、时间窗口、影片/资源版本与现有外部地址；不输出正文中的凭据或完整上游错误。
3. 在目标平台核对是否已发布及是否重复，不能只看本地 HTTP 结果。
4. 保留“已确认成功 / 已确认未发送 / 无法确定”的证据。后续修复需要明确单条范围和方案；无可靠证据继续阻断，不新建另一条记录规避保护。

相关实现来源：[backend 服务](../backend/src/main/java/com/gying/movie/service/impl/SocialPublishingService.java)、[publisher 状态机](../social-publisher/publish-task.mjs)、[前端策略](../frontend/src/lib/socialPublishing.ts)。

## 4. 凭据、账号与健康检查

- backend 使用 `SOCIAL_PUBLISHER_BASE_URL` 与 `SOCIAL_PUBLISHER_TOKEN` 访问内部发布器（容器端口 8093）。不能把该端口或内部写接口加入公网 Tunnel。
- `QQ_CHANNEL_ACCOUNTS_ROOT` 在生产 Compose 中为 `/data/qq-accounts`；迁移时检查实际 QQ 与微博两个持久卷，不只备份一个账号目录。
- 微博涉及 `WEIBO_WEB_COOKIE`、`WEIBO_WEB_XSRF_TOKEN`、`WEIBO_WEB_FINGERPRINT`、`WEIBO_WEB_CLIENT_VERSION`、`WEIBO_WEB_USER_AGENT`。只记录键名、受保护来源和就绪结果，不复制真实值到文档或数据库。
- QQ 账号标识为 2–32 位字母、数字、下划线或连字符；每个标识对应独立授权目录。先扫码并核对频道权限，再创建该账号目标。
- 移除账号会删除该账号凭据并停用目标，历史发布日志保留。这是有状态操作，不是无害清理，执行前确认范围和备份。
- `GET /health` 无发布副作用；匿名仅返回最小存活信息，带内部认证才可查看详细状态。存活、configured/authenticated/ready 和真实帖子成功是不同证据。
- 登录失效、频控或验证码时停止盲重试。先处理会话/权限，后续是否发布仍受本节状态保护和授权约束。

## 5. 部署、凭据更新与回滚

1. 按[部署流程](deployment.md)固定镜像并记录有效配置。变更发布状态机时，backend 与 publisher 必须协调切换/回退，前端状态展示也要匹配，不能只重建 publisher。
2. 记录所有发布目标和状态分布，协调调度、手工入口与在途操作。无在途证据不足、UNKNOWN 未核清或状态持续变化时，停止该切换，不强制回写状态。
3. 备份 MySQL 发布表、QQ/微博状态卷及受保护配置，并验证 hash 与认证解密。恢复 DB 与凭据卷不等于撤回远端帖子。
4. 更换环境凭据也按最小服务范围重建/重新创建，并验证实际加载；不要用 `.env` 已修改或单独健康 200 代替确认。
5. 应用回滚前重新核对新状态、新帖子及配置差异；旧版本不理解新状态时不能直接回退。保留新审计，不自动覆盖数据库或把状态改回 PENDING。
6. 恢复环境先关闭所有发布自动化。真实验证帖子只有在明确账号、目标和计划保留内容获批后才发送；没有授权时只做只读与 mock 验证，保留真实发布待验收。

## 6. 手机界面与验收

- 管理页 `/admin/automation` 在小屏使用可编辑目标卡片与日志卡片；主要操作至少 44px，长文本换行，筛选/分页可用，切换桌面宽度不丢草稿。
- 手机和桌面共用状态规则；禁用按钮不能代替 backend/publisher 的校验。目标禁用、缺失或状态未知时不能出现可执行重试。
- 回归入口见[开发与测试](development.md#3-常规回归入口)，包括 Node 策略、真实隔离 SQL 与 mock 浏览器场景。
- 2026-10-11 的部署证据见[上线报告](code-optimization-deployment-20261011.md)；手机视口通过不等于真机或真实账号发帖验收。
