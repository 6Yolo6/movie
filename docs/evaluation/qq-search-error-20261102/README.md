# QQ 搜索发送错误回退修复

2026-11-02，Asia/Shanghai。修复仓库 OpenClaw 安装补丁及线上源插件、唯一安全运行时副本，只重启 `openclaw-openclaw-gateway-1`。

## 故障原因

22:11:43 收到群内搜索，22:12:14 插件已命中并准备直接回复；22:12:20 QQ messages 请求出现 `fetch failed`。原 gateway 将整个插件处理过程的异常重新 `msgQueue.enqueue(msg)`，于是搜索消息进入 OpenClaw 模型分支，并因 `openai` 鉴权缺失返回 `missing api key`。

修复前读取的24小时日志窗口内共5次同类回退：4次 QQ 网络发送失败，1次平台拒收消息。后端搜索内部 token 有效；报错来自发送失败后的错误路由。

## 实现与测试

`tools/patch-openclaw-qqbot-gying.ps1` 将原异常回退替换为记录错误后结束处理。未匹配命令和明确 `delegatePrompt` 的原有分流继续保留。精确校验原处理块或已修复处理块，未知上游变化时拒绝安装。兼容 CRLF/LF，重复执行结果一致。

仓库测试17/17通过、0跳过；覆盖搜索发送失败、平台拒收、token获取失败、匹配器异常、旧问题对照、未匹配与明确委托。另对真实插件函数验证13个场景、11项文本提取、管理员白名单、未授权管理命令拒绝及UTF-8进度文案，全部通过。完整安装器演练两次，均与候选hash一致；原自然语言路由测试的一项过时断言已对齐现役行为，业务helper字节保持。

## 部署验收

22:55:51 OpenClaw 正常退出0，22:55:52 启动，22:56:05 健康恢复。受保护的临时容器以原固定镜像、node身份、无网络、只读根文件系统及禁止提权完成两个目标文件的原子替换。保留文件权限，Gateway配置、搜索凭据、白名单和 `slash-commands.js` 字节不变，其余9个运行容器身份/镜像/启动时间/重启计数保持。

- OpenClaw镜像：`sha256:3814fb1f62f9cfc5944de088c5817c68c88b5d721feebe36420b666a90a61ce7`。
- 源和唯一运行时 `gateway.js`：`94922920ec9f653d6976ab95b0dee9501fd55962432ec29d84f2aa21fcc1593d`。
- `slash-commands.js`：`ce0cbaa6019572daf0022d3a8e615172548b6c0a712317c92c94b9af0a4cf3c4`。

部署后从实际运行时导出文件并验证上述场景，网络失败/平台拒收/匹配器异常的模型入队均为0。实际已部署 QQ helper 使用现役认证请求后端搜索“星际穿越”，279毫秒返回694字正常回复，没有 `missing api key` 或HTTP失败；该检查没有调用QQ发送API。

22:57检查 OpenClaw health、后端QQ health、nginx主页均200，nginx的QQ路径404；QQ连接已重建。22:55:52–22:57:02日志没有新插件权限/加载错误、搜索异常或缺key错误。短窗口正常不等于所有后续网络请求成功。

## 备份与回退

检查点：`G:/gying-backups/qq-search-error-20261102T145108.776437Z`。一份age保存源和运行时两个gateway/两个slash文件及Gateway配置。文件大小、SHA-256、完整age认证解密、归档成员hash在内存中验证通过；没有明文凭据归档。

外部部署与回退工具：`E:/gying-tools/releases/qq-search-error-20261102/deploy.py`。回退命令为 `python -X utf8 E:/gying-tools/releases/qq-search-error-20261102/deploy.py rollback`，会校验现役hash/config/身份后正常停止、恢复两个旧gateway文件并验证健康；后续插件/config变动时拒绝覆盖。实际回退未执行，数据库、队列状态和历史消息不参与回退。

本次没有发送测试群消息、转存资源或发布内容。真实群内端到端以及自然网络失败后的表现仍待实际使用观察；QQ网络波动和平台内容拒收仍需按原错误分别处理。本次只阻止这些错误误触发模型鉴权报错。

机器摘要见 [validation-summary.json](validation-summary.json)，非敏感演练与部署材料保留在外部release目录。
