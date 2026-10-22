# Windows 与 Linux 迁移

先完整读[迁移手册](../../../../MIGRATION.md)，步骤/检查点以其维护；本参考仅补平台差异。[旧参考](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/windows-migration.md)。

## 必须先确认

源/目标目录/提交/Compose项目、依赖现有或profile/托管、实际卷/owner、写入方/在途、域名/账号/计划是否沿用。受保护凭据单独迁，不打印。

## 迁移判断

Git不是备份；有dump不先schema，MinIO对象键保持；Redis仅缓存可重建，特殊不可重建状态单审；Quark/OpenClaw/QQ微博两卷/宿主计划必须盘点，NapCat不迁移/不恢复。

## 执行顺序

快照/就绪→协调停写→流式加密清单/认证/隔离恢复→目标隔离且自动化关闭→依赖/source/publisher/backend/SSR/nginx/OpenClaw→数据/入口/日志→按原开关逐项恢复→切换观察/保源回退。真实转存/发帖不是迁移默认健康测试。

## Windows 注意事项

Docker Desktop/WSL2/MySQL及数据路径、Interactive用户/计划触发/XML/cwd/ACL/时区；真实挂载必要字段投影，不完整inspect；UTF8读写、DPAPI绑定原账号/主机不当异机恢复。

## Linux 注意事项

支持的Engine/Compose、UID/GID/mode/SELinux/AppArmor、systemd的User/WorkingDirectory/EnvironmentFile、日志/NTP/监控。公网仅已评审入口，Tunnel不要求额外开放数据库/控制面；内部DNS与host.docker.internal需按平台实际替换验证。

## 禁止事项

不down-v/删卷/拷运行数据当一致备份、不盲跑全部SQL/旧外部队列/恢复NapCat、不把密钥配置入Git、不用旧dump自动覆盖新数据。难回退动作先备份/当前确认。
