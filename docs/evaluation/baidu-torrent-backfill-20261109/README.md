# 百度按 GYING ID 补全种子

本轮修正慢速补全范围：直接使用库内已确认的 GYING 来源 ID，读取种子并归档到百度，归档后仅回写本库 TORRENT 资源。视频分享、历史发现候选和片名搜索不参与，补全不会发布到 GYING 网站。

2026-11-09 13:54:51（Asia/Shanghai）已仅 backend/frontend 上线，固定代码 `b55080d8c0449f6dc6852beff0356d7350cce570`。实际镜像/JAR、Compose 16/10 层、375 份前端产物与 11 个无关容器保持通过；真实 ADMIN 八场景、配置/父任务保持和 194 份公网/本机资源 SHA 核对通过。详见[部署验收](deployment/README.md)。

14:24 的唯一单影片实跑按优选 GYING 身份 41，选中 1080P/4K 两条来源资源，在已暂停的新补全父 17583 下建立专属 P2P 子 17584。首个磁力文本上传返回不确定结果，子任务为 UNKNOWN，父任务为 BLOCKED/CHILD_UNKNOWN；没有保存上传文件 ID 或分享提交意图，未验证归档成功。14:29 的百度官方只读目录检查确认同一专用账号、加密会话保持、目标目录中 0 个文件；目录为空不能排除不可见的上传临时块，不能据此重放请求。14:32 只读续查仍为同一阻塞状态。详见[实际结果](torrent-acceptance/summary.json)、[原单次结果](torrent-acceptance/result.json)与[只读目录](torrent-acceptance/remote-folder.json)。

自动补全保持关闭、十分钟一条；启用助手仅完成离线验证，未执行。新 v2 使用独立指针 `resource.hub.baidu.torrent-backfill.task-id`。旧指针、父任务 17580 和百度视频转存子任务 1 原样保留；旧父于 12:34:28 精确暂停，旧子在读取旧分享时遇到 SOURCE_INVALID，未提交转存或分享。本轮没有重放这些旧任务，也没有重放新 UNKNOWN。

后端覆盖固定来源身份高水位、同片优选身份、手动资源保护、专属子任务、并发领取与短事务检查点。登录/权限问题暂停；上传或分享结果未知时保留检查点。最终隔离全套 1,204 项中 1,203 通过、1 项既有 Redis 跳过，零失败或错误；真实 MySQL 必需测试和独立 Java17 compile 通过。385 份源文件绑定，两临时容器已清理。上传适配器 14、P2P 44、补全父任务 19、补全 SQL 19、GYING 78 项均通过；见[最终报告](backend-final/result.json)。这些测试不能代替本次真实上传成功。

前端 52 项 React、TS/lint/build 和八组中英、明暗、手机桌面 mock 浏览器通过，78 构建源文件、8 测试文件、375 实际产物绑定，见[固定候选](frontend-candidate-123542-10de06a0/README.md)。实跑前验收脚本漏处理生产 SQL JSON 对象，原 TypeError 在发送前被保留；仅仓库外助手修正对象/字符串兼容与 canonical 摘要，20 Python/5 Node、独审 must-fix 0 及修复后真实只读检查通过。业务源码与四份证据 pin 保持。启用/观测助手另有 27/5 项离线检查，但没有实际启用调度。

发布目录为 `E:/gying-tools/releases/baidu-torrent-backfill-20261109/`。停止窗口七份 age 与 pin 后三份 age 完整认证/内容/会话权限及原 XML/ACL 独立闭环通过，日常备份新增覆盖 CAS/dry-run 38 项通过，自然完整备份未验。回退保留当前 SQL、会话、密钥、父任务和 P2P 意图，禁止直接启动不识别新状态的旧消费者。

后续上传保护与 mv/tv/ac 分类修复已完成测试/固定候选，尚未部署或真实上传，见[新候选](../baidu-upload-fix-20261109/README.md)。当前剩余工作是确认未知上传及其接口原因；现有记录只保留 BaiduActionException 类别，未保存原接口错误码或逐文件上传票据。确认前不启用自动补全、不重建或重放 UNKNOWN，不把 HTTP 200、来源采集成功或排队当作百度归档成功。视频转存及 GYING 网站发布仍未测试。

所有历史失败原件保留，包括前端首次启动探针、运维脚本首次语法检查、后端早先编译/断言失败、第一次部署 dry-run 在途阻断、post attempt01 原因 UNKNOWN 与验收脚本的 native JSON TypeError。新路径通过不会覆盖旧失败。安全 58 PASS / 11 FAIL / 1 UNKNOWN 及 G01–G11 未关闭；原两项摘要误报以独立客观证明记录，扫描器与原报告不改。
