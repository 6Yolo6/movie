import os
import pymysql

DB_HOST = os.environ.get("GYING_DB_HOST", "127.0.0.1")
DB_USER = os.environ["GYING_DB_USER"]
DB_PASS = os.environ["GYING_DB_PASSWORD"]
DB_NAME = os.environ.get("GYING_DB_NAME", "gying")

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
