-- Existing source journals remain V1 with no asset basis; never infer or backfill eligibility.
ALTER TABLE ord_pricing_funding_source ADD COLUMN basis_snapshot LONGTEXT NULL;
ALTER TABLE ord_pricing_funding_source ADD CONSTRAINT ck_pricing_funding_basis_json
    CHECK (basis_snapshot IS NULL OR JSON_VALID(basis_snapshot));
