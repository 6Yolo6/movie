# QQ 频道同片多网盘帖子

2026-10-15 19:44:42 已部署，fc5ef04；[现状](current-project-status.md)、[完整旧证据](history/README.md#git-history)。

## 范围与行为

仅原宿主 GYing QQ Channel Auto Post/bridge→publish-latest-resource-to-qq-channel.ps1→publish-qq-channel-feed.ps1。群搜索/每日推荐/独立publisher 分开。每片一帖；已审核/活动/未删除/NORMAL 的 DISK 按 provider 选 updated_at DESC,id DESC 第一条，正文列网盘/链接/非空提取码；磁力/种子/在线播放不冒充视频。

候选来源/目标/调度/代表资源审计保留，不重发历史。旧 `{{link}}` 展开多盘列表，无占位符追加一次；自定义正文/单Link兼容。

## 验证

`python -X utf8 tools/tests/test_qq_channel_multidrive.py`：27行为测试，假DB/CLI，不真实发帖。SQLite查询适配不等于MySQL；额外真实READ ONLY两片各百度/夸克/UC/迅雷一条，utf8mb4十六进制字面量解决排序冲突，无DDL。三脚本PowerShell5.1解析、密钥扫描0。锁内真实入口子进程跳过、审计不变；频道自然真实展示待验收。

## 运行基线与部署状态

原任务每日09:00/3篇/间隔300秒/5候选、XML hash不变；“下一次10月16日”已是历史。公共helper后原子替换两个入口，候选hash匹配；初次替换绑定失败恢复旧文件后修复上线。11容器不变，本机/公网200，无DB/模板/计划/账号变更或真实发帖。原安全59/11/0，限定当次授权不关闭风险。

## CLI 依赖与登录恢复

宿主发布优先使用 `QQ_CHANNEL_CLI_PATH`（或 `-CliPath`）显式文件，其次使用 `$env:LOCALAPPDATA/GYing/tools/tencent-channel-cli/tencent-channel-cli.cmd`，最后兼容 PATH/用户npm及原Node目录。nvm切换不会迁移旧Node版本的全局包；独立安装命令为 `npm install -g --prefix "$env:LOCALAPPDATA/GYing/tools/tencent-channel-cli" tencent-channel-cli@1.0.10`。

发现依赖缺失后，先用实际选中的CLI执行 `login status --json` 及只读 `feed get-guild-feeds`；凭证失效时通过 `login --json` / `login poll-token --json` 重新授权，凭证仅存系统密钥链。doctor显示凭证存在不足以证明有效；Windows任务返回0也不足以证明发帖成功，应核对POST success、发布审计与官方结果。恢复原调度后，真实发帖按下一自然轮次验收，未知结果不盲目补发。

## 回退与证据

`E:/gying-tools/releases/qq-channel-multi-drive-20261015` 保存 previous/tools、candidate、release-manifest/activate-result/post-deploy-verification。确认无在途且hash仍匹配后：

```powershell
& 'E:/gying-tools/releases/qq-channel-multi-drive-20261015/Manage-ChannelRelease.ps1' -Mode Rollback
```

同互斥锁、拒未知版本、恢复两个原入口/撤本次helper；保留审计/后来帖子，不恢复旧SQL。核对实际差异并提交回退。
