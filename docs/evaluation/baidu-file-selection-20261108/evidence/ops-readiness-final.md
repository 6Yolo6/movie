# GYing 项目运维就绪检查

仓库：D:\gying-movie\movie

| 状态 | 检查项 | 详情 |
| --- | --- | --- |
| 通过 | 必需项目文件 | 21 个必需文件均存在 |
| 通过 | 状态文档章节 | 全部维护章节均存在 |
| 通过 | 状态更新时间 | 更新时间：2026-11-08 |
| 通过 | UTF-8 文本 | 未检测到常见乱码标记 |
| 通过 | 敏感文件跟踪 | 没有跟踪在线环境文件 |
| 通过 | 历史凭据字面量 | 未检测到已知 crawler 凭据字面量 |
| 通过 | Compose 服务 | 已检测：nginx, backend, frontend, gying-source, social-publisher, redis, pansou, quark-auto-save, napcat |
| 通过 | 迁移文档漂移 | 全部迁移文件均已列出 |
| 警告 | 新库架构覆盖 | 仅由迁移创建的表：qq_bot_search_log, qq_channel_post_log |
| 通过 | 环境变量契约漂移 | .env.example 已覆盖生产配置引用 |
| 通过 | Skill 引用链接 | 11 个直接引用均可解析 |
| 通过 | Skill 入口大小 | 109 行 |

汇总：通过=11 警告=1 失败=0
