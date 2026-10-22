> 整理前原文快照：2026-10-22。保留当时文字与证据日期；当前结论和操作入口见 [现行导航](../../../../README.md)。仅调整本快照的相对链接，不重新验收或复用历史授权。

# QQ 频道同片多网盘帖子

更新时间：2026-10-15

## 范围与行为

本变更只涉及原 QQ 频道宿主发布链路：`GYing QQ Channel Auto Post` Windows 计划任务及原频道 bridge → `tools/publish-latest-resource-to-qq-channel.ps1` → `tools/publish-qq-channel-feed.ps1`。QQ 群搜索、每日群推荐与独立 `social-publisher` 使用各自链路。

每部影片仍只生成一篇帖子。发帖前读取该影片已审核、活动、未删除、链接状态正常的 DISK 资源，每种网盘选择按 `updated_at DESC, id DESC` 排序的第一条链接；把网盘名称、链接与非空提取码一起列入正文。磁力、种子归档及在线播放不冒充视频网盘资源。

保留自动候选的原有来源规则、调度时间、每轮帖子数、频道目标和代表资源审计字段。多网盘是该影片正文的扩展，不把一部影片拆成多篇，也不重发历史帖子。

已有模板中的 `{{link}}` 展开为完整多网盘列表；没有该占位符时追加一次列表。其他变量和自定义正文保持，调用 `publish-qq-channel-feed.ps1 -Link ...` 的原单链接用法继续支持，不要求覆盖 `sys_config` 模板。

## 验证

候选在独立目录构建，使用假 MySQL 返回和假频道 CLI 检查实际内容文件。验证过程不调用真实发帖，不加载生产登录态，不触发转存。

在仓库根目录执行：

```powershell
python -X utf8 tools/tests/test_qq_channel_multidrive.py
```

2026-10-15：27 项行为测试通过，0 失败、0 跳过（21 项首轮及 6 项补充边界）。覆盖三盘同帖、每盘最新有效链接、同时间按 ID 决胜、大小写/空白 provider、排除失效/未审核/删除/非 DISK、提取码、单 Link/旧模板兼容、无占位符只追加一次、自动与 PENDING 同片去重、PostLogId 共用聚合、中文/换行/引号、空资源及 CLI 失败。

查询行为夹具用 SQLite 执行实际 SELECT，仅适配 MySQL 字符集字面量等差异；不能单凭它证明 MySQL 排序规则兼容。额外以非 root 的 READ ONLY 事务在现有 MySQL 8.0.28 执行候选 helper 的实际查询，两部影片均返回百度/夸克/UC/迅雷四种网盘且各一条。初版 CONVERT 表达式的排序规则冲突已修正为 `_utf8mb4` 十六进制字面量；未修改数据库排序规则。生产抽样只记录资源 ID、provider 和提取码是否存在，不输出 URL/提取码。

三个生产脚本通过 PowerShell 5.1 解析；候选代码密钥扫描 0 命中。没有真实发帖或网盘写入，频道内实际展示留待原计划自然运行验收。

## 运行基线与部署状态

2026-10-15 19:44:42（Asia/Shanghai）已上线。用户在获知现有 11 项安全失败后明确要求“直接上线”，本次只替换原频道三个宿主脚本；代码提交为 `fc5ef04`，与独立已推送候选 `68de6d7` 的树完全一致。运维就绪检查 11 PASS / 1 WARN / 0 FAIL；全局安全扫描仍 59 PASS / 11 FAIL / 0 UNKNOWN，本次授权不关闭安全待办。

`GYing QQ Channel Auto Post` 仍读取 `D:/gying-movie/movie/tools/publish-latest-resource-to-qq-channel.ps1`，每日 09:00、每轮 3 篇、篇间隔 300 秒、候选数 5；下次为 2026-10-16 09:00。计划任务 XML 的部署前后 SHA-256 相同，现有模板含 `{{link}}`，未改目标或调度配置。

切换在原 `Global\GYingQqChannelAutoPost` 互斥锁内执行：先安装公共 helper，再原子替换 feed 与自动发布入口。三个在线脚本的 SHA-256 与通过测试的候选一致，语法解析通过；独立 PowerShell 5.1 子进程调用真实入口，在父进程持锁时确认正常跳过，未进入数据库或外部发布分支。首次原子替换因空备份路径的 PowerShell/.NET 参数绑定失败；核对两个入口仍为旧版后恢复全部基线，改用明确临时备份路径，文件替换验证及随后上线通过。

上线后再次执行实际 helper 生成的 MySQL 只读查询：两部影片均返回百度/夸克/UC/迅雷各一条；配置、频道审计（278 POSTED / 1 FAILED，最后成功 2026-10-15 09:10:10）与本地发布状态文件均未改变。11 个容器的 ID、镜像、启动时间和重启次数不变，本机首页/API 与公网首页均 200。没有受影响的 Docker 服务，未重建容器、修改数据库或触发真实发帖。频道内实际展示仍待原计划自然运行后验收。

## 回退与证据

发布材料在 `E:/gying-tools/releases/qq-channel-multi-drive-20261015`：`release-manifest.json` 固定候选/旧脚本 hash，`previous/tools/` 保存两个原入口，`candidate/` 保存候选；`activate-result.json` 与 `post-deploy-verification.json` 保存脱敏验收。部署前确认工作树中原有 21 个无关改动未变；状态文档仅提交本次新增事实，无关改动继续保留。

若需回退，在确认没有在途发布且当前脚本 hash 仍匹配本次材料后执行：

```powershell
& 'E:/gying-tools/releases/qq-channel-multi-drive-20261015/Manage-ChannelRelease.ps1' -Mode Rollback
```

回退脚本获取同一互斥锁，拒绝未知文件版本，恢复两个原入口并移除本次新增 helper；仅变更代码，保留发布审计、状态文件及后来生成的帖子。完成后需核对入口、记录实际 Git 差异并提交回退，不能通过恢复旧数据库来撤销外部帖子。上线过程中已实测恢复基线及 hash 校验。
