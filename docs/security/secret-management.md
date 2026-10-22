# 密钥、Cookie 和 Token 管理

[现状](../current-project-status.md) · [备份](backup-plan.md) · [完整旧证据](../history/snapshots/20261022/docs/security/secret-management.md)

## 1. 规则

真实值仅受保护外部文件/secret/configtree/内存，按服务身份ACL；示例仅键名/占位符。生产强校验fail closed，用途独立值。轮换先验证新链路再撤旧；凭据轮换/历史重写须当前明确范围，操作记录仅键名/时间/结果。

## 2. 当前扫描结果

2026-10-22工作区0 findings；旧可达历史1278文本Blob/105重复规则命中，不等于105唯一凭据。历史重写/旧值轮换未闭环，当前文件清理不消除历史风险。按事件确认有效范围→轮换/审计→必要访问撤销→协调clone/镜像/备份并另行批准历史重写；不保存匹配值。

## 3. 密钥分类和来源

| 范围 | 来源/恢复边界 |
| --- | --- |
| DB/JWT/Redis/MinIO | 外部env/secret；独立DB身份/ACL/policy；JWT轮换使设备失效 |
| GYING/social/QQ/OpenClaw | 独立内部token/外部配置；provider重新授权，不能跨用途复用 |
| Quark/Xunlei/微博 | Quark config/Cookie、backend-data、publisher状态卷；撤旧会话后验权限/自然任务 |
| Tunnel/Access | 外部受限credentials/账号侧策略；恢复不得扩大公网入口 |
| age | 配置公钥，私钥离线保管；旧recipient保留恢复窗口 |

需要通知外部人员/真实平台动作时遵循当前授权，不因手册自动发送消息。

## 4. Quark 特别说明

QUARK_COOKIE覆盖仍可能落/app/config；backend fallback仅内存、禁POST回/update。9月25父目录root0777→0700；六文件自身仍五0777/一0755，仅父目录阻遍历，不防root/Docker管理员。Cookie撤销、单文件600、非root待验；备份age加密，恢复不重新启用已撤会话。

### 本次目录权限变更的备份与回退

`G:/gying-tools/security-20260925/quark-directory-20260925-191131-90e50cb0` 保存两加密配置/运行产物、身份/inode/hash/健康证据。Rollback-QuarkDirectory.ps1默认dry-run；紧急授权Apply验证精确身份后仅回原目录模式（会扩大访问），不恢复整个旧归档覆盖后续写入。9隔离POSIX测试/9健康通过，无容器重启或网盘动作。

## 5. Docker Secret/configtree

Spring optional:configtree:/run/secrets/已支持；受限文件不可挂前端/static/log目录。实际Compose仍兼容env，隔离验证property precedence后逐服务切换，只按键名/长度/健康验证，禁完整inspect/config输出。真实配置可能仍在本机工作区且Git忽略，不能从目标“仓库外”规则写成已迁移完成。

## 6. 开发和提交门禁

```powershell
python -X utf8 tools/security/scan_secrets.py
python -X utf8 tools/security/scan_secrets.py --history
```

仅报告路径/行号/规则摘要；不使用会直接打印匹配密钥行的普通rg命令。组织CI可补独立secret scanner，不能用本扫描器推断全部泄露已消除。

## 7. 迅雷状态文件写入边界（2026-09-25 复核）

PrivateFileWriter同目录随机私有临时文件，POSIX创建600/Windows先owner ACL再写，拒符号链接；ATOMIC_MOVE不支持就失败，清临时、保留旧状态，不降级。旧644替换补丁已部署。10月8同步真实非root写回/Drive200/10001:10001 600通过，见[迅雷](../xunlei-token-sync.md)；backend独立长期续期/重复写入仍单列，权限不等于授权有效，不能触发业务转存补验收。
