ALTER TABLE pts_pool_request
    ADD COLUMN submitted_reason VARCHAR(500) DEFAULT NULL;
ALTER TABLE pts_pool_request
    ADD COLUMN review_reason VARCHAR(500) DEFAULT NULL;
ALTER TABLE pts_pool_request
    ADD COLUMN reviewed_at DATETIME DEFAULT NULL;
