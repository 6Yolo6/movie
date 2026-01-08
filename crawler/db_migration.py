import pymysql

# Configuration
DB_HOST = "localhost"
DB_USER = "root"
DB_PASS = "Root@123"
DB_NAME = "gying"

def migrate():
    conn = pymysql.connect(host=DB_HOST, user=DB_USER, password=DB_PASS, database=DB_NAME)
    cursor = conn.cursor()
    
    try:
        # Check if columns exist
        print("Checking columns...")
        cursor.execute("SHOW COLUMNS FROM movie_metadata LIKE 'series_name'")
        if not cursor.fetchone():
            print("Adding series_name column...")
            cursor.execute("ALTER TABLE movie_metadata ADD COLUMN series_name VARCHAR(255) DEFAULT NULL COMMENT 'Series Name' AFTER title_en")
        else:
            print("series_name already exists.")

        cursor.execute("SHOW COLUMNS FROM movie_metadata LIKE 'season'")
        if not cursor.fetchone():
            print("Adding season column...")
            cursor.execute("ALTER TABLE movie_metadata ADD COLUMN season INT DEFAULT NULL COMMENT 'Season Number' AFTER series_name")
        else:
            print("season already exists.")

        conn.commit()
        print("Migration completed successfully.")
        
    except Exception as e:
        print(f"Migration Failed: {e}")
        conn.rollback()
    finally:
        conn.close()

if __name__ == "__main__":
    migrate()
