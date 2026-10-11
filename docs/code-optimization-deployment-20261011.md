# 代码优化与手机适配部署验收（2026-10-11）

## 结果与范围

**已部署并完成本轮验收。** 业务提交 `362090cb43f0b8e96947b3f9ef456e237b6650d6`，分支 `codex/security`。镜像于 **2026-10-10** 构建，实际切换发生于 **2026-10-11 10:19–10:26 Asia/Shanghai**，因此保留 `20261010a` 构建标签，不能将标签日期当成上线日期。

按后续“部署吧 / 继续”指令，本次仅做四个应用的业务增量部署。原 G01–G11 仍未关闭；最近完整台账为 2026-10-08，本轮未重跑完整安全扫描或改记 PASS。Access、分服务身份、MinIO policy、公网/IPv6 防火墙及完整恢复门禁没有因此完成。

- 只替换 backend、frontend、gying-source、social-publisher；nginx 仅配置测试和平滑重载。
- 实际环境变量、UID、挂载、网络、端口、安全选项、资源限制与重启策略均与原运行态等价。另 7 个容器（含既有迅雷诊断容器）的 ID、启动时间、重启计数及配置未变。
- 未执行 schema.sql、增量 SQL、队列重置、凭据轮换、网络/防火墙/Access 修改或真实发布、转存、下载验收。

## 实际镜像

| 服务 | 镜像 | 镜像 ID 前缀 | UID |
| --- | --- | --- | --- |
| backend | gying-code-optimization-backend:20261010a | 1c38d3471c77 | 10001 |
| frontend | gying-code-optimization-frontend:20261010a | 42b61b863c20 | 1001 |
| gying-source | gying-code-optimization-gying-source:20261010a | d7ecc468409c | 10001 |
| social-publisher | gying-code-optimization-social-publisher:20261010a | a5a2c09753de | 1000 |

四个 revision label 均为上述提交，restart count 均为 0。backend JAR SHA-256 为 `c3a2d655d4d47960b1b697ec133eb29598b1d4bed48e7157293293684b6c3559`，镜像内 JAR 与 Java 17 打包产物一致。backend/source/publisher 复用原固定运行时；publisher 锁文件与原镜像一致。前端完成 Linux production build。

## 验收

实现阶段全量证据见 `docs/code-optimization-20261009-round2.md`：后端 523 项（0 failure / 0 error / 5 skip）、crawler 54、publisher 31、真实隔离 MySQL 6、Node 工具/策略 42；TypeScript/build 通过。后端跳过项为 4 个 POSIX 用例和 1 个 opt-in Redis 用例，不冒充本次上线后重跑的测试。

| 本次复核 | 结果 |
| --- | --- |
| 候选 source / publisher 镜像内单测 | 54 / 31 全通过；无网络、无生产挂载或凭据 |
| 候选前端浏览器 | 发布管理 14 + 首页 10 全通过 |
| 生产 nginx 上的浏览器回归 | 发布管理 14 + 首页 10 全通过；所有 API 和写入均 mock |
| 真实本机 / 公网入口 | 首页、影片列表和直接 backend 健康 200；匿名管理 API 401；公开内部/QQBot 路径 404，共 8 项通过 |
| 容器内真实链路 | backend → source/publisher 健康读取成功；frontend 按 INTERNAL_API_URL → backend 影片列表 200 |
| 账号/配置 | source 账号指纹与配置开关不变；QQ accounts=0 与原来一致；微博配置健康三项仍 true，不等于重新验证真实发帖 |
| 启动日志 | 四应用无 ERROR/Exception、SQL 结构或 Redis 鉴权错误；backend WARN 来自匿名探针预期的 401 拒绝 |
| 仓库运维就绪 | 10 PASS / 2 WARN / 0 FAIL；既有迁移文档及新库覆盖警告，不是安全门禁通过 |

浏览器覆盖中文 360/375/390/430px 首页、中文 360/390/430px 发布管理、英文 360px 与中英桌面；含编辑/切屏保留草稿、分页、筛选、长文本换行和受保护状态禁止重试。属于 Headless Edge 响应式视口，不是真机 iOS Safari/Android 或真实账号发布验收。

