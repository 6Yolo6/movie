import requests
import re
import json
import time
import os
import argparse
import pymysql
from minio import Minio
from io import BytesIO
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

# ================= Configuration =================
# MySQL Configuration
DB_HOST = os.getenv("GYING_DB_HOST", "localhost")
DB_USER = os.getenv("GYING_DB_USER", "root")
DB_PASS = os.getenv("GYING_DB_PASSWORD", "")
DB_NAME = os.getenv("GYING_DB_NAME", "gying")

# MinIO Configuration (Update these credentials)
MINIO_ENDPOINT = os.getenv("GYING_MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("GYING_MINIO_ACCESS_KEY", "admin")
MINIO_SECRET_KEY = os.getenv("GYING_MINIO_SECRET_KEY", "")
MINIO_BUCKET = os.getenv("GYING_MINIO_BUCKET", "gying")

# Crawler Configuration
TARGET_USER = "救星小窝"
COOKIE_STR = os.getenv("GYING_COOKIE", "")
LOGIN_USERNAME = os.getenv("GYING_USERNAME", "")
LOGIN_PASSWORD = os.getenv("GYING_PASSWORD", "")

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
    "Cookie": COOKIE_STR,
    "Referer": "https://www.xn--wcv59z.com/"
}

BASE_URL = os.getenv("GYING_BASE_URL", "https://www.xn--wcv59z.com").rstrip("/")
SESSION_HEADERS = {k: v for k, v in HEADERS.items() if k.lower() != "cookie"}
POW_MIN_SECONDS = 3
_SITE_SESSION = None
API_TOKEN = os.getenv("GYING_SOURCE_API_TOKEN", "")
API_HOST = os.getenv("GYING_SOURCE_API_HOST", "0.0.0.0")
API_PORT = int(os.getenv("GYING_SOURCE_API_PORT", "8091"))

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
        # https://s.tutu.pm/img/mv/{id}/384.avif"
        
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

def list_get(values, index, default=""):
    if not isinstance(values, list) or index >= len(values):
        return default
    value = values[index]
    return default if value is None else value

def detect_provider(raw):
    raw = raw or ""
    if "百度" in raw: return "BAIDU"
    if "迅雷" in raw: return "XUNLEI"
    if "夸克" in raw: return "QUARK"
    if "阿里" in raw: return "ALIYUN"
    if "UC" in raw: return "UC"
    if "磁" in raw or "BT" in raw.upper(): return "P2P"
    return "OTHER"

def detect_resource_type(url, provider):
    lower_url = (url or "").lower()
    if lower_url.startswith("magnet:"):
        return "MAGNET"
    if lower_url.endswith(".torrent") or provider == "P2P":
        return "TORRENT"
    return "DISK"

def extract_share_code(url, explicit_code=""):
    if explicit_code and explicit_code not in ("无", "暂无", "-"):
        return str(explicit_code).strip()

    if not url:
        return ""

    match = re.search(r"[?&]pwd=([^&#]+)", url)
    if match:
        return match.group(1)

    match = re.search(r"#([A-Za-z0-9]{4})$", url)
    if match:
        return match.group(1)

    return ""

def load_cookie_string(session, cookie_str):
    if not cookie_str or cookie_str == "***":
        return

    cookies = SimpleCookie()
    try:
        cookies.load(cookie_str)
    except Exception:
        print("      Ignoring invalid COOKIE_STR; login env vars can still authenticate.")
        return

    domain = urlparse(BASE_URL).hostname
    for key, morsel in cookies.items():
        session.cookies.set(key, morsel.value, domain=domain, path="/")

def get_site_session():
    global _SITE_SESSION
    if _SITE_SESSION is None:
        _SITE_SESSION = requests.Session()
        _SITE_SESSION.headers.update(SESSION_HEADERS)
        load_cookie_string(_SITE_SESSION, COOKIE_STR)
    return _SITE_SESSION

def is_pow_challenge_response(resp):
    text = resp.text if resp is not None else ""
    return "powSolve" in text or ("/res/pow" in text and any(flag in text for flag in ("浏览器验证", "浏览器安全验证", "安全验证")))

def is_login_required_response(resp):
    text = resp.text if resp is not None else ""
    return (
        ("未登录" in text and "访问受限" in text)
        or "会话已过期" in text
        or ("跳转提示" in text and "/user/login" in text)
    )

