# MCP 运维

## 项目 MCP 服务

backend/mysql_gying_mcp.py（mysql_gying）、docker_mcp.py（docker_tools）；本机Codex config仅外部配置状态，不提交/打印。当前宿主是否加载需另验，脚本存在不代表可调用。

## MySQL MCP 配置

list_tables/describe_table/query_sql/execute_sql。默认127.0.0.1:3306、gying_readonly、gying库；MYSQL_HOST/PORT/USER/DB及MYSQL_PASSWORD优先、GYING_DB_PASSWORD回退。拒root/空密码，默认只读；execute_sql额外要求GYING_MCP_ALLOW_WRITES=1，仅单条参数化，不因此获得业务授权。查询参数通过params绑定，不拼接值。

修改受保护环境后重启MCP宿主，list_tables/SELECT DATABASE(),CURRENT_USER()核目标。schema/migration/DELIMITER/dump使用CLI，不整文件传execute_sql。[数据库规则](database-operations.md)。

## Docker MCP 配置

ps/logs可只读；start/stop/run/Compose等会改状态，另需GYING_MCP_ALLOW_WRITES=1且有当前授权；删除卷已禁止，按[部署](../../../../docs/deployment.md)。默认Compose工具不支持本项目显式文件/项目/运行覆盖时用shell `docker compose -p gying-movie -f docker-compose.prod.yml -f <审查覆盖>`；不重命名文件/依赖默认目录，不输出完整inspect/config。

## 配置审计

列mcp_servers章节/脚本路径/cwd/启用及env键→核Python→import mcp,pymysql→无副作用工具→只报结果。迁移重写绝对路径，独立虚拟环境按批准依赖安装；不能把“进程启动”当下游可用。

## 安全规则

只读身份与DB grants双层防写；变更前确目标/备份/授权，后核行数和业务。MCP/shell不同先比环境/cwd/host，不改数据。真实凭据不入示例/报告，泄露匹配不打印。

## 恢复矩阵

| 现象 | 检查 |
| --- | --- |
| 1045/空密码或配置拒绝 | 受保护密码键/专用非root身份，重启宿主 |
| 脚本/import缺失 | cwd/command/args/实际Python、依赖 |
| Compose找不到配置 | shell显式-p/-f/覆盖 |
| Docker daemon/权限 | docker version/context |
| 错库/查询差异 | DATABASE/CURRENT_USER/环境/已知表，不按默认值猜 |

[旧完整配置说明](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/mcp-operations.md)。
