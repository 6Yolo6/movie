# 独立采集进度与搜索资源链接验收

日期：2026-10-22；时区 Asia/Shanghai。代码基线 0242183，分支 codex/security。

## 修复行为

- TMDB/GYING 每个来源独立设置起止页，局部更新合并指定来源，缺配置沿用旧共用范围。
- 修改范围保留范围内的页码/偏移，越界只回该来源起始页；扩展刚完成的旧结束页后续采下一页，空页仍结束本轮。失败和在途批次保持位置，手动单页不影响自动游标。
- 回归覆盖电影第150页和剧集第121页独立进度；自动enqueue和overview采用同一来源范围。
- 搜索资源保留二维码，并显示打开按钮及完整可点击地址；长地址无二维码时仍可点击。磁力交给支持的客户端下载。
- 网站与QQ回复按网盘/磁力/种子/在线资源分组。网盘托管的种子归档注明不含视频；API保留名称/类型，历史仅允许HTTP(S)/magnet。
- 英文390px资源中心清理按钮允许换行，避免撑宽页面。

## 验证

- 完整后端687项：686通过、1依赖跳过、0失败/错误。真实MySQL查询边界7项、监控7项均通过；mvn -q -DskipTests compile标记通过。两个fixture容器均清理，未使用生产数据/网络。
- 机器结果 tmp/query-boundaries-mysql-f9cbf6f31b86/result.json，required_sql_verified及compile_verified为true。
- 前端typegen/tsc/lint通过；全库lint有7条原有warning、0error，受影响文件lint无warning。独立副本webpack生产构建通过，SSR指向未承载生产的loopback18999。
- 原生Turbopack构建遇本机EXDEV遥测写入、独立副本依赖junction越根失败；关闭遥测并用独立webpack构建完成验证，未修改项目构建配置。生产镜像未构建或替换。
- node --test tools/tests/resource-search-ui.test.cjs 9/9通过：分类、协议白名单、历史、候选和分页解析。
- 真实React/AntD离线构建浏览器：资源链接中文桌面/深色390、英文390通过；验证二维码与可点击链接、类型分组、长URL降级、危险链接拒绝、刷新不重复查询。全部API为mock，无真实转存/账号/发布。
- 资源中心中文/英文桌面、中文深色390、英文浅色390旧配置通过：独立修改/保存/重载、未编辑来源保留、反向范围拒绝、周榜配置保留及无横向溢出。
- 构建与截图 tmp/metadata-crawl-validation/。串行浏览器重试已通过；前次系统页面文件不足导致启动失败。视口不等于真机，未执行下载。

## AI日志入口

网站/ai-assistant回答底部展开“执行记录”。本机 http://127.0.0.1:3000/#/logs 为执行日志；页面及其/api/traces本轮HTTP200，后者code=200、3条现有记录，未打印内容。全局最多200条内存记录，AI服务重启清空，并非持久聊天存档。

## 部署及回退

快照确认backend/frontend仍为current-project-status记录的既有镜像。就绪11PASS/1warning/0FAIL；安全扫描59PASS/11FAIL/0UNKNOWN，G01–G11未关闭：Quark/MinIO非loopback、5个依赖缺NNP、3个依赖有root进程、Redis网络隔离。

按AGENTS.md和运维技能的门禁规则停止部署，以前发布的例外不能复用。未创建本轮生产检查点、改变在线采集开关、写入独立范围或替换镜像。待本轮确认后按docs/deployment.md留存检查点及旧镜像，仅替换backend/frontend并验证真实入口。

独立范围属于新增sys_config键，无新增表。旧版本忽略新键并使用共用范围；回退前须保留每来源配置/任务payload并审查调度范围，不能声称行为完全等价。SQL和任务历史不自动恢复或重放。
