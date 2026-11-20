# 百度种子补全前端候选

按库内确认的 GYING ID 读取种子，仅归档种子和必要磁力文本至百度。页面显示映射/影视/GYING ID、具体文件、当前动作、归档任务状态和错误类别；历史任务单独保留。

固定候选镜像 `sha256:663b83853b72bbaff753a12c42e6a9a54718643c3cb3d10b95dda1e938f90c54`，buildId `q5wwVUB40SraMiAz1xwR0`。78 源文件、8 测试文件和 375 实际运行产物 SHA 通过；typegen、TS、lint、52 项 React、隔离 Next build 和 8 组中英/明暗/手机桌面 mock 浏览器通过。首个启动探针 fetch failed，后续探针和全部产物验证通过。

候选及助手在 `E:/gying-tools/releases/baidu-torrent-backfill-20261109/frontend-runtime-candidate-123542-10de06a0/`。32 张原截图和日志保留；中文手机浅色、英文桌面深色种子核验卡片已人工查看。

此目录只记录候选，未部署。未执行生产 ADMIN、公网资产、补全、转存或发布验证；生产前端身份在候选 smoke 前后保持。真实只读验收助手已准备并语法校验，需 root 给定新后端/前端镜像和已审提交后执行。助手允许历史父 17580 和已存在的 v2 父，比较前后父 payload/行状态与新旧指针的 SHA；不创建账号、设备或任务，不保存配置。

源码提交后先用 `BindFrontendCommit.py --commit <已审提交> --frontend-image <候选镜像>` 建立提交绑定，再由 root 执行 `admin-verify/VerifyBaiduAdminUi.py`、`VerifyBaiduPublicAssets.py` 和 `VerifyFinalBaiduMetadata.py`。实际验收另存于 `../frontend-admin-verification/`，不能把候选 mock 与生产事实混合。
