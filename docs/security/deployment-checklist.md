# 安全加固部署检查清单

完整清单尚未闭环。最近已记录机器扫描 2026-10-25：58 PASS/11 FAIL/1 UNKNOWN，旧迅雷探针退出造成 UNKNOWN，本次整理未重跑；[G01–G11](hardening-gates.md)只覆盖容器/网络，以下门禁仍逐次验收。

## A. 变更前

- [ ] 读取当前状态、审计摘要，确认目标/提交/范围/维护窗口；保留无关改动。
- [ ] 采集实际容器/镜像/配置/卷/端口/网络/在途基线，保护凭据。
- [ ] 本轮适用完整检查点：manifest/hash、认证解密、所需隔离恢复，不用历史 partial 替代。
- [ ] 当前 secrets 扫描、受限文件 ACL、回退兼容性及执行条件通过。

## B. 身份和密钥

- [ ] 应用分服务非 root DB 身份、窄 Host/最小 grants；readonly/backup 单独使用。
- [ ] MinIO scoped/anonymous policy、root key 轮换/旧 key 验证。
- [ ] Redis ACL/JWT/internal/QQ/Quark 各用途独立、外部受限注入、缺失拒绝。
- [ ] Quark 单文件权限/非 root/NNP/旧 Cookie；迅雷两写链路重复私有原子写回与实际同账户授权。

9/25 已完成 localhost 备份账号及 Quark root 父目录 0700，不重复创建/Apply；父目录拒绝不等于单文件 600。10/8 迅雷写回/当前会话通过，不能代替自然失效路径。

## C. 依赖和网络迁移

- [ ] MinIO 真实消费者先移入内部 endpoint，data/config/policy 通过后收窄 9000/9001。
- [ ] Redis cache-net、认证/Lua/所有消费者、失联 fail closed。
- [ ] OpenClaw 内网调用/唯一插件/权限；其他依赖逐项 NNP/非 root/私有卷。
- [ ] 公网直连/IPv6 敏感端口拒绝，与 Tunnel/本机 Firewall 分开验收。

### Windows 防火墙单步执行与故障定位

9/25 规则已实施，不重复 Apply。原记录/回退目录 `G:/gying-tools/security-20260925/firewall-attempts/20260925-113327-6e2eac1b/`；本机有效策略/健康及同网客户端报告通过，公网/IPv6、真实回退仍未验证。

只读 CheckOnly 使用已配套发布的 Apply-FirewallStep.ps1/firewall_health.ps1/Rollback-FirewallStep.ps1；健康检查接受 Quark 同源 /login 的 302，不接受任意重定向；TLS 不降级。回退只删除本次规则、恢复本次 Public enabled/default-inbound，不导入覆盖全策略。完整 watchdog/路径/命令见[原文](../history/README.md#git-history)。

## D. Compose 预检和发布

- [ ] 显式项目/生产文件/已审查覆盖，config --quiet、固定 ID，无 pull/build/全栈重建。
- [ ] 新/恢复环境关闭所有自动化；实际读取 sys_config，单一写入方、在途协调。
- [ ] 候选真实卷副本/身份/配置与回退通过，再逐服务切换；nginx -t 后平滑重载。

## E. 入口验收

- [ ] 内部依赖 → backend → SSR → 本机 nginx → 公网 → 受影响登录/UI/日志。
- [ ] 匿名管理拒绝、internal/QQ 404、图片范围、私有对象拒绝；有效身份/端口/环境一致。
- [ ] 未改容器和原调度保持；外部结果/审计核对，模拟与实际业务分开。

## F. Cloudflare/外部链路

- [ ] 本人 OTP 登录与网站 ADMIN/USER，origin JWT/WAF、路径继承/冲突；不扩大公开管理范围。
- [ ] 所需真实消息/转存/发帖按独立批准范围验收，不作为默认健康探针。

10/13 Access 单邮箱/管理路径/匿名边缘保护已启用，不重复录入 Token 或创建对象。

## G. 收尾

- [ ] 保留原失败/UNKNOWN、回退材料、绝对时间/版本/结果；更新风险与当前状态。
- [ ] 恢复原启用开关，保留原禁用目标；不重置任务、不恢复旧 SQL、不删卷。
- [ ] 定期 CVE/恢复/外部攻击面复验；本轮业务例外不标记整体安全完成。
