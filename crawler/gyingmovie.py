import requests
import re
import json
import pandas as pd
import time
import os

# ================= 配置区域 =================
# 输出文件名
OUTPUT_FILE = "影视资源库_救星小窝_扁平版.xlsx"
# 目标发布者
TARGET_USER = "救星小窝"

# ⚠️ 你的 Cookie (必填! 否则无法获取数据)
# 请定期更新这个字符串
COOKIE_STR = "BT_cookietime=c820Ys-oTZT215bLxmw6m7FIMHq4KLV9WyQEFwiO2yqLh0z5XxRr; BT_auth=93caizifLDe4E2vhHKfV8-WR8cuW0ZKHlBQllT6701Hvpm4rvulPsm8gCO8PiQplqfAjZWemKhOUgC2VBnuH3hiCwd3YMArW3SqqsG50nBD3m8vT09Ia6kg63nH4DZoo_uT1bVY0zAQnN244HpLL3nB3TSBmySRCHogEbf4uALrnlq2PXDxrfVw"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Cookie": COOKIE_STR,
    "Referer": "https://www.gyg.si/"
}

# ================= 核心功能函数 =================

def get_home_page_tasks():
    """从首页获取最近更新的资源ID列表"""
    url = "https://www.gyg.si"
    print("📡 正在扫描首页资源...")
    try:
        resp = requests.get(url, headers=HEADERS, timeout=15)
        
        match = re.search(r"_obj\.inlist\s*=\s*(\[\{.*?\}\]);", resp.text, re.DOTALL)
        if not match:
            print("❌ 首页解析失败，未找到资源列表")
            return []
        
        inlist = json.loads(match.group(1))
        tasks = []
        category_map = {"mv": "电影", "tv": "剧集", "ac": "动漫"}
        
        for section in inlist:
            ids = section.get("i", [])
            type_code = section.get("ty", "mv")
            category_name = category_map.get(type_code, "其他")
            titles = section.get("t", [])
            
            print(f"   - 发现 [{category_name}] 板块，包含 {len(ids)} 个资源")
            
            for index, res_id in enumerate(ids):
                tasks.append({
                    "id": res_id,
                    "type_code": type_code,
                    "category": category_name,
                    "temp_title": titles[index] if index < len(titles) else res_id
                })
                
        print(f"✅ 任务生成完毕，共 {len(tasks)} 个资源待处理。\n")
        return tasks
    except Exception as e:
        print(f"❌ 首页请求网络错误: {e}")
        return []

