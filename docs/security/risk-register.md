# 风险登记表

本表与根目录 `docker-security-report.md` 的风险编号保持一致；状态以在线证据为准，目标配置不能代替部署证据。最近复核：2026-09-24；入口、非 root 应用身份和 MinIO alias 已部分上线，剩余风险不因此自动结案。

| ID | 等级 | 领域 | 当前在线事实 | 处理/上线验收 |
| --- | --- | --- | --- | --- |
| C-01 | Critical | 主机/DB/MinIO | MySQL、MinIO 监听所有接口，Windows Public Firewall Disabled；外部可达性尚未完成证明 | 收紧 Windows 防火墙和 loopback/内部绑定，再复测 3306/33060/8880/5005/9000/9001 |
| C-02 | Critical | MinIO | backend 已不使用 root key；匿名/scoped policy 与 root key 轮换本次未重新验收 | 保留未闭环状态，核验最小 policy/匿名范围并在维护窗口轮换 root key |
| H-01 | High | 网络边界 | nginx/backend 已 loopback；quark 5005、MinIO 9000/9001 仍为非 loopback | 继续收紧剩余端口，独立验证 Firewall/Access；不能重复写成 backend 尚未收紧 |
| H-02 | High | MySQL 身份 | 三服务已使用非 root gying_app，但仍共用身份；本次未完整复核 grants | root 迁移已部分解决；分服务账号/最小 grants 仍待验收，保留受控 break-glass |
| H-03 | High | Cloudflare Access/WAF | 账号侧 Access/WAF 和管理员路径三态结果未核验 | 创建/核验 Access policy、WAF、默认 deny，验证登录/未登录/Service Token |
| H-04 | High | 凭据历史 | Git 历史仍存在凭据规则命中，历史未重写且凭据未全部轮换 | provider 侧 revoke/rotate；历史重写另行审批 |
| H-05 | High | Quark | Cookie 仍持久化在配置/状态，卷权限和备份边界未完成迁移 | 收紧 ACL/文件模式、加密备份并轮换 Cookie，验证 WebUI 访问 |
| H-06 | High | 恢复能力 | 加密备份、解密恢复和演练尚未真实执行 | 执行首次加密备份、临时/异机恢复 MySQL 和关键卷，保留 manifest |
| H-07 | High | 供应链 | 旧 2026-09-20 backend 扫描为 61 个唯一漏洞，不能代表 2026-09-22 重建的当前镜像 | 记录当前 backend 镜像 8f15013b5a35，重新扫描并做 fixed-version triage；不以旧计数或 npm audit 0 结案 |
| M-01 | Medium | MySQL TLS | `require_secure_transport=OFF`，密码策略未确认 | 先完成证书/连接验证计划，再开启并回归所有连接器 |
| M-02 | Medium | 镜像治理 | 部分第三方镜像仍使用 `latest`，完整 JVM/Python/容器 CVE triage 未闭环 | 固定版本/摘要，保留 Scout/依赖扫描和定期复核 |
| M-03 | Medium | MinIO 网络 | 已接入 gying-movie_gying-net 且有 minio alias；nginx 图片抽样 200 | 网络 alias 与图片路径已验证；端口与对象权限风险仍归 C-01/C-02 |
| M-04 | Medium | OpenClaw 集成 | 当前只接入默认 bridge，尚未接入应用网络；本轮未验证真实 QQ 搜索 | 备份配置后迁移到 backend:8880 内部链路，核验 token 与真实 QQ 搜索 |
| M-05 | Medium | 监控/告警 | 已有结构化安全日志，尚未接入集中保留和告警 | 对 401/403/429/5xx、权限异常、备份失败接入告警 |
| H-08 | High | 运行时凭据 | 迅雷凭据在线仍为 10001:10001 / 644；后端私有原子写入修复尚未部署 | 备份后发布修复，验证重复写入始终 600、旧文件可回滚；登录刷新故障另行处理 |
| M-06 | Medium | 容器权限 | quark/PanSou/MinIO 有 root 进程；5 个旧依赖容器缺 no-new-privileges | 逐个验证非 root、卷可写路径与 no-new-privileges；避免批量重建造成中断 |
| L-01 | Low | Origin TLS | origin 使用 HTTP，由 Cloudflare 提供公网 TLS | 开启 Always Use HTTPS、HSTS 和严格 origin policy；不直接暴露 origin |
| L-02 | Low | 审计范围 | 本地工具不能代替外部渗透和 Cloudflare 账号侧审计 | 每季度或重大变更后复核外部攻击面和账号策略 |
