# 11月1日Agent部署尝试与应用回退

截至2026-11-01 08:59（Asia/Shanghai），Movie backend保持已上线的8d65…镜像/JAR d0149…；Agent最终候选曾启动，但部署未通过最终核对，已确认应用回退到旧版01eab8…，当前PID40148。08:55修正助手的Windows提权返回取消，助手未执行；新4条实际回答仍为0。

[机器绑定摘要](deployment-20261101-summary.json) · [82条原因审计与三轮观测](README.md) · [此前仅Movie上线阶段](deployment-summary.md)

## 实际尝试与回退

08:34管理员助手实际执行，新的受管身份和自然空闲门禁通过，旧PID36916正常受管停止。停止后创建三份age检查点 `G:/gying-backups/ai-agent-quality-20261031-20261101T003442.363732Z`；旧JAR、私有配置/当前state/key/token及启动脚本的大小/SHA-256和完整认证解密通过，manifest SHA `1db0133a20316d8efc61f7ce82dcb481193de63c20ff9392bcab5b94c77657b6`。这是独立停止后检查点，不宣称跨服务原子快照。

固定候选JAR `1f9d9628fbe2cdbb50190c12fcda60ae9870daeb1563caaf84ecef5ddf09f07d`、源码提交 `e19b6b16f4f9df07593da88b221017e8925c2bca` 经原135项测试和绑定核对。候选PID35532/instance4d271681…于08:35受管ready、宿主hostInJob=false；随后最终运行态状态文件核对抛出PermissionError，不能计部署成功。

候选正常受管停止后，08:35–08:36应用回退已确认：旧JAR `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`，instance `0ad13b71-f4a9-4ff0-82d4-073a13ee5189`，PID40148，startedAt `2026-11-01T00:35:47.2818621Z`；回退宿主hostInJob=false，Agent/Ollama/Router/本机frontend健康均200。保留当前data/state/key/config/token/index，未恢复旧SQL/state、重放任务或外发。

## 修正助手及剩余阻断

旧回执未记录PermissionError的具体文件名；结合ready后调用顺序和独立Java文件锁复现实验，诊断指向正在运行的 `bridge.bin.lock` 内容读取。修正只在运行态对这一精确相对路径校验大小为0并跳过内容读取；停止态检查点仍读取全部文件，bridge.bin和其他运行态文件仍校验，任意其他PermissionError不忽略。保留路径/符号链接限制与正常停止、加密备份、独立启动和应用回退门禁。

新助手79dc521b…/wrapper916126d4…绑定已确认回退的身份及前序回执；7项离线检查、独立静态审查a4d3…通过。300秒仍是停止前busy重试许可和接受空闲结果的截止时间；在途有界只读IO可延长返回，但截止后成功不会获准停机。08:55:05 Windows返回“操作已被用户取消”，未取得管理员令牌、未执行修正助手、未发新停止请求。静态与离线验证不证明新部署成功。

08:59普通权限现场读取确认回退记录、PID40148存在、5000监听属于该PID且health200，并核对恢复后的旧runtime文件hash；这不是新的提权argv或OS创建时间证明。Movie容器77135385…仍为8d65…/d0149…，restart0、OOMfalse、有效配置与内存参数保持；其他12容器及本机Ollama/Router/frontend记录保持。回退后快照与readiness均exit0，11通过/1原QQschema警告/0失败；09:00最终快照已完成并绑定。

## 回答与质量边界

新4条Agent实际回答未执行，新增质量评分0；网站USER15轮、生产跨轮继承和缺分显示仍未验。82条历史原因已审计，但不称全部修复；原三轮隔离15轮观测、回答及评分保持，不能据重复开发观测计算独立质量提升。主题、作者关系、早期列表引用和柔性偏好不足继续保留，新增真人评分0，11项安全FAIL未关闭。

下一步仅Agent阶段：取得Windows管理员令牌，重新核当前回退身份、自然空闲及新的停止后加密检查点，固定候选全部部署门禁通过后才执行4条独立回答与模型辅助复核。应用回退保留当前状态，不重放历史任务。

新证据另行追加；此前deployment-summary、原9个JSON、三轮回答和08点前外部stage绑定不覆盖。完整路径/SHA-256与验证范围见机器摘要。

交付绑定校验通过：固定源码/JAR、原9份JSON和冻结材料保持，14份直接外部证据以及新检查点三份密文的实际大小/SHA与认证记录匹配。此前阶段JSON和交付回执保持原字节；当前工作树秘密扫描0命中，132个相对Markdown文件链接可解析，无关10文件保留。
