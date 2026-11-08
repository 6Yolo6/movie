# 网盘配置上线验收

2026-11-08 22:34:28（Asia/Shanghai）完成，仅替换 backend / frontend；代码 `6b4104f2cabb570ebc40867162a507fdcf46532d`，开发分支 `codex/cloud-drive-workflows`。用户本轮明确“可部署”，安全 58 PASS / 11 FAIL / 1 UNKNOWN 及 G01–G11 保持未关闭。[机器摘要](verification-summary.json)、[原始证据清单](evidence/manifest.json)。

## 实际运行

| 服务 | 已核对产物 |
| --- | --- |
| backend | image `sha256:2925c3b129e1ddf505f0483403ae5c3eb11c4675fbb6f8cec6af96ffcb78c6d4`，JAR `21dd135cdc9f604e0d2f2ab9a62e43e40e616e2bd64638a7f7ea7cfc465c95f6`，360 源文件 / 364 class-resource 绑定 |
| frontend | image `sha256:35cce07fe8af5dbcd6b625b96d0c6c3a83b91995766989b865d5ae282744359f`，74 Git 源文件加单列生成文件，375 实际产物 SHA 一致 |

原 backend 12 层 / frontend 6 层 Compose 末尾追加 `E:/gying-tools/releases/cloud-drive-workflows-20261108/candidate.json`，实际 13 / 7 层。环境、用户、挂载、网络、端口、内存、日志参数保持；nginx 校验后重载，其他容器和本机服务保持。[独立只读](evidence/post-deployment-readonly.json)为 21 PASS / 0 FAIL / 1 UNKNOWN，仅 Agent 普通权限实时命令行不可读；受管记录、创建时间、唯一监听、固定 JAR 和四本机健康入口通过。该 UNKNOWN 不冒充已验证。

## 检查点与回退

旧 backend / frontend 正常 TERM 退出 143。在 backend 停止窗口完成最终六份 age：`G:/gying-backups/cloud-drive-workflows-20261108-20261108T143327.142120Z`。包含两旧镜像、有效配置、单事务 MySQL、backend-data、ai-state-key；size / SHA / 完整认证及 OCI、33 配置成员、25 SQL 对象和队列密钥恢复核对通过，SQL 未导入。静态镜像复用已认证的在线预备制品；在线预备目录 `...T141852.558294Z` 不称最终停机快照。[最终检查点](evidence/final-checkpoint.json)。无关写入方保持，非跨服务原子快照。

日常备份配置原值已加密认证备份，在 SHA CAS 下只追加 candidate 路径；31 项 dry-run 通过，下一次自然完整备份未验。[备份覆盖](evidence/daily-backup-plan.json)。固定助手清单及 41 项离线检查另存；当前应用未实际回退。回退保持当前 SQL / state / key，任何关闭或非法网盘阶段、SQL 读取失败均阻止旧 backend 启动；候选写入者停止后再次检查，必要时恢复固定候选。[助手清单](evidence/helper-manifest.json)、[配置与回退约束](../../../cloud-drive-workflows.md#回退)。

## 真实入口和页面

本机与经既有 loopback 7897 代理的公网首页、片库和同 ID 详情通过；源站匿名管理 401、公网 Access 302、internal / QQ 路径 404。新后台的 38 个脚本公网与本机 SHA 全部一致；首次逐项传输 URLError 原证据保留，定向复查 38 / 38 通过。[首轮入口](evidence/entrypoints-143539.json)、[静态资源复查](evidence/public-static-recheck.json)。不改系统代理；公网管理员 Access OTP 未在本轮登录。

本机实际 nginx / Next / React / AntD 页面与真实 ADMIN API 完成中英 × 明暗 × 390 / 1280 八组：字段与 API 一致，刷新保持配置和主题，百度只读 PLANNED、按钮高度与 Switch ARIA、无横向溢出和页面错误通过。复用既有有效 ADMIN device 的 480 秒内存 JWT，无新登录 / 用户 / device；所有非读取 API（包括 page-view）在浏览器拦截，Resource Hub 配置行 hash / 更新时间保持，新 drive 标量键仍为 0。保存及失败草稿行为来自部署前 React / mock 测试，生产未写配置。[真实浏览器](evidence/live-admin-ui-v4.json)、[认证与配置保持](evidence/live-admin-verification-v4.json)。截图在本轮可视化目录 `cloud-drive-workflows/live-v4/`。

原浏览器超时和 HTTP 断言失败保留；诊断助手第二轮包含无效 Windows 截图名 ENOENT，已改用独立步骤字段；最终按场景间隔 24 秒重新完成八组，不改生产限流或应用代码。原失败不计通过。[首轮](evidence/live-admin-ui.json)、[诊断二](evidence/live-admin-ui-v2.json)、[诊断三](evidence/live-admin-ui-v3.json)。冻结部署 / 回退助手未修改，新验收脚本在 release 外部目录单列。

独立日志观察 backend 从 22:33:56、frontend 从 22:34:20 至 22:39:00，ERROR / Exception / OOM 及重启均 0。[日志补充](evidence/independent-log-observation-through-1439.json)。这是限定窗口，后续自然运行仍需观察。

## 维护记录与边界

上午维护已完成容器 / 本机服务恢复，末尾健康探针失败使 journal 留在 recovery_failed。部署前独立只读复核后，将原 state 加密并认证内容，持全局锁 / SHA CAS 保留原 health、recoveryError、recoveredAt，追加两次 fresh direct 健康复查并标记 recovered，pendingMaintenance 0；无重启或队列变更。[维护收尾](evidence/maintenance-journal-reconciliation.json)、[健康复核](evidence/maintenance-health-recheck.json)。原公网 direct / proxy 传输差异保留，不归因于本轮应用。

本轮未做 SQL 迁移、旧任务重置、数据删除、真实网盘登录 / 转存、GYING 发帖或群发。百度只有扩展入口；后续专用账号扫码接入另行实现和验证。安全 G01–G11 和自然完整备份、真实副作用及真机验收保持待处理。
