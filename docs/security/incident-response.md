# 安全事件响应手册

## 1. 目标和原则

本手册处理凭据泄露、未授权访问、数据库/对象存储暴露、机器人滥用、恶意资源提交和容器异常。优先顺序：**止血 → 保留证据 → 轮换凭据 → 恢复最小服务 → 验证 → 复盘**。不在事件处理中删除卷、清空日志或覆盖证据。

## 2. 分级

| 等级 | 示例 | 首要时限 |
| --- | --- | --- |
| P0/Critical | MySQL/MinIO 可被公网写入、root/Cloudflare/Tunnel 凭据泄露、已确认数据外泄 | 立即隔离；负责人确认后每 15 分钟更新 |
| P1/High | 管理后台绕过、Quark/GYING/QQ token 泄露、备份不可恢复、持续 API 攻击 | 1 小时内止血和轮换 |
| P2/Medium | 单接口刷取、错误限流、依赖高危但未利用 | 当日修复/缓解 |
| P3/Low | 文档漂移、低影响 header/版本问题 | 计划修复 |

## 3. 通用止血（Windows + Docker）

1. 记录当前时间、分支/提交、`docker ps`、端口和相关日志摘要；不要复制环境变量。
2. 若公网仍可达：在 Cloudflare 暂停/收紧 hostname 或 Access policy；Windows Firewall 临时拒绝 3306/33060/9000/9001/8880/5005；不要先删除数据。
3. 对受影响服务停止入口或缩小网络范围；保留容器/卷用于取证，避免 `down -v` 和全局 prune。
4. 运行只读检查：

   ```powershell
   python tools/security/check_security.py --repo . --probe
   docker ps -a --format "{{.Names}}`t{{.Status}}`t{{.Ports}}"
   docker compose -f docker-compose.prod.yml logs --tail=300 backend nginx gying-source social-publisher
   ```

5. 将日志和 manifest 复制到仓库外的受保护、只读证据目录；脱敏后才可共享。

## 4. 凭据事件矩阵

| 事件 | 立即动作 | 后续验证 |
| --- | --- | --- |
| DB 密码/root | 防火墙隔离；创建新 scoped user；改应用配置；验证所有连接；最后处理旧 root | `CURRENT_USER()`、SHOW GRANTS、业务读写和备份恢复 |
| MinIO root/key | 禁止匿名写；应用新 scoped key；撤销旧 key；审查对象访问日志 | 图片公开、私有对象拒绝、控制台不可公网访问 |
| JWT/internal token | 生成新值并重启；撤销设备/旧会话；切换 source/social/QQ token | 旧 JWT/token 返回 401/拒绝；新链路成功 |
| Quark Cookie | provider 侧退出/撤销；备份受控状态；新 Cookie 写入外部 secret；收紧卷 ACL | 转存/分享 fixture 成功，旧会话失效 |
| Cloudflare Tunnel/Access | 立即禁用/rotate tunnel credentials；收紧 Access 默认 deny | 新 tunnel 仅到 loopback；管理员三态验收 |
| QQ/OpenClaw | provider 侧撤销 token；关闭 webhook/命令；保留管理员白名单 | 未授权事件拒绝，搜索限流和真实群消息按批准窗口验证 |
| Git 历史 | 停止继续传播；轮换所有可能有效值；通知 clone 持有者 | 新 clone 扫描 0；历史重写按单独批准执行 |

## 5. 应用层攻击

### API 刷取/DoS

- 先观察 429、连接数、Redis 状态和上游耗时；不要直接提高限额。
- 临时在 Cloudflare/nginx 对路径或来源收紧；保留公开页面最小可用。
- 检查搜索、资源提交、审核、登录是否产生异常 MySQL 扫描；必要时暂停高成本 worker。

### 未授权管理员操作

- 立即撤销受影响用户设备/JWT，冻结管理员账号或 Access group；保留审计记录。
- 对资源、配置、用户角色、转存任务做差异核对；优先软删除/回滚，不直接物理删除。

### 恶意资源/外部 URL

- 停止相关 worker；保留资源 ID、provider、任务状态和请求 ID。
- 通过现有资源审核/repair 流程处理，禁止把第三方原始 URL 直接升级为正式资源。

## 6. 恢复和关闭条件

事件只有同时满足下列条件才可关闭：

- 暴露面复测为预期（端口、Tunnel、Access、内部路由）；
- 所有相关凭据已轮换/撤销，旧值验证为失效；
- 数据库、MinIO、Quark 和用户会话的影响范围已记录；
- 备份可解密，至少一个临时恢复路径通过；
- 监控告警已覆盖触发原因；
- `docs/current-project-status.md` 和本手册补充绝对日期、证据路径和未决事项，不写秘密值。

## 7. 联系和记录模板

```text
事件编号：
发现时间（含时区）：
发现人/来源：
影响服务：
初步等级：
已采取的止血：
凭据轮换键名（不写值）：
备份/证据目录：
验证命令和结果：
回滚/恢复决定：
后续责任人和截止日期：
```
