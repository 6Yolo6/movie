# 开发与测试

[导航](README.md) · [前端](../frontend/README.md) · [API](api.md) · [数据库](database.md) · [完整旧步骤](history/snapshots/20261022/docs/development.md)

## 1. 环境与隔离

Java17/Maven、Node22/npm、Python3.12；版本以pom/package/锁文件/Dockerfile为准。使用无生产env/凭据的独立检出与DB/Redis/MinIO，关闭Resource Hub/Worker/元数据/QQ/发布及相关sys_config/宿主任务。backend会读当前及上级.env，不能在现役backend目录启动来“隔离”。schema含DROP，只用于确认空库，无默认管理员。

## 2. 本地启动

先git rev-parse --show-toplevel；在独立backend运行 `mvn spring-boot:run`，frontend依次 `npm ci`、`npm run dev`。默认后端8880/前端3000，冲突只改测试端口。开发NEXT_PUBLIC_API_URL/INTERNAL_API_URL指隔离backend；生产浏览器NEXT_PUBLIC为空走同域/api、SSR用内部backend；NEXT_PUBLIC在构建时固化。

## 3. 常规回归入口

| 目录 | 入口 | 说明 |
| --- | --- | --- |
| backend | mvn test；交付mvn package、mvn -q -DskipTests compile | 依赖skip单列，不算通过 |
| frontend | npm ci → npx next typegen → npx tsc --noEmit → npm run lint → npm run build | 依次通过；无npm test |
| frontend | node --test tests/async-lists.test.mjs | 真实React/组件替身，禁止网络，现有Chromium；FRONTEND_TEST_BROWSER |
| social-publisher | npm ci → npm test | 不加载真实账号/DB |
| 根目录 | python -m unittest discover -s crawler -p 'test_*.py' | Python3.12/requirements，测试导入crawler.* |
| 根目录 | node --test tools/tests/i18n-hydration.test.cjs tools/tests/social-publishing-ui.test.cjs | 按影响显式选择，不递归执行整个tools |

### 浏览器与手机适配

独立生产构建、loopback、无凭据；SSR也须隔离backend，浏览器mock不拦服务端。使用既有Playwright/PLAYWRIGHT_MODULE与Edge，BROWSER_CHANNEL默认msedge，不擅改锁文件安装。输出到忽略目录/仓库外。

| 脚本（根目录node tools/tests/...） | 入口/产物/输出变量 |
| --- | --- |
| social-publishing.browser.cjs | SOCIAL_PUBLISHING_BASE_URL（默认18083）、SOCIAL_PUBLISHING_OUTPUT_DIR |
| home-loading.browser.cjs | HOME_LOADING_BASE_URL（默认18083）、HOME_LOADING_OUTPUT_DIR |
| admin-monitoring-ui.cjs / movie-resource-counts.browser.cjs | WEB_TEST_BASE_URL（默认18080）、SCREENSHOT_DIR；SSR fixture下节 |
| activity-pages.browser.cjs | ACTIVITY_PAGES_BUILD_DIR绝对.next+相邻public，离线虚拟WEB_TEST_BASE_URL默认18086；ACTIVITY_PAGES_OUTPUT_DIR |
| catalog-search.browser.cjs | CATALOG_BUILD_DIR绝对.next+相邻public，虚拟CATALOG_BASE_URL默认18080；CATALOG_OUTPUT_DIR |

离线构建模式路由提供页面/静态/API、不启HTTP/动态SSR，外部请求拒绝。activity可改独立Next服务，INTERNAL_API_URL仍隔离。按受影响页面检查中英/明暗、360/375/390/430及桌面、44px操作、长文本/分页/错误/旧响应/草稿和无水合错误。视口不等于iOS/Android真机或真实登录写入。

### 监控与资源数量回归

`node --test tools/tests/monitoring-models.test.cjs`；详情SSR先 `node tools/tests/fixtures/monitoring-resources.cjs`（loopback18085），独立frontend INTERNAL_API_URL指向fixture；Docker候选使用独立网络。MonitoringServiceMySqlTest显式opt-in，仅新空loopback monitoring_fixture_<数字>，MONITORING_TEST_MYSQL_URL/PASSWORD；缺变量skip，不能复用/重置已有库。

### 热门榜与通知页完整浏览器回归

activity-pages.browser.cjs：真实React/AntD生产构建、延迟/重试/未读/全部已读回第一页，中英桌面/390深色。API为mock，离线模式不覆盖SSR；真实通知事务另验。

### 推荐特征与自然语言搜索回归

