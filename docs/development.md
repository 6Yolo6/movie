# 开发与测试

[文档导航](README.md) · [前端说明](../frontend/README.md) · [API](api.md) · [数据库](database.md)

## 1. 环境与隔离

| 组件 | 本仓库基线 | 依据 |
| --- | --- | --- |
| backend | Java 17；Maven | `backend/pom.xml`、`backend/Dockerfile` |
| frontend / social-publisher | Node.js 22；npm | 各自 Dockerfile、`package.json` 与锁文件 |
| crawler | Python 3.12 | `crawler/Dockerfile`、`requirements.txt` |
| 数据依赖 | 独立 MySQL 8、Redis、MinIO | 不连接现役生产数据库、缓存或凭据卷 |

以仓库声明和锁文件为准，不把文档中的主版本当成允许自动升级到任意新版本。

**先隔离，再启动：**

- 使用不含生产配置的独立检出目录。backend 会加载工作目录的 `.env` 和上一级 `../.env`；在生产仓库的 `backend/` 启动可能读到根目录真实配置，不能假设子目录文件会隔离所有来源。
- `backend/.env.example` 是起点，不是完整运行配置。根据 `application.yml` 和根目录 `.env.example` 补齐独立 DB/Redis/MinIO、JWT 和内部 token 等必要键；只使用测试凭据，不把值提交到 Git。
- 开发环境关闭 Resource Hub、Worker、元数据定时采集、QQBot 和外部发布；检查隔离库的 `sys_config`、发布目标及本机计划任务，不能只改一个环境开关。
- `schema.sql` 含破坏性语句；仅在确认空的新库使用。当前无默认管理员种子，管理功能初始化需要单独审查，不使用旧文档中的默认密码。详见[数据库](database.md)。

## 2. 本地启动

下面每行在指定目录独立执行；先用 `git rev-parse --show-toplevel` 确认仓库根目录，避免连续 `cd` 进入错误位置。

| 工作目录 | 命令 | 前提 |
| --- | --- | --- |
| `backend/` | `mvn spring-boot:run` | Java 17；已核对独立依赖及自动化关闭状态 |
| `frontend/` | `npm ci`，完成后 `npm run dev` | 使用锁文件；不要以 `npm install` 顺带升级依赖 |

前端开发默认入口为 `http://localhost:3000`，后端默认 `http://localhost:8880`。若冲突，只修改隔离环境端口，不停生产服务。

前端 `.env.local` 示例（仅开发）：

    NEXT_PUBLIC_API_URL=http://localhost:8880
    INTERNAL_API_URL=http://localhost:8880

生产浏览器走 nginx 同域 `/api`，`NEXT_PUBLIC_API_URL` 留空；服务端读取使用 `INTERNAL_API_URL`。浏览器不能使用 Docker 内部主机名，服务端也不能把容器内 `localhost` 误当宿主机。前端公开环境变量在构建时固化，详见[部署](deployment.md)。

## 3. 常规回归入口

| 工作目录 | 命令 / 入口 | 覆盖与限制 |
| --- | --- | --- |
| `backend/` | `mvn test` | 后端回归；环境依赖导致的 skip 单独记录，不当作通过 |
| `frontend/` | `npm ci` → `npx tsc --noEmit` → `npm run lint` → `npm run build` | 依次执行，任何失败先停止；没有 `npm test` 脚本 |
| `social-publisher/` | `npm ci` → `npm test` | `node --test`；不加载真实账号/生产数据库配置 |
| 仓库根目录 | `python -m unittest discover -s crawler -p 'test_*.py'` | 使用 Python 3.12 测试环境并安装 `crawler/requirements.txt`；测试导入 `crawler.*`，不从 crawler 子目录运行 |
| 仓库根目录 | `node --test tools/tests/i18n-hydration.test.cjs tools/tests/social-publishing-ui.test.cjs` | 首页水合与发布 UI/策略的定向回归；其他 `tools/tests/*.test.cjs` 按影响范围显式选择 |

不要递归执行整个 `tools/`：其中包含真实运维和平台写入工具。若本机缺依赖，使用无生产挂载/凭据的隔离环境；不拿生产容器补测。

### 浏览器与手机适配

- 入口：[发布页面回归](../tools/tests/social-publishing.browser.cjs)、[首页回归](../tools/tests/home-loading.browser.cjs)。两者 mock API，只接受无凭据的 loopback 地址；默认 `http://127.0.0.1:18083`。
- 先启动已构建的隔离前端，仅将测试入口绑定到 loopback 18083；镜像入口以 `frontend/Dockerfile` 为准（standalone 的 `node server.js`），不在生产 Compose 项目增开测试服务。确认其 **SSR 也不连接生产 backend**，浏览器拦截不能隔离前端服务端请求。
- 测试环境需提供 Playwright 模块及浏览器；可用 `PLAYWRIGHT_MODULE` 指定现有模块路径，`BROWSER_CHANNEL` 默认 `msedge`。不要为运行脚本擅自改项目锁文件。
- 从仓库根目录运行：`node tools/tests/social-publishing.browser.cjs`、`node tools/tests/home-loading.browser.cjs`。自定义测试入口分别使用 `SOCIAL_PUBLISHING_BASE_URL`、`HOME_LOADING_BASE_URL`；结果目录分别使用 `SOCIAL_PUBLISHING_OUTPUT_DIR`、`HOME_LOADING_OUTPUT_DIR`，置于忽略目录或仓库外。
- 重点检查 360/375/390/430px、小屏卡片、长文本、44px 操作区、筛选分页、桌面切换草稿保留，以及中英/明暗主题和无水合错误。按受影响页面扩展场景，不只测截图。
- Headless Edge 的手机/平板视口不是 iOS Safari / Android 真机，也不是真实账号保存或外部发布验收。各类证据分别记录。

### 真实 SQL 的隔离回归

[发布状态机 MySQL fixture](../tools/tests/social-publishing-mysql.ps1) 从仓库根目录执行：

    ./tools/tests/social-publishing-mysql.ps1

前提是 publisher 依赖已安装，脚本要求的 MySQL/Node 镜像已在本地。它创建无宿主端口、无外网、无生产数据挂载的临时 MySQL，数据仅在 tmpfs；结束清理本次带标签的 fixture。不得把它改成连接生产库的测试。

## 4. 代码约定

- 管理员接口使用 `AuthHelper.requireAdmin`，登录用户接口使用 `AuthHelper.requireUser`；不要通过隐藏按钮替代后端鉴权。
- Resource Hub 优先复用 canonical `movie_metadata` 与已有 `resource_link`；不确定的外部身份进入人工候选。
- 核心记录与历史关系采用软删除；数据库新注释用中文；变更必须同时维护[迁移清单](database.md)。
- 自动网盘资源先完成自有分享和校验，再进入正式资源表；真实副作用测试需明确授权。
- 发布仅 `PENDING/PREPARE_FAILED` 可进入普通重试，其他在途/不确定状态不重置，详见[发布规则](social-publishing.md)。
- API 错误优先用 `readApiError`；异步 UI 同时处理请求取消、旧响应覆盖与卸载后的更新。
- 凭据不进入 Git、日志和测试 fixture；模拟 token 不能复用真实值。

## 5. 文档与交付

- 按[文档维护约定](README.md#阅读与维护约定)更新契约、现状或历史；仅实现/测试通过不能写成已上线。
- Compose 校验、镜像固定、生产门禁和推送后的上线验证见[部署文档](deployment.md)。本地测试通过不授权跳过门禁或真实发帖。
- 纯文档修改运行链接/格式/一致性检查即可，不因此重建应用、执行 SQL 或触发生产测试。
