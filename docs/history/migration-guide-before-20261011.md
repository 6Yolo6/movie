# 旧迁移资料索引

[现行迁移](../../MIGRATION.md) · [历史导航](README.md) · [完整旧索引](snapshots/20261022/docs/history/migration-guide-before-20261011.md)

## 适用边界

2026-10-11归档，不是新迁移/备份/恢复。旧盘符/“当前”表数/不固定镜像/明文导出命令过期，实际执行先核当前源/目标/认证/版本/隔离，不复制旧命令。

## 2026-09-10 导出记录

旧源D:/lide_expert_manage/gying-movie、migration-data/20260910-102117；DB/对象/backend/QQ微博/OpenClaw/Quark/工具/计划/环境快照，在线非最终一致性；敏感副本不提交，LATEST仅当时指针。

## 2026-09-14 迁移验证记录

migration-data/20260914-081539，4832/4832 SHA匹配；movie_metadata1631/resource_link2165，旧回退E:/gying-data/gying-pre-deploy-20260914.sql。仅历史计数/路径，DPAPI原机器账号限制；与9月25完整加密及后续应用检查点不同。

## 原手册溯源

```powershell
git show fdf7276a4606b3957d2ee912232781e71503818a:MIGRATION.md
```

只读文字不执行；现行[DB](../database.md)/[部署](../deployment.md)/[加密备份](../security/backup-plan.md)替代旧做法。迁移工具存在不证明满足加密/一致性/恢复门禁，NapCat不恢复。