隔离backend：`mvn -Dtest=CatalogRecommendationEngineTest,NaturalMovieQueryTest,CatalogEvaluationTest test`。search-v1.json及冻结旧实现输出target/catalog-evaluation/search-report.json/recommendation-benchmark.json。40合成题由实施阶段编写，两个split不是独立盲测/留出，固定测量不代表线上质量。

根目录 `python -X utf8 tools/tests/catalog_mysql.py` 只计划，--run执行8项CatalogAiMySqlTest。catalog-search.browser覆盖中英/明暗390/1440、分页失败保留/资料片段/新标签/推荐/模型离线/游客；全部API替身，动态SSR/镜像/真实用户另验。

### Agent执行与真实调用回归

根目录 `python -X utf8 tools/ai/evaluate_agent.py` 默认只打印计划；`python -X utf8 -m unittest discover -s tools/tests -p test_agent_evaluation.py -v`为离线评测器测试；`python -X utf8 tools/ai/evaluate_agent.py --run --repeat 2 --output tmp/agent-evaluation/report.json`才访问既有loopback助手和只读工具。需要助手已健康运行，实际工具清单必须是三个只读影视工具；不启Spring/调度、不使用真实账号或桥接密钥、不重试推理。业务code与实际证据判分，失败/未完成非零退出；7场景8轮为实施者回归，非独立人工留出。详见[路线与边界](ai-strengthening-roadmap-20261022.md)。

### 可比较Agent回归与独立网站题

`python -X utf8 tools/ai/comparable_evaluation.py`与`evaluate_website.py`默认只计划。前者9场景10轮真实本机模型回归；后者30独立问题/44轮，普通USER会话、网站分流/队列/桥接，显式--run才发送。题集/口径hash冻结，模型回答只作观测，严禁拿独立题调prompt。输出新文件；传输失败不重发推理；两入口串行执行，尊重每分钟6次网站限流。

回归行为测试：`python -X utf8 -m unittest discover -s tools/tests -p 'test*evaluation.py'`；快照/复核页面安全测试：`python -X utf8 -m unittest discover -s tools/tests -p test_evaluation_artifacts.py`。人工review三项0–2或null，未完成保留pending；观测与HTML放受限仓库外，Token不入报告。详情及完整命令见[评测说明](ai-comparable-evaluation-20261023.md)、[链路说明](ai-system-chain-20261023.md)。

### 真实 SQL 的隔离回归

#### 后端资源查询与通知已读（含监控 SQL）

```powershell
python -X utf8 tools/tests/query_boundaries_mysql.py
python -X utf8 tools/tests/query_boundaries_mysql.py --run
python -X utf8 tools/tests/query_boundaries_mysql.py --run --full-suite
```

- 默认只计划；--run创建一次性MySQL/Maven、7查询+4监控，--full-suite全量后再offline compile。必需SQL缺/skip、编译标记缺、清理失败均非零。
- 已有Docker、本地mysql:8.0/maven:3.9-eclipse-temurin-17与完整离线缓存（--maven-repository，默认~/.m2/repository）；固定ID，不自动pull/install。
- MySQL无网络/宿主端口、tmpfs；Maven只共享fixture loopback，独立allowlist副本/只读缓存/空settings。均非root/只读rootfs/限资源，无生产env/卷/网络，不启Spring/调度。
- DDL前校验随机runID、精确库名/hostname/marker；不DROP/重置已有库。结果tmp/query-boundaries-mysql-<id>核source hash、required_sql_verified、全量compile_verified和两个容器清理，不能只看exit0。
- 工具自测：`python -X utf8 -m unittest discover -s tools/tests -p test_query_boundaries_mysql.py -v`。catalog_mysql共享这些环境约束。

#### 发布状态机 SQL

`./tools/tests/social-publishing-mysql.ps1`（根目录）：已有publisher依赖/本地MySQL-Node镜像，无宿主口/外网/生产挂载、tmpfs，精确清本次label；不得改成生产库测试。

## 4. 代码约定

ADMIN/USER鉴权在服务端；canonical幂等/来源严格消歧、软删除/中文SQL注释/迁移清单；自有分享验证后入库；UNKNOWN等不能重置；readApiError与取消/旧响应/卸载边界；fixture不得含真实凭据。真实副作用须当前授权。

## 5. 文档与交付

[导航规则](README.md#阅读规则)区分契约/现状/历史；[部署](deployment.md)固定候选/门禁/真实入口。纯文档跑链接/格式/一致性即可，不重建应用、跑SQL或触发生产测试。
