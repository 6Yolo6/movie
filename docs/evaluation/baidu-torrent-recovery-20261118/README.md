# 百度种子补全阻塞恢复（2026-11-18）

父任务 17583 在《排球少年 第二季》（W7ea）被子任务 17761 的种子准备失败阻塞，FAILED 3/3。真实下载返回 45,802 字节、末尾额外 LF；严格 bencode 校验原先拒绝这个尾缀。去掉 LF 后的原始 info 字节 BTIH 与源磁力、数据库记录均为 `28e41aa7ae59ba18ff9806fc5b9e3e468841141b`。

采集器现在仅兼容单个 LF 或 CRLF；BTIH 仍取原始 info 字节，上传前移除行尾，SHA-256 和大小按上传字节计算。任意尾部内容、重复换行、非法种子、超限、磁力不匹配、跳转继续拒绝。61 项采集器/有界响应回归在断网、只读容器中通过，复用原镜像的依赖。

## 上线与恢复

- 仅 gying-source 于 13:05:58（Asia/Shanghai）最终上线，镜像 `sha256:f24203be8b93f2d64b0bfeec6069e13a1b072cd03ae667edc98f45f60bb083ed`。当前 Compose 项目及原两份配置保持，仅既有 `resource-search-p2p-20261019/deploy.json` 的 source 镜像指向更新。
- 首次严格比较发现 Docker 空 DNS 数组和 null 的表示差异，自动回退后复核语义相同；最终检查归一化这三项空列表及环境变量排序，其他配置和 HostConfig 保持。backend、frontend 及另七个运行容器代次保持。
- 父任务先通过原 ADMIN 接口暂停，保留 10 分钟/1 条设置和游标。完成本轮 MySQL、来源运行配置和覆盖文件三份 age 检查点、完整认证解密后，以父/子行锁和 payload SHA 双重 CAS，只对 17761 将 max_attempts 从 3 增至 4。attempts、原 FAILED 状态和失败信息保留，并加入 prior failure/时间/镜像/原 payload SHA 审计。
- 13:07:53–13:08:18 手动执行当前一条：17761 SUCCEEDED 4/4，文本 160 B、种子 45,801 B，共 45,961 B，资源 28729 为 BAIDU/TORRENT/ACTIVE/NORMAL/已审核。
- 独立 GET-only 检查使用实际 backend JAR 编译的现有只读核验器：两文件内容 MD5/大小与文件 ID、完整目录集合、自有分享目录与同页账号归属、实际分享 URL 绑定均通过。36 次百度 GET、1 次 source GET，无云写；会话文件和密钥文件保持。文件 SHA 见 [verification.json](verification.json)。
- 13:13:53 恢复 enabled=true、每 10 分钟 1 条。下一自然子任务 18125（BR83）13:14:14–13:14:50 首次成功，四份元数据文件共 255,864 B；其上传检查点和任务完成已验，未再次逐文件执行独立官方核验。父任务累计 processed=103、succeeded=84、skipped=19、failed=2，PENDING/无阻塞，nextRunAt=13:24:50。
- 三个 deferred UNKNOWN 和同片夸克/迅雷两个旧失败任务的五个整行 SHA 保持。本机/公网 W7ea 详情 API 均 200，资源 28729 可见。上线后的有界 source/backend Docker 日志无 ERROR/Exception/OOM，重启计数 0；backend 此窗口 Docker 日志为空，不代表应用文件日志已核验。

## 回退与证据

旧镜像 `sha256:76fe318b24b1c1222a6615918f5aedc83f772357d6ba01ed1e94458d63bdb58a` 保留，并有 `gying-movie-gying-source:rollback-baidu-20261118` 标签。应用回退只更新 source 镜像，先让在途条目完成，保留当前 SQL、任务审计和上传文件；不以旧 SQL 覆盖完成后的新数据。

认证检查点：`G:/gying-backups/baidu-torrent-recovery-20261118/20261118T050311.273793Z`，三份 age 的 manifest/hash 和完整解密认证通过，未执行恢复。本轮外部脚本/细节在 `E:/gying-tools/releases/baidu-torrent-recovery-20261118`。原日常备份配置保持；既有 source 覆盖文件不在原计划路径内，本轮已单独加密认证，未扩充计划覆盖。

[验证摘要](verification.json)。修复源码提交见本目录 Git 历史。未重放历史 UNKNOWN、未下载视频、未发布 GYING/QQ/社交内容或删除云端文件。
