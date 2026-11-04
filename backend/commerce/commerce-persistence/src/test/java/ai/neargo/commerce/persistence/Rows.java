package ai.neargo.commerce.persistence;

final class Rows {
    static String sys_business_type(String tenant) {
        return "INSERT INTO `sys_business_type` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`business_type_code`,`name`,`enabled`,`sort`,`plan_catalog_version`,`remark`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,0,0,NULL)"
                .formatted(tenant);
    }

    static String sys_business_end(String tenant) {
        return "INSERT INTO `sys_business_end` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`end_code`,`entitlement_code`,`name`,`scope`,`allowed_business_types`,`release_status`,`sale_enabled`,`bootstrap_required`,`enabled`,`route_policy_ref`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','{}','x',0,0,0,NULL)"
                .formatted(tenant);
    }

    static String sys_industry_plan(String tenant) {
        return "INSERT INTO `sys_industry_plan` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_no`,`business_type_code`,`tier_code`,`rank`,`current_version_no`,`sale_status`,`sort`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,NULL,'x',0)"
                .formatted(tenant);
    }

    static String sys_industry_plan_version(String tenant) {
        return "INSERT INTO `sys_industry_plan_version` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_version_no`,`plan_no`,`revision`,`display_name`,`summary`,`status`,`policy_version`,`published_at`,`published_by`,`change_note`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,'x',NULL,'x','x',NULL,NULL,NULL)"
                .formatted(tenant);
    }

    static String sys_plan_version_end(String tenant) {
        return "INSERT INTO `sys_plan_version_end` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_version_no`,`end_code`,`entitlement_code`,`sort`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0)"
                .formatted(tenant);
    }

    static String mall_product(String tenant) {
        return "INSERT INTO `mall_product` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`product_no`,`category_code`,`product_type`,`name`,`description`,`media`,`allowed_business_types`,`sale_status`,`sort`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x',NULL,NULL,'{}','x',0)"
                .formatted(tenant);
    }

    static String mall_sku(String tenant) {
        return "INSERT INTO `mall_sku` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`sku_no`,`product_no`,`sku_name`,`current_version_no`,`sale_status`,`max_per_order`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',NULL,'x',0)"
                .formatted(tenant);
    }

    static String mall_sku_version(String tenant) {
        return "INSERT INTO `mall_sku_version` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`sku_version_no`,`sku_no`,`revision`,`status`,`currency`,`list_price_minor`,`sale_price_minor`,`allowed_business_types`,`refund_policy_snapshot`,`published_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,'x','x',0,0,'{}','{}',NULL)"
                .formatted(tenant);
    }

    static String mall_sku_component(String tenant) {
        return "INSERT INTO `mall_sku_component` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`component_no`,`sku_version_no`,`component_seq`,`component_type`,`plan_version_no`,`entitlement_code`,`hardware_sku_no`,`period_days`,`unit_count`,`allocation_weight`,`is_gift`,`activation_policy`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,'x',NULL,NULL,NULL,NULL,0,0,0,'x')"
                .formatted(tenant);
    }

    static String mall_quote(String tenant) {
        return "INSERT INTO `mall_quote` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`quote_no`,`store_no`,`entity_no`,`buyer_user_no`,`intent`,`business_type_code`,`currency`,`valuation_at`,`expires_at`,`account_version`,`credit_account_version`,`pricing_policy_version`,`stacking_policy_version`,`request_hash`,`line_snapshot`,`conversion_snapshot`,`schedule_preview_snapshot`,`price_minor`,`new_conversion_minor`,`credit_used_minor`,`cash_due_minor`,`status`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x','x',0,0,0,0,'x','x','x','{}','{}','{}',0,0,0,0,'x')"
                .formatted(tenant);
    }

    static String mall_order(String tenant) {
        return "INSERT INTO `mall_order` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`order_no`,`store_no`,`entity_no`,`buyer_user_no`,`business_type_code`,`quote_no`,`intent`,`currency`,`gross_minor`,`discount_minor`,`deal_minor`,`credit_used_minor`,`cash_due_minor`,`cash_paid_minor`,`cash_refunded_minor`,`pay_status`,`fulfillment_status`,`refund_status`,`pay_deadline`,`paid_at`,`closed_at`,`idempotency_key`,`request_hash`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x','x','x',0,0,0,0,0,0,0,'x','x','x',0,NULL,NULL,'x','x')"
                .formatted(tenant);
    }

