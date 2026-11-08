# 网盘工作流配置

开发分支：`codex/cloud-drive-workflows`。本轮先统一夸克、迅雷配置，百度为待接入入口。部署状态和验收证据见[本轮交付](evaluation/cloud-drive-workflows-20261108/README.md)。

## 页面与阶段

后台 `/admin/resource-hub` 的“网盘工作流”页签按网盘独立保存，不覆盖元数据采集范围或周榜草稿。

| 配置 | 作用 |
| --- | --- |
| 发现资源 | 控制 GYING / PanSou 为该网盘发现候选；关闭不把影片改成预告 |
| 转存资源 | 控制该网盘的新转存任务、手动执行和重试；已有分享可以继续入库 |
| 生成自有分享 | 控制新分享和失效分享修复；自动网盘工作流须有自有分享才能入库，已有分享仍可使用 |
| 发布 GYING | 控制该网盘向 GYING 新发布或更新；本库入库独立 |
| 保存目录 | 绝对网盘路径，1–300 字符，禁止反斜杠、控制字符和 `..` 路径段；不移动既有任务目录 |
| 手动批次上限 | 1–20；保存不自动提交任务，需点击“执行一批转存” |

发现不依赖转存凭据。凭据标签只表示配置存在，不代表账号有效或网盘连接已验证。迅雷令牌更新沿用独立表单、不回显、留空保持当前值；本轮不新增百度凭据存储。

普通 Worker 继续只处理元数据 / P2P，不恢复旧发现、转存、发布或定时重试队列。三个周榜沿用独立计划，并在选择候选时检查网盘阶段开关。已有上游夸克追更任务是独立流程，这些开关不会批量修改其周期或远端文件。

## 配置 API

ADMIN 读取 `GET /api/admin/resource-hub/config`，响应新增 `driveWorkflows` 列表。`provider` 为 `QUARK / XUNLEI / BAIDU`，`supported`、`status`、`credentialConfigured` 为只读状态；百度为 `supported=false / PLANNED`。

通过现有 `PUT /api/admin/resource-hub/config` 仅更新一个网盘：

```json
{
  "driveWorkflows": [{
    "provider": "QUARK",
    "discoveryEnabled": true,
    "transferEnabled": true,
    "gyingPublishEnabled": true,
    "shareEnabled": true,
    "savePath": "/GYing Resource Hub",
    "batchLimit": 5
  }]
}
```

未提供或为 `null` 的可写字段保持现值。`supported`、`status`、`credentialConfigured` 提交时忽略。请求内的百度、未知网盘、重复网盘、未知字段、非布尔开关和非整数批次拒绝；路径或批次非法返回 400，在保存前验证完整列表。

可写字段按 `resource.hub.drive.<quark|xunlei>.<discovery_enabled|transfer_enabled|gying_publish_enabled|share_enabled|save_path|batch_limit>` 分别存入已有 `sys_config`，不迁移表、不存令牌。缺键沿用启动配置；旧 `workerQuarkLimit` / `resource.hub.worker.quark_limit` 与新夸克批次互相兼容。读取不插入新网盘键。

手动 `POST /api/admin/resource-hub/transfers/run` 接受 `{ "provider": "QUARK", "limit": 5 }`，`limit` 可省略以使用已保存上限。百度、未知网盘、额外或重复字段、非整数 / 越界 limit 返回 400；Resource Hub 或对应转存关闭返回 409。旧夸克接口保留并同样受运行时开关约束。

## 百度后续接入

手动资源编辑原已支持百度链接，本次占位不代表自动转存可用。正式接入需补百度账号授权与受限凭据保存、链接与提取码识别、转存及目录恢复、生成和验证自有分享、provider 专属任务与重试、GYING 网盘类型映射，并用隔离测试覆盖发现到发布。实现完整后再将目录中的百度能力从 `PLANNED` 改为可用；禁止默认落入夸克分支或直接发布第三方来源 URL。

## 回退

本轮没有数据迁移、旧任务重置或历史删除。旧应用会忽略新网盘阶段开关，冻结的回退助手先读取当前 SQL，并在候选写入者正常停止后再次检查。任何已关闭或非法阶段、SQL 读取失败都会阻止旧后端启动；此时保持或恢复固定候选后端，保留当前 SQL、任务、状态和密钥。兼容时才使用原固定镜像与运行覆盖进行应用回退，不用旧 SQL 覆盖新配置，也不重放队列。真实转存和 GYING 发布不作为默认健康检查。
