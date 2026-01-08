import pymysql
import bcrypt

# 数据库配置
DB_HOST = "localhost"
DB_USER = "root"
DB_PASS = "Root@123"
DB_NAME = "gying"

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
        conn.commit()
        
        if cursor.rowcount > 0:
            print(f"✅ 成功重置用户 '{username}' 的密码")
            print(f"新密码: {new_password}")
        else:
            print(f"❌ 用户 '{username}' 不存在")
        
        cursor.close()
        conn.close()
        
    except Exception as e:
        print(f"❌ 错误: {e}")

if __name__ == "__main__":
    print("=== 管理员密码重置工具 ===\n")
    
    # 重置admin账号密码为 admin123
    username = "admin"
    new_password = "admin123"
    
    confirm = input(f"确定要重置 '{username}' 的密码为 '{new_password}' 吗？(y/n): ")
    
    if confirm.lower() == 'y':
        reset_admin_password(username, new_password)
    else:
        print("操作已取消")
