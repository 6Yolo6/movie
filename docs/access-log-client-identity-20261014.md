# 访问日志原始 IP / User-Agent（2026-10-14）

926e61e，2026-10-14 20:53已部署；[API](api.md#后台监控)、[数据库](database.md#5-访问日志客户端身份)、[完整旧证据](history/snapshots/20261022/docs/access-log-client-identity-20261014.md)。

## 范围与当前阶段

backend/frontend及site_access_log两可空列，nginx测试/重载；9其余容器/云端/凭据不变。用户当次明确业务例外，59/11/0安全失败保留。

## 行为与边界

client_ip只信任显式nginx传输对端/32（IPv6/128），其他用直连，不直接信任CF/X-Real-IP或整个Docker网段。重建nginx/网络需重验对端，空配置只记代理。UA≤1024码点、缺头NULL、自报非身份；保留统计hash、不记query/Cookie/body，不反推回填旧值。ADMIN/no-store、IP/UA分页/旧流可搜，手机展开、安全文本。

## 迁移与回退

备份/预检InnoDB/类型→维护身份INSTANT追加→两应用切换；不扩应用DDL。回退保留新列/日志/utf8mb4_bin，不DROP或恢复旧dump，审在途/配置。

## 实现与隔离证据

后端614（613通过/1Redis skip），8身份专项/7监控真实SQL+查询；compile。314后端/71前端源码hash，临时fixture清理；typegen/tsc/lint/build、模型12/守护7、19mock页面（Unicode/IP/UA/注入/手机）。这些不等真实管理员登录。

## 生产交付证据

gying-access-identity-{backend,frontend}:20261014a；JAR318文件匹配，唯一配置增加APP_TRUSTED_PROXIES；20:52:17–20:53:01切换。检查点 `G:/gying-backups/20261014T125228.430856Z` 八age认证/hash；80467旧行原字段hash不变/新字段NULL。真实公网trace IP/UA回读一致、直连伪造头拒绝，边缘伪CF头403；10入口/依赖/SSR通过，20:53–21:01无ERROR。21:06新增506 API行全含身份只是样本，不保证未来缺UA也有值。监控生产19mock场景通过，热门榜通知生产被loopback守护拒绝未绕过，不算完成。材料 `E:/gying-tools/releases/access-identity-20261014` 的deploy/rollback-deploy及旧镜像保留；无真实发帖/转存/历史队列重置。
