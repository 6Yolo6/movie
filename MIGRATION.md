# Gying Movie 上线迁移指南

> 本文档覆盖从本地开发环境迁移到云端生产环境的完整流程。

---

## 一、当前架构总览

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Next.js 16  │───▶│ Spring Boot  │───▶│    MySQL     │
│  (Port 3000) │    │  (Port 8880) │    │  (Port 3306) │
└──────────────┘    └──────┬───────┘    └──────────────┘
                           │
                    ┌──────▼───────┐    ┌──────────────┐
                    │    Redis     │    │    MinIO     │
                    │  (Port 6379) │    │  (Port 9000) │
                    └──────────────┘    └──────────────┘
```

**本地环境配置：**
- MySQL: `localhost:3306`，用户 `root`，数据库 `gying`
- Redis: `localhost:6379`，无密码
- MinIO: Docker 容器 `minio-server`，端口 `9000-9001`，桶名 `gying`
- 图片存储路径: `gying/{category}/{movieId}/384.avif`

---

## 二、生产架构（推荐）

```
                    ┌─────────────────────────────┐
                    │          Nginx              │
                    │   (80/443 + SSL 终止)       │
                    │   /api/** → Backend          │
                    │   /* → Frontend              │
                    └──────┬──────────┬───────────┘
                           │          │
                    ┌──────▼───┐  ┌───▼──────────┐
                    │ Next.js  │  │ Spring Boot   │
                    │ (容器)   │  │  (容器)       │
                    └──────────┘  └──┬─────┬──────┘
                                     │     │
                            ┌────────▼┐ ┌──▼──────────┐
                            │ Cloud   │ │ Cloud Redis  │
                            │ MySQL   │ │              │
                            └─────────┘ └──────────────┘

        图片 CDN/OSS ← 用户浏览器直接访问（不经过 Nginx）
```

---

## 三、数据库迁移（MySQL → 云端 MySQL）

### 3.1 推荐的云数据库服务

| 云厂商 | 产品 | 最低规格 | 参考价格 |
|--------|------|---------|---------|
| 阿里云 | RDS MySQL | 1核1G | ~¥50/月 |
| 腾讯云 | CDB MySQL | 1核1G | ~¥45/月 |
| 华为云 | RDS MySQL | 1核1G | ~¥50/月 |

### 3.2 导出数据

在本地 WSL 或 Docker 中执行：

```bash
# 导出整个数据库（含数据）
mysqldump -u root -p'Root@123' \
  --single-transaction \
  --routines \
  --triggers \
  --set-gtid-purged=OFF \
  gying > gying_backup_$(date +%Y%m%d).sql

# 只导出表结构（不含数据，用于初始化）
mysqldump -u root -p'Root@123' --no-data gying > gying_schema.sql
```

### 3.3 云端数据库初始化

```bash
# 1. 创建数据库和用户
mysql -h <cloud-host> -u <admin-user> -p <<EOF
CREATE DATABASE gying CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'gying_app'@'%' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON gying.* TO 'gying_app'@'%';
FLUSH PRIVILEGES;
EOF

# 2. 导入数据
mysql -h <cloud-host> -u gying_app -p gying < gying_backup_20260611.sql
```

### 3.4 注意事项

- 云端 MySQL 一般**强制 SSL**，`application-prod.yml` 已配置 `useSSL=true`
- 创建专用数据库用户，**不要用 root**
- 确保 `serverTimezone=Asia/Shanghai`
- 建议开启自动备份（云厂商一般提供）

---

## 四、图片存储迁移（MinIO → 云端 OSS）

### 4.1 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| **阿里云 OSS** | 国内速度快、CDN 集成好 | 需备案域名 |
| **腾讯云 COS** | 与腾讯云生态集成 | 需备案域名 |
| **继续用 MinIO** | 无迁移成本、S3 兼容 | 需自己维护服务器 |
| **Cloudflare R2** | 免出口流量费、全球 CDN | 国内访问可能偏慢 |

### 4.2 迁移到阿里云 OSS（推荐）

#### Step 1: 创建 OSS Bucket

```
Bucket 名称: gying-images
地域: 华东1（杭州）或与云服务器同地域
读写权限: 公共读（Public Read）
存储类型: 标准存储
```

#### Step 2: 批量迁移 MinIO 中的图片

```bash
# 安装 rclone（通用 S3 迁移工具）
curl https://rclone.org/install.sh | sudo bash

# 配置 MinIO 源
rclone config create minio s3 \
  provider=Minio \
  access_key_id=admin \
  secret_access_key=miniopassword \
  endpoint=http://localhost:9000

# 配置阿里云 OSS 目标
rclone config create alioss s3 \
  provider=Alibaba \
  access_key_id=<your-ak> \
  secret_access_key=<your-sk> \
  endpoint=https://oss-cn-hangzhou.aliyuncs.com

# 执行迁移（同步所有文件）
rclone sync minio:gying alioss:gying-images --progress

# 验证
rclone ls alioss:gying-images | head -20
```

#### Step 3: 更新后端配置

在 `.env` 中设置：

```bash
# 阿里云 OSS 公网域名
MINIO_URL_PREFIX=https://gying-images.oss-cn-hangzhou.aliyuncs.com/

# 如果绑定了 CDN 域名（推荐）
MINIO_URL_PREFIX=https://img.yourdomain.com/
```

**无需修改后端代码！** `processMovieUrl()` 只拼接前缀，数据库中的相对路径 `tv/1Znq/384.avif` 保持不变。

#### Step 4: 更新爬虫脚本

```python
# crawler/gying_crawler.py 中修改：

# 方案 A：直接上传到 OSS（使用 oss2 SDK）
import oss2
auth = oss2.Auth('<AccessKeyId>', '<AccessKeySecret>')
bucket = oss2.Bucket(auth, 'https://oss-cn-hangzhou.aliyuncs.com', 'gying-images')

def upload_image_to_oss(type_code, movie_id):
    object_name = f"{type_code}/{movie_id}/384.avif"
    target_url = f"https://s.tutu.pm/img/{type_code}/{movie_id}/384.avif"
    resp = requests.get(target_url, headers=HEADERS, timeout=10)
    if resp.status_code == 200:
        bucket.put_object(object_name, resp.content)
        return object_name

# 方案 B：继续使用 MinIO + rclone 定期同步
# 保持现有爬虫不变，用 crontab 定期 rclone sync
```

---

## 五、应用部署

### 5.1 服务器准备

推荐配置（入门级）：

| 项目 | 规格 |
|------|------|
| CPU | 2 核 |
| 内存 | 4 GB |
| 系统盘 | 50 GB SSD |
| 带宽 | 3-5 Mbps（或按量付费） |
| 系统 | Ubuntu 22.04 / Debian 12 |

### 5.2 安装 Docker

```bash
# 安装 Docker + Docker Compose
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# 验证
docker --version
docker compose version
```

### 5.3 部署应用

```bash
# 1. 上传项目到服务器
scp -r ./gying-movie user@server:/opt/

# 2. SSH 到服务器
ssh user@server

# 3. 配置环境变量
cd /opt/gying-movie
cp .env.example .env
nano .env  # 填入实际值

# 4. 构建并启动
docker compose -f docker-compose.prod.yml up -d --build

# 5. 检查状态
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

### 5.4 配置 HTTPS

```bash
# 使用 Let's Encrypt 免费证书
apt install certbot
certbot certonly --standalone -d yourdomain.com

# 将证书复制到 nginx 目录
mkdir -p nginx/ssl
cp /etc/letsencrypt/live/yourdomain.com/fullchain.pem nginx/ssl/
cp /etc/letsencrypt/live/yourdomain.com/privkey.pem nginx/ssl/

# 取消 nginx.conf 中 HTTPS 部分的注释，然后重启
docker compose -f docker-compose.prod.yml restart nginx

# 设置自动续期
crontab -e
# 添加：0 3 * * * certbot renew --quiet && docker compose -f /opt/gying-movie/docker-compose.prod.yml restart nginx
```

---

## 六、环境变量速查表

| 变量 | 说明 | 示例值 |
|------|------|--------|
| `DB_HOST` | 云端 MySQL 地址 | `rm-xxx.mysql.rds.aliyuncs.com` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_NAME` | 数据库名 | `gying` |
| `DB_USER` | 数据库用户 | `gying_app` |
| `DB_PASSWORD` | 数据库密码 | `***` |
| `REDIS_HOST` | Redis 地址 | `redis`（compose 内部） |
| `REDIS_PASSWORD` | Redis 密码 | `***` |
| `MINIO_URL_PREFIX` | 图片 URL 前缀 | `https://img.yourdomain.com/` |
| `CORS_ALLOWED_ORIGIN` | 允许的前端域名 | `https://yourdomain.com` |
| `JWT_SECRET` | JWT 签名密钥 | `openssl rand -base64 48` 生成 |
| `JWT_EXPIRATION_MS` | Token 过期时间 | `86400000`（24小时） |

---

## 七、上线前检查清单

### 安全项
- [ ] JWT Secret 已替换为强随机值（≥32 字节）
- [ ] 数据库密码已修改，不使用 root 用户
- [ ] Redis 设置了密码
- [ ] `.env` 文件未提交到 Git（已在 `.gitignore` 中）
- [ ] CORS 仅允许生产域名，移除了 `http://localhost:*`
- [ ] HTTPS 已启用

### 数据项
- [ ] 数据库已导入到云端 MySQL
- [ ] MinIO 图片已同步到 OSS
- [ ] `MINIO_URL_PREFIX` 指向正确的 CDN/OSS 域名
- [ ] 测试了图片加载是否正常

### 功能验证
- [ ] 首页电影列表加载正常
- [ ] 电影详情页显示完整
- [ ] 搜索功能正常
- [ ] 登录/注册功能正常
- [ ] 注册验证码必填且错误验证码会被拒绝
- [ ] 发表评论/回复正常
- [ ] 资源下载链接可点击
- [ ] 登录用户可提交网盘、磁力、种子或在线播放资源
- [ ] 用户可在“我的投稿”查看审核状态并删除自己的投稿
- [ ] 用户可举报失效资源，后台可看到疑似失效状态
- [ ] 管理员审核投稿通过/拒绝后，投稿用户可在“通知”看到结果且未读数更新
- [ ] 管理员修改资源链接状态后，投稿用户可收到链接状态变更通知
- [ ] 管理后台功能正常

### 性能项
- [ ] Nginx Gzip 压缩已开启
- [ ] 静态资源缓存已配置（30天）
- [ ] HikariCP 连接池参数合理

---

## 八、后续优化建议

| 优先级 | 项目 | 说明 |
|--------|------|------|
| P1 | **接口缓存** | 电影列表/详情加 Redis 缓存，TTL 10-30 分钟，降低数据库压力 |
| P1 | **图片懒加载** | 首页 MovieCard 使用 `loading="lazy"` + 低质量占位图 |
| P2 | **全文搜索** | 接入 Elasticsearch 替代 `LIKE` 模糊查询，提升搜索精度和速度 |
| P2 | **CDN 加速** | 前端静态资源上 CDN（Vercel / Cloudflare Pages） |
| P2 | **日志监控** | 接入阿里云 SLS 或 Grafana + Loki，实时监控错误和性能 |
| P3 | **CI/CD** | GitHub Actions 自动构建 Docker 镜像并部署 |
| P3 | **定时任务** | 在现有用户举报基础上增加资源链接有效性检测，自动标记失效链接 |
| P3 | **国际化完善** | 补充缺失的翻译 key，增加日语等语言 |

---

## 九、常用运维命令

```bash
# 查看所有服务状态
docker compose -f docker-compose.prod.yml ps

# 查看后端日志（最近 100 行 + 实时跟踪）
docker compose -f docker-compose.prod.yml logs -f --tail=100 backend

# 重启单个服务
docker compose -f docker-compose.prod.yml restart backend

# 更新部署（拉取新代码后）
git pull
docker compose -f docker-compose.prod.yml up -d --build

# 数据库备份
docker exec -it <mysql-container> mysqldump -u gying_app -p gying > backup_$(date +%Y%m%d).sql

# 清理无用 Docker 镜像
docker image prune -f
```
