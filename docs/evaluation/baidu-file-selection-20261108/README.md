# 百度接入与视频/种子独立配置

2026-11-08（Asia/Shanghai），分支 `codex/cloud-drive-workflows`，开发基线35eaec05。本轮代码及隔离验证完成，生产替换因当前安全门禁停止。线上仍是22:34上一轮统一配置与百度占位版本。

## 页面使用与实现

每盘独立 `videoEnabled / torrentEnabled`，保留发现、转存、分享、GYING发布、目录和手动批次配置。空间不足可关闭“采集视频文件”、开启“采集种子文件”，保存对应网盘。只保存小型torrent与磁力文本，不触发视频离线下载，也不清理已有视频。

关闭视频不消费旧视频任务；关闭种子/转存/分享不领取种子任务，不重置关闭盘的中断任务。手动视频与种子共享1–20总上限。夸克/迅雷缺少新类型键时兼容原开启状态；百度文件类型和四阶段默认全部关闭，扫码不会启用阶段。完整契约见[网盘工作流](../../cloud-drive-workflows.md)。

百度使用专用Edge扫码、十分钟单次票据、AES-GCM受限会话和专用账号绑定。新增独立表持久化内容计划、账号、CAS租约与转存/分享意图。未知远端结果只读恢复，自动入库需已验证自有分享。仅种子资源为TORRENT，不把影片标为视频可用；混合含禁用/未知文件的分享目录整体跳过，避免连带转存视频。

客户端采用百度网页协议兼容适配，依赖上游接口；固定公开参考及文件SHA见[来源](evidence/client-upstream-references.json)、[协议说明](evidence/client-protocol-notes.md)。这不是开放平台OAuth，真实账号端到端尚未验证。

## 验证

| 范围 | 结果与证据 |
| --- | --- |
| 后端最终全套 | 1,141项：1,140通过、1 Redis环境跳过、0失败/错误；[结果](backend-tests.json) |
| SQL与编译 | 必需真实MySQL全部通过，百度新增表8项涵盖DDL/mapper/唯一键/中文/租约/意图恢复；独立Java17 compile通过，两夹具已清理 |
| 来源绑定 | 最终379文件当前/测试副本SHA一致；[清单](backend-tested-source-manifest.json) |
| 前端 | 58项离线React、TS/ESLint、隔离Next构建；77源文件哈希绑定；[汇总](frontend-test-summary.json) |
| 浏览器 | 中英/明暗/390与1280八组mock API场景，0页面错误/溢出/外域请求；[场景](browser-qa.json) |
| 固定后端镜像 | network-none/UID10001构建，395类/资源与镜像内JAR SHA一致，379源再次匹配，临时容器清理；[候选](backend-candidate-build.json) |
| 固定前端镜像 | 非root UID1001、network-none页面/脚本200、375产物SHA一致，临时容器清理；[镜像](frontend-runtime-image.json)、[运行](frontend-runtime-smoke.json) |
| 宿主与库存 | Node会话助手11/11、Python夹具边界7/7、三PowerShell语法检查通过；26控制器/184路由与最终源及行号一致；[核验](evidence/verification-summary.json)、[API](api-inventory-validation.json)、[宿主候选](host-artifact-verification.json) |
| 独立审查 | 两项缺陷已修复并回归：旧成功视频任务遵循当前类型策略；种子任务旧分享URL入库前重新核验精确目录自有分享 |
| 生产保持 | 仅只读检查入口、镜像/启动时间与日志；候选未上线，[事实](evidence/production-unchanged.json) |

早先编译/测试失败及较早成功副本保留为[原尝试](evidence/prior-backend-attempts.json)，最终验证使用0c560876b4b8副本。前端模拟结果不代表生产管理员登录或真实百度操作。秘密扫描只有两处既有历史证据命中，本轮新增0；原历史文件未修改，[扫描](evidence/secret-scan.json)。

## 部署门禁与待验

23:13–23:14基线及23:38复查均为安全58 PASS/11 FAIL/1 UNKNOWN，G01–G11全FAIL；UNKNOWN为退出的旧迅雷探针。[本轮复查](evidence/security-final.json)。就绪从10 PASS/2 WARN/0 FAIL变为11 PASS/1 WARN/0 FAIL；新迁移文档已登记，剩余为既有QQ两表初始化缺口，[最终就绪](evidence/ops-readiness-final.md)。

[运维技能部署与恢复](../../../.agents/skills/gying-project-ops/references/deployment-recovery.md)明确要求：“门禁 FAIL、测试失败或目标不清时停止相应动作并汇报；历史部署不代表风险关闭或授权可复用。”此前限定业务例外未扩展至本轮，故没有替换生产服务。

本轮未执行生产SQL、安装/注册宿主扫码任务、真实登录百度账号、转存、分享或GYING发布。新增迁移仅加表。backend-data已有备份规则，但本轮私有会话/票据及宿主profile尚未安装/完成加密备份和恢复认证。仓库外材料目录：`E:/gying-tools/releases/baidu-file-selection-20261108`；保留旧镜像与实际Compose覆盖，宿主工具只有候选副本。固定backend/frontend覆盖已生成，两套现役Compose层叠只读config校验通过；[候选清单](release-candidate.json)仍标记未部署。

上线前须先满足本轮门禁，建立MySQL/backend-data/宿主private与profile的适用加密检查点、完整认证及日常覆盖，再执行新增迁移并仅替换backend/frontend。核对固定镜像、UID10001、权限和真实入口，宿主工具仅在管理员页面PENDING后开官方Edge。

旧后端忽略新文件类型/百度意图，不得盲目回退或重放任务；保留当前SQL、会话、计划和P2P检查点，先阻断旧消费者。真实扫码、受限文件权限、专用账号转存/自有分享、GYING发布及自然过期/未知恢复仍待明确样本验收；不以真实外发作健康探针。
