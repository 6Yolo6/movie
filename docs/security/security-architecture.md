# 安全架构设计

[在线状态](../current-project-status.md) · [风险](risk-register.md) · [完整旧设计](../history/snapshots/20261022/docs/security/security-architecture.md)

## 1. 目标边界

公网→Cloudflare Tunnel/Access/WAF→loopback nginx→frontend/backend；数据、机器人、source/publisher仅内部。下列为目标架构，Redis隔离/MinIO身份/OpenClaw内网/origin JWT等仍未全部实施。

## 2. 信任区和访问矩阵

| 区域 | 允许链路/认证 |
| --- | --- |
| 页面/公开API | Cloudflare→nginx→前后端，TLS/CORS/输入及限流 |
| 图片 | nginx→MinIO白名单图片前缀/扩展，GET/HEAD、清query；非图片私有 |
| 管理 | Access入口身份+网站JWT ADMIN；禁止应用直连绕过 |
| QQ/OpenClaw | 受控内部网络→backend，token/管理员白名单/用户限流 |
| source/publisher | backend或获批worker，独立token，无宿主公开口 |
| Redis/MySQL | 专用网络ACL；独立DB身份/Host、Firewall、TLS计划 |
| MinIO/Docker控制面 | 本机操作者，不入Tunnel，不挂socket给应用 |

## 3. 不变量

单一公网入口127.0.0.1:80；双层管理授权；内部fail closed；独立最小身份；图片/私有对象隔离；外部age备份/认证/恢复分别验收；代码/目标/部署/真实证据分开。目标无暴露不等于旧生产已做到，查当前门禁。

## 4. 部署依赖关系

### 4.1 MinIO

备份data/config→核实际网络/alias minio→创建scoped key/policy→先从所有消费者验证新内部endpoint→逐服务迁移并验图片/私有对象→再改宿主9000/9001绑定→验控制台→观察。backend/source仍host.docker.internal时不可先关闭宿主口。失败回原配置/网络/镜像，保留卷。

### 4.2 OpenClaw

先备份源/唯一运行插件/Gateway配置、接受控共享网、验证backend内部地址和token/命令/限流，再收兼容host入口。当前loopback已收但内网迁移待验，不能重复假设尚未收口。NapCat不恢复。

### 4.3 数据库

新身份实际来源/SQL验证后逐服务切换，保留旧身份回退，不用Host=%或先删root。TLS独立验证CA/连接器及恢复方案。[DB流程](database-security.md)。

## 5. 监控信号

API401/403/413/429/5xx与内部路径404、Redis认证/限流503、DB来源/权限变化、MinIO非图片匿名访问/管理key、provider会话失败、backup incomplete/认证/恢复失败分别告警。日志禁凭据、完整query/body与分享token；旧CVE需当前digest另审。
