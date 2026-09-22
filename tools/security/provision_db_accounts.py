"""Provision NEW scoped accounts. Dry-run by default; never changes root or existing users."""
import argparse
import os
import re

ACCOUNTS = {
    "gying_app": ("GYING_APP_DB_PASSWORD", "SELECT, INSERT, UPDATE, DELETE"),
    "gying_source": ("GYING_SOURCE_DB_PASSWORD", "SELECT, INSERT, UPDATE, DELETE"),
    "gying_social": ("GYING_SOCIAL_DB_PASSWORD", "SELECT, INSERT, UPDATE, DELETE"),
    "gying_readonly": ("GYING_READONLY_DB_PASSWORD", "SELECT, SHOW VIEW"),
    "gying_backup": ("GYING_BACKUP_DB_PASSWORD", "SELECT, SHOW VIEW, TRIGGER, EVENT"),
}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--host",default="127.0.0.1"); parser.add_argument("--port",type=int,default=3306)
    parser.add_argument("--database",default="gying"); parser.add_argument("--account-host",default="localhost")
    parser.add_argument("--apply",action="store_true")
    args=parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}",args.database): raise SystemExit("Invalid database name")
    if not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,255}",args.account_host): raise SystemExit("Use an explicit host/subnet, never %")
    for name,(key,grants) in ACCOUNTS.items(): print(f"{name}@{args.account_host}: {grants} ON {args.database}.*; secret source={key}")
    print("gying_backup additionally needs SHOW_ROUTINE on MySQL 8.0.20+ for complete routine backups")
    if not args.apply:
        print("DRY RUN. No connection, accounts or grants changed."); return
    import pymysql
    secrets={name:os.environ.get(key,"") for name,(key,_) in ACCOUNTS.items()}
    if any(len(value.encode())<24 for value in secrets.values()): raise SystemExit("Every account requires an independent >=24-byte secret")
    if len(set(secrets.values()))!=len(secrets): raise SystemExit("Do not reuse account passwords")
    with pymysql.connect(host=args.host,port=args.port,user=os.environ["MYSQL_ADMIN_USER"],
                         password=os.environ["MYSQL_ADMIN_PASSWORD"],autocommit=True) as conn:
        with conn.cursor() as cur:
            # Preflight the whole batch before DDL (MySQL account DDL cannot be transactionally rolled back).
            for name in ACCOUNTS:
                cur.execute("SELECT COUNT(*) FROM mysql.user WHERE User=%s AND Host=%s",(name,args.account_host))
                if cur.fetchone()[0]: raise SystemExit("An account already exists; review its grants separately; no changes made")
            for name,(_,grants) in ACCOUNTS.items():
                cur.execute("CREATE USER %s@%s IDENTIFIED WITH caching_sha2_password BY %s",(name,args.account_host,secrets[name]))
                cur.execute(f"GRANT {grants} ON `{args.database}`.* TO %s@%s",(name,args.account_host))
                if name=="gying_backup": cur.execute("GRANT SHOW_ROUTINE ON *.* TO %s@%s",(name,args.account_host))
                print(f"Created scoped account: {name}@{args.account_host}")
    print("Root/remote-root and live application configuration were NOT changed. Validate each new login, then switch services.")


if __name__=="__main__":
    try: main()
    except (KeyboardInterrupt,SystemExit): raise
    except Exception as error: raise SystemExit("Provisioning failed ("+type(error).__name__+"); inspect account existence/grants before retry")
