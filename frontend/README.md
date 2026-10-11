# Frontend

Next.js App Router 前端，使用 React、Ant Design、Zustand 和 i18next。依赖版本以 [package.json](package.json) 和锁文件为准；本仓库构建镜像使用 Node.js 22。

## 本地运行

先阅读[开发隔离要求](../docs/development.md#1-环境与隔离)，以下命令均在 `frontend/` 执行：

    npm ci
    npm run dev

在不含生产配置的检出目录创建 `.env.local`：

    NEXT_PUBLIC_API_URL=http://localhost:8880
    INTERNAL_API_URL=http://localhost:8880

- 浏览器使用 `NEXT_PUBLIC_API_URL`；生产构建留空以走 nginx 同域 `/api`。
- 服务端使用 `INTERNAL_API_URL`；生产 Compose 中连接内部 backend，不能把内部 Docker 主机名暴露为浏览器 API 地址。
- `NEXT_PUBLIC_*` 在构建时固化，修改运行时环境不等于改好了浏览器地址。

## 校验

依次执行，任一失败先处理，不把后续成功掩盖为整套通过：

    npx tsc --noEmit
    npm run lint
    npm run build

package 中 `start` 为 `next start`；生产容器则由 Dockerfile 使用 standalone 的 `node server.js`，不能把本地脚本等同线上部署。此 package 没有 `npm test`，浏览器入口和 Node 定向回归在[开发与测试](../docs/development.md#3-常规回归入口)。

## 手机端检查

- 360/375/390/430px：卡片、长标题、筛选与分页不横向溢出，主要操作保留至少 44px 操作区。
- 检查中英、明暗主题、加载/失败/空状态，以及切换桌面宽度时草稿不丢失。
- 发布页面遵守[统一重试规则](../docs/social-publishing.md)；不能因小屏布局放开在途/不确定记录。
- 无水合错误与视口回归只是模拟浏览器证据，真机键盘、触摸和 iOS/Android 行为需要另行验收。

开发约定见[开发文档](../docs/development.md)，已部署范围见[当前项目状态](../docs/current-project-status.md)。
