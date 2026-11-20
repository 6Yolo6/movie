# 重复影片合并与TMDB绑定（2026-11-11）

backend65fd377于13:24:08上线，可信TMDB绑定和canonical消费者修复通过；1461项通过/1既有Redis跳过，真实合并SQL、构建及入口通过。恢复检查点 G:/gying-backups/duplicate-tmdb-binding-20261111-20261111T052216.834247Z 七age、post-pin三age完整认证；覆盖/旧镜像 E:/gying-tools/releases/duplicate-tmdb-binding-20261111/。应用回退保留当前SQL/state/key及新增别名/审核历史。

## 数据结论

73组确认候选单次完成66组、380关联、512history、7legacy窄窗更新，收藏删除0；7组运行态/临时清理/身份阻塞和71组排除理由保留。MobLand此前一次合并19关联/21history/8资源，本轮仅首次补canonical两字段tmdb_id=247718、tmdb_type=tv，其他字段/时间/历史保持。最终67详情、67列表、6代表SSR通过；电影原部数/NULL与来源命名空间season0分别处理。旧批次、未写字段尝试和SSR断言失败由后续补证说明，原回执不改写。

## 百度追加验收

用户明确追加identity47–50四手动槽、最多一次真实归档，成功官方对账后才启用并观察一自然轮。47正常SKIP；48单POST原HTTP504/transport UNKNOWN与缺ACK保留，后查新17650 SUCCEEDED/COMPLETE、4文件143163B，已耗唯一手动归档额度，manual3/4未执行。

V5官方GET-only核对同根owner、会话账户前后、逐文件content/fileId/manifest和自有分享目录通过。21:41一次enable、0pause后自然17655首次成功、4文件35236B；21:42:58独立完整after/API/SQL父一致，true/10分钟1条。原三个UNKNOWN及所有官方STOP保留，不补造ACK或重放。nginx百度run读取超时精确330秒单独提交df448c5，普通API30秒与安全限制保持。

| 必要证据 | 入口 |
| --- | --- |
| 合并/字段/阻塞 | [首批结果](batch-1-final-verification.json) · [两字段增量](mobland-binding-v4-committed-readonly-verification.json) · [7阻塞](runtime-blocked-seven-summary.json) |
| 最终实际入口 | [四SSR补齐](entry-ssr-completion-actual-pass.json)，复用已通过67/67/2 |
| 官方/自然闭环 | [V5官方](baidu-owner-page-v5-actual-official-pass.json) · [自然回执](baidu-restore-natural-owner-page-v5-actual-receipt.json) · [最终只读](baidu-workflow-v5-final-readonly.json) |

完整自然备份、临时删除/配额、真实群内/外部发布和安全门禁未被代验。后续父阻塞及11月18日恢复见现行状态，以上数量为11月11日事实。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
