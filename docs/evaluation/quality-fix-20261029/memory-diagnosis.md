# 2026-10-29 片库查询堆内存故障诊断（只读）

本轮真实 Agent 查询返回 `CATALOG_RESEARCH_UNAVAILABLE`，受限片库查询入口返回 HTTP 500。Movie backend 在 **13:35:02.388 +08:00** 记录 `java.lang.OutOfMemoryError: Java heap space`；后续日志涉及 HTTP Poller、Acceptor、Catalina utility 和 HttpClient selector 线程，影响不能限定为一条 AI 回答。

用户随后要求“先暂缓上线吧，继续其他”。**没有修改 backend heap 或容器限额，没有重启、替换服务，也没有部署 Agent 候选。** 本文只记录诊断和未执行恢复建议，不作为上线记录。脱敏机器记录见 [memory-observations.json](memory-observations.json)。

## 已核实的运行数值

以下数值来自同一轮只读调查的不同采样，不是原子快照；不将后采样的堆使用量当成发生 OOM 那一刻的使用量。

| 项目 | 观测 |
| --- | --- |
| Movie backend | 容器 `23fe261a5021`，现役镜像前缀 `fa06d609e0d0` |
| 实际 JVM 参数 | `-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200` |
| 容器内存 / 内存+交换限额 | 768 MiB / 1536 MiB |
| Docker stats | 763.7 MiB / 768 MiB，99.43%，58 个进程/线程条目 |
| cgroup 分时采样 | `memory.current=805289984` 字节，`memory.swap.current=244121600` 字节；`memory.events.max=19359` |
| 内核 OOM 状态 | `OOMKilled=false`、`restartCount=0`；cgroup `oom=0`、`oom_kill=0`。这不否定 JVM 已发生 heap OOM |
| Java 进程 | RSS 765624 KiB，匿名 RSS 755376 KiB，58 线程 |
| 现有 HotSpot perfdata | Old 330400568 字节、Eden 33554432 字节、Survivor 1714936 字节；合计约 348.7 MiB；Metaspace 单列 105610720 字节 |
| 当前有效片目 | `ACTIVE` 且未软删除 **12,351** 条；两个索引路径的上限均为 50,000 |
| Windows 主机 | 13:42:28 +08:00：总物理内存 15.42 GiB、可用 2.96 GiB |
| Docker/Linux | 引擎总内存 8020353024 字节；另次 `/proc/meminfo` 可用 5557728 KiB |

Windows 与 Docker/Linux 可用内存口径不同，不能相加。**2.96 GiB 只代表该次采样，不是未来维护窗口的余量保证。** 当前片目数比 10 月 25 日留存投影的 11,272 条增加约 9.6%，没有证据表明本次由片目数量骤增单独造成。

检查仅读取指定 Docker 字段、JVM 参数白名单、日志 OOM 行、proc/cgroup 数值和现有 HotSpot perfdata；JRE 没有 `jcmd`，没有安装诊断工具、强制 GC 或转储 heap。计数使用既有 source 容器的受限数据库环境，只执行带 5 秒语句上限的 `SELECT COUNT(*)`，只读事务随后回滚，没有输出凭据、用户数据或资源 URL。

## 源码中明确存在的内存放大路径

| 路径 | 代码事实与影响 |
| --- | --- |
| 两份独立全库快照 | [CatalogAiService](../../../backend/src/main/java/com/gying/movie/service/impl/CatalogAiService.java) 缓存 `Film` 列表和 `FeatureIndex`，按需再建 `CatalogTextIndex`；[CatalogResearchService](../../../backend/src/main/java/com/gying/movie/service/impl/CatalogResearchService.java) 另行缓存完整 `ObjectNode` 元数据、`Film` 列表、两张 ID map 和一个立即构建的 `CatalogTextIndex`。两份快照与文字索引没有共享机制 |
| 冷加载和刷新峰值 | 两个服务各自以 60 秒 TTL 全量读最多 50,000 条。在新数据/新索引构建完成前，旧 cached 仍被持有；读取相同数据也先产生全量临时对象。单个服务的 `synchronized` 能阻止自身同时建两份，但两个服务的锁互不互斥，不能阻止跨服务构建重叠 |
| 不必要的研究索引加载 | Research `dataset()` 立即 `new CatalogTextIndex(films)`；因此纯结构条件查询、详情/澄清和 documents 导出也先加载全文索引，即使该次请求不会调用 lexical recall |
| 变化无关也重建文字索引 | Research 用完整 `docs.equals` 判定变化，Ai 用完整 `Film.equals`；评分、热度和资源数改变也会失效，虽然 BM25 文字输入可能未变 |
| BM25 对象放大 | [CatalogTextIndex](../../../backend/src/main/java/com/gying/movie/service/impl/CatalogTextIndex.java) 同时保存每片词集合和倒排 posting 对象；构造过程中还存在可变 map/list 与不可变副本。词条、字符串、集合槽和每条 posting 的对象开销不能按原始 JSON 字节数估算 |
| 逐请求人物字典 | Research `explicitPeople` 对非空问题每次构造演员/导演字典及别名集合；`resolvePeople` 对六个人物字段每次重建字典，即使字段没有条件也先遍历全库。这些主要是临时分配，会增加刷新期间压力和 GC 工作 |
| 推荐特征成本 | [CatalogRecommendationEngine.FeatureIndex](../../../backend/src/main/java/com/gying/movie/service/impl/CatalogRecommendationEngine.java) 为每片保留字符串键和 `Double` 权重 map；不是固定维度 primitive 数组。`related` 中的静态 `recommend` 还会为候选列表构建局部特征索引 |

10 月 25 日既有公开投影中，简介 UTF-8 JSON 文本合计约 6.66 MB，演员约 2.72 MB、别名约 1.33 MB。这些是旧投影的序列化文本大小，不是当前 Java retained heap，也不能据此为 BM25 或 FeatureIndex 填造内存数值。

当前证据支持“512 MiB heap 与 768 MiB 容器余量不足，代码存在重复快照及构建峰值”，**尚不能定位触发 OOM 的准确分配点**：日志没有可用分配栈，没有取得对象直方图或 heap dump，也未证明当时两套文字索引均已驻留或跨服务刷新正好重叠。没有把这些推断写成已证实泄漏。

## 未执行的恢复与修复候选

临时恢复候选为：保留现役 backend 镜像，`Xms256m / Xmx768m`，容器 `mem_limit=1280m`。相比当前只增加 256 MiB heap 和 512 MiB 容器额度，为非堆和构建峰值留出空间；**这只是恢复起点，未验证足够，也不是长期容量结论**。用户已暂停上线，本轮没有写入运行覆盖、发 TERM、调用 Compose 替换或重建在线缓存。

后续恢复前重新核查主机/WSL 余量与就绪状态，保存现役镜像、有效覆盖及回退材料；正常 TERM 并等待确认退出，超时停止流程，不强杀；仅更新 backend，随后串行验证真实入口与冷加载/后续 TTL 刷新。单纯重启清缓存不能修复上述重复构建路径，短查询通过也不能证明长期稳定。

源码的最小后续方向是：让 Research 的文字索引按需构建，空人物条件跳过 `resolvePeople` 字典构建，避免 documents/澄清因不需要的文字索引耗尽内存。再将文本指纹与评分/资源变化分离，并评估共享不可变语料/索引以及有界、串行刷新。任何旧索引复用都必须配合当前有效元数据过滤，不能因省内存而返回已删除或过期候选。本轮没有实施这些业务代码修改，也没有进行压力测试。