def solve_browser_pow(session, referer_url):
    try:
        challenge_resp = session.get(
            f"{BASE_URL}/res/pow",
            headers={"Accept": "application/json", "Referer": referer_url},
            timeout=10,
        )
        if challenge_resp.status_code != 200:
            print(f"      PoW challenge HTTP {challenge_resp.status_code}")
            return False

        challenge = challenge_resp.json()
        modulus = int(challenge["N"], 16)
        value = int(challenge["x"], 16)
        steps = int(challenge["t"])

        started_at = time.time()
        for _ in range(steps):
            value = (value * value) % modulus

        elapsed = time.time() - started_at
        if elapsed < POW_MIN_SECONDS:
            time.sleep(POW_MIN_SECONDS - elapsed)

        verify_resp = session.post(
            f"{BASE_URL}/res/pow",
            data={"y": format(value, "x")},
            headers={
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
                "Referer": referer_url,
            },
            timeout=10,
        )
        verify = verify_resp.json()
        if verify.get("success"):
            print("      Browser verification solved.")
            return True

        print(f"      PoW verification failed: {verify}")
    except Exception as e:
        print(f"      PoW verification failed: {e}")

    return False

def login_site_session(session):
    if not LOGIN_USERNAME or not LOGIN_PASSWORD:
        print("      Login required. Set GYING_USERNAME and GYING_PASSWORD env vars, or refresh COOKIE_STR with a valid app_auth.")
        return False

    login_url = f"{BASE_URL}/user/login"
    try:
        resp = session.get(login_url, headers={"Referer": f"{BASE_URL}/"}, timeout=10)
        if is_pow_challenge_response(resp):
            if not solve_browser_pow(session, login_url):
                return False
            session.get(login_url, headers={"Referer": f"{BASE_URL}/"}, timeout=10)

        payload = {
            "code": "",
            "siteid": "1",
            "dosubmit": "1",
            "cookietime": "10506240",
            "username": LOGIN_USERNAME,
            "password": LOGIN_PASSWORD,
        }
        login_resp = session.post(
            login_url,
            data=payload,
            headers={"Accept": "application/json", "Referer": login_url},
            timeout=10,
        )
        result = login_resp.json()
        if result.get("code") == 200:
            print("      Site login succeeded.")
            return True

        if result.get("captcha"):
            print("      Site login requires captcha; complete login in browser and refresh COOKIE_STR.")
        else:
            print(f"      Site login failed: {result.get('msg') or result}")
    except Exception as e:
        print(f"      Site login failed: {e}")

    return False

def site_get(url, *, timeout=10, headers=None, **kwargs):
    session = get_site_session()
    request_headers = headers or {}
    resp = session.get(url, timeout=timeout, headers=request_headers, **kwargs)

    if is_pow_challenge_response(resp):
        if solve_browser_pow(session, url):
            resp = session.get(url, timeout=timeout, headers=request_headers, **kwargs)

    if is_login_required_response(resp):
        if login_site_session(session):
            resp = session.get(url, timeout=timeout, headers=request_headers, **kwargs)
            if is_pow_challenge_response(resp) and solve_browser_pow(session, url):
                resp = session.get(url, timeout=timeout, headers=request_headers, **kwargs)

    return resp

def site_post(url, *, data=None, timeout=15, headers=None, **kwargs):
    session = get_site_session()
    request_headers = headers or {}
    referer = request_headers.get("Referer", BASE_URL + "/")

    warmup = session.get(referer, timeout=timeout)
    if is_pow_challenge_response(warmup) and solve_browser_pow(session, referer):
        warmup = session.get(referer, timeout=timeout)
    if is_login_required_response(warmup) and not login_site_session(session):
        raise RuntimeError("Gying login is required")

    resp = session.post(url, data=data or {}, timeout=timeout, headers=request_headers, **kwargs)
    if is_pow_challenge_response(resp) and solve_browser_pow(session, referer):
        resp = session.post(url, data=data or {}, timeout=timeout, headers=request_headers, **kwargs)
    if is_login_required_response(resp) and login_site_session(session):
        resp = session.post(url, data=data or {}, timeout=timeout, headers=request_headers, **kwargs)
    return resp

