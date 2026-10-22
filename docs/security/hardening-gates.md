# 11 项安全门禁与续验工具

最近线上扫描：2026-10-22，59 PASS / 11 FAIL / 0 UNKNOWN，下面 11 项全 FAIL。`gate_ledger` 不包含 Firewall、DB grants、Access、MinIO policy、凭据与恢复等全部门禁，后者见[部署清单](deployment-checklist.md)。

## 固定编号与当前结果

| 编号 | 对象 | 未完成项 | 验收要求 |
| --- | --- | --- | --- |
| G01 | Quark | 5005 非 loopback | 收窄发布，内部调用/本机登录可用 |
| G02 | MinIO | 9000/9001 非 loopback，合计一项 | 先迁移真实 S3 调用，再改绑；图片/私有对象/控制台验证 |
| G03 | OpenClaw | 缺 NNP | 实际 no-new-privileges=true；Gateway/插件/QQ 鉴权 |
| G04 | Redis | 缺 NNP | 认证/Lua/限流正常 |
| G05 | Quark | 缺 NNP | 私有配置及启动无需提权 |
| G06 | PanSou | 缺 NNP | 健康和只读搜索 |
| G07 | MinIO | 缺 NNP | S3/图片/权限 |
| G08 | Quark | UID 0 | 所有实际进程非 root，生产 config 兼容 |
| G09 | PanSou | UID 0 | 非 root、缓存可写 |
| G10 | MinIO | UID 0 | 非 root、真实 data/config/policy 兼容 |
| G11 | Redis | 共享 gying-net | 仅 internal cache-net；所有消费者/认证/Lua/失联拒绝 |

检查器依据实际进程、网络、发布和 security options；缺失/重复 PASS、daemon 错误为 UNKNOWN，已知 FAIL 不被异常元数据覆盖。53→59 PASS 的历史变化来自检查覆盖，不是修复了六个风险。

## 为什么不能直接运行一次 Compose

目标文件不等于运行态。Quark root-owned 0700 卷不能直接切 UID；MinIO/OpenClaw 是独立容器；2026-10-08 backend/source 仍用 host.docker.internal:9000，先迁移调用再收窄发布。应用回退检查点也不覆盖新依赖迁移的真实数据/配置。

## 非 root 改造可行性：隔离验证已通过，尚未部署

2026-10-08 固定现役镜像、network=none、私有空 tmpfs、合成凭据验证：PanSou/Quark/MinIO UID 10001 + NNP 健康通过；Redis UID 999 + NNP，匿名拒绝/认证 PING/Lua/CONFIG 拒绝通过。自有临时容器已清理。未验证真实卷、Cookie、policy、外部转存和 OpenClaw 账号，不能直接上线。

## 真实部署前的独立阻碍

Access 在 2026-10-13 已启用限定管理路径；此前 403/缺 Token 是历史阶段，不再要求重复录入或扩大权限。本人 OTP、网站 ADMIN/USER、origin JWT/WAF 仍待验收。其余前置为 DB 分服务身份、MinIO policy/密钥、Firewall 公网/IPv6、待变服务的加密检查点与恢复验证。

## 门禁通过后的分步路线

按 PanSou → Redis → OpenClaw → Quark → MinIO 逐服务安排维护；每次核对原 ID/镜像/真实消费者/卷和私有身份，验证后再继续。保留旧镜像/配置，不批量重建；真实转存/发布单独按任务范围执行，不重放旧队列。

## 可重复执行的验证

    python -X utf8 tools/security/check_security.py --repo . --probe --output tmp/security-current.json
    python -X utf8 tools/security/test_dependency_profiles.py --output tmp/dependency-profiles.json

第一条只读，有 FAIL/UNKNOWN 返回非零；第二条显式创建/清理无生产挂载的自有候选，使用本机已存在固定镜像，禁止拉取。P2P 进度工具用非 root defaults + READ ONLY 事务验证真实下一游标/持久重试引用；没有自然失败时保持 UNKNOWN，不人为制造生产错误。

命令参数和全部 10 月 8 日材料见[原文](../history/snapshots/20261022/docs/security/hardening-gates.md)。当前发布和扫描证据见[本轮报告](../catalog-features-search-20261021.md)。
