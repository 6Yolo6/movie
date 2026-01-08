import pymysql

# 数据库配置
DB_HOST = "localhost"
DB_USER = "root"
DB_PASS = "Root@123"
DB_NAME = "gying"

def run_migration():
    try:
        # 连接数据库
        conn = pymysql.connect(
            host=DB_HOST,
            user=DB_USER,
            password=DB_PASS,
            database=DB_NAME
        )
        
        cursor = conn.cursor()
        
        # 创建sys_config表
        print("Creating sys_config table...")
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS sys_config (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                config_key VARCHAR(100) UNIQUE NOT NULL COMMENT 'Configuration key',
                config_value VARCHAR(500) NOT NULL COMMENT 'Configuration value',
                description VARCHAR(255) COMMENT 'Configuration description',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) COMMENT 'System configuration table'
        """)
        
        # 插入默认配置
        print("Inserting default configurations...")
        configs = [
            ('resource.audit.enabled', 'false', 'Enable resource submission audit (true/false)'),
            ('resource.max.per.user', '100', 'Maximum resources per user'),
            ('resource.submit.interval.seconds', '60', 'Minimum seconds between resource submissions')
        ]
        
        for key, value, desc in configs:
            cursor.execute("""
                INSERT INTO sys_config (config_key, config_value, description) 
                VALUES (%s, %s, %s)
                ON DUPLICATE KEY UPDATE config_value = config_value
            """, (key, value, desc))
        
        conn.commit()
        print("✅ Migration completed successfully!")
        print("\nDefault configurations:")
        print("  - resource.audit.enabled = false (审核默认关闭)")
        print("  - resource.max.per.user = 100 (每用户最大资源数)")
        print("  - resource.submit.interval.seconds = 60 (提交间隔秒数)")
        
        cursor.close()
        conn.close()
        
    except Exception as e:
        print(f"❌ Migration failed: {e}")

if __name__ == "__main__":
    print("=== Database Migration for Resource Management System ===\n")
    confirm = input("This will create sys_config table and add configurations. Continue? (y/n): ")
    
    if confirm.lower() == 'y':
        run_migration()
    else:
        print("Migration cancelled")
