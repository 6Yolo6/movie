# 查询与异步列表优化

2026-10-12实现、14日SQL续验，20:53随926e61e两服务上线。[生产证据](access-log-client-identity-20261014.md)、[完整原文](history/snapshots/20261022/docs/code-optimization-20261012.md)。

## 交付边界

查询/列表，不改评论路由/DDL/平台行为；初次“未部署”仅原日期，旧安全失败保留。

## 修复内容

### 热门榜与通知页

取消/失效/单飞拒旧响应，分页错误保结果，“全部已读”由当前effect回第一页/末页收敛，每页20。

### 后端查询边界

非关键搜索统计/Redis热词/查看统计失败不影响成功查询，仅固定操作/异常类脱敏；真实查询/通知更新错误仍抛。资源AND完整影片/审核/ACTIVE/未删及括号(NULL或非INVALID)。全部已读单user_id/read_flag=false UPDATE、返行数，空user不SQL，不给通知添加不存在deleted_at。

## 2026-10-12 至 2026-10-13 验证

新增37中修前13失败/3异常；定向86；后端594（585通过/9skip），React15修前全失败、Node37、离线真页面10；tsc/lint/build。fixture不生产，skip不算SQL通过。

## 2026-10-14 真实 MySQL 续验

隔离全量601（600通过/1Redis skip）、compile；QueryBoundary7/Monitoring4真实SQL，守护7/源码312匹配/两临时容器清理。576资源边界矩阵；通知单UPDATE/他人不变/真实事务回滚/两并发0+400/只读1792-25006拒真UPDATE；EXPLAIN用已有user前缀索引/另5000他人不变。不是生产锁等待/负载结论。

运行 `python -X utf8 tools/tests/query_boundaries_mysql.py --run --full-suite`，随机精确fixture/无宿主口/外网/生产挂载/非root/tmpfs/离线依赖；结果 `tmp/query-boundaries-mysql-f98ffe3fc93e`，10:35–10:39；前次测试失败保留不改写。源码/工具补测不改业务。

## 运行态与上线门禁

当日10:43只读入口6+健康5通过、11容器不变；20:53随后获限定业务授权上线。59/11安全失败未关闭；SQL通过不代门禁，实际镜像/检查点/回退以身份报告为准。

## 尚未验证与后续风险

全量未读UPDATE仍可能锁等待；真实账号已读/编辑事务、手机真机与生产规模性能未验收。模拟API/离线页面不替代这些结果。