def process_single_resource(task):
    """处理单个资源：扁平化处理，一行一部电影"""
    res_id = task["id"]
    type_code = task["type_code"]
    category = task["category"]
    url_meta = f"https://www.gyg.si/{type_code}/{res_id}"
    
    print(f"🔍 正在处理: [{category}] {task['temp_title']} ...")
    
    try:
        # --- 步骤 1: 获取 Meta ---
        resp_meta = requests.get(url_meta, headers=HEADERS, timeout=10)
        match_meta = re.search(r"_obj\.d\s*=\s*(\{.*?\});", resp_meta.text, re.DOTALL)
        if not match_meta: return []
        meta = json.loads(match_meta.group(1))
        
        # --- 步骤 2: 获取链接 API ---
        url_link = f"https://www.gyg.si/res/downurl/{type_code}/{res_id}"
        resp_link = requests.get(url_link, headers=HEADERS, timeout=10)
        try:
            link_data = resp_link.json()
        except:
            return []
        
        pan = link_data.get("panlist", {})
        if not pan or "name" not in pan: return []
            
        # --- 步骤 3: 初始化单行数据 ---
        # 预先定义好所有列，网盘列默认为空字符串
        row = {
            "资源ID": meta.get("id", res_id),
            "电影名称": meta.get("title", "") + " " + meta.get("name", ""),
            "资源标签": ",".join(meta.get("leixing", [])),
            "资源类型": category,
            "上映年份": meta.get("year", ""),
            "导演": ",".join(meta.get("daoyan", [])),
            "主演": ",".join(meta.get("zhuyan", [])),
            "豆瓣评分": str(meta.get("pf", {}).get("db", {}).get("s", "0")),
            "电影简介": meta.get("introduce", ""),
            "发布者": TARGET_USER,
            "详情页链接": url_meta,
            "资源名称": "", # 后续填充
            
            # 聚合列
            "百度网盘": "",
            "迅雷网盘": "",
            "夸克网盘": "",
            "天翼网盘": "",
            "阿里网盘": ""
        }

        count = len(pan["name"])
        platform_names = pan.get("tname", [])
        
        found_valid_link = False
        
        # --- 步骤 4: 遍历链接并聚合 ---
        for i in range(count):
            user = pan["user"][i]
            
            if user == TARGET_USER:
                found_valid_link = True
                
                # 记录第一个文件名作为资源名称
                if not row["资源名称"]:
                    row["资源名称"] = pan["name"][i]
                
                # 获取平台类型
                type_idx = pan["type"][i]
                p_name = platform_names[type_idx] if type_idx < len(platform_names) else "其他"
                
                # 拼接链接字符串：链接 (码:xxxx)
                url_val = pan["url"][i]
                pwd_val = pan["p"][i]
                link_text = url_val
                if pwd_val and "无" not in pwd_val:
                    link_text += f" (码:{pwd_val})"
                
                # 归类并追加 (使用换行符分隔多条链接)
                if "百度" in p_name:
                    row["百度网盘"] = (row["百度网盘"] + "\n" + link_text).strip()
                elif "迅雷" in p_name:
                    row["迅雷网盘"] = (row["迅雷网盘"] + "\n" + link_text).strip()
                elif "夸克" in p_name:
                    row["夸克网盘"] = (row["夸克网盘"] + "\n" + link_text).strip()
                elif "天翼" in p_name:
                    row["天翼网盘"] = (row["天翼网盘"] + "\n" + link_text).strip()
                elif "阿里" in p_name:
                    row["阿里网盘"] = (row["阿里网盘"] + "\n" + link_text).strip()

        # 只有找到目标发布者的资源才返回
        if found_valid_link:
            return [row]
        else:
            return []

    except Exception as e:
        print(f"   ❌ 处理出错: {e}")
        return []

def save_to_excel(data_list, filename):
    """保存数据到 Excel"""
    if not data_list:
        print("\n⚠️ 没有数据需要保存。")
        return

    print(f"\n💾 正在保存 {len(data_list)} 条数据到 Excel...")
    df = pd.DataFrame(data_list)
    
    # 定义期望的列顺序
    columns_order = [
        "资源ID", "电影名称", "资源标签", "资源类型", "上映年份", 
        "豆瓣评分", "资源名称", 
        "百度网盘", "迅雷网盘", "夸克网盘", "天翼网盘", "阿里网盘",
        "发布者", "详情页链接", "导演", "主演", "电影简介"
    ]
    
    # 筛选并排序存在的列
    final_cols = [c for c in columns_order if c in df.columns]
    df = df[final_cols]

    try:
        # 使用 openpyxl 引擎保存
        df.to_excel(filename, index=False, engine='openpyxl')
        print(f"🎉 文件已成功生成：{os.path.abspath(filename)}")
    except Exception as e:
        print(f"❌ 保存 Excel 失败: {e}")
        print("请检查文件是否被打开。")

# ================= 主程序执行入口 =================

if __name__ == "__main__":
    print(f"=== 影视资源爬虫 (Excel扁平版) 启动 ===\n")
    
    tasks = get_home_page_tasks()
    all_rows = []
    
    if tasks:
        for task in tasks:
            new_rows = process_single_resource(task)
            all_rows.extend(new_rows)
            time.sleep(1) # 延时防封
            
        save_to_excel(all_rows, OUTPUT_FILE)
    
    print("\n=== 执行完毕，按回车键退出 ===")
    input()