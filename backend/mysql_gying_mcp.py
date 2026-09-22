"""Local stdio MCP. Dedicated DB grants are the boundary; read-only is the default."""
import os
import re
from typing import Any, Dict, List

import pymysql
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("mysql-gying")
DB_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.getenv("MYSQL_PORT", "3306")),
    "user": os.getenv("MYSQL_USER", "gying_readonly"),
    "password": os.getenv("MYSQL_PASSWORD") or os.getenv("GYING_DB_PASSWORD", ""),
    "database": os.getenv("MYSQL_DB", "gying"),
    "charset": "utf8mb4",
    "autocommit": False,
    "connect_timeout": 5,
    "read_timeout": 10,
    "write_timeout": 10,
}


def get_conn():
    if not DB_CONFIG["password"] or DB_CONFIG["user"].lower() == "root":
        raise ValueError("Configure a dedicated non-root MYSQL_USER and MYSQL_PASSWORD")
    return pymysql.connect(**DB_CONFIG)


def validate_read_sql(sql: str) -> str:
    if not isinstance(sql, str) or len(sql) > 32768:
        raise ValueError("Invalid SQL length")
    statement = sql.strip().rstrip(";").strip()
    # Deliberately narrow. Complex migrations belong in the separately authorized CLI, not query_sql.
    if (not re.match(r"^(SELECT|SHOW|DESCRIBE|DESC|EXPLAIN)\b", statement, re.I)
            or ";" in statement or "/*" in statement or "--" in statement
            or re.search(r"\b(INTO|OUTFILE|DUMPFILE|LOAD_FILE|SLEEP|BENCHMARK|FOR\s+UPDATE)\b", statement, re.I)):
        raise ValueError("query_sql accepts one bounded read-only statement")
    return statement


def read_query(sql: str, params=None):
    sql = validate_read_sql(sql)
    with get_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute("SET SESSION MAX_EXECUTION_TIME=5000")
            cur.execute("SET TRANSACTION READ ONLY")
            conn.begin()
            # None is intentional: passing [] makes literal '%' in SQL a formatting operation.
            cur.execute(sql, params if params else None)
            rows = list(cur.fetchmany(1001))
            conn.rollback()
            if len(rows) > 1000:
                raise ValueError("Result exceeds 1000 rows; add pagination or aggregation")
            return rows


@mcp.tool()
def list_tables() -> List[str]:
    return [next(iter(row.values())) for row in read_query("SHOW TABLES")]


@mcp.tool()
def describe_table(table_name: str) -> List[Dict[str, Any]]:
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", table_name):
        raise ValueError("Invalid table identifier")
    return read_query(f"DESCRIBE `{table_name}`")


@mcp.tool()
def query_sql(sql: str, params: List[Any] | None = None) -> List[Dict[str, Any]]:
    return read_query(sql, params)


@mcp.tool()
def execute_sql(sql: str, params: List[Any] | None = None) -> Dict[str, Any]:
    if os.getenv("GYING_MCP_ALLOW_WRITES") != "1":
        raise PermissionError("MCP writes are disabled; use a reviewed maintenance session with a backup")
    if not re.match(r"^\s*(INSERT|UPDATE|DELETE)\b", sql, re.I) or ";" in sql.rstrip().rstrip(";"):
        raise ValueError("MCP write sessions allow one DML statement only; no schema/privilege changes")
    with get_conn() as conn:
        with conn.cursor() as cur:
            affected = cur.execute(sql, params if params else None)
            conn.commit()
            return {"affected_rows": affected, "lastrowid": cur.lastrowid}


if __name__ == "__main__":
    mcp.run(transport="stdio")
