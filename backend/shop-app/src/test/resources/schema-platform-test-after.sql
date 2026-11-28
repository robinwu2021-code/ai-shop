-- 【自动生成，勿手改】由 backend/scripts/gen-platform-test-schema.py 生成。
-- 挂在旧表上的过渡触发器（旧测试还按旧表写，同步到新结构），在 schema-test.sql 之后执行。

CREATE TRIGGER IF NOT EXISTS mch_store_role_to_grant AFTER INSERT, UPDATE, DELETE ON mch_store_role FOR EACH ROW CALL "ai.neargo.shop.support.StoreRoleToGrantTrigger";
