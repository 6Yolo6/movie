# 搜索资源游客入口与 GYING 种子隔离上线（2026-10-19）

cbec6b1/20880e7已推送部署；[采集契约](gying-p2p-collection.md)、[完整旧报告](history/snapshots/20261022/docs/resource-search-p2p-deployment-20261019.md)。

## 结果

游客菜单可见、登录提示/返回原搜索，游客不请求API。新账号/dbt下载URL不写资源/任务，公开过滤历史、磁力/自有归档保留；v2磁力ID内部取种子。source SIGTERM/SIGINT排空请求。

## 镜像与服务范围

backend/frontend gying-resource-p2p-*:20261019a；source gying-resource-p2p-source:20261019b；精确全hash归档/外部manifest。显式gying-movie/生产文件/ `E:/gying-tools/releases/resource-search-p2p-20261019/deploy.json`，三服务、nginx重载，原env/挂载/网络/端口/可信peer保持。

## 测试与真实验收

后端632（617通过/15依赖skip），JAR匹配、compile；Python3.12 60含真实SIGTERM排空；前端类型/lint7旧warning/webpack及Docker构建。候选四mock场景，生产本机/公网游客入口与八真实GET通过。gying_tv_vkN9详情36→20（隐藏16账号URL、保16磁力/2归档等）；历史5258账号行不删。发布216POSTED/12FAILED/3UNKNOWN不变，旧测试MySQL精确停止但容器数据保留。21:25:30后三服务无ERROR/Traceback，安全59/11/0；无真实登录/搜索转存/外部帖子/任务重放。

## 备份与回退

`G:/gying-backups/20261019T132109.901502Z` 八age清单/hash/认证、SQL完成，backend/source停而其他写入方原状态，非全服务灾备。同发布目录rollback.json/旧镜像。frontend可独立回退；旧backend不懂v2先暂停相关采集/Worker、核新任务/远端，保任务/资源/文件，不旧dump覆盖或让旧consumer误执行。
