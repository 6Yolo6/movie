# TMDB 高分剧集空跑修复

2026-11-02，Asia/Shanghai。此次继续请求明确授权修复空跑。构建和验证材料位于 `E:/gying-tools/releases/tmdb-empty-range-20261102/`；当前上线和入口结果见[验收摘要](validation.json)。

17:43:49–50 上游高分剧集为131页、2610条，现配置360–500；截至当时，自10月23日21:05以来104轮请求空页并记录SUCCEEDED。最后有内容的任务13686于10月23日17:03采集第131页。电影任务17298于11月2日16:16仍17新建/3更新、下一页251。详细历史及配置变化见[只读调查](../platform-diagnosis-20261102/README.md)。没有证据确认是谁调整范围。

原客户端丢弃total_pages，规划器把空页推进回所设起页，Worker因此反复建立同页任务。修复后自动范围请求严格验证上游page、total_pages、total_results、results和条目身份；HTTP、解析、缺字段、范围内异常空页及非法条目保持FAILED并保留位置，不能建立无数据冷却。手动单页请求维持原契约。

配置起页超过真实末页时，任务payload.crawl记录EMPTY_RANGE、实测页数及24小时后nextCheckAt，保留范围和历史。在复查前不创建该榜的新自动任务，时间到再读上游；重启沿用持久检查点，调整该榜范围解除旧冷却。上游增长覆盖范围后可继续。有效范围内按min(配置末页,上游末页)推进，末页全部处理完成才回所设起页；页内分批、失败重试和原正常循环保持。旧游标超过末页但范围仍有效时显示RANGE_EXHAUSTED，下一轮回所设起页，不进入24小时冷却。

Worker按原轮转顺序检查每个榜，跳过无数据等待中的榜继续其他榜；全局最低1小时及在途任务限制保持，force不会绕过。GYING、手动任务及云发现/转存策略保持。管理页显示真实末页、范围无数据原因、北京时间复查时间；任务存储仍用兼容的SUCCEEDED表示请求完成，明确的TMDB自动范围空结果在界面显示中性范围标签，不表示采集了影片。旧历史任务不伪造新增字段。

仅替换backend/frontend，沿各自现役Compose覆盖链固定镜像，不改范围、旧任务、数据库结构、凭据或其他应用。回退只恢复旧应用镜像和有效配置，保留当前SQL/state/key；旧backend会忽略新增冷却字段并恢复原空跑行为。加密检查点、认证解密、源码/产物绑定、在途协调、实际入口和日志观察分别记录；历史安全加固风险没有因本次业务发布自动关闭。未手工发博、发送群消息或转存视频。

19:06:45–19:08:11 仅backend/frontend部署完成，代码已提交并推送 `fe04269242817fac95f8e202e7ba649026bca30e`。新backend镜像d7014158…、实际JAR SHA256 40aa225e…，frontend镜像f16cb8f144…；旧两服务正常TERM退出143。现役backend原七层/frontend原五层各追加本轮candidate.json，其他容器与有效运行配置保持。19:12:39本机五项health200；后续入口、自然首轮和短日志结果见下文。

最终停止窗口检查点 `G:/gying-backups/tmdb-empty-range-20261102-20261102T110716.404280Z` 六份age SHA/完整认证/离线内容核验通过，含两旧镜像、有效配置、单事务MySQL、backend-data与ai-state-key；SQL未导入、实际回退未执行，非跨服务原子快照。新覆盖已纳入现役日常备份，dry-run通过但自然完整备份未代验。源码绑定backend348文件/frontend80文件匹配；native manifest唯一差异为浏览器测试断言调整，测试不进入应用编译，最终fixture与工作区及隔离副本一致。原候选阶段frontend-validation.json的productionDeployed=false保留其原时点，实际上线由deployment-result.json证明。

后端全套881项：880通过、1 Redis集成跳过；111个受影响测试全部通过，必需SQL、独立compile及临时容器清理通过。前端Offline React报告15通过（14子场景与父套件）、i18n5、TS/lint/build；全量lint7既有warning/0error，变更文件无warning。6组真实Next/React/AntD资产浏览器检查通过，覆盖中英、明暗、390/1440视口与旧API，API全部模拟，无生产写入；手机范围提示适配已核图。

19:15:57 自然任务17301自动创建，19:15:59完成，保存EMPTY_RANGE、上游131页/2610条及原值 `2026-11-03T11:15:59.397276044Z`（北京时间11月3日19:15:59），约24小时后复查。19:18:02只读快照确认范围/开关保持、无活跃TMDB、短窗口提前TV重排0。电影17300原page251→next252保持，本轮未出现新电影成功任务；下一全局最早资格为11月2日20:15:57，轮转到电影，不能把尚未执行计作生产自然通过。复查、跨重启和上游增长恢复已有离线覆盖，生产事实待发生时验。计划19:17:30前读取因外部Python3.10不能直接解析Java九位纳秒ISO而失败，仅审计脚本兼容解析后19:18:02重新SELECT；原nextCheckAt保留，未强制Worker/插入任务/修改时间。

本机七入口通过；公网经现有verge-mihomo loopback7897代理：首页、片库及同ID详情均200，片库13186条/返回2条，详情完整189613字节/正确ID；internal AI与QQ路由404，匿名admin API和Resource Hub页302到Access登录。公开新管理JS本机/公网200、48784字节、SHA `16df8906623add0629436c71584921c708394b4f10f73b33349974a43df23b6d` 相同，含observedTotalPages与EMPTY_RANGE。最后完整公网复核19:17:24；未新建登录，未实际登录公网管理UI。原入口脚本两次exit1保留，包含直连API URLError、代理详情IncompleteRead及把正常Access302误判为admin资产失败；独立有界详情/重定向/静态资产复核补齐证据。直连详情curl exit35（TLS），不称公网直连全过，未改主机网络。

新backend自19:07:39.901至19:15:59.596共25行、frontend自19:08:03.837至19:15:59.961共6行，ERROR/Exception/OOM/TMDB异常匹配均0，OOMKilled=false、restarts=0；只计该短窗口。原始分段JSON和hash见[验收摘要](validation.json)，原候选和失败入口证据不覆盖。
