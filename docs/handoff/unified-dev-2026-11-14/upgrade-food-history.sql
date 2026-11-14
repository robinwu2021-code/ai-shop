-- 已跑过餐饮线（codex/food-next01 及其上的 PR #16–#18）平台迁移的库，升级到统一开发分支前执行一次（root）。
-- 只改 flyway_schema_history 的版本号与脚本名；迁移内容未变，校验和不变。
UPDATE flyway_schema_history SET version='668', script='V668__service_execution_start_ack.sql' WHERE version='649' AND script='V649__service_execution_start_ack.sql';
UPDATE flyway_schema_history SET version='669', script='V669__service_execution_completion.sql' WHERE version='651' AND script='V651__service_execution_completion.sql';
UPDATE flyway_schema_history SET version='670', script='V670__service_work_order_task_claim.sql' WHERE version='654' AND script='V654__service_work_order_task_claim.sql';
UPDATE flyway_schema_history SET version='671', script='V671__service_work_order_claim_evidence.sql' WHERE version='657' AND script='V657__service_work_order_claim_evidence.sql';
UPDATE flyway_schema_history SET version='672', script='V672__retail_stock_replenishment_admission.sql' WHERE version='659' AND script='V659__retail_stock_replenishment_admission.sql';
UPDATE flyway_schema_history SET version='673', script='V673__retail_stock_replenishment_effects.sql' WHERE version='660' AND script='V660__retail_stock_replenishment_effects.sql';
UPDATE flyway_schema_history SET version='674', script='V674__postpay_receipt.sql' WHERE version='665' AND script='V665__postpay_receipt.sql';
-- 之后应用以 --spring.flyway.out-of-order=true 启动一次，补跑主线 V648–V667 中缺的迁移；补完即可去掉该参数。
