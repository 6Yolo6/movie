# TVmaze 剧集增量接入（2026-11-19）

> 2026-11-19后续调整：TVmaze已由24小时改为每6小时一轮，每轮仍为10条详情尝试，最短间隔支持1小时。[频率调整及现役部署](../tvmaze-frequency-20261119/README.md)。下文保留首次接入的部署与首轮验收记录。

TVmaze已在当前生产后台启用，后台与前端均已上线。本次只补剧集元数据；TMDB／GYING继续每日查询已选榜单前两页，TMDB新增元数据的既有GYING种子跟进继续沿用。

TVmaze使用 `/updates/shows?since=day` 的变更ID和上游时间戳，漏跑按week／month重叠窗口补查。优先更新已绑定剧集，每24小时最多尝试10条详情；未知ID候选池最多100条、7天过期，单个版本失败最多3次。详情预算、候选与重试时间使用既有任务表持久保存，没有SQL或表结构迁移。

通过精确IMDb／TVDB→TMDB剧集ID、年份与第一季身份关联canonical记录，不按近似片名自动绑定。保留已有中文和本地资料，仅补空值、更新TVmaze曾写入且未被人工改动的字段。身份凭证保存TVmaze ID、上游版本、来源URL和许可；页脚显示TVmaze及CC BY-SA 4.0。

| 实际服务 | 固定镜像 | 启动时间（Asia/Shanghai） |
| --- | --- | --- |
| backend | `sha256:44a74bb1976de81b3d932ab66ae0edc587a817fd5287c20e75b3b9cda0c20f42` | 2026-11-19 21:32:12 |
| frontend | `sha256:bb21744b05ec2bde1dfc8a360ce9580bfb75c687352d579f7b8ff457c3e42d69` | 2026-11-19 21:43:15 |

沿用两项服务各自的现役生产Compose覆盖，后台27层、前端17层；运行环境、命令、用户、HostConfig与挂载保持。后台Worker短暂暂停后恢复true，同时启用TVmaze=true／24小时／10条，其他来源配置哈希保持。仅在现有宿主文件内更新expectedBackendImage，旧应用镜像与回退覆盖已保留，沿用既有日常备份计划。

自然首轮18190于21:36:31创建、21:37:14成功结束：尝试10条、关联／更新9部剧集、1条无精确关联候选、失败0；剩余90条待后续预算，跳过204含候选池筛选与未关联条目。独立只读核对9个CONFIRMED剧集身份、TMDB类型／季号、上游版本、来源URL和许可通过。21:49回读仍为同一完成任务，三平台均24小时／10条、两个原UPDATES及Worker保持开启。

156项后端定向测试、Java17 compile／package通过；前端11项React配置回归、TypeScript、定向ESLint和生产构建通过。中文390浅色、英文1440深色真实构建／模拟API页面验证保存、进度、复核提醒与无横向溢出；修正镜像另经隔离Linux容器首页／管理页／剧集详情验证。最终5个本机入口、5个公网入口通过，公网管理页保留Access302，公开署名通过，有界日志错误标记0。

前端初次构建遇到telemetry跨卷写入及并发内存问题，最终关闭telemetry并使用单Worker的Webpack构建。首次前端镜像沿用旧Turbopack裁剪依赖，缺少Webpack运行模块而返回500，已恢复旧前端；补入实际构建的traced Node依赖与对应server.js后，隔离运行通过才再次仅替换前端。后台没有重复重启。

回退先关闭TVmaze并等待相关任务空闲，使用同套生产Compose及 `E:/gying-tools/releases/tvmaze-metadata-20261119/` 的backend／frontend rollback覆盖恢复旧镜像；回退后台时同步宿主镜像指向。保留现有来源身份、检查点和数据，不重放历史UNKNOWN。TVmaze下一24小时冷却、真实版本更新及长期积压仍待自然发生时验；不覆盖全部全球日变更。Wikidata、IMDb、Trakt保持未接入。
