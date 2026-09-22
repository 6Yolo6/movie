import os
import pymysql
import bcrypt
from getpass import getpass

# 数据库配置
DB_HOST = os.environ.get("GYING_DB_HOST", "127.0.0.1")
DB_USER = os.environ["GYING_DB_USER"]
DB_PASS = os.environ["GYING_DB_PASSWORD"]
DB_NAME = os.environ.get("GYING_DB_NAME", "gying")

def reset_admin_password(username, new_password):
    try:
        # 连接数据库
        conn = pymysql.connect(
            host=DB_HOST,
            user=DB_USER,
            password=DB_PASS,
            database=DB_NAME,
            cursorclass=pymysql.cursors.DictCursor
        )
        
        cursor = conn.cursor()
        
        # 生成BCrypt密码哈希
        password_hash = bcrypt.hashpw(new_password.encode('utf-8'), bcrypt.gensalt())
        password_str = password_hash.decode('utf-8')
        
        # 更新密码
        update_sql = "UPDATE sys_user SET password = %s WHERE username = %s"
        cursor.execute(update_sql, (password_str, username))
        cursor.execute("UPDATE login_device SET revoked_at=NOW() WHERE user_id IN (SELECT id FROM sys_user WHERE username=%s) AND revoked_at IS NULL", (username,))
        conn.commit()
        
        if cursor.rowcount > 0:
            print(f"✅ 成功重置用户 '{username}' 的密码")

        else:
            print(f"❌ 用户 '{username}' 不存在")
        
        cursor.close()
        conn.close()
        
    except Exception as e:
        print(f"❌ 错误: {e}")

if __name__ == "__main__":
    print("=== 管理员密码重置工具 ===\n")
    
    username = input("Username: ").strip()
    new_password = getpass("New password (not displayed): ")
    if len(new_password) < 12 or len(new_password.encode("utf-8")) > 72:
        raise SystemExit("Password must contain at least 12 characters and at most 72 UTF-8 bytes")
    confirm = input("Reset this account password? (y/n): ")
    if confirm.lower() == "y":
        reset_admin_password(username, new_password)
