import requests
import re
import json
import time
import os
import pymysql
from minio import Minio
from io import BytesIO

# ================= Configuration =================
# MySQL Configuration
DB_HOST = "localhost"
DB_USER = "root"
DB_PASS = "Root@123"
DB_NAME = "gying"

# MinIO Configuration (Update these credentials)
MINIO_ENDPOINT = "localhost:9000"
MINIO_ACCESS_KEY = "admin"
MINIO_SECRET_KEY = "miniopassword"
MINIO_BUCKET = "gying"

# Crawler Configuration
TARGET_USER = "救星小窝"
COOKIE_STR = "BT_auth=473ebAnmwow7rlUr4lIlmhKPytdwYUe-HrGi3oYjuKsANDd23TOEh3rtqllX1eGt4YXXbTWe1uIuuOZv3X2SK2hM4DVDWzZXbVdu-eP7zs1YQvQlJSIMQdu7isrQAUzMW-Jf2Rw5X2kzCo3A2y2yGpPn8e8EFIz1Y06aT-_PNmACI3RleqxoGpE; BT_cookietime=f92fV80YIyWgoJhKe-32499c6FjgjgGPx4GamWL3RDOHKsXcEtU0"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Cookie": COOKIE_STR,
    "Referer": "https://www.gyg.si/"
}

# ================= Services =================

def get_db_connection():
    return pymysql.connect(host=DB_HOST, user=DB_USER, password=DB_PASS, database=DB_NAME, cursorclass=pymysql.cursors.DictCursor)

def get_minio_client():
    return Minio(MINIO_ENDPOINT, access_key=MINIO_ACCESS_KEY, secret_key=MINIO_SECRET_KEY, secure=False)

def upload_image_to_minio(url, movie_id):
    """Downloads image and uploads to MinIO. Returns the object name or URL."""
    if not url: return None
    try:
        # Special logic for gying specific image pattern if needed, or just download
        # User mentioned: https://s.tutu.pm/img/mv/31z0/384.avif
        # But url provided in _obj.d might be different?
        # Actually _obj.d doesn't seem to have the full image URL.
        # User said: "请求都是384.avif... https://s.tutu.pm/img/mv/{id}/384.avif"
        
        # We construct the URL if it's not present, or if we know the pattern.
        # Let's assume the pattern based on user input:
        if "http" not in url:
             # Fallback to the pattern user discovered
             # Need to know the category (mv, tv etc). Assuming 'mv' for now based on context or we pass it
             pass 
             
        # Use a constructing logic
        # Default gying pattern: https://s.tutu.pm/img/{type}/{id}/384.avif
        # We need to pass type_code (mv/tv)
        return None # Placeholder, logic in main loop
    except Exception as e:
        print(f"Image upload failed: {e}")
        return None

def upload_image_by_pattern(type_code, movie_id):
    target_url = f"https://s.tutu.pm/img/{type_code}/{movie_id}/384.avif"
    object_name = f"{type_code}/{movie_id}/384.avif" # Removed leading slash for minio key
    
    try:
        client = get_minio_client()
        if not client.bucket_exists(MINIO_BUCKET):
            client.make_bucket(MINIO_BUCKET)
            
        # Check if exists
        try:
            client.stat_object(MINIO_BUCKET, object_name)
            print(f"   图片已存在 (MinIO): {object_name}")
            return object_name
        except:
            # Not found, proceed to upload
            pass

        print(f"   下载图片: {target_url}")
        resp = requests.get(target_url, headers=HEADERS, timeout=10)
        if resp.status_code == 200:
            data = BytesIO(resp.content)
            length = len(resp.content)
            client.put_object(MINIO_BUCKET, object_name, data, length, content_type="image/avif")
            return object_name # Return relative path for DB
    except Exception as e:
        print(f"   ⚠️ Image upload warning: {e}")
        
    return None

# ================= Core Logic =================

# ... (Imports and Config same)

