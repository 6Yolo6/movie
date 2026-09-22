# 风险登记表

本表与根目录 `docker-security-report.md` 的风险编号保持一致；状态以在线证据为准，目标配置不能代替部署证据。当前 Critical/High 项均未完成生产闭环。

| ID | 等级 | 领域 | 当前在线事实 | 处理/上线验收 |
| --- | --- | --- | --- | --- |
| C-01 | Critical | 主机/DB/MinIO | MySQL、MinIO 监听所有接口，Windows Public Firewall Disabled；外部可达性尚未完成证明 | 收紧 Windows 防火墙和 loopback/内部绑定，再复测 3306/33060/8880/5005/9000/9001 |
| C-02 | Critical | MinIO | 应用使用 root identity，匿名读取范围覆盖全部 `gying/*` | 创建 scoped key 和最小 policy，公开策略只保留图片扩展，轮换 root key |
| H-01 | High | 网络边界 | backend、quark、MinIO 旧容器存在宿主机端口，可绕过 Cloudflare/Access | 部署 loopback/内部网络目标，OpenClaw 改走 Docker 网络 |
| H-02 | High | MySQL 身份 | 应用会话仍为 root | 创建并验证专用 CRUD 账号；保留受控 break-glass root，不直接删除 |
| H-03 | High | Cloudflare Access/WAF | 账号侧 Access/WAF 和管理员路径三态结果未核验 | 创建/核验 Access policy、WAF、默认 deny，验证登录/未登录/Service Token |
| H-04 | High | 凭据历史 | Git 历史仍存在凭据规则命中，历史未重写且凭据未全部轮换 | provider 侧 revoke/rotate；历史重写另行审批 |
| H-05 | High | Quark | Cookie 仍持久化在配置/状态，卷权限和备份边界未完成迁移 | 收紧 ACL/文件模式、加密备份并轮换 Cookie，验证 WebUI 访问 |
| H-06 | High | 恢复能力 | 加密备份、解密恢复和演练尚未真实执行 | 执行首次加密备份、临时/异机恢复 MySQL 和关键卷，保留 manifest |
| H-07 | High | 供应链 | 现有 backend 镜像 Scout 证据为 17 个受影响包、61 个唯一漏洞（13 Critical、48 High）；不是工作区 3.5.16 目标镜像 | fixed-version triage、锁定 digest，重建目标镜像后复扫；不能以 npm audit 0 结案 |
| M-01 | Medium | MySQL TLS | `require_secure_transport=OFF`，密码策略未确认 | 先完成证书/连接验证计划，再开启并回归所有连接器 |
| M-02 | Medium | 镜像治理 | 部分第三方镜像仍使用 `latest`，完整 JVM/Python/容器 CVE triage 未闭环 | 固定版本/摘要，保留 Scout/依赖扫描和定期复核 |
| M-03 | Medium | MinIO 网络 | MinIO 仍为独立容器，目标 nginx 使用 Docker DNS `minio:9000` | 先备份并以 alias `minio` 接入 `gying-net`，验证对象读写后再收紧入口 |
| M-04 | Medium | OpenClaw 集成 | 现有配置仍可能调用 `host.docker.internal:8880` | 端口收紧前改为 `http://backend:8880/api/qq-bot/search-reply`，验证内部 token 和 QQ 链路 |
| M-05 | Medium | 监控/告警 | 已有结构化安全日志，尚未接入集中保留和告警 | 对 401/403/429/5xx、权限异常、备份失败接入告警 |
| L-01 | Low | Origin TLS | origin 使用 HTTP，由 Cloudflare 提供公网 TLS | 开启 Always Use HTTPS、HSTS 和严格 origin policy；不直接暴露 origin |
| L-02 | Low | 审计范围 | 本地工具不能代替外部渗透和 Cloudflare 账号侧审计 | 每季度或重大变更后复核外部攻击面和账号策略 |
