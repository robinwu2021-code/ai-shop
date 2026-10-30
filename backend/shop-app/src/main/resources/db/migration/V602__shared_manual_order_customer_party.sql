-- Existing USER keys and economic hashes remain untouched; counter identity is explicit.

ALTER TABLE ord_order
    MODIFY COLUMN user_no VARCHAR(64) NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD COLUMN entity_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN caller VARCHAR(16) COLLATE utf8mb4_bin NULL,
    ADD COLUMN operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_counter_order_party CHECK (
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
        AND CHAR_LENGTH(TRIM(store_no))>0));

ALTER TABLE ord_sub_order
    MODIFY COLUMN user_no VARCHAR(64) NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD COLUMN caller VARCHAR(16) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_counter_sub_party CHECK (
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
        AND fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION'));

ALTER TABLE ord_create_intent
    MODIFY COLUMN user_no VARCHAR(64) NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD COLUMN entity_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN caller VARCHAR(16) COLLATE utf8mb4_bin NULL,
    ADD COLUMN operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN admission_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN creation_kind VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN confirmation_no VARCHAR(40) COLLATE utf8mb4_bin NULL,
    ADD COLUMN sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN authority_namespace_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN counter_plan_json LONGTEXT NULL,
    ADD COLUMN plan_hash CHAR(64) NULL,
    ADD COLUMN reservation_operation_no CHAR(64) NULL,
    ADD COLUMN reservation_state VARCHAR(16) NULL,
    ADD COLUMN reservation_observation_json LONGTEXT NULL,
    ADD COLUMN reservation_observation_hash CHAR(64) NULL,
    ADD CONSTRAINT ck_counter_intent_party CHECK (
        (customer_kind='USER' AND user_no IS NOT NULL AND CHAR_LENGTH(TRIM(user_no))>0
         AND OCTET_LENGTH(user_no)=OCTET_LENGTH(TRIM(user_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND creation_kind='COUNTER' AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY'
        AND operation_no IS NOT NULL AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(operation_no))>0 AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0)))
        OR (customer_kind='GUEST' AND user_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND creation_kind='COUNTER' AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY'
        AND operation_no IS NOT NULL AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(operation_no))>0 AND CHAR_LENGTH(TRIM(entity_no))>0
        AND CHAR_LENGTH(TRIM(store_no))>0));

ALTER TABLE ord_checkout_operation
    MODIFY COLUMN buyer_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD COLUMN purchase_authority VARCHAR(40) NOT NULL DEFAULT 'CONSUMER_SESSION',
    ADD COLUMN authority_namespace_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN account_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_counter_operation_party CHECK (
        (customer_kind='USER' AND buyer_no IS NOT NULL AND CHAR_LENGTH(TRIM(buyer_no))>0
         AND OCTET_LENGTH(buyer_no)=OCTET_LENGTH(TRIM(buyer_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND admission_kind='MANUAL_OFFLINE' AND purchase_authority='MERCHANT_RECORDED_IN_PERSON'
        AND caller='BEAUTY' AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0)))
        OR (customer_kind='GUEST' AND buyer_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND admission_kind='MANUAL_OFFLINE' AND purchase_authority='MERCHANT_RECORDED_IN_PERSON'
        AND caller='BEAUTY' AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0));

ALTER TABLE ord_checkout_confirmation
    MODIFY COLUMN buyer_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD COLUMN entity_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN admission_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN purchase_authority VARCHAR(40) NOT NULL DEFAULT 'CONSUMER_SESSION',
    ADD COLUMN authority_namespace_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN accepted_at DATETIME(3) NULL,
    ADD COLUMN actor_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN actor_realm VARCHAR(16) NULL,
    ADD COLUMN actor_kind VARCHAR(8) NULL,
    ADD COLUMN account_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_counter_confirmation_party CHECK (
        (customer_kind='USER' AND buyer_no IS NOT NULL AND CHAR_LENGTH(TRIM(buyer_no))>0
         AND OCTET_LENGTH(buyer_no)=OCTET_LENGTH(TRIM(buyer_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND admission_kind='MANUAL_OFFLINE' AND purchase_authority='MERCHANT_RECORDED_IN_PERSON'
        AND caller='BEAUTY' AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0)))
        OR (customer_kind='GUEST' AND buyer_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND admission_kind='MANUAL_OFFLINE' AND purchase_authority='MERCHANT_RECORDED_IN_PERSON'
        AND caller='BEAUTY' AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0));