候选首次请求曾因 Docker internal-only 网络未激活宿主端口映射而失败，容器内首页为 200。增加独立、非 root、只读、仅 GET/HEAD 的本机测试代理后通过；候选始终未连接生产网络或加载生产凭据。两个自有容器和两个自有网络已移除，18084 无监听。

## 协调切换与数据边界

1. 10 月 11 日重新读取数据库：既有 09:00 自动批次已执行，发布表为 **193 POSTED / 12 历史 FAILED**、205 行，PENDING/在途为 0；昨日为 190/12。本次未手工触发这 3 条计划发布。
2. 10:19:16 先停止旧 backend，停止前后只读守护均确认发布行/目标配置指纹不变；随后停止 source/publisher，完成加密快照。
3. 先替换并验收 source/publisher，再启动新 backend，最后替换 frontend、测试并重载 nginx；没有让新旧发布写入方混用。
4. 10:26:29 真实入口全部通过，上线后发布行/目标配置守护仍通过；2026-09-28 的历史 RUNNING 任务 3426 未重置或重放。

Compose 始终显式使用 `-p gying-movie -f D:/gying-movie/movie/docker-compose.prod.yml -f E:/gying-tools/releases/code-optimization-20261010/deploy.json`，启动只使用 `up -d --no-deps --no-build --pull never` 加指定服务。没有 down、拉取 latest 或删除卷。

Compose 提示三个历史状态卷缺少 Compose 创建标签；实际挂载源/目标/RW 与原容器完全一致，未创建替代卷。发布器 DNS 字段只有空数组与 null 的默认表示差异，均无自定义条目。

## 加密检查点与回退

- 外部发布/回退材料：`E:/gying-tools/releases/code-optimization-20261010`，目录仅当前用户和 SYSTEM 可访问。
- 新检查点：`G:/gying-backups/20261011T022133.799192Z`。**10/10** 文件大小、SHA-256 与 age 认证解密通过，覆盖 MySQL、环境、Compose、deploy/rollback 覆盖、backend-data/logs、两个 publisher 凭据卷及完整运行参数。
- SQL 25 表清单及 dump 完成标记通过，配置归档与原文件 hash 一致，tar 路径安全。QQ 卷当前为空，与 accounts=0 相符。无明文 SQL/凭据/完整 inspect/解密归档落盘。
- 本次不是完整恢复演练，不覆盖独立依赖卷、MySQL 系统账号或远端网盘事务。
- 既有后台调度保持启用，会自然推进新的资源任务；本轮只读验收不意味着数据库全局静止。实际回退前还必须核查并等待新近在途资源任务结束，发布守护不能替代全部外部副作用核查。
- 四个 `gying-code-optimization-rollback-*:20261010a` 别名保留原镜像，`rollback.json` 指向它们；回退未实际执行。
- `release_ops.py rollback-check` 只读检查发布行/目标指纹、状态/数量、配置文件 hash 与镜像 ID；步骤见外部 `RUNBOOK.md`。先停 backend，再复核并协调回退，不能单独回退某个发布组件。
- 新记录、PREPARING/PUBLISHING/UNKNOWN 或配置变化会阻断自动回退；先核对外部结果或前向修复，绝不重置为 PENDING，不自动恢复 SQL 或替换卷。

## 公网波动与保留项

2026-10-11 **10:12:35**，任何生产停止/替换之前，本机正常但公网短暂 530，切换因此暂停。公网随后自行恢复，未重启或修改 Tunnel、代理、DNS、凭据、防火墙。10:14:58–10:17:01 连续 7 轮、每轮 5 项通过后才进入维护窗口。上线后 10:41:04–10:43:08 再次连续 7 轮通过，但**不代表历史间歇故障根因已解决**。

仍需独立处理：G01–G11 与完整安全门禁；UNKNOWN/崩溃在途状态人工处置；跨资源版本同影片的全局去重；真机/真实账号验收；海报响应完整缓冲的后续有界读取。

证据位于忽略目录 `tmp/code-optimization-deploy-20261010/`：镜像/配置等价性、备份校验、运行态、真实入口、连续公网探针、内部 HTTP 链路、日志摘要、两阶段浏览器 JSON/截图与 ops-after。目录日期是构建批次，不代表全部证据产生于 10 月 10 日。