def parse_site_action_response(resp):
    try:
        data = resp.json()
    except ValueError:
        raise RuntimeError(f"Gying returned non-JSON response (HTTP {resp.status_code})")

    success = resp.ok and (
        data.get("success") is True
        or data.get("code") in (0, 1, 200)
        or data.get("status") in ("success", "ok")
    )
    if not success:
        raise RuntimeError(str(data.get("msg") or data.get("message") or data))
    return data

def extract_js_assignment_json(text, assignment):
    marker = re.search(rf"{re.escape(assignment)}\s*=", text)
    if not marker:
        return None

    start = text.find("{", marker.end())
    if start == -1:
        return None

    depth = 0
    in_string = False
    escaped = False
    quote = ""

    for pos in range(start, len(text)):
        char = text[pos]

        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                in_string = False
            continue

        if char in ('"', "'"):
            in_string = True
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                snippet = text[start:pos + 1]
                try:
                    return json.loads(snippet)
                except json.JSONDecodeError:
                    return None

    return None

def fetch_movie_metadata(type_code, mid):
    url = f"{BASE_URL}/{type_code}/{mid}"
    try:
        resp = site_get(url, timeout=10)
    except Exception as e:
        print(f"      ⚠️ Metadata request failed: {url} ({e})")
        return None

    if resp.status_code != 200:
        print(f"      ⚠️ Metadata HTTP {resp.status_code}: {url}")
        return None

    try:
        data = resp.json()
        if isinstance(data, dict):
            meta = data.get("d") or data.get("data")
            if isinstance(meta, dict):
                return meta
    except ValueError:
        pass

    meta = extract_js_assignment_json(resp.text, "_obj.d")
    if meta:
        return meta

    if any(flag in resp.text for flag in ("浏览器验证", "浏览器安全验证", "安全验证")) or '"code":419' in resp.text:
        print("      ⚠️ Browser verification expired; update COOKIE_STR/browser_verified.")

    return None


def normalize_download_item(item):
    if isinstance(item, str):
        url = item.strip()
        if not url:
            return None
        provider = detect_provider("磁力" if url.lower().startswith("magnet:") else "")
        link_type = detect_resource_type(url, provider)
        return {
            "title": "Magnet Link" if link_type in ("MAGNET", "TORRENT") else url,
            "url": url,
            "code": extract_share_code(url),
            "provider": provider,
            "type": link_type,
            "tname": "",
        }

    if not isinstance(item, dict):
        return None

    url = str(item.get("url") or item.get("link") or item.get("magnet") or item.get("magnet_url") or "").strip()
    if not url:
        return None

    provider_raw = item.get("tname") or item.get("type_name") or item.get("provider") or item.get("type") or ""
    provider = detect_provider(str(provider_raw))
    link_type = detect_resource_type(url, provider)
    name = item.get("name") or item.get("title") or ("Magnet Link" if link_type in ("MAGNET", "TORRENT") else url)

    return {
        "title": name,
        "url": url,
        "code": extract_share_code(url, item.get("p") or item.get("pwd") or item.get("code") or ""),
        "provider": provider,
        "type": link_type,
        "tname": str(provider_raw),
    }

def normalize_download_section(section, target_user):
    if isinstance(section, list):
        normalized = [normalize_download_item(item) for item in section]
        return [item for item in normalized if item]

    if not isinstance(section, dict):
        return []

    names = section.get("name", [])
    urls = section.get("url", [])
    passwords = section.get("p", section.get("pwd", []))
    users = section.get("user", [])
    ids = section.get("id", [])
    type_indexes = section.get("type", [])
    type_names = section.get("tname", [])

    iterable_fields = (names, urls, passwords, users, type_indexes)
    count = max((len(v) for v in iterable_fields if isinstance(v, list)), default=0)
    has_target_user = any(user == target_user for user in users) if isinstance(users, list) else False
    resources = []

    for i in range(count):
        uploader = list_get(users, i)
        if has_target_user and uploader != target_user:
            continue

        url = str(list_get(urls, i)).strip()
        if not url or url in ("#", "暂无"):
            continue

        type_index = list_get(type_indexes, i)
        provider_raw = ""
        if isinstance(type_index, int):
            provider_raw = list_get(type_names, type_index)
        elif isinstance(type_index, str) and type_index.isdigit():
            provider_raw = list_get(type_names, int(type_index))
        if not provider_raw:
            provider_raw = list_get(type_names, i) or str(type_index or "")

        provider = detect_provider(provider_raw)
        link_type = detect_resource_type(url, provider)
        name = str(list_get(names, i)).strip()
        if not name:
            name = "Magnet Link" if link_type in ("MAGNET", "TORRENT") else url

        resources.append({
            "source_id": str(list_get(ids, i)).strip(),
            "title": name,
            "url": url,
            "code": extract_share_code(url, list_get(passwords, i)),
            "provider": provider,
            "type": link_type,
            "tname": provider_raw,
        })

    return resources

