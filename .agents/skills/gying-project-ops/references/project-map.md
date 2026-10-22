# 项目地图

[当前状态](../../../../docs/current-project-status.md)是有日期的在线交接，源码/Compose是目标。旧分支、表数与镜像不能当默认基线。

## 仓库边界

| 区域 | 职责/依据 |
| --- | --- |
| backend | Java17/Spring Boot3.5.16；pom、Dockerfile、application*.yml |
| frontend | Next16/React19/AntD；package/锁文件/next.config |
| crawler | source会话/元数据/P2P；Python Dockerfile/gying_crawler，旧一次性脚本单审 |
| social-publisher | 独立QQ/微博发布；Node/状态机/网页会话 |
| nginx | loopback入口及/api代理，nginx.conf |
| tools | 宿主频道/OpenClaw/维护/安全工具，不能递归执行 |
| docs/db | [专项手册](../../../../docs/README.md)；schema/手工增量无自动迁移引擎 |
| Compose/env | docker-compose.prod.yml显式项目/文件/覆盖；.env.example仅键契约 |

## 运行拓扑

公网Tunnel→loopback nginx→frontend SSR/backend→Windows MySQL/Redis/MinIO/source/PanSou/Quark/publisher。OpenClaw独立Gateway、原频道宿主任务、backend QQBot/每日推荐、独立publisher三链分开。embedded-deps不盲启，现存外部依赖不重复创建；NapCat停用不恢复。

## 稳定数据模型

movie_metadata canonical、resource_link正式资源、movie_source_identity外部来源/类型/ID/season（电影0）；resource_hub_task、resource_discovery_result、双盘transfer保存流程；sys_config开关；QQ/频道/social各自审计。合并迁关联后软删除，保留utf8mb4_bin身份。[表及全部迁移](../../../../docs/database.md)。

## 最近代码变化的运维边界

当前推荐/自然搜索见[成果](../../../../docs/catalog-features-search-20261021.md)；高频元数据/周榜/P2P分开，先完整自有分享后入库。QQ每日推荐属backend，手动run是真实发送；OpenClaw补丁必须源/唯一运行副本/Gateway同步，升级后重新验UTF8/白名单/拒绝未授权。

## 配置层级

进程环境/当前会话→受支持sys_config→application/Compose/env契约→有日期状态→历史证据。文件写好不代表已加载；env变更需正确recreate，不仅restart；配置优先级按具体源码核实，不能用此列表推断所有key统一覆盖。

## 分支同步基线

每次git log/rev-parse及实际容器镜像定基线；旧origin/master525ae17/16表是历史。当前schema23表、另QQ两表需增量，运行25仅原验收证据。指定分支比较文档和实际配置，只同步授权范围；目标能力不写成已部署。

## 证据命令

```powershell
git rev-parse --show-toplevel
git status --short --branch
git log -1 --format="%H %cI %s"
docker ps -a --format "{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
docker compose -p gying-movie -f docker-compose.prod.yml ps
```

变更前加入已审查覆盖；只取Compose working_dir/config_files标签、必要运行字段，不输出完整inspect。[原地图](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/project-map.md)。
