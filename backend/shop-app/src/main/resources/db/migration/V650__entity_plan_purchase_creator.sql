-- Separate request authority from the merchant owner receiving the entity purchase.
ALTER TABLE mch_plan_purchase
  ADD COLUMN creator_no VARCHAR(64) NULL AFTER buyer_kind,
  ADD COLUMN creator_kind VARCHAR(16) NULL AFTER creator_no;
UPDATE mch_plan_purchase SET creator_no=buyer_no,creator_kind=buyer_kind,
  snapshot_json=JSON_SET(snapshot_json,'$.creatorNo',buyer_no,'$.creatorKind',buyer_kind);
ALTER TABLE mch_plan_purchase
  MODIFY creator_no VARCHAR(64) NOT NULL,
  MODIFY creator_kind VARCHAR(16) NOT NULL,
  DROP INDEX uk_plan_request,
  ADD UNIQUE KEY uk_plan_request(tenant_no,creator_kind,creator_no,request_no);
