# 百度种子阻塞恢复（2026-11-18）

17761 FAILED3/3阻塞父17583，下载种子末尾单个LF被严格bencode拒绝。source仅兼容单个LF/CRLF尾缀，BTIH仍取原info字节；上传前去尾并按实际上传字节核SHA/大小，其余非法/超限/错磁力继续拒绝。61回归通过。

仅source于13:05:58上线 sha256:f24203be8b93f2d64b0bfeec6069e13a1b072cd03ae667edc98f45f60bb083ed。暂停父并认证备份后，用父/子行锁及payload CAS仅给17761追加一次额度、保留原失败审计；13:08成功4/4，两文件45,961B、资源28729 NORMAL。官方GET独立核content/fileId/目录/账号及自有分享通过。[验证](verification.json)。

13:13恢复true/10分钟1条，首自然18125于13:14:50成功、四文件255,864B；父103处理/84成功/19跳过/2历史失败、PENDING无阻塞。自然子仅核任务/上传检查点，未再次逐文件官方核验。三个deferred UNKNOWN及同片两旧失败保持，本机/公网详情通过。

旧source sha256:76fe318b24b1c1222a6615918f5aedc83f772357d6ba01ed1e94458d63bdb58a，认证三age G:/gying-backups/baidu-torrent-recovery-20261118/20261118T050311.273793Z；覆盖/原失败及完整证据 E:/gying-tools/releases/baidu-torrent-recovery-20261118/。回退先等在途完成，仅恢复source镜像，保留当前SQL/失败审计/上传文件；旧覆盖不在原日常计划，本轮单独认证，未扩充计划或执行恢复。未重放UNKNOWN、下载视频/发帖/云端删除。

[现役状态](../../current-project-status.md) · [未决事项](../../history/issues.md) · [原始全过程/旧SHA表](../../history/README.md#git-history)
