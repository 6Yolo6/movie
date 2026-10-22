# API 4xx 占比只读复核（2026-10-18）

[完整原证据](history/snapshots/20261022/docs/api-4xx-investigation-20261018.md) · [当前状态](current-project-status.md)

## 结论

10月18截点10:07:40近24小时657条4xx中655（99.70%）是外部错误评论GET，最后10月17 20:58:34，18日当时0；前后端正确契约一致。另6条5xx（其中3 QQ空值500）独立未闭环，不能沿用前一天零5xx。

## 统计口径与结果

仅event_type=API、READ ONLY一致性快照/+08:00，4xx400–499/5xx500–599；先统计后探针，不屏蔽日志改口径。错误GET /api/comments=405（326次）、/api/movies/{id}/comments=404（329）；另phpinfo404/通知401是不同事件。窗口总数原表保留归档。

## 正确契约与线上验证

GET /api/comments/{relateId}，POST裸/api/comments；源码与生产53JS核查无嵌套评论GET。10:10本机/公网各3GET：正确200且records/total、错误405/404。无评论写入，非PAGE_VIEW混算问题。

## 本次新发现的 5xx

10月17 13:34:12/17/22 GYING recent/trailers/catalog各503（5.1/10.2/15.2秒），仅确认入口失败，未定Cookie/网络原因。10月18 09:26:56/09:27:08/09:27:19 QQ search-reply三NullPointerException500（832/268/506ms），脱敏日志无业务行；10:11:16前无后续该端点请求，不能宣称恢复。需隔离复现/自然结果，不为调查触发转存/消息。

## P2P 关联只读附记

账号种子历史引用与公开URL问题已在[10月19隔离](resource-search-p2p-deployment-20261019.md)处理；不因此修复QQ空值或评论客户端。

## 可复核证据

`G:/gying-tools/restart-20261018` 的readonly-aggregates/refs-and-5xx/5xx-followup/runtime-evidence/comment-route-probes，原17日汇总在restart-20261017；只读、不生产修改。真实身份/原始日志不提交。

## 2026-10-19 续查

21:33左右当日API129、4xx11/5xx0、旧评论错误路径0；包含本轮探针、非最终全天/持续恢复证据。原QQ500/GYING503继续独立待办。
