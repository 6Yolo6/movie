# Frontend

Next.js 16 App Router 前端，使用 React 19、Ant Design、Zustand 和 i18next。

## 本地运行

```bash
npm install
npm run dev
```

开发环境在 `frontend/.env.local` 设置：

```text
NEXT_PUBLIC_API_URL=http://localhost:8880
```

生产构建通过 nginx 同域转发 `/api`，`NEXT_PUBLIC_API_URL` 留空。

## 验收

```bash
npm run lint
npm run build
```

主要页面和开发约定见 [开发文档](../docs/development.md)。
