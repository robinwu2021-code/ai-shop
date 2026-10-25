-- Preserve the canonical 64-character merchant/store/order identities owned by V1.
-- This additive upgrade changes no identifier values, delivery leases, epochs or financial facts.
ALTER TABLE prn_device MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_device MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_template MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_template MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_route MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_route MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_job MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_job MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_attempt MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_attempt MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_agent MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_agent MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_printer MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_printer MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_credential_version MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_printer_runtime MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_template_catalog MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_template_catalog MODIFY COLUMN store_no VARCHAR(64) NULL;

ALTER TABLE prn_template_version MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_route_catalog MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_route_catalog MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_route_version MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_scene_policy MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_scene_policy MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_policy_version MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_request MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_request MODIFY COLUMN store_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_request MODIFY COLUMN order_no VARCHAR(64) NULL;
ALTER TABLE prn_request MODIFY COLUMN sub_order_no VARCHAR(64) NULL;

ALTER TABLE prn_batch MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_batch MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_receipt MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_operation MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_operation MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_incident MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_incident MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_store_stream MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_store_stream MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_action_log MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_action_log MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_reconcile_cursor MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_reconcile_cursor MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_simulation_run MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_simulation_run MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_export_task MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE prn_export_task MODIFY COLUMN store_no VARCHAR(64) NOT NULL;

ALTER TABLE ord_print_fact MODIFY COLUMN entity_no VARCHAR(64) NOT NULL;
ALTER TABLE ord_print_fact MODIFY COLUMN store_no VARCHAR(64) NOT NULL;
ALTER TABLE ord_print_fact MODIFY COLUMN order_no VARCHAR(64) NOT NULL;
ALTER TABLE ord_print_fact MODIFY COLUMN sub_order_no VARCHAR(64) NOT NULL;

ALTER TABLE prn_template_catalog MODIFY COLUMN scope_key VARCHAR(80) NOT NULL COMMENT 'PLATFORM / ENTITY:<no> / STORE:<no>，不可为空';

-- Merchant operation identities reference V1 user_no / mch_account_no (64), not generated print keys.
ALTER TABLE prn_device MODIFY COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE prn_device MODIFY COLUMN updated_by VARCHAR(64) NULL;
ALTER TABLE prn_template MODIFY COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE prn_template MODIFY COLUMN updated_by VARCHAR(64) NULL;
ALTER TABLE prn_route MODIFY COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE prn_route MODIFY COLUMN updated_by VARCHAR(64) NULL;
ALTER TABLE prn_job MODIFY COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE prn_job MODIFY COLUMN updated_by VARCHAR(64) NULL;
ALTER TABLE prn_attempt MODIFY COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE prn_attempt MODIFY COLUMN updated_by VARCHAR(64) NULL;
ALTER TABLE prn_job MODIFY COLUMN requested_by VARCHAR(64) NOT NULL;
ALTER TABLE prn_device MODIFY COLUMN revoked_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
