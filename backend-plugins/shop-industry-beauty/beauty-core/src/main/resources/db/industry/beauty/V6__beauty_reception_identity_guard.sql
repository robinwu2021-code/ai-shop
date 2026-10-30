-- Beauty V6: reject half-null reception identity; V1-V5 remain frozen.
-- Existing invalid rows must block upgrade; no repair, baseline or data rewrite.

ALTER TABLE `bty_reception_operation`
    ADD CONSTRAINT `ck_bty_reception_operation_5` CHECK ((`identity_kind` IS NULL AND `identity_ref` IS NULL) OR (`identity_kind` IS NOT NULL AND `identity_kind` IN ('USER','GUEST') AND `identity_ref` IS NOT NULL AND CHAR_LENGTH(`identity_ref`) > 0));
