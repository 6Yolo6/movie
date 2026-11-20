# 入口与Docker端口恢复（2026-11-13）

Docker WSL退出/自动恢复前有虚拟内存不足事件，时序相关但不证明WSL被OOM终止。nginx旧固定IP与Quark冲突；backend/MinIO容器内健康但宿主端口空响应。13:17 nginx改动态地址并启动，13:26 MinIO、13:27 backend正常TERM后启动原容器，原ID/镜像/Config/HostConfig保持。

13:31本机/公网首页、片库/SSR/海报、20入口及Redis认证PING/PanSou TCP通过；隔离网络夹具9项通过，有界日志无新错误。[最终独立验收](independent-verification.json)。Quark2262前代RUNNING全行保持、未重放；内部健康不代provider业务。

原配置DPAPI认证材料 E:/gying-tools/releases/entrypoint-recovery-20261113/，仅原主机/账户可解；回退网络先核地址空闲，不能恢复已冲突固定IP。数据卷保持，无数据恢复。

内存提交约85%、Quark2262远端结果、APP_TRUSTED_PROXIES精确地址失配仍待处理；入口恢复不关闭资源耗尽根因。原全仓扫描两告警为既有SHA指纹，原扫描结果保留；安全/自然维护备份/外部出站未代验。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