def publish_pan_resource(type_code, mid, title, panurl, panpw="", login_visible=0):
    endpoint = f"{BASE_URL}/res/pan/add/{type_code}/{mid}"
    payload = {
        "title": title,
        "panurl": panurl,
        "panpw": panpw or "",
        "is": str(int(login_visible)),
    }
    resp = site_post(
        endpoint,
        data=payload,
        headers={
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Referer": f"{BASE_URL}/{type_code}/{mid}",
            "X-Requested-With": "XMLHttpRequest",
        },
    )
    return parse_site_action_response(resp)

def update_pan_resource(source_id, title, panurl, panpw="", login_visible=0, type_code=None, mid=None):
    endpoint = f"{BASE_URL}/res/pan/edit/{source_id}"
    referer = f"{BASE_URL}/{type_code}/{mid}" if type_code and mid else f"{BASE_URL}/user/content_list"
    payload = {
        "title": title,
        "panurl": panurl,
        "panpw": panpw or "",
        "is": str(int(login_visible)),
    }
    resp = site_post(
        endpoint,
        data=payload,
        headers={
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Referer": referer,
            "X-Requested-With": "XMLHttpRequest",
        },
    )
    return parse_site_action_response(resp)

def normalize_content_list_resources(resources):
    normalized = []
    for res in resources:
        r_url = (res.get("url") or "").strip()
        if not r_url:
            continue

        provider = detect_provider(res.get("tname", ""))
        normalized.append({
            "title": res.get("title", ""),
            "url": r_url,
            "code": extract_share_code(r_url),
            "provider": provider,
            "type": detect_resource_type(r_url, provider),
            "tname": res.get("tname", ""),
        })
    return normalized

def fetch_download_resources(type_code, mid, fallback_resources):
    url = f"{BASE_URL}/res/downurl/{type_code}/{mid}"
    try:
        resp = site_get(
            url,
            timeout=10,
            headers={
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Referer": f"{BASE_URL}/{type_code}/{mid}",
                "X-Requested-With": "XMLHttpRequest",
            },
        )
    except Exception as e:
        print(f"      ⚠️ Downurl request failed: {e}")
        return normalize_content_list_resources(fallback_resources)

    if resp.status_code != 200:
        print(f"      ⚠️ Downurl HTTP {resp.status_code}; fallback to content_list resources.")
        return normalize_content_list_resources(fallback_resources)

    try:
        data = resp.json()
    except ValueError as e:
        print(f"      ⚠️ Downurl JSON decode failed: {e}")
        return normalize_content_list_resources(fallback_resources)

    resources = []
    for key in ("panlist", "downlist", "magnetlist", "btlist"):
        resources.extend(normalize_download_section(data.get(key), TARGET_USER))

    return resources or normalize_content_list_resources(fallback_resources)

