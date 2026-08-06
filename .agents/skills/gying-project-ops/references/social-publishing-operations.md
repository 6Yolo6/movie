# 多平台发布运维

## 架构边界

- 宿主机 `tencent-channel-cli` 和 `QQ_CHANNEL_PUBLISHER_*` 保留原腾讯频道账号与旧计划任务。
- Compose 中的 `social-publisher` 独立承载多个 QQ 频道账号和新浪微博，不复用原账号目录。
- backend 通过 `SOCIAL_PUBLISHER_BASE_URL` 和内部 token 调用容器；容器内部端口为 `8093`。
- 自动发布默认关闭。首次部署、恢复凭据或更换微博会话后，先手工发布计划保留的内容。

## 配置与敏感信息

只检查键是否存在，不输出值：

```text
SOCIAL_PUBLISHER_BASE_URL
SOCIAL_PUBLISHER_TOKEN
SOCIAL_PUBLISHER_SCHEDULER_DELAY_MS
WEIBO_WEB_COOKIE
WEIBO_WEB_XSRF_TOKEN
WEIBO_WEB_FINGERPRINT
WEIBO_WEB_CLIENT_VERSION
WEIBO_WEB_USER_AGENT
```

`QQ_CHANNEL_ACCOUNTS_ROOT` 在生产 Compose 中固定为 `/data/qq-accounts`。QQ 登录凭据保存在
`social-publisher-qq-accounts` 卷；微博 Cookie、XSRF token、fingerprint 和 User-Agent 只来自部署环境。
这些材料不得写入数据库、Git、状态文档或日志摘录。

## 数据模型

- `social_publish_target`：平台、账号标识、目标频道、启用状态、每日时间、条数、间隔和模板。
- `social_post_log`：目标、影片、资源、`PENDING`/`POSTED`/`FAILED`、外部地址和失败原因。
- 唯一约束按目标和资源防止重复发布；候选层还要按影片去重，资源链接更新不应导致同片重发。
- 删除 QQ 账号时删除该账号凭据并停用目标，但保留历史发布日志。

已有数据库执行 `migration_social_publishing.sql`。该迁移创建两张表并预置自动发布关闭的微博
`default` 目标。执行前备份，执行后检查表、索引、中文注释和默认关闭状态。

## 部署与恢复

```powershell
docker compose -f docker-compose.prod.yml up -d --build social-publisher
docker compose -f docker-compose.prod.yml logs --tail=200 social-publisher
docker compose -f docker-compose.prod.yml exec -T social-publisher `
  node -e "fetch('http://127.0.0.1:8093/health').then(r=>r.json()).then(console.log)"
```

迁移或灾难恢复时：

1. 关闭所有 `auto_post_enabled` 目标并停止发布调度。
2. 备份 MySQL 中两张发布表和 `social-publisher-qq-accounts` 卷。
3. 在目标主机重新注入微博网页会话，不从 Git 或普通文档恢复。
4. 恢复卷后启动容器，检查 `/health` 中 QQ 账号和微博 readiness。
5. 分别对每个 QQ 账号和微博手工发布一条计划保留内容。
6. 核对外部帖子地址和 `social_post_log` 后再逐目标启用自动发布。

## QQ 账号生命周期

- 账号标识为 2-32 位字母、数字、下划线或连字符，每个标识对应独立 `.qqcli` 目录。
- 通过后台生成二维码并轮询授权；授权完成后再创建频道目标。
- 一个账号可配置多个频道目标；发布前确认账号已加入目标频道和版块。
- 移除账号会删除该账号卷目录，属于有状态操作；执行前确认目标和备份，不批量清理整个卷。

## 微博网页会话

- 发布器调用 `https://www.weibo.com/ajax/statuses/update`，当前只发布文本和资源链接。
- Cookie 与 fingerprint 必须来自同一有效网页登录会话；XSRF token 可显式提供或从 Cookie 读取。
- 登录过期、频率限制和安全验证会写入失败日志；验证码不能自动处理。
- 更新 `.env` 后重新创建 `social-publisher`，再手工验证。容器健康不等于微博发布可用。

## 运维接口

管理接口前缀为 `/api/admin/social-publishing`，用于概览、QQ 账号授权、目标维护、手工发布、
日志筛选和失败重试。独立容器的 `GET /health` 无副作用；`POST /posts/{logId}` 需要
`X-Internal-Token`，会产生外部帖子，必须按生产变更处理。

## 故障判定

| 现象 | 检查项 |
| --- | --- |
| 容器健康但 backend 调用 401 | backend 与容器的 `SOCIAL_PUBLISHER_TOKEN` 是否一致 |
| QQ 未授权 | 对应账号目录、扫码状态、账号是否已加入频道 |
| 微博显示未就绪 | Cookie、fingerprint、XSRF token 和会话有效期 |
| 发布返回安全验证 | 停止重试，人工完成平台验证并刷新会话 |
| 同片重复发布 | 目标、资源唯一键、影片去重和历史日志 |
| `PENDING` 长期不动 | 调度开关、容器连接、目标启用状态和日志错误 |
| 卷恢复后账号全部失效 | 卷是否正确挂载到 `/data/qq-accounts`，目录权限是否保留 |
