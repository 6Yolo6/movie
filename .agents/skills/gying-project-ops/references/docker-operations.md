# Docker 运维

[部署与回滚](../../../../docs/deployment.md)是修改容器的主流程；本文用于只读盘点和定位。读取[当前状态](../../../../docs/current-project-status.md)确认实际拓扑，不把仓库 Compose 当成已部署事实。

## 服务边界

- nginx 是站点入口；当前 Tunnel 连接 loopback nginx，backend 的主机入口也为 loopback。不要按旧通用示例开放所有主机接口。
- frontend、gying-source、social-publisher 通过内部网络提供服务；SSR 与浏览器使用不同 API 地址。
- MySQL、Redis、MinIO、PanSou、quark-auto-save 和 OpenClaw 可能是独立实例；`embedded-deps` 不可盲启。
- 原频道宿主机任务、backend QQBot 与独立发布器分别盘点。NapCat/旧卷仅记历史，不恢复、不删除。

## 只读盘点

现役项目名为 `gying-movie`，仓库没有默认 Compose 文件：

    docker ps -a --format "{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
    docker compose -p gying-movie -f docker-compose.prod.yml ps
    docker network ls
    docker volume ls

上述 ps 用于盘点；任何重建/变更前都须加入经审查的实际覆盖文件，固定版本并完成部署门禁。查看容器时按标签定位项目/工作目录和挂载，仅报告必要字段；完整 `docker inspect` 或有效 Compose 配置含敏感值，不能直接输出。

按受影响服务和时间窗口读取日志并脱敏，不直接粘贴整段认证或 provider 响应。记录原容器 ID、启动时间和重启计数，复核时比较基线而不是强求全部为 0。

## 真实链路检查

- 先查依赖与 source/publisher，再查 backend、frontend SSR、nginx 本机和公网入口。
- source/publisher 不需要开放主机端口来测试；在现有容器内做受控只读探针。
- 匿名 publisher health 只证明进程存活，详细就绪与内部业务接口需要认证；凭据只从已有受保护来源读取。
- PanSou 根路径 404 不等于服务故障；Resource Hub 使用已知管理概览或带认证内部 health，不猜路径。
- 网络、alias 和主机路由以实际挂载/依赖验证为准；`host.docker.internal` 不能出现在浏览器 API 地址中，也不能因 alias 存在就改绑生产 MinIO 端口。
- 替换上游后 nginx 先测试再重载；不要通过全栈重建来刷新地址。

## 状态与操作边界

- backend data、日志、QQ/微博状态卷和独立依赖挂载分开盘点；以真实卷名、目标目录和权限制定备份/恢复。
- restart 不会重新加载已变化的容器环境；recreate/build 有运行中断和状态影响，都不是“安全查看命令”。具体步骤只从[部署流程](../../../../docs/deployment.md#3-compose-与最小替换)取用。
- 共享网络或独立容器存在时，不能假定 `compose down` 无害；禁止日常维护使用 `down -v`、删除卷或全局清理。
- 发布状态机的联动与不确定结果见[发布参考](social-publishing-operations.md)，不为探活执行 POST /posts 或重放任务。

## 工具边界

`backend/docker_mcp.py` 的普通 Compose 调用不能代替显式生产文件、项目名和运行覆盖。MCP 可用于可用的只读 Docker 查询；改变容器前使用可审查的明确命令，不依赖默认目录推导。

故障按[排查参考](troubleshooting.md)定位；安全差异按[安全加固](security-hardening.md)逐项处理，不混入无关服务更新。
