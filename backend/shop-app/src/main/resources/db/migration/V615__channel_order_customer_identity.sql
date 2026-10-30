-- Extend the existing customer and admission checks for independently verified channel orders.
-- Prior USER/GUEST checks remain unchanged; CHANNEL carries no local customer identity.
ALTER TABLE ord_order DROP CHECK ck_order_admission_kind;
ALTER TABLE ord_order ADD CONSTRAINT ck_order_admission_kind CHECK
  ((admission_kind IN ('LEGACY_FUNDED','MANUAL_OFFLINE') AND customer_kind <> 'CHANNEL')
   OR (admission_kind='CHANNEL' AND customer_kind='CHANNEL'));
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
        AND CHAR_LENGTH(TRIM(store_no))>0 AND CHAR_LENGTH(TRIM(operation_no))>0));
ALTER TABLE ord_sub_order DROP CHECK ck_sub_manual_kind;
ALTER TABLE ord_sub_order ADD CONSTRAINT ck_sub_manual_kind CHECK
  ((admission_kind='LEGACY_FUNDED' AND customer_kind <> 'CHANNEL') OR
   (admission_kind='MANUAL_OFFLINE' AND customer_kind <> 'CHANNEL'
    AND fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION'
    AND source_operation_no IS NOT NULL AND fulfillment='STORE_VERIFY') OR
   (admission_kind='CHANNEL' AND customer_kind='CHANNEL' AND fulfillment_authority IS NULL));
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
        AND source_operation_no IS NOT NULL AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0 AND CHAR_LENGTH(TRIM(source_operation_no))>0
        AND fulfillment_authority IS NULL));
