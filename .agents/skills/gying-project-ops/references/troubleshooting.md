# 故障排查

[现状](../../../../docs/current-project-status.md) · [完整旧事故/回退记录](../../../../docs/history/snapshots/20261022/.agents/skills/gying-project-ops/references/troubleshooting.md)

## 事故处理顺序

时间/主机/提交/容器基线→依赖/内部→backend/SSR→nginx本机→Tunnel/公网→副作用/日志。先只读、按范围处理，保留配置/加密回退；容器Up/单次200不能关闭事故。

## 现象矩阵

| 现象 | 定位 |
| --- | --- |
| MySQL1045/passwordNO | MCP专用身份/密码键/宿主环境，不改grant试错 |
| GYING5xx/超时 | source会话/短熔断/来源结果，AUTO可PanSou但显式GYING失败不冒成功 |
| 分享不可用/WAITING_SHARE | 分provider核保存目录/本轮文件完整/分享/任务，HTTP200不代业务 |
| publisher200/UNKNOWN | 查持久化状态/远端，不重试在途/历史FAILED |
| 本机好/公网530、ready503 | HA连接/代理/DNS/边缘网络，不先改业务/Access凭据 |
| git SSH22被fake-IP关闭 | 用已信任ssh.github.com:443单次配置，不擅改全局remote/insteadOf |
| 相同片/季错配 | 大小写身份/年份/季/主创/canonical，不删历史消歧 |

## 日志命令

```powershell
Invoke-WebRequest http://127.0.0.1:20241/ready -UseBasicParsing
docker compose -p gying-movie -f docker-compose.prod.yml logs --tail=200 backend gying-source social-publisher frontend nginx
```

涉及配置变更用已审查覆盖；独立PanSou/Quark/OpenClaw按实际名字读取窗口。metrics核ha_connections。分享只脱敏摘要，禁Cookie/token/body/完整URL参数。

## Docker 恢复后端口空响应与 Tunnel fake-IP 漂移

09月30历史两独立问题：容器内部HTTP正常但宿主8880/5005 curl52，vpnkit仍旧IP；先记身份/配置/挂载/转发日志、无新在途后按授权逐原容器重启注册，每项验宿主，backend变化后nginx测试/重载。不要扩大端口/删卷/全Docker重启。

HTTP2 Tunnel缓存漂移198.18地址时，对照当前DNS/目标/代理；不是所有超时都QUIC。限定dns.fake-ip-filter `+.argotunnel.com`，blacklist模式/现有项/生成顺序先核，不关TUN或固定边缘IP。受限Clash配置 `%APPDATA%/io.github.clash-verge-rev.clash-verge-rev`、profiles/Merge.yaml DNS；Mihomo -t验证/授权本地控制重载，确认region1/2.v2真实IP再按范围重启原Tunnel清缓存。历史回退 `E:/gying-tools/releases/entrypoint-recovery-20260930/Rollback-ProxyDns.ps1` 默认检查，Apply遇后续hash变化必须合并；DPAPI仅原主机/账号。

## Tunnel 边缘连接被代理节点绑架（TUN 默认路由）

10月14 17:17–17:23公网530/1033、机场测速超时、HA归零；代理恢复后自动重连。查service日志7844 match-Match超时/ready/源站分层，不改Token/Access。

持久规则仅 DOMAIN-SUFFIX argotunnel.com 和198.41.192.0/24、198.41.200.0/24的IP-CIDR no-resolve，prepend置最前；rules/groups订阅增强只支持prepend/append/delete，Merge深合并中的prepend-rules无效。fallback `GYing-Tunnel` 原机场→DIRECT，以Cloudflare trace健康检查；保留TLS/HTTP2/其他策略。修改增强文件后重启客户端，核生成时间、rules前三/7844 connections IPCIDR/chains及连续公网。不要直接改生成clash-verge.yaml；配置可能含secret，加密备份仅路径/hash。原回退tmp/tunnel-diagnose-20261014/backup，删本次prepend后重启，后续用户配置不覆盖。

## 数据安全排查

SELECT/dry-run+汇总及少量样本；重跑先核远端副作用，区分验证器故障/疑似失效/真无文件。重复按标题/别名/季核canonical，优先软处理；不把观测不足改成删除/无限重试。

## 近期功能专项排查

- repair-invalid分双盘，核任务/saved_path、新URL/NORMAL/url_hash；gyingUpdated=false先查映射/账号/res/pan/add，无映射可预期。
- QQ每日推荐核sys_config目标/篇数/时间/模板、官方主动权限/错误码；40034105不是重启可解决。手动run真实消息，不能默认排障试发，NapCat不备用。
- QQ进度乱码/越权：核源/唯一运行插件/Gateway配置、Unicode转义、allowFrom/ownerAllowFrom/commands.allowFrom.qqbot的QQ/member_openid，插件二次拒/bot-*/stop/approve。补丁/重启需当前范围。
- 自然搜冷启动/误召回先核TTL/50k截断/filters与词覆盖，不把词法映射当embedding或模型生成；[成果](../../../../docs/catalog-features-search-20261021.md)。

## 编码

UTF8读写，已乱码按已知原文修，不能反复转码猜测。

## 关闭事故

原路径/连续日志无立即复发、任务恢复原状态或明确关闭、回退可用、数据/外部效果明确、待办和状态一致；真实群消息/网盘操作不从health通过推断。