ALTER TABLE ord_manual_service_admission
    MODIFY COLUMN buyer_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN customer_kind VARCHAR(8) NOT NULL DEFAULT 'USER',
    ADD COLUMN guest_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN identity_revision BIGINT NULL DEFAULT 1,
    ADD CONSTRAINT ck_counter_admission_party CHECK (
        (customer_kind='USER' AND buyer_no IS NOT NULL AND CHAR_LENGTH(TRIM(buyer_no))>0
         AND OCTET_LENGTH(buyer_no)=OCTET_LENGTH(TRIM(buyer_no)) AND guest_no IS NULL
         AND ((identity_revision IS NOT NULL AND identity_revision>0)
              OR (identity_revision IS NULL AND admission_kind='MANUAL_OFFLINE' AND caller='BEAUTY'
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0)))
        OR (customer_kind='GUEST' AND buyer_no IS NULL AND guest_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(guest_no))>0 AND OCTET_LENGTH(guest_no)=OCTET_LENGTH(TRIM(guest_no))
            AND identity_revision IS NOT NULL AND identity_revision>0 AND admission_kind='MANUAL_OFFLINE' AND caller='BEAUTY'
        AND CHAR_LENGTH(TRIM(entity_no))>0 AND CHAR_LENGTH(TRIM(store_no))>0));

-- New keys supplement old keys. No old key/hash is recomputed or silently reinterpreted.
UPDATE ord_checkout_operation SET authority_namespace_hash=SHA2(CONCAT(
    'LEGACY_CHECKOUT_NAMESPACE_V1',CHAR_LENGTH(tenant_no),':',tenant_no,
    CHAR_LENGTH(caller),':',caller,CHAR_LENGTH(entity_no),':',entity_no,
    CHAR_LENGTH(store_no),':',store_no,CHAR_LENGTH(actor_realm),':',actor_realm,
    CHAR_LENGTH(actor_kind),':',actor_kind,CHAR_LENGTH(actor_no),':',actor_no),256)
WHERE authority_namespace_hash IS NULL;

UPDATE ord_checkout_confirmation c JOIN ord_checkout_operation o
    ON c.tenant_no=o.tenant_no AND c.caller=o.caller AND c.operation_no=o.operation_no
SET c.authority_namespace_hash=o.authority_namespace_hash
WHERE c.authority_namespace_hash IS NULL;

-- An orphan historical consent still receives its own immutable legacy namespace, never counter consent.
UPDATE ord_checkout_confirmation SET authority_namespace_hash=SHA2(CONCAT(
    'LEGACY_CHECKOUT_NAMESPACE_V1',CHAR_LENGTH(tenant_no),':',tenant_no,
    CHAR_LENGTH(caller),':',caller,CHAR_LENGTH(operation_no),':',operation_no,
    CHAR_LENGTH(buyer_no),':',buyer_no),256)
WHERE authority_namespace_hash IS NULL;

ALTER TABLE ord_checkout_operation
    MODIFY COLUMN authority_namespace_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    ADD UNIQUE KEY uk_counter_place_namespace (tenant_no,authority_namespace_hash,place_key_hash),
    ADD CONSTRAINT ck_counter_operation_authority CHECK (
        (purchase_authority='CONSUMER_SESSION' AND customer_kind='USER'
         AND identity_revision IS NOT NULL AND identity_revision>0 AND account_no IS NULL)
        OR (purchase_authority='MERCHANT_RECORDED_IN_PERSON' AND admission_kind='MANUAL_OFFLINE'
            AND account_no IS NOT NULL AND CHAR_LENGTH(TRIM(account_no))>0)),
    ADD CONSTRAINT ck_counter_operation_namespace CHECK (authority_namespace_hash REGEXP '^[a-f0-9]{64}$');

