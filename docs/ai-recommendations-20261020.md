# AI 找片、猜你喜欢与上线验收

2026-10-20实现/18:12部署，10月21续验；[当前特征版](catalog-features-search-20261021.md)、[完整旧原文](history/snapshots/20261022/docs/ai-recommendations-20261020.md)。

## 检索与推荐

明确片名从模型最多3项改服务端标题/英文/别名/系列完整分页（默认20、1–30），ACTIVE/未删、LIKE转义。收藏3+90天去重浏览 exp(-days/30)，最多各100，排除已看/收藏；内容/共同收藏/评分/热度与多样性理由，共同收藏至少2独立用户。目录60秒/5万、共收藏最多5万行，是规模上限。详情浏览30分钟SQL去重无唯一键，不承诺exactly-once。本机Vue仅local查询；模型用于前序详情/资源追问。

## 验收证据

后端658（638通过/20skip），隔离MySQL5/5；compile/JAR332文件；真实库只读蜘蛛侠20、三页去重/个人冷各12，鉴权fixture，不是真实账号。前端类型/lint/build（7旧warning）；候选/本机/公网各5组mock页面，真实入口26项与专用桥接认证通过；QQ内部→真实模型队列→存量资源回复通过，审计不变，未发群/转存。Vue两视口、会话5/Vite构建通过。

## 部署与回退

backend `sha256:ab1b5c6abee4cb17e966a4a5e9a479db21df72cd49c00bd5834e1897c9e242ba`、frontend `sha256:cdd6346fcbdba8209fec41aa71dacfa6b06874ff9727f14f23d4bf995d2f53ca`，只两服务，9其他不变，无DDL。材料 `E:/gying-tools/releases/ai-recommendations-20261020` 的rollback-deploy.json；检查点 `G:/gying-backups/20261020T101133.216772Z` 七age认证/hash通过，非灾难恢复。初次环境/挂载顺序误报回退后改按键/目标比较上线。回退审新写入/契约、仅两应用+nginx，不恢复SQL。安全59/11/0原失败未关闭，历史授权不复用。

## 可用于简历的真实实现

完整分页、解释推荐、鉴权队列/回环桥接、隔离SQL/页面、可回退上线；没有训练/向量RAG/CTR/吞吐证据。当前可复现性能与求职措辞从最新报告读取。