CN_MAP = {'一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}

def parse_season(text):
    if not text: return None, None
    # 1. Digits: "第4季"
    match = re.search(r'^(.*?)\s*第(\d+)季', text)
    if match:
        return match.group(1).strip(), int(match.group(2))
    
    # 2. Chinese: "第五季"
    match_cn = re.search(r'^(.*?)\s*第([一二三四五六七八九十]+)季', text)
    if match_cn:
        num_str = match_cn.group(2)
        val = 0
        if len(num_str) == 1: val = CN_MAP.get(num_str, 0)
        elif len(num_str) == 2:
            if num_str[0] == '十': val = 10 + CN_MAP.get(num_str[1], 0)
            elif num_str[1] == '十': val = CN_MAP.get(num_str[0], 0) * 10
        elif len(num_str) == 3: # 二十一
            val = CN_MAP.get(num_str[0],0)*10 + CN_MAP.get(num_str[2],0)
        
        return match_cn.group(1).strip(), val
    
    # 3. Numeric Suffix: "Title 2", "Title2"
    # Exclude years (1900-2100)
    match_num = re.search(r'^(.*?)(\d+)$', text)
    if match_num:
        name = match_num.group(1).strip()
        num_val = int(match_num.group(2))
        if num_val < 1900 or num_val > 2100:
             # Ensure name ends with valid separator or is clean?
             # "Name2" is common. "Name 2" is common.
             # If name is empty? "2".
             if name:
                 return name, num_val

    # 4. Default: Treat as Season 1 of itself
    return text.strip(), 1

def crawl_user_content(db):
    page = 4
    processed_movies = set()
    
    while True:
        url = f"https://www.gyg.si/res/user/content_list?page={page}"
        print(f"📡 Crawling Page {page}...")
        
        try:
            resp = requests.get(url, headers=HEADERS, timeout=15)
            data = resp.json()
            
            # Validation
            if "inlist" not in data or "title" not in data["inlist"]:
                print("❌ Invalid response or end of list")
                break
                
            inlist = data["inlist"]
            titles = inlist.get("title", [])
            count = len(titles)
            
            if count == 0:
                print("⚠️ No items on this page.")
                break
                
            # Parallel Arrays
            dirs = inlist.get("dir", [])
            tnames = inlist.get("tname", [])
            ids = inlist.get("id", [])
            id2s = inlist.get("id2", [])
            urls = inlist.get("url", [])
            atitles = inlist.get("atitle", [])
            
            # Group by Movie ID (id2)
            grouped = {}
            for i in range(count):
                mid = id2s[i]
                if mid not in grouped: grouped[mid] = []
                grouped[mid].append({
                    "title": titles[i],
                    "atitle": atitles[i],
                    "dir": dirs[i],
                    "id": ids[i], # resource ID
                    "url": urls[i],
                    "tname": tnames[i]
                })
            
            print(f"   Found {len(grouped)} unique movies on page {page}.")
            
            # Process Movies
            for mid, resources in grouped.items():
                first = resources[0]
                type_code = first["dir"]
                atitle = first["atitle"]
                
                # Parse Series/Season
                series_name, season = parse_season(atitle)
                
                # Process Movie Metadata (Only if not processed in this run to save time? 
                # But we might need to update season info if changed. 
                # existing crawler 'process_and_save' fetches meta properly.)
                
                # We need to adapt 'process_and_save' or write new logic.
                # Let's reuse 'process_and_save' concept but pass our ID (id2).
                
                # Fetch Meta
                url_meta = f"https://www.gyg.si/{type_code}/{mid}"
                print(f"   🎬 Processing {atitle} ({mid})... Season: {season}")

                try:
                    resp_meta = requests.get(url_meta, headers=HEADERS, timeout=10)
                    match_meta = re.search(r"_obj\.d\s*=\s*(\{.*?\});", resp_meta.text, re.DOTALL)
                    
                    if match_meta:
                        meta = json.loads(match_meta.group(1))
                        
                        # Upload Poster
                        poster_url = ""
                        try:
                            poster_url = upload_image_by_pattern(type_code, mid)
                        except Exception as e:
                            print(f"      ⚠️ Image Upload Failed (MinIO not running?): {e}")
                        
                        # DB Insert Movie
                        pf = meta.get("pf", {})
                        db_score = pf.get("db", {}).get("s", 0)
                        im_score = pf.get("im", {}).get("s", 0)
                        
                        # Ensure JSON fields are strings
                        directors = json.dumps(meta.get("daoyan", []), ensure_ascii=False)
                        actors = json.dumps(meta.get("zhuyan", []), ensure_ascii=False)
                        genres = json.dumps(meta.get("leixing", []), ensure_ascii=False)
                        regions = json.dumps(meta.get("diqu", []), ensure_ascii=False)
                        
                        sql_movie = """
                            INSERT INTO movie_metadata (
                                id, title_cn, title_en, year, runtime, directors, actors, genres, 
                                regions, languages, release_dates, poster_url, 
                                douban_score, imdb_score, summary, category, series_name, season
                            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                            ON DUPLICATE KEY UPDATE 
                                updated_at=NOW(), 
                                douban_score=VALUES(douban_score), 
                                imdb_score=VALUES(imdb_score),
                                series_name=VALUES(series_name),
                                season=VALUES(season)
                        """
                        val_movie = (
                            mid, # Use id2
                            meta.get("title"),
                            meta.get("name"),
                            meta.get("year"),
                            meta.get("times"),
                            directors, actors, genres, regions, 
                            json.dumps(meta.get("yuyan", []), ensure_ascii=False),
                            meta.get("stime"),
                            poster_url,
                            db_score, im_score,
                            meta.get("introduce"),
                            type_code,
                            series_name, season
                        )
                        
                        with db.cursor() as cursor:
                            cursor.execute(sql_movie, val_movie)
                            
                    else:
                        print(f"      ⚠️ No metadata found for {mid}")

                    # Insert Resources (All from the group)
                    for res in resources:
                        provider_raw = res["tname"]
                        provider = "OTHER"
                        if "百度" in provider_raw: provider = "BAIDU"
                        elif "迅雷" in provider_raw: provider = "XUNLEI"
                        elif "夸克" in provider_raw: provider = "QUARK"
                        elif "阿里" in provider_raw: provider = "ALIYUN"
                        elif "UC" in provider_raw: provider = "UC"
                        
                        # Parse code from URL or text? JSON 'inlist' doesn't show 'code'.
                        # But wait, original 'link_data' from /res/downurl/{id} had 'p' (password).
                        # The user content list JSON has 'url' but NO 'code' column in parallel arrays visible in snippet?
                        # Wait, snippet shows: title, dir, tname, time, id, id2, url, atitle, status.
                        # It does NOT show password.
                        # BUT, maybe I should fetch /res/downurl/{type}/{id} using the resource ID?
                        # Resource ID = res['id'] (e.g. 8D2kE).
                        # Let's check 'process_and_save' original logic:
                        # It fetched /res/downurl/{type}/{res_id}.
                        # Here, `mid` is MovieID. `res['id']` is ResourceID (8D2kE).
                        # I should fetch detailed link info for `res['id']`?
                        # Actually, `res['url']` is already the FULL link (e.g. quark.cn/s/...).
                        # However, password might be inside the page or `res['id']` related API?
                        # User snippet `url` column: "https://pan.baidu.com/s/...?pwd=f38n".
                        # The password is IN the URL param `pwd`!
                        # I can extract it or just store the full URL.
                        # My `resource_link` table has `code` column.
                        # I should extract `pwd` or similar.
                        
                        r_url = res["url"]
                        r_code = ""
                        # Simple extraction
                        if "?pwd=" in r_url:
                            r_code = r_url.split("?pwd=")[1].split("&")[0]
                        elif "#" in r_url and len(r_url.split("#")[1]) == 4: # Xunlei/Quark sometimes
                             pass 
                        
                        # Upsert Resource
                        insert_sql = """
                            INSERT INTO resource_link (movie_id, type, provider, url, code, uploader_id, audit_status, name)
                            VALUES (%s, 'DISK', %s, %s, %s, 1, 1, %s)
                            ON DUPLICATE KEY UPDATE 
                                url=VALUES(url), code=VALUES(code), provider=VALUES(provider)
                        """
                        with db.cursor() as cursor:
                            cursor.execute(insert_sql, (mid, provider, r_url, r_code, res["title"]))
                            
                    db.commit()
                    print(f"      ✅ Saved.")
                    
                except Exception as e:
                    print(f"      ❌ Error processing {mid}: {e}")
                    db.rollback()
            
            # Pagination Check
            curr = data['page']['curr']
            # pages = 1
            pages = data['page']['pages']
            if curr >= pages:
                print("🏁 All pages processed.")
                break
            page += 1
            time.sleep(1) # Polite delay
            
        except Exception as e:
            print(f"❌ Page Error: {e}")
            break

if __name__ == "__main__":
    print("=== Enhanced User Content Crawler ===")
    try:
        db = get_db_connection()
        print("✅ DB Connected.")
        crawl_user_content(db)
        db.close()
    except Exception as e:
        print(f"❌ Fatal: {e}")
    print("=== Finished ===")
