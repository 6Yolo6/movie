# Docker 安全加固

[现状](../current-project-status.md) · [部署](../deployment.md) · [门禁](hardening-gates.md) · [完整旧证据](../history/snapshots/20261022/docs/security/docker-security.md)

## 1. 适用文件和运行原则

显式 `-p gying-movie -f docker-compose.prod.yml` 加经审查覆盖。Compose是目标，实际容器另采集；先只读快照、就绪、加密检查点及旧镜像，按受影响服务替换。

## 2. 端口和网络

| 服务 | 目标 | 已记录差距 |
| --- | --- | --- |
| nginx/backend | loopback80/8880，backend后续按机器人迁移决定保留 | 已loopback |
| frontend/source/publisher | 内部3000/8091/8093，无宿主发布 | 当前保持 |
| Redis | 私有cache-net、无宿主发布 | 实际仍共享gying-net，backend双网 |
| Quark | 管理loopback5005 | 实际全接口5005 |
| MinIO | 内部S3/管理loopback，不走Tunnel | 实际全接口9000/9001；alias存在不证明消费者已迁移 |
| OpenClaw | 受控共享内部调用 | 仍默认bridge；需先迁移backend地址 |
| PanSou/NapCat | 内部8888；NapCat legacy-disabled不启动 | 不重复启动embedded-deps |

实际以[最新状态](../current-project-status.md)及只读扫描为准；09月25网络/权限是原证据。

## 3. 容器权限

目标NNP、适用cap_drop ALL/只读rootfs/tmpfs、内存/PID/log rotation限制；无privileged/socket/宿主根挂载。nginx101/backend-source10001/frontend1001/social node。五依赖缺NNP、Quark/PanSou/MinIO有UID0进程，不能写为已完成。必要状态卷保留写权限，read_only不代替鉴权。

## 4. 卷和敏感数据

backend-data与logs、QQ/微博两个状态卷、Quark配置、MinIO持久对象分别备份，核实际卷/owner/ACL；Redis/PanSou缓存可重建但旧卷不删除。Quark父目录9月25仅改root0700，六文件自身模式/内容不变，Cookie轮换/非root/NNP待补；backend/source MinIO仍host.docker.internal:9000，先迁移消费者再改绑端口。

## 5. 安全检查命令

```powershell
python -X utf8 tools/security/check_security.py --repo . --probe
python -X utf8 tools/security/scan_secrets.py
docker compose -p gying-movie -f docker-compose.prod.yml config --quiet
docker ps -a --format "{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
```

只输出必要字段，完整inspect/config含敏感值。历史扫描按需要另运行scan_secrets.py --history。

## 6. 发布顺序

备份/可恢复→独立DB/MinIO身份→接网络/alias并验证消费者→逐服务endpoint迁移→收端口→按范围固定镜像最小切换→内部依赖/SSR/图片/私有对象/控制台/鉴权→Tunnel/Access→日志观察。失败恢复旧配置/镜像，保留卷/新审计，不做down-v或全局清理。[架构顺序](security-architecture.md)。

## 7. 供应链

旧2026-09-20审计：目标frontend10个critical/high、social58、source剩1个zlib high，backend未闭环；旧npm audit零不是当前镜像无CVE。当前digest需独立复扫/可达性与fixed版本评估，不在文档整理中声称已完成。