    static String mall_order_item(String tenant) {
        return "INSERT INTO `mall_order_item` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`order_item_no`,`order_no`,`sku_no`,`sku_version_no`,`quantity`,`unit_price_minor`,`gross_minor`,`discount_minor`,`deal_minor`,`sku_snapshot`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x',0,0,0,0,0,'{}')"
                .formatted(tenant);
    }

    static String mall_order_component(String tenant) {
        return "INSERT INTO `mall_order_component` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`order_component_no`,`order_item_no`,`component_no`,`component_type`,`target_snapshot`,`unit_count`,`allocated_deal_minor`,`allocated_cash_minor`,`allocated_credit_minor`,`eligible_basis_minor`,`is_gift`,`fulfillment_status`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','{}',0,0,0,0,0,0,'x')"
                .formatted(tenant);
    }

    static String mall_payment_ref(String tenant) {
        return "INSERT INTO `mall_payment_ref` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`payment_attempt_no`,`order_no`,`pay_order_no`,`receiver_account_no`,`payment_business_type`,`currency`,`amount_minor`,`status`,`provider_paid_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x',0,'x',NULL)"
                .formatted(tenant);
    }

    static String mall_fulfillment_task(String tenant) {
        return "INSERT INTO `mall_fulfillment_task` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`task_no`,`order_component_no`,`unit_seq`,`task_type`,`status`,`attempt_count`,`next_retry_at`,`result_no`,`last_error_code`,`lease_until`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,'x','x',0,0,NULL,NULL,NULL)"
                .formatted(tenant);
    }

    static String mall_shipment(String tenant) {
        return "INSERT INTO `mall_shipment` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`shipment_no`,`order_no`,`shipment_items`,`address_snapshot`,`carrier_code`,`tracking_no`,`status`,`shipped_at`,`signed_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','{}','{}',NULL,NULL,'x',NULL,NULL)"
                .formatted(tenant);
    }

    static String mall_refund(String tenant) {
        return "INSERT INTO `mall_refund` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`refund_no`,`order_no`,`store_no`,`reason`,`policy_snapshot`,`requested_cash_minor`,`approved_cash_minor`,`restore_credit_minor`,`pay_refund_no`,`status`,`idempotency_key`,`reviewed_by`,`reviewed_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','{}',0,0,0,NULL,'x','x',NULL,NULL)"
                .formatted(tenant);
    }

    static String mall_refund_item(String tenant) {
        return "INSERT INTO `mall_refund_item` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`refund_item_no`,`refund_no`,`order_component_no`,`period_no`,`quantity`,`cash_refund_minor`,`credit_restore_minor`,`rights_action`,`status`,`adjustment_snapshot`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',NULL,0,0,0,'x','x','{}')"
                .formatted(tenant);
    }

    static String commerce_event_inbox(String tenant) {
        return "INSERT INTO `commerce_event_inbox` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`source_system`,`event_id`,`event_type`,`aggregate_no`,`payload_hash`,`status`,`received_at`,`processed_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x',0,NULL)"
                .formatted(tenant);
    }

    static String mch_subscription_account(String tenant) {
        return "INSERT INTO `mch_subscription_account` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`store_no`,`entity_no`,`business_type_code`,`entitlement_version`,`pending_change_order_no`,`legacy_contract_ref`,`migration_batch_no`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,NULL,NULL,NULL)"
                .formatted(tenant);
    }

    static String mch_subscription_period(String tenant) {
        return "INSERT INTO `mch_subscription_period` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`period_no`,`store_no`,`entity_no`,`business_type_code`,`stream_key`,`source_type`,`source_ref_no`,`fulfillment_task_no`,`plan_no`,`plan_version_no`,`standalone_entitlement_code`,`tier_code`,`delivered_at`,`delivery_seq`,`granted_duration_ms`,`revoked_duration_ms`,`original_start_at`,`original_end_at`,`effective_start_at`,`effective_end_at`,`eligible_basis_minor`,`cash_basis_minor`,`credit_basis_minor`,`currency`,`state`,`converted_by_no`,`source_policy_snapshot`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x','x',NULL,NULL,NULL,NULL,NULL,0,0,NULL,0,0,NULL,0,NULL,0,0,0,'x','x',NULL,'{}')"
                .formatted(tenant);
    }

    static String mch_store_end_grant(String tenant) {
        return "INSERT INTO `mch_store_end_grant` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`grant_no`,`store_no`,`period_no`,`end_code`,`entitlement_code`,`schedule_revision`,`segment_seq`,`schedule_event_no`,`start_at`,`end_at`,`state`,`revoked_at`,`reason`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x',0,0,'x',0,NULL,'x',NULL,'x')"
                .formatted(tenant);
    }

