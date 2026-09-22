"""First-admin initialization without a shared password. Does not run on application startup."""
import getpass
import os
import re


def main():
    import bcrypt
    import pymysql
    username=input("Initial admin username: ").strip()
    email=input("Initial admin email: ").strip().lower()
    password=getpass.getpass("New password (hidden): ")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{3,50}",username): raise SystemExit("Invalid username")
    if "@" not in email or len(email)>254: raise SystemExit("Valid email required")
    if len(password)<12 or len(password.encode())>72: raise SystemExit("Use 12+ characters, at most 72 UTF-8 bytes")
    if password!=getpass.getpass("Repeat password: "): raise SystemExit("Passwords differ")
    if input("Create the FIRST administrator? Type CREATE: ")!="CREATE": raise SystemExit("Cancelled")
    with pymysql.connect(host=os.environ.get("DB_HOST","127.0.0.1"),port=int(os.environ.get("DB_PORT","3306")),
                         user=os.environ["DB_USER"],password=os.environ["DB_PASSWORD"],database=os.environ.get("DB_NAME","gying")) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN'")
            if cur.fetchone()[0]: raise SystemExit("An administrator already exists; use the reviewed password-reset procedure")
            cur.execute("INSERT INTO sys_user(username,password,email,role,score,enabled) VALUES(%s,%s,%s,'ADMIN',0,1)",
                        (username,bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=12)).decode(),email))
            conn.commit()
    print("Initial administrator created; no credentials logged")


if __name__=="__main__":
    try: main()
    except (KeyboardInterrupt,SystemExit): raise
    except Exception as error: raise SystemExit("Initialization failed ("+type(error).__name__+")")
