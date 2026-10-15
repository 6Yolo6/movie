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

2026-10-15 只读确认：原计划任务读取 `D:/gying-movie/movie/tools/publish-latest-resource-to-qq-channel.ps1`；自动发布启用，每日 09:00、每轮 3 篇、篇间隔 300 秒、候选数 5，下一次计划为 2026-10-16 09:00（Asia/Shanghai）。现有模板含 `{{link}}`。频道审计 278 条 POSTED、1 条 FAILED；本次未重试或重置历史记录。

当前为独立候选，尚未替换计划任务读取的在线脚本。运维就绪检查 11 PASS / 1 WARN / 0 FAIL；全局安全扫描仍 59 PASS / 11 FAIL / 0 UNKNOWN。按部署规则保留上线门禁，不把历史业务例外当成本次授权。

上线仅需在现有发布互斥锁内备份并原子替换有关宿主脚本，核验内容 hash 与 PowerShell 5.1 解析；没有受影响的 Docker 服务，不执行容器重建。脚本或接口自检不能代表频道内真实帖已验收。回退仅恢复本次脚本备份，保留发布审计、状态文件和后来生成的帖子。
