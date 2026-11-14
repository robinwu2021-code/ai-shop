-- Sales receipt identity is independent of bank references. Existing banking facts stay unchanged.
ALTER TABLE stl_payment
    ADD COLUMN platform_collection_ref_key VARCHAR(64) DEFAULT NULL,
    ADD UNIQUE KEY uk_platform_collection_reference (platform_collection_ref_key);
