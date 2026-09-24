# 密钥、Cookie 和 Token 管理

## 1. 规则

- 密钥值只存在于 Windows 受保护路径、外部 Cloudflare/应用配置、Docker Secret/configtree 或进程内存；不提交 Git、不写报告、不写 URL、不写日志。
- `.env.example` 只放键名和占位符；生产 `.env` 不在仓库中。文件 ACL 至少限制当前服务账号、管理员和 SYSTEM。
- 生产启动必须 fail closed：弱/空 DB、Redis、JWT、内部服务 token 或 CORS 时拒绝启动。
- 每个用途独立随机值；不能把 JWT、DB、GYING、social、QQ、Quark、MinIO key 复用。
- 轮换先创建新凭据并验证，再撤销旧凭据；记录键名、时间、操作者和结果，不记录值。

## 2. 当前扫描结果

- 工作区扫描：0 findings。
- 可达历史：1,278 个文本 Blob，105 条规则命中；命中跨多个版本重复，不等于 105 个唯一凭据。
- 历史中曾出现过 DB 密码、GYING Cookie、共享管理员 password hash 和可选第三方客户端 secret。Git 历史尚未重写，凭据尚未全部轮换，因此不能把“当前文件已清理”当成泄露已消除。

处理顺序：

1. 立即轮换仍可能有效的外部/内部凭据；
2. 检查访问日志和 provider 会话；
3. 需要时撤销 Git clone/read 权限；
4. 只有在确认所有协作者、镜像和备份可迁移后，另行批准历史重写；
5. 保留本次扫描摘要，不保存匹配值。

## 3. 密钥分类和来源

| 类别 | 运行来源 | 轮换动作 |
| --- | --- | --- |
| DB | 外部 `.env`/Docker Secret/configtree | 创建 scoped DB user，验证后撤销旧密码 |
| JWT | `.env`/secret file | 更换会使旧 token 失效；通知用户重新登录 |
| Redis | Compose secret/env → ACL | 改 ACL 密码，重启依赖并验证限流 |
| MinIO | scoped access/secret key | 先应用 policy，再撤销 root/旧 key |
| GYING Source/social | 各自独立内部 token | 双 token 窗口切换，旧 token 立即失效 |
| QQ Bot/OpenClaw | 外部 bot/Access 配置 | 从 provider 控制台撤销、重新授权；不写状态文档 |
| Quark | `QUARK_COOKIE` 覆盖 + `/app/config` 状态 | 退出/撤销旧会话，重新登录，收紧卷 ACL |
| Xunlei/Weibo | backend-data/发布器卷/外部会话 | provider 侧撤销或重新登录，检查任务日志 |
| Cloudflare Tunnel | 仓库外 credentials 文件 | Cloudflare 侧 rotate/revoke，重新安装计划任务 |
| age backup | 公钥在 backup config，私钥离线保管 | 生成新 recipient，保留旧恢复窗口后撤销/封存 |

## 4. Quark 特别说明

上游 `quark-auto-save` 支持 `QUARK_COOKIE` 环境变量覆盖，但其认证状态仍可能写入 `/app/config`；GYing backend 只在内存中读取该 fallback，已禁止把 Cookie POST 回上游 `/update`。这不是加密存储：

- 不把 Cookie 写进代码、SQL、截图、日志或 Git；
- 生产卷备份必须 age 加密；
- Windows ACL 和容器内文件权限同时收紧；当前观测到配置文件 `0755`，部署前修正为仅服务用户可读；
- Cookie 失效/疑似泄露时先撤销并重新登录，再更新 secret；
- 备份恢复后检查 WebUI/任务是否仍使用旧会话，避免恢复已撤销 Cookie。

## 5. Docker Secret/configtree

Spring Boot 已支持 `optional:configtree:/run/secrets/`。若使用 Docker Secret：

- secret 文件放在仓库外，Windows ACL 限制读取；
- 不把 secret 文件 bind mount 到 frontend/static 或日志目录；
- 启动后通过键名/长度/健康状态验证，不输出值；
- `docker inspect`、Compose config、CI 日志必须脱敏。

当前 Compose 仍兼容 `.env`，切换到 Secret 要在隔离环境验证 Spring property precedence，再逐服务迁移。

## 6. 开发和提交门禁

```powershell
python -X utf8 tools/security/scan_secrets.py
python -X utf8 tools/security/scan_secrets.py --history
rg -n --hidden --glob '!.git' --glob '!node_modules' --glob '!target' --glob '!dist' "(PASSWORD|SECRET|COOKIE|TOKEN|API_KEY|ACCESS_KEY)\s*[:=]" .
```

命中时只报告路径/行号/规则，禁止把匹配行复制到聊天、Issue 或提交信息。扫描器不是 Gitleaks/TruffleHog 的替代品；CI 应再接入组织批准的 secret scanner。

## 7. 迅雷状态文件写入边界（2026-09-24）

- 在线 `/app/data/xunlei-auth.json` 仍观测为 `10001:10001 / 644`；不能仅凭外部同步脚本的 `600` 验收认定所有写入路径已安全。
- backend 新代码通过 `PrivateFileWriter` 建立同目录随机临时文件：POSIX 创建即 `600`；Windows 空文件先设置 owner-only ACL，再写入任何凭据字节。
- 写入不跟随临时符号链接，以 `ATOMIC_MOVE` 发布；不支持私有权限或原子替换时失败，不降级为公开/非原子写入。失败清理临时文件，保留之前状态；旧固定 `.tmp` 文件不作为写入目标。
- 已增加重复写入、旧 `644` 替换、符号链接和失败清理回归；生产部署前先备份状态文件及旧镜像，部署后只检查 owner/mode 和授权状态，不打印内容。
- 权限修复不代表官方刷新接口或 Edge 登录态已恢复；不得自动触发转存、发布或账号重新授权来替代权限验收。
