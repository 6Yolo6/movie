# 百度上传与分享兼容验证

2026-11-09，修复已提交并推送 `e6370cbc25ec3c35bec63a6574aa54591e15cdea`。真实合成文本上传与同一目录分享均已核验；当前为经过完整测试的本地候选，生产仍为原版本，部署门禁失败。

上传一个61字节合成磁力文本，实际发送建目录2次、precreate/block/commit各1次；commit HTTP200/errno0。block HTTP200且摘要匹配，真实回执没有error_code字段，不能把它描述为实测error_code0。新客户端正确解码百度网页混淆MD5；PCS分块摘要仍要求标准明文MD5。历史17584没有保留原响应，具体失败原因无法还原。

同一目录仅创建1次period=0分享并验证1次，均HTTP200/errno0。原runner STOPPED历史保留；随后独立只读对账8 GET、0写入，确认同账号、精确自有活动分享、根目录及唯一共享文件的路径/fsid/大小/解码摘要全部匹配，状态SHARE_VERIFIED_READ_ONLY。分享URL仅保存在私有ACL目录，不进入仓库。没有重传、创建第二个分享、清理探针或重放原UNKNOWN。

真实接口还返回了共享根目录isdir字符串"1"和不带leading 1的分享raw短码。兼容分支仅对/share/list接受literal字符串0/1；owned仍只接受范围受限的整数0/1，file size仍严格long整数。自有记录raw短码必须与record中的正规URL精确对应，通用URL解析不接受raw，也不统一短码大小写。

最终全套1254项，1253通过/1既有Redis跳过、零失败错误；真实MySQL与独立Java17 compile通过。386份源码绑定，405份JAR class/resource一致。独立静态审查PASS、必须修正0；此前lookup-only快照不作为最终源码证据。镜像`sha256:683fa2c73d27aae9a1fe3fb9cfcf26b9b0802486449959612f259da231d188df`，JAR SHA`2a54c376cb6d4703fb758273568f57e4ca34e278d4474a89633de968ee055e3a`。[脱敏证据绑定](validation-summary.json)。

新永久目录分类已实现为/GYing Resource Hub/mv、tv、ac，没有迁移旧目录。原17583 BLOCKED/CHILD_UNKNOWN、17584 UNKNOWN保持，自动补全保持关闭/十分钟一条，生产idle和旧消费者兼容门禁仍失败。成功探针不能关闭这些门禁，也不能复用旧有限上线例外。

QQ与网站百度搜索临时任务与清理候选已完成，详见[新候选](../baidu-search-temp-20261109/README.md)。用户明确选择到期后彻底删除本系统新建的精确临时目录，以释放空间；需独立任务scope、账号/完整文件树证据、回收站精确对象及两阶段删除意图，UNKNOWN只读恢复，不碰永久分类目录、历史任务或当前探针。该选择没有改变生产开关、执行清理或发布群/网站内容。
