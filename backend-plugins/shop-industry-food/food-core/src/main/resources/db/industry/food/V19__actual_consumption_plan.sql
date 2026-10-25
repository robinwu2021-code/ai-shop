-- Existing historical BLOCKED projections remain historical facts; new exclusive plans execute real stock.
ALTER TABLE fnb_production_consumption_line MODIFY recipe_revision_no VARCHAR(32) NULL,
 MODIFY theory_qty DECIMAL(24,6) NOT NULL, MODIFY actual_qty DECIMAL(24,6) NULL;
ALTER TABLE fnb_production_consumption ADD reversal_event_no VARCHAR(32) NULL, ADD reversal_hash VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_food_consumption_reversal(entity_no,store_no,reversal_event_no);

ALTER TABLE fnb_service_event ADD source_request_hash VARCHAR(64) NULL;
ALTER TABLE fnb_production_consumption ADD reversal_snapshot JSON NULL;

ALTER TABLE fnb_service_event ADD ready_loss_qty DECIMAL(24,6) NULL COMMENT 'Null legacy LOSS was entirely ready food; new events freeze exact stage quantity';