def ingest_movie(db, type_code, mid, upload_poster=True):
    global _SITE_SESSION
    meta = None
    for attempt in range(3):
        meta = fetch_movie_metadata(type_code, mid)
        if meta:
            break
        _SITE_SESSION = None
        if attempt < 2:
            time.sleep(1)
    if not meta:
        raise RuntimeError(f"No metadata found for {type_code}/{mid}")

    title = meta.get("title") or meta.get("name") or meta.get("ename")
    series_name, season = parse_season(title)
    poster_url = upload_image_by_pattern(type_code, mid) if upload_poster else None
    pf = meta.get("pf") or {}
    db_score = (pf.get("db") or {}).get("s") or 0
    im_score = (pf.get("im") or {}).get("s") or 0

    sql_movie = """
        INSERT INTO movie_metadata (
            id, title_cn, title_en, year, runtime, directors, actors, genres,
            regions, languages, release_dates, aliases, poster_url,
            douban_score, imdb_score, summary, category, series_name, season,
            status, resource_status, created_at, updated_at
        ) VALUES (
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s, %s, 'ACTIVE', 'AVAILABLE', NOW(), NOW()
        )
        ON DUPLICATE KEY UPDATE
            title_cn=VALUES(title_cn), title_en=VALUES(title_en), year=VALUES(year),
            runtime=VALUES(runtime), directors=VALUES(directors), actors=VALUES(actors),
            genres=VALUES(genres), regions=VALUES(regions), languages=VALUES(languages),
            release_dates=VALUES(release_dates), aliases=VALUES(aliases),
            poster_url=COALESCE(VALUES(poster_url), poster_url),
            douban_score=VALUES(douban_score), imdb_score=VALUES(imdb_score),
            summary=VALUES(summary), category=VALUES(category),
            series_name=VALUES(series_name), season=VALUES(season),
            status='ACTIVE', resource_status='AVAILABLE', deleted_at=NULL, updated_at=NOW()
    """
    values = (
        mid,
        title,
        meta.get("name") or meta.get("ename"),
        meta.get("year"),
        meta.get("times"),
        json.dumps(meta.get("daoyan", []), ensure_ascii=False),
        json.dumps(meta.get("zhuyan", []), ensure_ascii=False),
        json.dumps(meta.get("leixing", []), ensure_ascii=False),
        json.dumps(meta.get("diqu", []), ensure_ascii=False),
        json.dumps(meta.get("yuyan", []), ensure_ascii=False),
        meta.get("stime"),
        meta.get("aliases") or meta.get("aka"),
        poster_url,
        db_score,
        im_score,
        meta.get("introduce") or meta.get("summary"),
        type_code,
        series_name,
        season,
    )
    resources = fetch_download_resources(type_code, mid, [])
    inserted = 0
    updated = 0

    try:
        with db.cursor() as cursor:
            cursor.execute(sql_movie, values)
        for resource in resources:
            resource_url = (resource.get("url") or "").strip()
            if not resource_url:
                continue
            provider = resource.get("provider") or detect_provider(resource.get("tname", ""))
            link_type = resource.get("type") or detect_resource_type(resource_url, provider)
            source_id = resource.get("source_id") or None
            with db.cursor() as cursor:
                cursor.execute(
                    "SELECT id FROM resource_link WHERE movie_id=%s AND url=%s LIMIT 1",
                    (mid, resource_url),
                )
                existing = cursor.fetchone()
                if existing:
                    cursor.execute(
                        """
                        UPDATE resource_link
                        SET name=%s, type=%s, provider=%s, code=%s, audit_status=1,
                            status='ACTIVE', link_status='NORMAL', source='GYING',
                            source_ref=%s, source_url=%s, auto_collected=1,
                            deleted_at=NULL, updated_at=NOW()
                        WHERE id=%s
                        """,
                        (
                            resource.get("title"),
                            link_type,
                            provider,
                            resource.get("code") or "",
                            source_id,
                            f"{BASE_URL}/{type_code}/{mid}",
                            existing["id"],
                        ),
                    )
                    updated += 1
                else:
                    cursor.execute(
                        """
                        INSERT INTO resource_link (
                            movie_id, name, type, provider, url, code, uploader_id,
                            audit_status, status, link_status, report_count, source,
                            source_ref, source_url, auto_collected, created_at, updated_at
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, 1, 1, 'ACTIVE', 'NORMAL', 0,
                            'GYING', %s, %s, 1, NOW(), NOW()
                        )
                        """,
                        (
                            mid,
                            resource.get("title"),
                            link_type,
                            provider,
                            resource_url,
                            resource.get("code") or "",
                            source_id,
                            f"{BASE_URL}/{type_code}/{mid}",
                        ),
                    )
                    inserted += 1
        db.commit()
    except Exception:
        db.rollback()
        raise

    return {
        "movieId": mid,
        "typeCode": type_code,
        "title": title,
        "resourcesFound": len(resources),
        "resourcesInserted": inserted,
        "resourcesUpdated": updated,
    }

def find_pan_resource(type_code, mid, panurl):
    resources = fetch_download_resources(type_code, mid, [])
    return next((item for item in resources if item.get("url") == panurl), None)

