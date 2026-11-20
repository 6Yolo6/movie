-- 人工重复影片合并：应用账号的实时只读保护元数据入口。
-- 必须由经过确认的维护身份在目标应用数据库中创建；该身份需能读取全部相关触发器。
-- 普通 CREATE，不覆盖已有同名 routine；已有对象须先逐字核验定义、安全属性及 definer。
-- 零参数、固定 21 张表，返回这些表的所有触发器，不筛选预期名称或 BEFORE 事件。
-- 应用启动不会自动执行本文件；本文件不改变表、触发器、数据或任何账户授权。
-- DBA 另行审查授予应用账号：仅本 procedure 的 EXECUTE，以及全局只读 SHOW_ROUTINE。
-- SHOW_ROUTINE 会让应用读取其他数据库的 routine 定义；不能限定为单个 routine。
-- 不授予应用 TRIGGER、CREATE/ALTER/DROP ROUTINE、表/视图 DDL、GRANT 或额外 DML 权限。
-- 本文件默认使用创建者为 definer；必须选用稳定维护身份并保留其最小元数据读取权限。
-- routine DDL 维护必须停止新的合并请求并等待在途事务结束；校验与 CALL 不是原子的 DDL 锁。
CREATE PROCEDURE movie_merge_guard_metadata()
READS SQL DATA
SQL SECURITY DEFINER
SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN ('baidu_temporary_cleanup_job','baidu_transfer_task','comment','movie_duplicate_review','movie_merge_alias','movie_merge_favorite_history','movie_merge_reference_history','movie_metadata','movie_source_identity','qq_bot_search_log','qq_channel_post_log','qq_transfer_cleanup_job','quark_transfer_task','resource_discovery_result','resource_hub_task','resource_link','resource_operation_log','social_post_log','user_favorite','user_notification','xunlei_transfer_task') ORDER BY TRIGGER_NAME;
