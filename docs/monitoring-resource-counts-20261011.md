# 后台监控与资源分类计数部署记录（2026-10-11）

de83b39已部署；[现行API](api.md#后台监控)、[完整旧证据](history/snapshots/20261022/docs/monitoring-resource-counts-20261011.md)。

## 1. 变更内容

API请求与PAGE_VIEW/访客分计，日期/AsiaShanghai/14天补零；状态分POSTED/失败/在途/UNKNOWN，热词Redis回SQL。日志服务端q≤100/size1–100/真实分页/状态白名单，type有则分页、省略兼容旧结构；参数转义、ADMIN。资源按DISK/MAGNET-TORRENT/ONLINE/未知内容计，网盘种子仍TORRENT，0/>99精确显示。

## 2. 测试证据

后端557（5skip/0失败），4真实隔离MySQL；compile、tsc/build/lint7旧warning、Node21；监控16+计数10中英/明暗/360–1440场景在隔离/候选/生产入口通过，API全mock、非管理员登录/写入。

## 3. 镜像与构建

gying-monitoring-{backend,frontend}:20261011a；源码387文件、JAR匹配，endpoint/身份/环境不变，当前版本另见状态。

## 4. 加密检查点

`G:/gying-backups/20261011T124714.996872Z` 八age hash/认证解密、SQL25表；只冻backend/应用回退，非跨服务恢复演练。

## 5. 切换与验收

两应用/仅nginx重载、9其他不变。实际tmdb_movie_969681 SSR：网盘2、磁力/种子126（62磁力+62种子+2归档）与DB吻合。发布193POSTED/12FAILED不变，旧RUNNING3426/434未重置，未真实转存/修复/发帖。

## 6. 回滚

gying-monitoring-rollback-{backend,frontend}:20261011a及受限覆盖保留；按[部署](deployment.md)核新写入/在途再两应用回退、nginx重载，不SQL恢复。完整路径/脚本从旧原文核验，不依赖短ID猜镜像。

## 7. 剩余风险

安全59/11原差距、schema缺两QQ表仍在；真机/真实管理员事务未被mock闭环。