    static String mch_subscription_reservation(String tenant) {
        return "INSERT INTO `mch_subscription_reservation` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`reservation_no`,`store_no`,`order_no`,`quote_no`,`subscription_version`,`credit_account_version`,`valuation_at`,`pay_deadline`,`status`,`source_periods_snapshot`,`reserved_credit_minor`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x',0,0,0,0,'x','{}',0)"
                .formatted(tenant);
    }

    static String mch_subscription_conversion(String tenant) {
        return "INSERT INTO `mch_subscription_conversion` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`conversion_no`,`store_no`,`order_no`,`valuation_at`,`effective_at`,`new_period_no`,`currency`,`converted_minor`,`credit_consumed_minor`,`cash_paid_minor`,`policy_version`,`status`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,0,'x','x',0,0,0,'x','x')"
                .formatted(tenant);
    }

    static String mch_subscription_conversion_item(String tenant) {
        return "INSERT INTO `mch_subscription_conversion_item` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`conversion_no`,`source_period_no`,`basis_minor`,`original_duration_ms`,`remaining_duration_ms`,`converted_minor`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,0,0,0)"
                .formatted(tenant);
    }

    static String mch_subscription_credit_account(String tenant) {
        return "INSERT INTO `mch_subscription_credit_account` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`store_no`,`currency`,`balance_minor`,`reserved_minor`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x',0,0)"
                .formatted(tenant);
    }

    static String mch_subscription_credit_lot(String tenant) {
        return "INSERT INTO `mch_subscription_credit_lot` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`credit_lot_no`,`store_no`,`currency`,`source_type`,`source_ref_no`,`issued_minor`,`remaining_minor`,`reserved_minor`,`parent_provenance`,`expires_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x',0,0,0,'{}',NULL)"
                .formatted(tenant);
    }

    static String mch_subscription_credit_ledger(String tenant) {
        return "INSERT INTO `mch_subscription_credit_ledger` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`ledger_no`,`store_no`,`currency`,`credit_lot_no`,`event_type`,`biz_ref_no`,`event_seq`,`amount_minor`,`balance_delta_minor`,`reserved_delta_minor`,`order_component_no`,`reversal_of_ledger_no`,`reason`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x','x','x','x',0,0,0,0,NULL,NULL,'x')"
                .formatted(tenant);
    }

    static String commerce_outbox(String tenant) {
        return "INSERT INTO `commerce_outbox` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`event_no`,`aggregate_type`,`aggregate_no`,`aggregate_version`,`event_type`,`payload`,`status`,`retry_count`,`next_retry_at`,schema_version,occurred_at) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,'x','{}','x',0,0,1,0)"
                .formatted(tenant);
    }

    static String mch_subscription_migration_map(String tenant) {
        return "INSERT INTO `mch_subscription_migration_map` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`migration_batch_no`,`legacy_entity_plan_id`,`legacy_contract_ref`,`store_no`,`new_period_no`,`allocated_basis_minor`,`evidence_ref`,`legacy_snapshot`,`validation_status`,`cutover_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x',0,'x','x','x',0,NULL,'{}','x',NULL)"
                .formatted(tenant);
    }

    static String mch_end_schedule_event(String tenant) {
        return "INSERT INTO `mch_end_schedule_event` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`schedule_event_no`,`store_no`,`entitlement_code`,`entitlement_version`,`operation_ref_no`,`reason_type`,`effective_at`,`before_snapshot`,`after_snapshot`,`duration_check_snapshot`,`stacking_policy_version`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,'x','x',0,'{}','{}','{}','x')"
                .formatted(tenant);
    }

    static String commerce_expiry_notice(String tenant) {
        return "INSERT INTO `commerce_expiry_notice` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`notice_no`,`store_no`,`entitlement_code`,`coverage_end_at`,`threshold_code`,`due_at`,`expires_at`,`policy_version`,`observed_entitlement_version`,`recipient_type`,`recipient_no`,`channel`,`dedup_key`,`status`,`attempts`,`next_attempt_at`,`lease_until`,`provider_message_no`,`last_error_code`,`sent_at`) VALUES ('%s','2026-11-03 00:00:00.000','2026-11-03 00:00:00.000',NULL,NULL,0,0,'x','x','x',0,'x',0,0,'x',0,'x','x','x','x','x',0,NULL,NULL,NULL,NULL,NULL)"
                .formatted(tenant);
    }
}