def mark_resource_published(db, resource_id, source_id, type_code, mid):
    if not resource_id:
        return
    with db.cursor() as cursor:
        cursor.execute(
            """
            UPDATE resource_link
            SET source='GYING_PUBLISHED', source_ref=%s, source_url=%s, updated_at=NOW()
            WHERE id=%s
            """,
            (source_id, f"{BASE_URL}/{type_code}/{mid}", resource_id),
        )
    db.commit()

class GyingSourceApiHandler(BaseHTTPRequestHandler):
    server_version = "GyingSource/1.0"

    def log_message(self, fmt, *args):
        print("[gying-source] " + fmt % args)

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        return not API_TOKEN or self.headers.get("X-Internal-Token") == API_TOKEN

    def read_json(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            raw = b"".join(chunks)
        else:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length else b""
        return json.loads(raw.decode("utf-8")) if raw else {}

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"ok": True})
        else:
            self.send_json(404, {"error": "Not found"})

    def do_POST(self):
        if not self.authorized():
            self.send_json(401, {"error": "Unauthorized"})
            return
        try:
            payload = self.read_json()
            print(f"[gying-source] {self.path} payload keys: {sorted(payload.keys())}", flush=True)
            if self.path == "/ingest":
                db = get_db_connection()
                try:
                    result = ingest_movie(
                        db,
                        payload["typeCode"],
                        payload["mid"],
                        bool(payload.get("uploadPoster", True)),
                    )
                finally:
                    db.close()
            elif self.path == "/publish":
                result = publish_pan_resource(
                    payload["typeCode"],
                    payload["mid"],
                    payload["title"],
                    payload["panurl"],
                    payload.get("panpw", ""),
                    payload.get("is", 0),
                )
                item = find_pan_resource(payload["typeCode"], payload["mid"], payload["panurl"])
                source_id = (item or {}).get("source_id") or result.get("id")
                if payload.get("resourceId"):
                    db = get_db_connection()
                    try:
                        mark_resource_published(
                            db,
                            payload["resourceId"],
                            source_id,
                            payload["typeCode"],
                            payload["mid"],
                        )
                    finally:
                        db.close()
                result = {"action": "published", "sourceId": source_id, "site": result}
            elif self.path == "/update":
                result = update_pan_resource(
                    payload["sourceId"],
                    payload["title"],
                    payload["panurl"],
                    payload.get("panpw", ""),
                    payload.get("is", 0),
                    payload.get("typeCode"),
                    payload.get("mid"),
                )
                result = {"action": "updated", "sourceId": payload["sourceId"], "site": result}
            else:
                self.send_json(404, {"error": "Not found"})
                return
            self.send_json(200, result)
        except (KeyError, ValueError) as error:
            self.send_json(400, {"error": f"Invalid request: {error}"})
        except Exception as error:
            self.send_json(502, {"error": str(error)})

