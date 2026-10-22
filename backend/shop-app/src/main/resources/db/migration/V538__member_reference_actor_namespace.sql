-- Legacy references retain NULL namespace for old consumers; new guest claims require the complete identity.
ALTER TABLE mbr_identity_ticket
    ADD COLUMN actor_realm VARCHAR(16) NULL COMMENT 'Authenticated reference issuer realm',
    ADD COLUMN actor_kind VARCHAR(8) NULL COMMENT 'Authenticated reference issuer subject kind';
ALTER TABLE mbr_identity_ticket
    ADD CONSTRAINT ck_mbr_ticket_actor_namespace CHECK (
        (actor_realm IS NULL AND actor_kind IS NULL)
        OR (purpose='CUSTOMER_REFERENCE' AND actor_realm='MERCHANT' AND actor_kind IN ('MCH','USR')));
