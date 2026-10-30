-- Preserve the existing NULL/NULL ordinary fulfillment authority contract for channel orders.
-- The immutable original admission binds the channel order and sub-order by their unique numbers.
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
        AND fulfillment_authority IS NULL AND source_operation_no IS NULL));
