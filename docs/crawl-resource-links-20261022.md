# 独立采集进度与搜索资源链接验收

日期：2026-10-22；时区 Asia/Shanghai。代码提交 `706ae3e`，前序基线 `0242183`，分支 `codex/security`。21:22 已部署 backend/frontend，21:34 完成入口和数据复核；[机器摘要](evaluation/crawl-resource-links-20261022/validation-summary.json)。

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
- 原生Turbopack构建遇本机EXDEV遥测写入、独立副本依赖junction越根失败；关闭遥测并用独立webpack构建完成验证，未修改项目构建配置。以该验证产物构建生产镜像并完成替换，详见部署记录。
- node --test tools/tests/resource-search-ui.test.cjs 9/9通过：分类、协议白名单、历史、候选和分页解析。
- 真实React/AntD离线构建浏览器：资源链接中文桌面/深色390、英文390通过；验证二维码与可点击链接、类型分组、长URL降级、危险链接拒绝、刷新不重复查询。全部API为mock，无真实转存/账号/发布。
- 资源中心中文/英文桌面、中文深色390、英文浅色390旧配置通过：独立修改/保存/重载、未编辑来源保留、反向范围拒绝、周榜配置保留及无横向溢出。
- 构建与截图 tmp/metadata-crawl-validation/。串行浏览器重试已通过；前次系统页面文件不足导致启动失败。视口不等于真机，未执行下载。

## AI日志入口

网站/ai-assistant回答底部展开“执行记录”。本机 http://127.0.0.1:3000/#/logs 为执行日志；页面及其/api/traces本轮HTTP200，后者code=200、3条现有记录，未打印内容。全局最多200条内存记录，AI服务重启清空，并非持久聊天存档。

## 部署及回退

就绪检查11 PASS/1 warning/0 FAIL。部署前后安全扫描均为59 PASS/11 FAIL/0 UNKNOWN，G01–G11原失败一致。本轮用户明确批准“通过部署”，只授权backend/frontend业务发布；Quark/MinIO端口、五依赖NNP、三依赖root进程及Redis隔离仍待整改，授权不复用于后续任务。

2026-10-22 21:22 优雅停止后端并生成新检查点，随后仅切换backend/frontend；nginx配置检查及平滑重载通过。后端镜像 `sha256:18a8c2e0e4966277102aa71c9ad1ef4a29fff2928cdaba07edfdb3e784dbbe5b`，前端镜像 `sha256:6066320686beb3ae02e07a9f44418527b0bf91bcad44857c2affff800be7e6c1`。运行环境、挂载、网络和端口摘要与原基线一致；其余9个容器的ID、镜像、启动时间和重启次数保持不变。

检查点 `G:/gying-backups/20261022T132212.039731Z` 含SQL、应用配置、Compose、旧覆盖、后端数据/日志、两个发布状态卷及运行态，共9份age产物；哈希、完整认证解密及SQL完成标记通过，未落地明文。它是应用回退检查点，不等于异机灾难恢复演练。实际覆盖、旧镜像回退配置和外部验收材料保存在 `E:/gying-tools/releases/crawl-resource-links-20261022/`。

21:33–21:34 复核本机与公网22个入口：首页、资源搜索、AI页面、详情和真实片库API返回200；匿名资源搜索/AI接口401，internal接口404。本机管理配置/overview接口401；公网管理路径302至Cloudflare Access，本轮未跟随重定向将登录页200误当业务响应。frontend经Docker DNS访问backend真实片库、source/publisher内部health均200。

生产jar哈希与已审查镜像一致，342个类/资源已在包内验证；95个前端静态文件与验证产物一致。公网资源搜索和本机资源中心中文深色390px页面通过链接分组、历史重载、独立范围保存和无横向溢出验收，HTML/静态资源来自真实部署，全部业务API为mock；未宣称真实登录账号端到端验证，未转存、下载或发布。

只读SQL复核独立范围配置、部署前5条最新自动范围任务的状态和完整payload均未改变；TMDB电影/剧集的保留nextPage为102/122，GYING电影/剧集/动漫为35/36/36。这些是当前真实断点，回归中的150/121是复现用例，不推断恢复更早已丢失的位置。转存与发布审计计数不变。21:22–21:34后端、前端、source、publisher的ERROR/Exception均为0；本次短窗口不代表全天无错。

独立范围属于新增sys_config键，无新增表。旧版本忽略新键并使用共用范围；回退前须保留每来源配置/任务payload并审查调度范围，不能声称行为完全等价。本次未写入生产范围配置，后续改为独立范围后须重新审查兼容性；SQL和任务历史不自动恢复或重放。