ALTER TABLE ord_checkout_confirmation
    MODIFY COLUMN authority_namespace_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    ADD UNIQUE KEY uk_counter_accept_namespace (tenant_no,authority_namespace_hash,request_key_hash),
    ADD CONSTRAINT ck_counter_confirmation_authority CHECK (
        (purchase_authority='CONSUMER_SESSION' AND customer_kind='USER'
         AND identity_revision IS NOT NULL AND identity_revision>0
         AND accepted_at IS NULL AND actor_no IS NULL AND actor_realm IS NULL
         AND actor_kind IS NULL AND account_no IS NULL)
        OR (purchase_authority='MERCHANT_RECORDED_IN_PERSON' AND admission_kind='MANUAL_OFFLINE'
            AND entity_no IS NOT NULL AND store_no IS NOT NULL AND CHAR_LENGTH(TRIM(entity_no))>0
            AND CHAR_LENGTH(TRIM(store_no))>0 AND actor_no IS NOT NULL AND CHAR_LENGTH(TRIM(actor_no))>0
            AND actor_realm IS NOT NULL AND actor_realm='MERCHANT'
            AND actor_kind IS NOT NULL AND actor_kind IN ('MCH','USR')
            AND account_no IS NOT NULL AND CHAR_LENGTH(TRIM(account_no))>0
            AND accepted_at IS NOT NULL AND accepted_at<=confirmed_at)),
    ADD CONSTRAINT ck_counter_confirmation_namespace CHECK (authority_namespace_hash REGEXP '^[a-f0-9]{64}$');

ALTER TABLE ord_create_intent
    ADD UNIQUE KEY uk_counter_intent_operation (tenant_no,creation_kind,caller,operation_no),
    ADD KEY idx_counter_intent_retry (creation_kind,status,next_attempt_at,id),
    ADD CONSTRAINT ck_counter_intent_stage CHECK (
        (creation_kind='LEGACY' AND customer_kind='USER' AND identity_revision IS NOT NULL
         AND identity_revision>0 AND counter_plan_json IS NULL AND plan_hash IS NULL
         AND confirmation_no IS NULL AND sub_order_no IS NULL AND reservation_state IS NULL
         AND reservation_operation_no IS NULL AND reservation_observation_json IS NULL
         AND reservation_observation_hash IS NULL AND authority_namespace_hash IS NULL)
        OR (creation_kind='COUNTER' AND admission_kind='MANUAL_OFFLINE' AND caller IS NOT NULL AND caller='BEAUTY'
            AND endpoint='POST /biz/service-orders/counter-orders' AND idem_key IS NOT NULL
            AND operation_no IS NOT NULL AND entity_no IS NOT NULL AND store_no IS NOT NULL
            AND confirmation_no IS NOT NULL AND sub_order_no IS NOT NULL
            AND authority_namespace_hash IS NOT NULL AND authority_namespace_hash REGEXP '^[a-f0-9]{64}$'
            AND counter_plan_json IS NOT NULL AND JSON_VALID(counter_plan_json)
            AND JSON_TYPE(counter_plan_json)='OBJECT'
            AND plan_hash IS NOT NULL AND plan_hash REGEXP '^[a-f0-9]{64}$'
            AND reservation_operation_no IS NOT NULL AND reservation_operation_no REGEXP '^[a-f0-9]{64}$'
            AND reservation_state IS NOT NULL AND reservation_state IN ('NOT_SENT','UNKNOWN','RESERVED','REJECTED')
            AND ((reservation_observation_json IS NULL AND reservation_observation_hash IS NULL)
                 OR (reservation_observation_json IS NOT NULL AND JSON_VALID(reservation_observation_json)
                     AND JSON_TYPE(reservation_observation_json)='OBJECT'
                     AND reservation_observation_hash IS NOT NULL
                     AND reservation_observation_hash REGEXP '^[a-f0-9]{64}$'))));

CREATE INDEX idx_guest_current_claim
    ON mbr_guest_registration(tenant_no,claimed_user_no,state,entity_no,store_no,guest_no);
