import pymysql

DB_HOST = "localhost"
DB_USER = "root"
DB_PASS = "Root@123"
DB_NAME = "gying"

def run_migration():
    try:
        conn = pymysql.connect(host=DB_HOST, user=DB_USER, password=DB_PASS, database=DB_NAME)
        cursor = conn.cursor()
        
        # Check if column exists
        cursor.execute("DESCRIBE resource_link")
        columns = [row[0] for row in cursor.fetchall()]
        
        if "name" not in columns:
            print("Adding 'name' column to resource_link...")
            cursor.execute("ALTER TABLE resource_link ADD COLUMN name VARCHAR(255) COMMENT 'Resource Name' AFTER movie_id")
            conn.commit()
            print("Success: Column added.")
        else:
            print("Info: 'name' column already exists.")
            
        conn.close()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    run_migration()
