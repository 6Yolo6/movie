# MCP 运维

## 项目 MCP 服务

仓库提供：

- `backend/mysql_gying_mcp.py`，服务名为 `mysql_gying`；
- `backend/docker_mcp.py`，服务名为 `docker_tools`。

Codex 配置通常位于 `%USERPROFILE%\.codex\config.toml`，并指向项目本地 Python 虚拟环境。
该配置属于主机本地状态，不得提交。

## MySQL MCP 配置

提供的工具：

- `list_tables`
- `describe_table`
- `query_sql`
- `execute_sql`

预期环境变量键：

```text
MYSQL_HOST
MYSQL_PORT
MYSQL_USER
MYSQL_DB
MYSQL_PASSWORD
GYING_DB_PASSWORD
```

脚本默认连接 `127.0.0.1:3306`、用户 `root`、数据库 `gying`。
密码优先读取 `MYSQL_PASSWORD`，未设置时兼容回退 `GYING_DB_PASSWORD`；两者都缺失时为空。
因此进程可能正常启动，但查询返回 `1045 ... using password: NO`。

敏感值只能放入本地 MCP 环境或受保护的密钥源。修改配置后重启 MCP 宿主，
再使用 `list_tables` 和只读 `SELECT` 验证。

默认使用 `query_sql`。只有完成备份、评审和明确确认后才使用 `execute_sql`。
每次调用只发送一条 SQL；多语句迁移和存储过程使用 CLI。

## Docker MCP 配置

主要工具：

- `docker_ps`
- `docker_logs`
- `docker_start` / `docker_stop`
- `docker_run`
- `compose_ps` / `compose_up` / `compose_down`

Docker MCP 直接运行子进程，可以改变在线容器。start、stop、run、remove 和 Compose
操作都按生产变更处理。

Compose 工具不接受文件名，只会在给定目录运行普通 `docker compose`，
因此无法发现本项目的 `docker-compose.prod.yml`。本仓库必须通过 shell 执行：

```powershell
docker compose -f docker-compose.prod.yml ...
```

事故处理中不能通过重命名生产文件来规避该限制。

## 配置审计

不暴露值的检查顺序：

1. 列出 `mcp_servers.*` 章节名称。
2. 检查 command、脚本路径、工作目录和启用状态。
3. 只列出环境变量键名称。
4. 确认 Python 可执行文件存在。
5. 验证依赖：

```powershell
& <python> -c "import mcp, pymysql; print('ok')"
```

6. 调用一个无副作用工具。
7. 只记录成功或失败、版本、路径和缺失键。

项目迁移后，更新本地 Codex 配置中的绝对路径，并重建虚拟环境：

```powershell
python -m venv backend/.venv-codex
backend/.venv-codex/Scripts/python.exe -m pip install mcp pymysql
```

Linux 使用对应平台路径。

## 安全规则

- MCP 配置包含 token 或密码时不能原样输出。
- 不把真实数据库凭据写入 `backend/.env.example`。
- MCP 进程启动成功不代表下游依赖可用。
- 写操作前，通过主机、数据库名或项目名以及只读证据确认目标。
- 写操作后验证影响行数和应用行为。
- MCP 与 shell 结果不一致时，先比较进程环境、工作目录和目标主机，不能直接修改数据。

## 恢复矩阵

| 现象 | 可能原因 | 检查方法 |
| --- | --- | --- |
| MySQL `1045` 且提示 password NO | 未注入 `MYSQL_PASSWORD` 或 `GYING_DB_PASSWORD` | 检查 MCP 环境键并重启宿主 |
| 找不到脚本 | 项目检出路径变化 | 检查 command、args、cwd |
| Python import 失败 | 虚拟环境缺失或过期 | 检查 Python 路径和 `import mcp, pymysql` |
| Compose 找不到配置 | 使用非默认文件名 | shell 显式传入 `-f` |
| Docker 权限或 daemon 错误 | Docker Desktop/daemon 不可用 | 检查 `docker version` 和 context |
| 查询到错误数据 | 默认主机或数据库不正确 | 验证 `DATABASE()`、主机配置和已知表集合 |
