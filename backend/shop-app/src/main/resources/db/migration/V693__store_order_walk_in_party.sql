-- 订单约束新增一支「门店散客零售单」（TDD-统一订单与开票资料 §2，ADR-065 决策 2）：
-- customer_kind='WALK_IN' + admission_kind='STORE_SALE' + caller='POS'，无 user / guest，主体、门店、操作号必填。
-- 原有 USER / GUEST / CHANNEL 各支逐字保留（取自 V615 / V616 当前定义），只在末尾追加一支。
-- 子单沿 CHANNEL 的写法：fulfillment_authority / source_operation_no 均为 NULL，与 ck_sub_checkout_authority 的 NULL/NULL 分支一致。
ALTER TABLE ord_order DROP CHECK ck_order_admission_kind;
ALTER TABLE ord_order ADD CONSTRAINT ck_order_admission_kind CHECK
  ((admission_kind IN ('LEGACY_FUNDED','MANUAL_OFFLINE') AND customer_kind <> 'CHANNEL')
   OR (admission_kind='CHANNEL' AND customer_kind='CHANNEL')
        OR (admission_kind='STORE_SALE' AND customer_kind='WALK_IN'));
ALTER TABLE ord_order DROP CHECK ck_counter_order_party;
ALTER TABLE ord_order ADD CONSTRAINT ck_counter_order_party CHECK (
        (customer_kind='USER' AND user_no IS NOT NULL AND CHAR_LENGTH(TRIM(user_no))>0
         AND OCTET_LENGTH(user_no)=OCTET_LENGTH(TRIM(user_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY' AND operation_no IS NOT NULL
        AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(operation_no))>0 AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0)))
        OR (customer_kind='GUEST' AND user_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY' AND operation_no IS NOT NULL
        AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(operation_no))>0 AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0) OR (customer_kind='CHANNEL' AND admission_kind='CHANNEL' AND user_no IS NULL
        AND guest_no IS NULL AND identity_revision IS NULL AND caller IS NOT NULL
        AND caller IN ('FOOD','RETAIL') AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND operation_no IS NOT NULL AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0 AND CHAR_LENGTH(TRIM(operation_no))>0)
        OR (customer_kind='WALK_IN' AND admission_kind='STORE_SALE' AND user_no IS NULL
            AND guest_no IS NULL AND identity_revision IS NULL AND caller IS NOT NULL AND caller='POS'
            AND entity_no IS NOT NULL AND store_no IS NOT NULL AND operation_no IS NOT NULL
            AND client_order_no IS NOT NULL AND CHAR_LENGTH(TRIM(entity_no))>0
            AND CHAR_LENGTH(TRIM(store_no))>0 AND CHAR_LENGTH(TRIM(operation_no))>0
            AND CHAR_LENGTH(TRIM(client_order_no))>0));
ALTER TABLE ord_sub_order DROP CHECK ck_sub_manual_kind;
ALTER TABLE ord_sub_order ADD CONSTRAINT ck_sub_manual_kind CHECK
  ((admission_kind='LEGACY_FUNDED' AND customer_kind <> 'CHANNEL') OR
   (admission_kind='MANUAL_OFFLINE' AND customer_kind <> 'CHANNEL'
    AND fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION'
    AND source_operation_no IS NOT NULL AND fulfillment='STORE_VERIFY') OR
   (admission_kind='CHANNEL' AND customer_kind='CHANNEL' AND fulfillment_authority IS NULL)
        OR (admission_kind='STORE_SALE' AND customer_kind='WALK_IN'
    AND fulfillment_authority IS NULL AND source_operation_no IS NULL));
ALTER TABLE ord_sub_order DROP CHECK ck_counter_sub_party;
ALTER TABLE ord_sub_order ADD CONSTRAINT ck_counter_sub_party CHECK (
        (customer_kind='USER' AND user_no IS NOT NULL AND CHAR_LENGTH(TRIM(user_no))>0
         AND OCTET_LENGTH(user_no)=OCTET_LENGTH(TRIM(user_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY' AND source_operation_no IS NOT NULL
        AND store_no IS NOT NULL AND CHAR_LENGTH(TRIM(store_no))>0
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(source_operation_no))>0
        AND fulfillment IS NOT NULL AND fulfillment='STORE_VERIFY'
        AND fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION')))
        OR (customer_kind='GUEST' AND user_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY' AND source_operation_no IS NOT NULL
        AND store_no IS NOT NULL AND CHAR_LENGTH(TRIM(store_no))>0
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(source_operation_no))>0
        AND fulfillment IS NOT NULL AND fulfillment='STORE_VERIFY'
        AND fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION') OR (customer_kind='CHANNEL' AND admission_kind='CHANNEL' AND user_no IS NULL
        AND guest_no IS NULL AND identity_revision IS NULL AND caller IS NOT NULL
        AND caller IN ('FOOD','RETAIL') AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0
        AND fulfillment_authority IS NULL AND source_operation_no IS NULL)
        OR (customer_kind='WALK_IN' AND admission_kind='STORE_SALE' AND user_no IS NULL
            AND guest_no IS NULL AND identity_revision IS NULL AND caller IS NOT NULL AND caller='POS'
            AND entity_no IS NOT NULL AND store_no IS NOT NULL AND CHAR_LENGTH(TRIM(entity_no))>0
            AND CHAR_LENGTH(TRIM(store_no))>0 AND fulfillment_authority IS NULL AND source_operation_no IS NULL));
