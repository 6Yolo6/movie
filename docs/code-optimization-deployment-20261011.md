# 代码优化与手机适配部署验收（2026-10-11）

[实现](code-optimization-20261009-round2.md) · [现行发布契约](social-publishing.md) · [完整旧证据](history/snapshots/20261022/docs/code-optimization-deployment-20261011.md)

## 结果与范围

362090c，10月10构建/11日10:19–10:26部署；四应用替换、nginx测试重载，无DDL/凭据/网络/云端/真实发帖转存。标签日期非上线日；原11安全失败保留。

## 实际镜像

| 服务 | 标签 / ID前缀 / UID |
| --- | --- |
| backend | gying-code-optimization-backend:20261010a / 1c38d3471c77 /10001 |
| frontend | gying-code-optimization-frontend:20261010a /42b61b863c20 /1001 |
| source | gying-code-optimization-gying-source:20261010a /d7ecc468409c /10001 |
| publisher | gying-code-optimization-social-publisher:20261010a /a5a2c09753de /1000 |

## 验收

实现阶段后端523（5skip）、Python54/publisher31/SQL6/Node42；上线候选source/publisher54/31复验，候选/生产首页10+发布14 mock场景、8真实入口/内部依赖/SSR通过，四应用无ERROR。手机视口非真机/真实账号出站；账号/环境/挂载不变。

## 协调切换与数据边界

原09:00自然3帖后193POSTED/12历史FAILED、在途0；切换不制造外部帖子。显式-p/-f/有效覆盖，指定四应用--no-deps/--no-build/--pull never，不隐式全栈启动。旧任务保审计，恢复按原开关。

## 加密检查点与回退

`G:/gying-backups/20261011T022133.799192Z` 十age hash/认证解密、25表dump/完成标记/配置一致；含两publisher状态卷，非全依赖/系统账号/灾备。`E:/gying-tools/releases/code-optimization-20261010` 保留rollback.json、四rollback别名、release_ops.py rollback-check/RUNBOOK。新行/配置/在途/UNKNOWN阻自动回退；审外部结果，backend/publisher协调，不SQL重置/恢复旧数据。

## 公网波动与保留项

10:12:35切换前公网530、本机正常，暂停后自行恢复；前后各连续7轮通过，不证明根因已解决。证据tmp/code-optimization-deploy-20261010（目录日是构建日），实际旧镜像/参数详情归档保留，未来执行重新核对。
