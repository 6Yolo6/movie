import os
from typing import Any, Dict, List

import pymysql
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("mysql-gying")

DB_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.getenv("MYSQL_PORT", "3306")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_PASSWORD") or os.getenv("GYING_DB_PASSWORD", ""),
    "database": os.getenv("MYSQL_DB", "gying"),
    "charset": "utf8mb4",
    "autocommit": True,
}

def get_conn():
    return pymysql.connect(**DB_CONFIG)

@mcp.tool()
def list_tables() -> List[str]:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("SHOW TABLES;")
            rows = cur.fetchall()
            return [row[0] for row in rows]

@mcp.tool()
def describe_table(table_name: str) -> List[Dict[str, Any]]:
    with get_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(f"DESCRIBE `{table_name}`;")
            return cur.fetchall()

@mcp.tool()
def query_sql(sql: str, params: List[Any] | None = None) -> List[Dict[str, Any]]:
    with get_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(sql, params or [])
            return list(cur.fetchall())

@mcp.tool()
def execute_sql(sql: str, params: List[Any] | None = None) -> Dict[str, Any]:
    with get_conn() as conn:
        with conn.cursor() as cur:
            affected = cur.execute(sql, params or [])
            conn.commit()
            return {"affected_rows": affected, "lastrowid": cur.lastrowid}

if __name__ == "__main__":
    mcp.run()
