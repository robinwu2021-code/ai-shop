CREATE TABLE sys_pos_feature_catalog_release (
    tenant_no VARCHAR(32) NOT NULL,
    release_no CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    manifest JSON NOT NULL,
    manifest_canonical MEDIUMTEXT NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    published_by VARCHAR(64) NOT NULL,
    change_note VARCHAR(512) NOT NULL,
    published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_no, release_no),
    CHECK (JSON_VALID(manifest_canonical))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_pos_feature_catalog_current (
    tenant_no VARCHAR(32) NOT NULL,
    release_no CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_no),
    CONSTRAINT fk_pos_feature_catalog_current_release
        FOREIGN KEY (tenant_no, release_no)
        REFERENCES sys_pos_feature_catalog_release (tenant_no, release_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
