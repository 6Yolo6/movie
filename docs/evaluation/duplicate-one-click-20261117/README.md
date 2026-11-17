# 全库一键去重交付（2026-11-17）

Resource Hub 在“扫描全库重复影片”上方新增直接可见的“一键去重”。读取全库所有分页，仅预选名称与人物支持且影片 ID 不重叠的候选；后端预览剔除受阻组，展示保留方向、关联影响和跳过原因。管理员核对全部待合并记录后一次确认，按每批最多 20 对执行。后续失败保留已完成数量，预览过期或变化停止，未知结果仅使用原审核与操作编号重试。原手工扫描/逐组审核保留。

## 验证与部署

- 37 项 React 回归全部通过（原 18 个场景及新增 18 个批量场景，另含父套件），TypeScript、组件 ESLint、生产镜像构建通过。
- 2026-11-17 12:44:26（Asia/Shanghai）仅 frontend 激活，复用原 14 个生产 Compose 文件并追加固定镜像覆盖；nginx 校验后平滑重载。后端及数据库结构未改。
- 现役 frontend 镜像 `sha256:8dc7a67355fd00ece6f7a69883fd27961ae42fa3b08573009e4626c660d7ff13`；旧镜像 `sha256:caa3874078be0a41da0ffb23c09d91d9fd8a01e9f041edcee470b7c9a28bb204` 保留，回退沿用原配置并恢复旧 frontend 镜像。
- 实际本机首页、后台页面、中文词条、影片列表及公网首页/词条/列表 HTTP 200；有界启动日志无错误标记。
- 已上线 Next/AntD 在中文 390 浅色及英文 1440 深色视口通过：按钮可见、全局确认门禁、阻断跳过、分批预览、成功后刷新，页面无错误和横向溢出。浏览器场景所有 API 使用夹具，未代替实际生产合并验证。

## 实际数据审核

操作前完整扫描 17,967 条影片、87 组候选；25 组冲突隔离，105 条缺少可靠元数据。与历史独立审核比较：7 组此前已确认、70 组此前已排除、10 组新增。新增中 6 组具备精确英文片名、年份、类别、演员和可信 TMDB/GYING 来源身份，另外 4 组属于不同影片。历史 7 组新预览仍被活跃/UNKNOWN、临时清理或身份冲突阻断，未覆盖门禁。

2026-11-17 12:53:43（Asia/Shanghai）通过现役审核接口单次成功合并 6 组：博物馆奇妙夜2、夺宝奇兵4、终结者3、终结者4／终结者2018、博物馆奇妙夜3、刺杀金正恩／采访，均保留独立确认的 GYING 记录。6 条源记录软删除，6 条来源身份迁移、6 个永久别名、18 条精确迁移历史通过；23 条资源和 12 条目标任务整行保持，目标 TMDB 身份绑定正确，其他元数据和时间保持。未发送历史 7 组或排除项的合并，未调用网盘清理或外部发布。

12:56:15最终只读复核通过：新旧12个详情入口均HTTP 200，六个旧ID解析到对应canonical，正常资源分别为4/4/4/4/4/3。全库复扫17,967→17,961条影片、87→81组候选，精确移除本次六组，其余81组候选完整保留。12:56:55独立重新读取SQL并计算整行哈希，身份/别名/历史和23资源/12任务保持再次通过。

本次去重前单独创建 MySQL 单事务加密检查点 `G:/gying-backups/duplicate-one-click-20261117/20261117T044423.063555Z`，manifest/hash/大小与完整 age 认证解密通过，无明文落盘；不代表系统恢复演练。现有日常备份配置保留。

原始脱敏运行证据在 `E:/gying-tools/releases/duplicate-one-click-20261117/`：`deployment-receipt.json`、`public-entry-checks.json`、`browser-smoke.json`、`scan-before.json`、`new-candidate-audit.json`、`historical-seven-preview.json`、`new-six-preview.json`、`mysql-checkpoint-verification.json`、`merge-response.json`、`merge-scoped-before.json`、`merge-scoped-after.json`、`merge-history-verification.json`、`merge-entry-verification.json`、`scan-after.json`、`merge-final-report.json`、`independent-merge-verification.json`。本次最初本地时间解析失败未发送合并，后续仅新建一次同六组预览再单次合并；旧详情响应解析假设与429限流失败保留，纠正契约并有界节流后最终只读通过，没有重发合并。合并契约见[重复影片审核与合并](../../duplicate-movie-merge.md)。
