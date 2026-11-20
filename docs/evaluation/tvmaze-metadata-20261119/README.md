# TVmaze剧集增量接入（2026-11-19）

使用/updates/shows?since=day变更ID/时间戳，漏跑用week/month重叠窗口；已绑定优先，未知候选最多100条/7天过期、单版本3次失败。详情预算/候选/重试沿用既有任务表，无SQL或迁移；当次24小时10次，后续改[6小时10次](../tvmaze-frequency-20261119/README.md)。

按精确IMDb/TVDB→TMDB剧集ID、年份/第一季身份关联，保留中文/人工字段，只补空值或未被人工修改的TVmaze字段。来源ID/版本/URL/许可持久保存，公开TVmaze与CC BY-SA 4.0署名；仅剧集元数据，不排视频转存。

backend44a74bb1…21:32:12、frontendbb21744b…21:43:15上线，Worker/TVmaze开启，其他来源保持。156后端/11前端、构建/模拟API两视口/隔离Linux/本机公网入口通过。首自然18190于21:37:14成功：10尝试、9精确关联、1候选、0失败，90待预算；9身份/上游版本/许可独立只读通过。

首次前端500来自Webpack依赖裁剪，已应用回退后补真实traced运行模块、隔离验证再上线，未重复重启backend。旧镜像/覆盖 E:/gying-tools/releases/tvmaze-metadata-20261119/；回退关闭TVmaze并等空闲、同步host pin，保留身份/检查点/数据。真实版本变动、后续冷却/积压未代验，不覆盖全球全部日变更；Wikidata/IMDb/Trakt未接入。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
