# GYING 手动资源补齐部署验收

验收日期：2026-10-23，Asia/Shanghai。功能提交 `2b1809b` 已推送 `codex/security`；21:22–21:23仅切换生产 backend/frontend，入口与页面验收截至21:25。本机 nvm Node 和受影响前端容器运行时同步完成安全更新。

“确保资源发布”与按影片/资源 ID 的“修复并补齐”共用 `ensureMovieResource`。每个影片先同步磁力/公开种子元数据，再分别完成夸克与迅雷视频资源；同一影片的多个资源 ID 只执行一次。既有分享只有检查确认有效才跳过，确认失效则优先复用已保存目录、更新原记录及上游分享；检查器无法确定时返回 `CHECK_REQUIRED`，保留既有状态，停止该盘继续转存/发布。缺少候选及部分失败分别报告，不计为批量完整成功。

该统一入口适用于 GYING 页面的 ensure 与 repair-by-ids。既有全账号批量失效修复、周榜入口仍使用各自范围和调度策略。未修改 SQL 架构或自动计划；P2P 同步沿用既有幂等存储及小文件归档链路。页面正常/未知计数使用后端返回值，逐资源结果保留分享状态与修复结果。

## 测试与构建

- 独立 MySQL、无网络出口/宿主端口/生产配置挂载：706项，705通过、1项Redis依赖跳过、0失败/错误；工作流58/58、查询MySQL7/7、监控MySQL7/7。独立compile通过，fixture已清理。
- 前端tsc及页面ESLint通过。初次Windows内存不足后，隔离完整webpack构建在Node20及新Node24.22.1均通过；仅构建暂存配置增加单CPU限制，79个前端文件与功能提交逐项核对（Git换行归一化）。未修改仓库构建配置。
- 后端JAR SHA-256 `aa34016a48e8aedfbf9fffa7e1deec9e462d051928d19977c97edbb8bdf90490`；344个类/资源匹配已测试产物，生产JAR一致。
- 候选和线上HTML/静态文件各完成中文1440px明色、中文390px暗色、英文390px明色三组Headless Edge验收。模拟API的按ID修复请求与正常1/未知1计数通过，无pageerror；模拟API不证明真实账号转存成功，视口不等于真机。

## 部署与恢复

| 服务 | 本次实际镜像 |
| --- | --- |
| backend | `sha256:89f127f9e023c966d02422202ce9cdf18755afe046b5203b2cef3ea33de386f3` |
| frontend | `sha256:a40e7cd3f750a63994b5036daf7a97e09c6ebd5a9a8638612084f09e23c077e4`，运行时Node22.23.4 |

本次有效Compose覆盖为 `E:/gying-tools/releases/gying-manual-completion-20261023/deploy.json`，显式项目 `gying-movie` 与 `docker-compose.prod.yml`。环境、挂载、端口、网络、身份及其他有效配置摘要保持原基线，AI私有key卷保留；仅frontend镜像内Node二进制补丁及应用包变化。

停机前新业务/宿主发布任务均空闲，两侧AI密文认证通过、active=0、bridgeEmpty=true；仅GYING后端优雅停止后创建检查点 `G:/gying-backups/20261023T132303.117468Z`。11份age产物包括SQL、配置、后端数据/key、两个发布状态卷和运行inspect，全部哈希/完整认证通过。它是应用回退检查点，不代表异机灾难恢复验收。

旧backend/frontend镜像保留，原JAR/server.js及 `rollback-deploy.json` 在上述外部release目录。应用回退仅恢复旧镜像和原配置，不自动恢复SQL、不覆盖新业务数据。未触发回退。

26项真实本机/公网页面与API/拒绝访问检查、3项内部依赖读取通过；源站管理API401，公网管理页/API由Cloudflare Access拦截，公网内部API404。95个线上前端静态文件与完整构建一致。其余9个容器身份、镜像、启动时间、重启计数不变；调度配置及转存/发布审计不变。21:22–21:24四应用日志ERROR/Exception均0，仅证明该窗口。

## Node安全升级

本机原Node20.17.0受官方披露漏洞影响，例如[2025年7月公告](https://nodejs.org/en/blog/vulnerability/july-2025-security-releases)的Windows路径遍历CVE-2025-27210（high，20.19.4修复），以及[2025年1月公告](https://nodejs.org/en/blog/vulnerability/january-2025-security-releases)的Windows路径遍历等。[官方版本状态](https://nodejs.org/en/about/previous-releases)显示Node20已EOL；不能以20.x旧安全补丁作为长期升级目标。

按[2026年10月21日安全公告](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases)选择当前LTS24.22.1，包含Brotli解码器越界写/RCE等本月补丁。通过本机nvm1.1.12安装并切换，`node --version`=v24.22.1、npm11.19.1；官方ZIP SHA-256及解压node.exe与安装文件一致。旧20.17.0/16.20.2保留用于回退，活跃链接为 `D:/nvm/nodejs`。

原Node20的本机AI frontend与Ollama启动器已通过生命周期确认优雅停止，再用24.22.1启动；Java后端实例保持不变。前端首次遇瞬时EACCES，未提供服务的失败启动器经身份/独立控制台核验后正常中断并重试，未强制终止。两个新实例生命周期、前端3000/Ollama11434/Java5000真实健康检查通过；本机UI测试5/5及GYING完整前端构建通过。未重新执行模型质量评测。

生产frontend原Node22.23.2同步补到公告修复版22.23.4。取同Alpine3.24官方镜像中的Node二进制，固定镜像digest `sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914`；保留既有Linux依赖、非root身份，隔离SSR及部署后真实SSR通过。本机nvm不控制Docker内其他服务运行时，其他容器未改。

## 保留边界

安全复检59 PASS / 11 FAIL / 0 UNKNOWN，G01–G11仍保留。最初按门禁停止，本次用户在获知失败后明确“部署”，仅用于本候选的例外授权；不代表风险关闭或以后部署授权。未做真实网盘转存、上游发布、群消息及真实登录业务API写入；真实双盘分享与上游发布仍需明确影片/资源范围。

外部完整证据：`E:/gying-tools/releases/gying-manual-completion-20261023/`；可随仓库交接的[机器摘要](evaluation/gying-manual-completion-20261023/validation-summary.json)。
