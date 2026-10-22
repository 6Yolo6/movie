# 数据库运维

[数据库与全部迁移](../../../../docs/database.md)、[安全身份](../../../../docs/security/database-security.md)、[加密恢复](../../../../docs/security/backup-plan.md)维护唯一契约，执行前先读。

## 基本规则

先SELECT/dry-run、备份/确认目标；禁止日常DROP/TRUNCATE/物理删历史。保留canonical/软删除/审计，新注释中文，MySQL/JVM/容器Asia/Shanghai一致。

## 迁移模型

### 全新数据库

schema含DROP，只确认空库可用；23表仍缺两QQ增量，不含默认管理员。按[清单](../../../../docs/database.md)补所需增量，默认自动化关闭。

### 历史数据库

先查表/字段/索引/注释/grants，迁移逐文件审查兼容/已执行/幂等/风险，不批跑全部SQL。应用无DDL不扩grant，维护身份执行；有dump不先跑schema。

## 预检查询

SELECT VERSION(),DATABASE(),CURRENT_USER()；查information_schema表/列/索引/外部ID collation、会话/服务器时区、关键状态计数。逐语句只读，不输出账号密码或完整敏感记录。

## 备份与恢复

流式age加密、清单/hash/完整认证；隔离空目标恢复核表/行/CHECKSUM/中文/对象/实际应用，备份不是恢复。不自动用旧dump覆盖新在线数据。

## MCP 与 CLI 选择

MCP仅单语句/参数绑定、默认只读；execute_sql需独立开关与当前授权。多句、DELIMITER/存储过程/dump/restore走CLI，凭据默认文件受保护，不上命令行。[MCP](mcp-operations.md)。

## 资源数据维护

重复先 `POST /api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true`；审核标题/季/来源后迁资源/任务/收藏/评论等关联到canonical再软删。错配资源同样dry-run。来源唯一(source,source_type,external_id,season)，电影0/utf8mb4_bin、冲突REJECTED保审计，不删除掩盖歧义。

## 变更后验证

字段/索引/中文注释/null/默认、mapper启动、时区/核心读取、任务/资源计数、来源/canonical、发布唯一/状态/默认关闭、回退仍兼容；记录实际时间与备份。[旧完整参考](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/database-operations.md)。
