# 风险登记表

线上状态按证据日期取值。2026-10-22 机器扫描仍 11 FAIL；下表包含该扫描之外的独立风险，不把历史证据视为本轮全量复验。

| ID | 等级 | 已有证据 | 未闭环与下一步 |
| --- | --- | --- | --- |
| C-01 | Critical | 9/25–28 本机 Firewall/物理网卡六端口 Block；同网客户端报告不可达 | MySQL/MinIO 敏感监听、公网直连/IPv6；不重复 Apply |
| C-02 | Critical | backend 已不用 MinIO root key | scoped/匿名 policy、root key 轮换与旧 key 撤销 |
| H-01 | High | nginx/backend loopback | Quark/MinIO 非 loopback，先迁移消费者再改绑 |
| H-02 | High | 9/28 三应用 gying_app@%，库级 CRUD/EXECUTE；localhost 备份账号已建 | 分服务身份、精确 Host、最小 grants |
| H-03 | High | 10/13 Access 限定管理路径/单邮箱 OTP 已启用 | 本人登录、网站角色、origin JWT/WAF；不重复 Token/策略 |
| H-04 | High | 当前工作区扫描 0，历史 105 重复规则命中 | provider 侧轮换/撤销；历史重写单独批准 |
| H-05 | High | 9/25 Quark 父目录 root 0700，非属主拒绝 | 单文件权限、非 root/NNP、旧 Cookie 与部署持久性 |
| H-06 | High | 9/25 19 加密文件、25 表/MinIO 隔离恢复；10/22 应用检查点 7 文件认证 | 系统账号、应用全链路/异机恢复、私钥离线保管 |
| H-07 | High | 9/20 旧 backend Scout 61 唯一漏洞 | 对当前镜像重新扫描/triage，不用旧版本结果结案 |
| H-08 | High | 10/8 私有写回/同账户 API/原计划入口通过，文件 10001:10001/600 | 自然失效登录、backend refresh-token 独立续期/长期两写链路 |
| M-01 | Medium | 9/28 require_secure_transport=OFF | 证书/连接回归后分步 TLS |
| M-02 | Medium | 业务发布固定镜像，部分第三方仍 latest | 全部版本治理/JVM/Python/容器 CVE |
| M-03 | Medium | MinIO 已有应用网络 alias，图片抽样通过 | 端口与对象权限归 C-01/C-02 |
| M-04 | Medium | OpenClaw 仍默认 bridge | 内网调用/唯一插件/token/真实 QQ 按范围验证 |
| M-05 | Medium | 结构化 API/安全事件日志 | 集中保留与 401/403/429/5xx/备份失败告警 |
| M-06 | Medium | Quark/PanSou/MinIO root，五依赖缺 NNP | 真实卷验证后逐服务收紧 |
| M-07 | Medium | Redis 无宿主发布但在共享网络 | cache-net/ACL/所有消费者/故障拒绝，归 G11 |
| L-01 | Low | Cloudflare 提供公网 TLS，origin HTTP | HSTS/HTTPS/严格 origin policy 单独验收 |
| L-02 | Low | 本机工具和匿名探针 | 外部攻击面/账号策略/定期审计 |

执行与回退见[部署清单](deployment-checklist.md)、[备份](backup-plan.md)、[原始审计](../history/snapshots/20261022/docker-security-report.md)。业务例外发布不关闭任何行。