def crawl_user_content(db):
    page = 1
    processed_movies = set()
    
    while True:
        url = f"{BASE_URL}/res/user/content_list?page={page}"
        print(f"📡 Crawling Page {page}...")
        
        try:
            resp = site_get(
                url,
                timeout=15,
                headers={
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                    "Referer": f"{BASE_URL}/user/content_list",
                    "X-Requested-With": "XMLHttpRequest",
                },
            )
            if resp.status_code != 200:
                print(f"❌ Request failed: HTTP {resp.status_code}")
                print(f"   Response preview: {resp.text[:250]!r}")
                break

            try:
                data = resp.json()
            except ValueError as e:
                print(f"❌ JSON decode failed: {e}")
                print(f"   Response preview: {resp.text[:500]!r}")
                break
            
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
                
                print(f"   🎬 Processing {atitle} ({mid})... Season: {season}")

                try:
                    meta = fetch_movie_metadata(type_code, mid)
                    
                    if meta:
                        
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
                            meta.get("name") or meta.get("ename"),
                            meta.get("year"),
                            meta.get("times"),
                            directors, actors, genres, regions, 
                            json.dumps(meta.get("yuyan", []), ensure_ascii=False),
                            meta.get("stime"),
                            poster_url,
                            db_score, im_score,
                            meta.get("introduce") or meta.get("summary"),
                            type_code,
                            series_name, season
                        )
                        
                        with db.cursor() as cursor:
                            cursor.execute(sql_movie, val_movie)
                            
                    else:
                        print(f"      ⚠️ No metadata found for {mid}")

                    # Insert resources from the new downurl endpoint. Fall back to content_list if needed.
                    resources_to_save = fetch_download_resources(type_code, mid, resources)
                    for res in resources_to_save:
                        provider = res.get("provider") or detect_provider(res.get("tname", ""))
                        r_url = (res.get("url") or "").strip()
                        if not r_url:
                            continue

                        link_type = res.get("type") or detect_resource_type(r_url, provider)
                        r_code = res.get("code") or extract_share_code(r_url)
                        r_name = res.get("title") or ("Magnet Link" if link_type in ("MAGNET", "TORRENT") else r_url)

                        check_sql = "SELECT id FROM resource_link WHERE movie_id=%s AND url=%s LIMIT 1"
                        with db.cursor() as cursor:
                            cursor.execute(check_sql, (mid, r_url))
                            existing_row = cursor.fetchone()

                        if existing_row:
                            resid = existing_row['id']
                            update_sql = """
                                UPDATE resource_link 
                                SET code=%s, provider=%s, name=%s, type=%s, uploader_id=1, audit_status=1
                                WHERE id=%s
                            """
                            with db.cursor() as cursor:
                                cursor.execute(update_sql, (r_code, provider, r_name, link_type, resid))
                        else:
                            insert_sql = """
                                INSERT INTO resource_link (movie_id, type, provider, url, code, uploader_id, audit_status, name)
                                VALUES (%s, %s, %s, %s, %s, 1, 1, %s)
                            """
                            with db.cursor() as cursor:
                                cursor.execute(insert_sql, (mid, link_type, provider, r_url, r_code, r_name))
                    db.commit()
                    print(f"      ✅ Saved.")
                    
                except Exception as e:
                    print(f"      ❌ Error processing {mid}: {e}")
                    db.rollback()
            
            # Pagination Check
            curr = data['page']['curr']
            pages = 1
            # pages = data['page']['pages']
            if curr >= pages:
                print("🏁 All pages processed.")
                break
            page += 1
            time.sleep(1) # Polite delay
            
        except Exception as e:
            print(f"❌ Page Error: {e}")
            break

def build_cli():
    parser = argparse.ArgumentParser(description="Gying source crawler and publisher")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("crawl-user", help="Crawl the configured user's content list")
    ingest = sub.add_parser("ingest", help="Ingest one Gying movie and its resources")
    ingest.add_argument("type_code", choices=("mv", "tv", "ac"))
    ingest.add_argument("mid")
    ingest.add_argument("--no-poster", action="store_true")

    publish = sub.add_parser("publish", help="Publish one pan resource to Gying")
    publish.add_argument("type_code", choices=("mv", "tv", "ac"))
    publish.add_argument("mid")
    publish.add_argument("title")
    publish.add_argument("panurl")
    publish.add_argument("--panpw", default="")
    publish.add_argument("--login-visible", type=int, choices=(0, 1), default=0)

    update = sub.add_parser("update", help="Update one Gying pan resource")
    update.add_argument("source_id")
    update.add_argument("title")
    update.add_argument("panurl")
    update.add_argument("--panpw", default="")
    update.add_argument("--login-visible", type=int, choices=(0, 1), default=0)

    serve = sub.add_parser("serve", help="Start the internal project integration API")
    serve.add_argument("--host", default=API_HOST)
    serve.add_argument("--port", type=int, default=API_PORT)
    return parser

def main():
    args = build_cli().parse_args()
    command = args.command or "crawl-user"
    if command == "serve":
        server = ThreadingHTTPServer((args.host, args.port), GyingSourceApiHandler)
        print(f"Gying source API listening on {args.host}:{args.port}")
        server.serve_forever()
        return
    if command == "publish":
        print(json.dumps(publish_pan_resource(
            args.type_code, args.mid, args.title, args.panurl, args.panpw, args.login_visible
        ), ensure_ascii=False))
        return
    if command == "update":
        print(json.dumps(update_pan_resource(
            args.source_id, args.title, args.panurl, args.panpw, args.login_visible
        ), ensure_ascii=False))
        return

    db = get_db_connection()
    try:
        if command == "ingest":
            result = ingest_movie(db, args.type_code, args.mid, not args.no_poster)
            print(json.dumps(result, ensure_ascii=False))
        else:
            crawl_user_content(db)
    finally:
        db.close()

if __name__ == "__main__":
    main()
